/* 
 * Copyright 2015-2024 (c) CoralBlocks LLC - http://www.coralblocks.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.coralblocks.coralme;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import com.coralblocks.coralds.map.LongMap;
import com.coralblocks.coralme.Order.CancelReason;
import com.coralblocks.coralme.Order.ExecuteSide;
import com.coralblocks.coralme.Order.RejectReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;
import com.coralblocks.coralme.Order.Type;
import com.coralblocks.coralme.util.DoubleUtils;
import com.coralblocks.coralme.util.SystemTimestamper;
import com.coralblocks.coralme.util.Timestamper;
import com.coralblocks.coralpool.ArrayObjectPool;
import com.coralblocks.coralpool.ObjectBuilder;
import com.coralblocks.coralpool.ObjectPool;

public class OrderBook {
	
	/**
	 * The default initial size of the {@link Order} object pool. Can be changed for tuning.
	 */
	public static int ORDER_POOL_INITIAL_SIZE = 128;
	
	/**
	 * The default initial size of the {@link PriceLevel} object pool. Can be changed for tuning.
	 */
	public static int PRICE_LEVEL_POOL_INITIAL_SIZE = 64;
	
	private static final boolean DEFAULT_ALLOW_TRADE_TO_SELF = true;
	
	private static final Timestamper TIMESTAMPER = new SystemTimestamper();
	
	public static enum State { NORMAL, LOCKED, CROSSED, ONESIDED, EMPTY }

	public static enum TraversalOrder {
		PRICE_TIME_PRIORITY(false),
		REVERSE_PRICE_TIME_PRIORITY(true);

		private final boolean reverse;

		private TraversalOrder(boolean reverse) {
			this.reverse = reverse;
		}
	}
	
	private final ObjectPool<Order> orderPool = new ArrayObjectPool<Order>(ORDER_POOL_INITIAL_SIZE, Order.class);
	
	private final ObjectPool<PriceLevel> priceLevelPool;
	
	private long execId = 0;
	
	private long matchId = 0;
	
	private PriceLevel[] head = new PriceLevel[2];
	
	private PriceLevel[] tail = new PriceLevel[2];
	
	private int[] levels = new int[] { 0, 0 };
	
	private final LongMap<Order> orders = new LongMap<Order>();

	private final ReusableOrderIterator priceTimePriorityIterator = new ReusableOrderIterator(false);

	private final ReusableOrderIterator reversePriceTimePriorityIterator = new ReusableOrderIterator(true);
	
	private final String security;
	
	private long lastExecutedPrice = Long.MAX_VALUE;
	
	private final List<OrderBookListener> listeners = new ArrayList<OrderBookListener>(8);

	private boolean externalListenerCallbackInProgress;

	private boolean deferListenerExceptionReporting;

	private OrderBookListenerExceptions listenerExceptions;

	private List<Order> deferredOrderListenerExceptionReports;
	
	private final Timestamper timestamper;
	
	private final boolean allowTradeToSelf;

	private final OrderListener internalOrderListener = new InternalOrderListener();
	
	
	public OrderBook(String security, boolean allowTradeToSelf) {
		this(security, TIMESTAMPER, null, allowTradeToSelf);
	}
	
	public OrderBook(String security) {
		this(security, TIMESTAMPER, null);
	}
	
	public OrderBook(String security, Timestamper timestamper, boolean allowTradeToSelf) {
		this(security, timestamper, null, allowTradeToSelf);
	}
	
	public OrderBook(String security, Timestamper timestamper) {
		this(security, timestamper, null);
	}
	
	public OrderBook(String security, OrderBookListener listener, boolean allowTradeToSelf) {
		this(security, TIMESTAMPER, listener, allowTradeToSelf);
	}
	
	public OrderBook(String security, OrderBookListener listener) {
		this(security, TIMESTAMPER, listener);
	}
	
	public OrderBook(String security, Timestamper timestamper, OrderBookListener listener) {
		this(security, timestamper, listener, DEFAULT_ALLOW_TRADE_TO_SELF);
	}
	
	public OrderBook(OrderBook orderBook) {
		this(orderBook.getSecurity(), orderBook.getTimestamper(), null, orderBook.isAllowTradeToSelf());
		 for(int i = 0; i < orderBook.listeners.size(); i++) {
			 addListener(orderBook.listeners.get(i));
		 }
	}

	public OrderBook(String security, Timestamper timestamper, OrderBookListener listener, boolean allowTradeToSelf) {
		
		this.security = security;
		
		this.timestamper = timestamper;
		
		this.allowTradeToSelf = allowTradeToSelf;
		
		ObjectBuilder<PriceLevel> priceLevelBuilder = new ObjectBuilder<PriceLevel>() {
			@Override
			public PriceLevel newInstance() {
				return new PriceLevel();
			}
		};
		
		this.priceLevelPool = new ArrayObjectPool<PriceLevel>(PRICE_LEVEL_POOL_INITIAL_SIZE, priceLevelBuilder);
		
		if (listener != null) listeners.add(listener);
	}
	
	/**
	 * Adds a listener if it has not already been registered.
	 *
	 * @param listener the listener to add
	 * @throws NullPointerException if the listener is null
	 */
	public void addListener(OrderBookListener listener) {
		checkExternalListenerReentrancy("addListener");
		if (listener == null) throw new NullPointerException("listener");
		if (!listeners.contains(listener)) listeners.add(listener);
	}
	
	public void removeListener(OrderBookListener listener) {
		checkExternalListenerReentrancy("removeListener");
		listeners.remove(listener);
	}

	final void checkExternalListenerReentrancy(String operation) {
		if (externalListenerCallbackInProgress) throw new ReentrantOrderBookOperationException(this, operation);
	}

	final void enterExternalListenerCallback() {
		externalListenerCallbackInProgress = true;
	}

	final void exitExternalListenerCallback() {
		externalListenerCallbackInProgress = false;
	}
	
	public final boolean isAllowTradeToSelf() {
		return allowTradeToSelf;
	}
	
	public Timestamper getTimestamper() {
		return timestamper;
	}
	
	public String getSecurity() {
		
		return security;
	}
	
	public final Order getBestBidOrder() {
		
		if (!hasBids()) return null;
		
		PriceLevel pl = head(Side.BUY);
		
		return pl.head();
	}
	
	public final Order getBestAskOrder() {
		
		if (!hasAsks()) return null;
		
		PriceLevel pl = head(Side.SELL);
				
		return pl.head();
	}
	
	/**
	 * Returns the cached iterator for the requested traversal order, reset to the
	 * beginning of the requested side. No iterator is allocated by this method.
	 * <p>
	 * {@link TraversalOrder#PRICE_TIME_PRIORITY} visits the best price first and
	 * the oldest order first at each price. Its reverse visits the worst price
	 * first and the newest order first at each price.
	 * <p>
	 * Orders may be canceled or reduced while traversal is in progress. The
	 * iterator follows the live linked structure and skips orders removed before
	 * they are returned. Orders added during traversal may or may not be visited,
	 * depending on whether they are linked into a position the iterator has not
	 * passed yet. {@link Iterator#remove()} is not supported.
	 * <p>
	 * The same iterator instance is reused for every traversal in the same
	 * direction. Requesting it again resets any traversal using that instance.
	 *
	 * @param side the side to traverse
	 * @param traversalOrder the order in which prices and orders are visited
	 * @return the cached, reset iterator
	 */
	public final Iterator<Order> iterator(Side side, TraversalOrder traversalOrder) {
		checkExternalListenerReentrancy("iterator");

		ReusableOrderIterator iterator = traversalOrder.reverse
				? reversePriceTimePriorityIterator
				: priceTimePriorityIterator;
		iterator.reset(side);
		return iterator;
	}

	/**
	 * Returns the cached price-time-priority iterator for the requested side.
	 *
	 * @param side the side to traverse
	 * @return the cached, reset iterator
	 * @see #iterator(Side, TraversalOrder)
	 */
	public final Iterator<Order> iterator(Side side) {
		return iterator(side, TraversalOrder.PRICE_TIME_PRIORITY);
	}

	private final class ReusableOrderIterator implements Iterator<Order> {

		private final boolean reverse;
		private PriceLevel nextPriceLevel;
		private Order nextOrder;

		private ReusableOrderIterator(boolean reverse) {
			this.reverse = reverse;
		}

		private void reset(Side side) {
			nextPriceLevel = reverse ? tail[side.index()] : head[side.index()];
			nextOrder = firstOrder(nextPriceLevel);
		}

		private Order firstOrder(PriceLevel priceLevel) {
			if (priceLevel == null) return null;
			return reverse ? priceLevel.tail() : priceLevel.head();
		}

		private void orderRemoved(Order order) {
			if (nextOrder != order) return;

			Order followingOrder = reverse ? order.prev : order.next;
			if (followingOrder != null) {
				nextOrder = followingOrder;
			} else {
				PriceLevel priceLevel = order.getPriceLevel();
				nextPriceLevel = reverse ? priceLevel.prev : priceLevel.next;
				nextOrder = firstOrder(nextPriceLevel);
			}
		}

		@Override
		public boolean hasNext() {
			checkExternalListenerReentrancy("Iterator.hasNext");
			return nextOrder != null;
		}

		@Override
		public Order next() {
			checkExternalListenerReentrancy("Iterator.next");
			if (nextOrder == null) throw new NoSuchElementException();

			Order order = nextOrder;
			// Save the next links before the caller can cancel or terminally reduce
			// this order and return it or its PriceLevel to an object pool.
			Order followingOrder = reverse ? order.prev : order.next;

			if (followingOrder != null) {
				nextOrder = followingOrder;
			} else {
				nextPriceLevel = reverse ? nextPriceLevel.prev : nextPriceLevel.next;
				nextOrder = firstOrder(nextPriceLevel);
			}

			return order;
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}
	}
	
	/**
	 * Returns the resting order with the given exchange order ID, or null if none exists.
	 * Exchange order IDs must be unique among resting orders. After an order becomes
	 * terminal and is removed from the order book, its ID may be reused.
	 *
	 * @param id the exchange order ID
	 * @return the resting order, or null if the ID is not in use
	 */
	public final Order getOrder(long id) {
		
		return orders.get(id);
	}
	
	public final int getNumberOfOrders() {
		
		return orders.size();
	}
	
	public final boolean isEmpty() {

		return orders.isEmpty();
	}
	
	public final PriceLevel head(Side side) {
		
		return head[side.index()];
	}
	
	public final PriceLevel tail(Side side) {
		
		return tail[side.index()];
	}
	
	public long getLastExecutedPrice() {
		
		return lastExecutedPrice;
	}
	
	public final boolean hasSpread() {
		return hasBestBid() && hasBestAsk();
	}

	/**
	 * Returns the difference between the best ask and best bid prices.
	 *
	 * @return the spread
	 * @throws ArithmeticException if the spread cannot be represented as a long
	 */
	public final long getSpread() {
		
		PriceLevel bestBid = head[Side.BUY.index()];
		
		PriceLevel bestAsk = head[Side.SELL.index()];
		
		return Math.subtractExact(bestAsk.getPrice(), bestBid.getPrice());
	}
	
	public final State getState() {

		PriceLevel bestBid = head[Side.BUY.index()];
		
		PriceLevel bestAsk = head[Side.SELL.index()];

		if (bestBid != null && bestAsk != null) {
			
			int priceComparison = Long.compare(bestAsk.getPrice(), bestBid.getPrice());
			
			if (priceComparison == 0) return State.LOCKED;
			
			if (priceComparison < 0) return State.CROSSED;
			
			return State.NORMAL;
			
		} else if (bestBid == null && bestAsk == null) {
			
			return State.EMPTY;
			
		} else {
			
			return State.ONESIDED;
		}
	}
	
	public final boolean hasTop(Side side) {
		
		return side.isBuy() ? hasBestBid() : hasBestAsk();
	}
	
	public final boolean hasAsks() {
		return hasBestAsk();
	}
	
	public final boolean hasBids() {
		return hasBestBid();
	}
	
	public final boolean hasBestBid() {
		
		return head[Side.BUY.index()] != null;
	}
	
	public final boolean hasBestAsk() {
		
		return head[Side.SELL.index()] != null;
	}
	
	public final long getBestPrice(Side side) {
		
		return side.isBuy() ? getBestBidPrice() : getBestAskPrice();
	}
	
	public final long getBestBidPrice() {
		
		int index = Side.BUY.index();
		
		return head[index].getPrice();
	}
	
	public final long getBestAskPrice() {
		
		int index = Side.SELL.index();
		
		return head[index].getPrice();
	}
	
	public final long getBestSize(Side side) {
		
		return side.isBuy() ? getBestBidSize() : getBestAskSize();
	}
	
	public final long getBestBidSize() {
		
		int index = Side.BUY.index();
		
		return head[index].getSize();
	}
	
	public final long getBestAskSize() {
		
		int index = Side.SELL.index();
		
		return head[index].getSize();
	}
	
	public final int getLevels(Side side) {
		
		return side.isBuy() ? getBidLevels() : getAskLevels();
	}
	
	public final int getBidLevels() {
		
		return levels[Side.BUY.index()];
	}
	
	public final int getAskLevels() {
		
		return levels[Side.SELL.index()];
	}
	
	public void showOrders() {
		System.out.println(orders());
	}
	
	public void showLevels() {
		System.out.println(levels());
	}
	
	public String levels() {
		StringBuilder sb = new StringBuilder(1024);
		levels(sb);
		return sb.toString();
	}
	
	public String orders() {
		StringBuilder sb = new StringBuilder(1024);
		orders(sb);
		return sb.toString();
	}
	
	public void levels(StringBuilder sb, Side side) {

		if (side == Side.SELL) {
			
			if (!hasAsks()) {
				return;
			}
		
			for(PriceLevel pl = head[side.index()]; pl != null; pl = pl.next) {
				
				String size = String.format("%6d", pl.getSize());
				String price = String.format("%9.2f", DoubleUtils.toDouble(pl.getPrice()));
				
				sb.append(size).append(" @ ").append(price);
				sb.append(" (orders=").append(pl.getOrders()).append(")\n");
			}
			
		} else {
			
			if (!hasBids()) {
				return;
			}
			
			for(PriceLevel pl = tail[side.index()]; pl != null; pl = pl.prev) {
				
				String size = String.format("%6d", pl.getSize());
				String price = String.format("%9.2f", DoubleUtils.toDouble(pl.getPrice()));
				
				sb.append(size).append(" @ ").append(price);
				sb.append(" (orders=").append(pl.getOrders()).append(")\n");
			}
		}
	}
	
	public void orders(StringBuilder sb, Side side) {

		if (side == Side.SELL) {
			
			if (!hasAsks()) {
				return;
			}
		
			for(PriceLevel pl = head[side.index()]; pl != null; pl = pl.next) {
				
				for(Order o = pl.head(); o != null; o = o.next) {

					String size = String.format("%6d", o.getOpenSize());
					String price = String.format("%9.2f", DoubleUtils.toDouble(o.getPrice()));
					
					sb.append(size).append(" @ ").append(price);
					sb.append(" (id=").append(o.getId()).append(")\n");
				}
			}
			
		} else {
			
			if (!hasBids()) {
				return;
			}
			
			for(PriceLevel pl = tail[side.index()]; pl != null; pl = pl.prev) {
				
				for(Order o = pl.head(); o != null; o = o.next) {

					String size = String.format("%6d", o.getOpenSize());
					String price = String.format("%9.2f", DoubleUtils.toDouble(o.getPrice()));
					
					sb.append(size).append(" @ ").append(price);
					sb.append(" (id=").append(o.getId()).append(")\n");
				}
			}
		}
	}
	
	public void orders(StringBuilder sb) {
		
		if (hasBids()) orders(sb, Side.BUY);
		if (hasSpread()) {
			sb.append("-------- ");
			String spread = String.format("%9.2f", DoubleUtils.toDouble(getSpread()));
			sb.append(spread).append('\n');
		} else {
			sb.append("-------- \n");
		}
		if (hasAsks()) orders(sb, Side.SELL);
	}
	
	public void levels(StringBuilder sb) {
		
		if (hasBids()) levels(sb, Side.BUY);
		if (hasSpread()) {
			sb.append("-------- ");
			String spread = String.format("%9.2f", DoubleUtils.toDouble(getSpread()));
			sb.append(spread).append('\n');
		} else {
			sb.append("-------- \n");
		}
		if (hasAsks()) levels(sb, Side.SELL);
	}
	
	private final void match(Order order) {
		
		int index = order.getSide().invertedIndex(); // NOTE: Inverted because bid hits ask and vice-versa
		
		OUTER:
		for(PriceLevel pl = head[index], nextPriceLevel; pl != null; pl = nextPriceLevel) {

			// Maker callbacks can release both objects, so save their links first.
			nextPriceLevel = pl.next;
			
			if (order.getType() != Type.MARKET && order.getSide().isOutside(order.getPrice(), pl.getPrice())) break;
			
			for(Order o = pl.head(), nextOrder; o != null; o = nextOrder) {

				nextOrder = o.next;
				
				if (!allowTradeToSelf && o.getClientId() == order.getClientId()) continue;
				
				long sizeToExecute = Math.min(order.getOpenSize(), o.getOpenSize());
				
				long priceExecuted = o.getPrice(); // always price improve the taker
				
				long ts = timestamper.nanoEpoch();
				
				lastExecutedPrice = priceExecuted;
				
				long execId1 = ++execId;
				long execId2 = ++execId;
				long matchId = ++this.matchId;
				
				o.execute(ts, ExecuteSide.MAKER, sizeToExecute, priceExecuted, execId1, matchId); // notify the maker first?
				
				order.execute(ts, ExecuteSide.TAKER, sizeToExecute, priceExecuted, execId2, matchId);
				
				if (order.isTerminal()) {
					
					break OUTER;
				}
			}
		}
	}
	
	private final PriceLevel findPriceLevel(Side side, long price) {
		
		PriceLevel foundPriceLevel = null;
		
		int index = side.index();
		
		for(PriceLevel pl = head[index]; pl != null; pl = pl.next) {
			
			if (side.isInside(price, pl.getPrice())) {
				
				foundPriceLevel = pl;
				
				break;
			}
		}
		
		PriceLevel priceLevel;
		
		if (foundPriceLevel == null) {
			
			priceLevel = priceLevelPool.get();

			priceLevel.init(security, side, price);
			
			levels[index]++;
			
			if (head[index] == null) {
				
				head[index] = tail[index] = priceLevel;
				
				priceLevel.next = priceLevel.prev = null;
				
			} else {
				
				tail[index].next = priceLevel;
				
				priceLevel.prev = tail[index];
				
				priceLevel.next = null;
				
				tail[index] = priceLevel;
			}
			
		} else if (foundPriceLevel.getPrice() != price) {
			
			priceLevel = priceLevelPool.get();
			
			priceLevel.init(security, side, price);
			
			levels[index]++;

			if (foundPriceLevel.prev != null) {
				
				foundPriceLevel.prev.next = priceLevel;
				
				priceLevel.prev = foundPriceLevel.prev;
			}

			priceLevel.next = foundPriceLevel;
			
			foundPriceLevel.prev = priceLevel;
			
			if (head[index] == foundPriceLevel) {
				
				head[index] = priceLevel;
			}
			
		} else {
			
			priceLevel = foundPriceLevel;
		}

		return priceLevel;
	}
	
	public Order createLimit(long clientId, CharSequence clientOrderId, long exchangeOrderId, Side side, long size, double price, TimeInForce tif) {
		return createLimit(clientId, clientOrderId, exchangeOrderId, side, size, DoubleUtils.toLong(price), tif);
	}

	public Order createLimit(long clientId, CharSequence clientOrderId, long exchangeOrderId, Side side, long size, long price, TimeInForce tif) {
		checkExternalListenerReentrancy("createLimit");
		return createOrder(clientId, clientOrderId, exchangeOrderId, side, size, price, Type.LIMIT, tif);
	}
	
	public Order createMarket(long clientId, CharSequence clientOrderId, long exchangeOrderId, Side side, long size) {
		checkExternalListenerReentrancy("createMarket");
		return createOrder(clientId, clientOrderId, exchangeOrderId, side, size, 0, Type.MARKET, null);
	}

	private void collectListenerException(OrderBookListener listener, OrderBookListenerException.Callback callback, long time, Order order, Exception exception) {
		collectListenerException(listener, callback, time, order, -1, -1, exception);
	}

	private void collectListenerException(OrderBookListener listener, OrderBookListenerException.Callback callback, long time, Order order,
			long executionId, long matchId, Exception exception) {
		if (listenerExceptions == null) listenerExceptions = new OrderBookListenerExceptions();
		listenerExceptions.add(new OrderBookListenerException(listener, callback, time, order, executionId, matchId, exception));
	}

	final boolean isOrderListenerExceptionReportingDeferred() {
		return deferListenerExceptionReporting;
	}

	final void deferOrderListenerExceptionReport(Order order) {
		if (deferredOrderListenerExceptionReports == null) {
			deferredOrderListenerExceptionReports = new ArrayList<Order>(4);
		}

		for(int i = 0; i < deferredOrderListenerExceptionReports.size(); i++) {
			if (deferredOrderListenerExceptionReports.get(i) == order) return;
		}

		deferredOrderListenerExceptionReports.add(order);
	}

	private void discardOrderBookListenerExceptions() {
		listenerExceptions = null;
	}

	private void discardDeferredOrderListenerExceptionReports() {
		if (deferredOrderListenerExceptionReports != null) {
			for(int i = 0; i < deferredOrderListenerExceptionReports.size(); i++) {
				deferredOrderListenerExceptionReports.get(i).discardListenerExceptions();
			}
			deferredOrderListenerExceptionReports = null;
		}
	}

	final void onOrderCallbacksFinished(boolean callbacksCompleted) {
		if (!callbacksCompleted) {
			discardOrderBookListenerExceptions();
			discardDeferredOrderListenerExceptionReports();
		} else {
			reportOrderBookListenerExceptionsIfNecessary();
		}
	}

	private void reportOrderBookListenerExceptionsIfNecessary() {

		if (deferListenerExceptionReporting) return;
		if (listenerExceptions == null) return;

		OrderBookListenerExceptions orderBookExceptions = listenerExceptions;
		listenerExceptions = null;

		int size = listeners.size();

		for(int i = 0; i < size; i++) {
			OrderBookListener listener = listeners.get(i);
			enterExternalListenerCallback();
			try {
				listener.onExceptionsThrown(this, orderBookExceptions);
			} catch(Exception ignored) {
				// Exceptions thrown while reporting listener exceptions are intentionally swallowed...
				// For someone to throw an exception here would be very silly
			} finally {
				exitExternalListenerCallback();
			}
		}
	}

	private void reportDeferredOrderListenerExceptions() {
		if (deferredOrderListenerExceptionReports == null) return;

		List<Order> orders = deferredOrderListenerExceptionReports;
		deferredOrderListenerExceptionReports = null;

		for(int i = 0; i < orders.size(); i++) {
			orders.get(i).reportListenerExceptions();
		}
	}

	/**
	 * Performs additional validation before an order is accepted. Implementations
	 * should inspect the order without mutating it and return a rejection reason, or
	 * null to accept it. If validation throws, the original failure is propagated
	 * after the unaccepted order has been returned to the object pool.
	 *
	 * @param order the unaccepted order to validate
	 * @return the rejection reason, or null if the order is valid
	 */
	protected RejectReason validateOrder(Order order) {
		return null;
	}

	private RejectReason validateOrderAndReleaseOnFailure(Order order) {
		boolean validationCompleted = false;

		try {
			RejectReason rejectReason = validateOrder(order);
			validationCompleted = true;
			return rejectReason;
		} finally {
			if (!validationCompleted) {
				order.discardBeforeAcceptance();
				orderPool.release(order);
			}
		}
	}
	
	private final Order fillOrCancel(Order order, long exchangeOrderId) {
		
		Type type = order.getType();
		
		if (type == Type.MARKET && order.getPrice() != 0) {
			
			order.reject(RejectReason.BAD_PRICE); // remember... the OrderListener callback will return the order to the pool...
			
			return order;
		}
		
		RejectReason rejectReason = validateOrderAndReleaseOnFailure(order);

		if (rejectReason != null) {
		
			order.reject(rejectReason); // remember... the OrderListener callback will return the order to the pool...
			
			return order;
		}

		// always accept first...
		order.accept(exchangeOrderId);
		
		// walk through the book matching:

		match(order);
		
		// check if there is quantity left that needs to be canceled:

		if (!order.isTerminal()) {
			
			if (type == Type.MARKET) {
				
				order.cancel(CancelReason.NO_LIQUIDITY);
					
			} else {
				
				CancelReason cancelReason = CancelReason.MISSED;
				
				if (!hasTop(order.getOtherSide())) {
					cancelReason = CancelReason.NO_LIQUIDITY;
				}
			
				order.cancel(cancelReason);
			}
		}
		
		return order;
	}
	
	private Order fillOrRest(Order order, long exchangeOrderId) {
		
		RejectReason rejectReason = validateOrderAndReleaseOnFailure(order);

		if (rejectReason != null) {
		
			order.reject(rejectReason); // remember... the OrderListener callback will return the order to the pool...
			
			return order;
		}
		
		// always accept first:
		order.accept(exchangeOrderId);
		
		// something needs to be executed first...
			
		match(order);
			
		if (order.isTerminal()) {
				
			return order;
		}
		
		// rest the remaining in the book:
		// but first check if it will not cross its own order
		if (!allowTradeToSelf && hasTop(order.getOtherSide()) && order.getSide().isInside(order.getPrice(), getBestPrice(order.getOtherSide()))) {
			
			CancelReason cancelReason = CancelReason.CROSSED;
			
			order.cancel(cancelReason);
			
		} else {
		
			rest(order);
			
		}
		
		return order;
	}
	
	final Order createOrder(long clientId, CharSequence clientOrderId, long exchangeOrderId, Side side, long size, long price, Type type,TimeInForce tif) {

		checkExternalListenerReentrancy("createOrder");
		
		boolean listenerExceptionReportingWasDeferred = deferListenerExceptionReporting;
		deferListenerExceptionReporting = true;
		boolean operationCompleted = false;
		
		try {

			Order order = getOrder(clientId, clientOrderId, security, side, size, price, type, tif);

			if (size <= 0) {
				order.reject(RejectReason.BAD_SIZE);
			} else if (order.getSide() == null) {
				order.reject(RejectReason.BAD_SIDE);
			} else if (type == Type.LIMIT && tif == null) {
				order.reject(RejectReason.BAD_TIF);
			} else if (exchangeOrderId <= 0) {
				order.reject(RejectReason.BAD_EXCHANGE_ORDER_ID);
			} else if (orders.containsKey(exchangeOrderId)) {
				order.reject(RejectReason.DUPLICATE_EXCHANGE_ORDER_ID);
			} else if (tif == TimeInForce.IOC || type == Type.MARKET) {
				order = fillOrCancel(order, exchangeOrderId);
			} else {
				order = fillOrRest(order, exchangeOrderId);
			}

			operationCompleted = true;
			return order;
			
		} finally {
			deferListenerExceptionReporting = listenerExceptionReportingWasDeferred;
			if (!operationCompleted) {
				discardOrderBookListenerExceptions();
				discardDeferredOrderListenerExceptionReports();
			} else if (!deferListenerExceptionReporting) {
				reportDeferredOrderListenerExceptions();
				reportOrderBookListenerExceptionsIfNecessary();
			}
		}
	}
	
	public long rollTo(OrderBook newOrderBook) {
		return rollTo(newOrderBook, 1);
	}

	/**
	 * Rolls this order book's GTC orders to another order book for the same security.
	 * Exchange order IDs already used by resting orders in the destination are skipped.
	 *
	 * @param newOrderBook the destination order book
	 * @param firstExchangeOrderId the first exchange order ID to consider
	 * @return the next exchange order ID after those considered by the roll
	 */
	public long rollTo(OrderBook newOrderBook, long firstExchangeOrderId) {

		checkExternalListenerReentrancy("rollTo");
		newOrderBook.checkExternalListenerReentrancy("rollTo");

		if (newOrderBook == this) {
			throw new IllegalArgumentException("Cannot roll an order book to itself");
		}

		if (!security.equals(newOrderBook.security)) {
			throw new IllegalArgumentException("Cannot roll between different securities: " + security + " and " + newOrderBook.security);
		}

		boolean listenerExceptionReportingWasDeferred = deferListenerExceptionReporting;
		deferListenerExceptionReporting = true;
		boolean operationCompleted = false;
		
		try {
			if (hasBids()) {
			
				for(PriceLevel pl = head(Side.BUY), nextPriceLevel; pl != null; pl = nextPriceLevel) {

					// A successful roll releases the source order and possibly its level.
					nextPriceLevel = pl.next;
				
					for(Order o = pl.head(), nextOrder; o != null; o = nextOrder) {

						nextOrder = o.next;
					
						if (o.getTimeInForce() != TimeInForce.GTC) continue;

						while(newOrderBook.orders.containsKey(firstExchangeOrderId)) firstExchangeOrderId++;

						Order rolledOrder = newOrderBook.createLimit(o.getClientId(), o.getClientOrderId(), firstExchangeOrderId++, o.getSide(), o.getOpenSize(), o.getPrice(), TimeInForce.GTC);
					
						if (rolledOrder.isAccepted()) o.cancel(CancelReason.ROLLED);
					}
				}
			}

			if (hasAsks()) {
			
				for(PriceLevel pl = head(Side.SELL), nextPriceLevel; pl != null; pl = nextPriceLevel) {

					// A successful roll releases the source order and possibly its level.
					nextPriceLevel = pl.next;
				
					for(Order o = pl.head(), nextOrder; o != null; o = nextOrder) {

						nextOrder = o.next;
					
						if (o.getTimeInForce() != TimeInForce.GTC) continue;

						while(newOrderBook.orders.containsKey(firstExchangeOrderId)) firstExchangeOrderId++;

						Order rolledOrder = newOrderBook.createLimit(o.getClientId(), o.getClientOrderId(), firstExchangeOrderId++, o.getSide(), o.getOpenSize(), o.getPrice(), TimeInForce.GTC);
					
						if (rolledOrder.isAccepted()) o.cancel(CancelReason.ROLLED);
					}
				}
			}

			operationCompleted = true;
			return firstExchangeOrderId;

		} finally {
			deferListenerExceptionReporting = listenerExceptionReportingWasDeferred;
			if (!operationCompleted) {
				discardOrderBookListenerExceptions();
				discardDeferredOrderListenerExceptionReports();
			} else if (!deferListenerExceptionReporting) {
				reportDeferredOrderListenerExceptions();
				reportOrderBookListenerExceptionsIfNecessary();
			}
		}
	}
	
	public void expire() {

		checkExternalListenerReentrancy("expire");
		
		boolean listenerExceptionReportingWasDeferred = deferListenerExceptionReporting;
		deferListenerExceptionReporting = true;
		boolean operationCompleted = false;
		
		try {
			Iterator<Order> iter = orders.iterator();

			while(iter.hasNext()) {
				
				Order order = iter.next();

				if (order.getTimeInForce() != TimeInForce.DAY) continue;

				iter.remove(); // important otherwise you get a ConcurrentModificationException!

				order.cancel(CancelReason.EXPIRED);
			}

			operationCompleted = true;
			
		} finally {
			deferListenerExceptionReporting = listenerExceptionReportingWasDeferred;
			if (!operationCompleted) {
				discardOrderBookListenerExceptions();
				discardDeferredOrderListenerExceptionReports();
			} else if (!deferListenerExceptionReporting) {
				reportDeferredOrderListenerExceptions();
				reportOrderBookListenerExceptionsIfNecessary();
			}
		}
	}
	
	public final void purge() {

		checkExternalListenerReentrancy("purge");
		
		boolean listenerExceptionReportingWasDeferred = deferListenerExceptionReporting;
		deferListenerExceptionReporting = true;
		boolean operationCompleted = false;
		
		try {
			
			Iterator<Order> iter = orders.iterator();

			while(iter.hasNext()) {
				Order order = iter.next();

				iter.remove(); // important otherwise you get a ConcurrentModificationException!

				order.cancel(CancelReason.PURGED);
			}

			operationCompleted = true;
			
		} finally {
			deferListenerExceptionReporting = listenerExceptionReportingWasDeferred;
			if (!operationCompleted) {
				discardOrderBookListenerExceptions();
				discardDeferredOrderListenerExceptionReports();
			} else if (!deferListenerExceptionReporting) {
				reportDeferredOrderListenerExceptions();
				reportOrderBookListenerExceptionsIfNecessary();
			}
		}
	}
	
	private final void rest(Order order) {
		
		PriceLevel priceLevel = findPriceLevel(order.getSide(), order.getPrice());
		
		order.setPriceLevel(priceLevel);
		
		priceLevel.addOrder(order);
		
		orders.put(order.getId(), order);
		
		order.rest();
	}
	
	private Order getOrder(long clientId, CharSequence clientOrderId, String security, Side side, long size, long price, Type type, TimeInForce tif) {
		
		Order order = orderPool.get();
		
		order.init(this, timestamper, clientId, clientOrderId, 0, security, side, size, price, type, tif);
		
		order.addInternalListener(internalOrderListener);
		
		return order;
	}

	final OrderListener internalOrderListener() {
		return internalOrderListener;
	}
	
	private void removeOrder(Order order) {

		/*
		 * Returning these objects to their pools does not modify them. External
		 * callbacks that follow can inspect them because reentrant operations on
		 * the same order book are blocked for the duration of callback dispatch.
		 */
		
		PriceLevel priceLevel = order.getPriceLevel();

		if (priceLevel != null) {
			// Advance cached traversals before pooled objects can be reused.
			priceTimePriorityIterator.orderRemoved(order);
			reversePriceTimePriorityIterator.orderRemoved(order);
		}
		
		if (priceLevel != null && priceLevel.isEmpty()) {
			
			// remove priceLevel...
			
			if (priceLevel.prev != null) {
				
				priceLevel.prev.next = priceLevel.next;
			}
			
			if (priceLevel.next != null) {
				
				priceLevel.next.prev = priceLevel.prev;
			}
			
			int index = order.getSide().index();
			
			if (tail[index] == priceLevel) {
				
				tail[index] = priceLevel.prev;
			}
			
			if (head[index] == priceLevel) {
				
				head[index] = priceLevel.next;
			}
			
			levels[index]--;
			
			priceLevelPool.release(priceLevel);
		}
		
		orders.remove(order.getId());
		
		orderPool.release(order);
	}
	
	private final class InternalOrderListener implements OrderListener {

	@Override
    public void onOrderReduced(long time, Order order, long canceledSize, long newSize, CancelReason reason) {

		checkExternalListenerReentrancy("onOrderReduced");
		
		int size = listeners.size();

		for(int i = 0; i < size; i++) {
			OrderBookListener listener = listeners.get(i);
			enterExternalListenerCallback();
			try {
				listener.onOrderReduced(OrderBook.this, time, order, canceledSize, newSize, reason);
			} catch(Exception e) {
				collectListenerException(listener, OrderBookListenerException.Callback.ON_ORDER_REDUCED, time, order, e);
			} finally {
				exitExternalListenerCallback();
			}
		}

		// The Order reports after its internal and external OrderListeners have finished
	}

	@Override
    public void onOrderCanceled(long time, Order order, long canceledSize, CancelReason reason) {

		checkExternalListenerReentrancy("onOrderCanceled");
		
		removeOrder(order);
		
		int size = listeners.size();

		for(int i = 0; i < size; i++) {
			OrderBookListener listener = listeners.get(i);
			enterExternalListenerCallback();
			try {
				listener.onOrderCanceled(OrderBook.this, time, order, canceledSize, reason);
			} catch(Exception e) {
				collectListenerException(listener, OrderBookListenerException.Callback.ON_ORDER_CANCELED, time, order, e);
			} finally {
				exitExternalListenerCallback();
			}
		}

		// The Order reports after onOrderCanceled and onOrderTerminated have both finished
	}

	@Override
    public void onOrderExecuted(long time, Order order, ExecuteSide execSide, long sizeExecuted, long priceExecuted, long executionId, long matchId) {

		checkExternalListenerReentrancy("onOrderExecuted");
		
		if (order.isTerminal()) {
			
			removeOrder(order);
		}
		
		int size = listeners.size();

		for(int i = 0; i < size; i++) {
			OrderBookListener listener = listeners.get(i);
			enterExternalListenerCallback();
			try {
				listener.onOrderExecuted(OrderBook.this, time, order, execSide, sizeExecuted, priceExecuted, executionId, matchId);
			} catch(Exception e) {
				collectListenerException(listener, OrderBookListenerException.Callback.ON_ORDER_EXECUTED, time, order, executionId, matchId, e);
			} finally {
				exitExternalListenerCallback();
			}
		}

		// Execution is an intermediate callback, so listener exceptions must not be reported here.
		// Any operation that executes an order must defer reporting until all affected orders have
		// been updated and the complete OrderBook operation has finished.
	}

	@Override
	public void onOrderAccepted(long time, Order order) {

		checkExternalListenerReentrancy("onOrderAccepted");
		
		int size = listeners.size();

		for(int i = 0; i < size; i++) {
			OrderBookListener listener = listeners.get(i);
			enterExternalListenerCallback();
			try {
				listener.onOrderAccepted(OrderBook.this, time, order);
			} catch(Exception e) {
				collectListenerException(listener, OrderBookListenerException.Callback.ON_ORDER_ACCEPTED, time, order, e);
			} finally {
				exitExternalListenerCallback();
			}
		}

		// Acceptance is an intermediate callback, so listener exceptions must not be reported here.
		// Any operation that accepts an order must defer reporting until the complete OrderBook
		// operation has finished.
	}
	
	@Override
	public void onOrderRejected(long time, Order order, Order.RejectReason reason) {

		checkExternalListenerReentrancy("onOrderRejected");
	
		removeOrder(order);
		
		int size = listeners.size();

		for(int i = 0; i < size; i++) {
			OrderBookListener listener = listeners.get(i);
			enterExternalListenerCallback();
			try {
				listener.onOrderRejected(OrderBook.this, time, order, reason);
			} catch(Exception e) {
				collectListenerException(listener, OrderBookListenerException.Callback.ON_ORDER_REJECTED, time, order, e);
			} finally {
				exitExternalListenerCallback();
			}
		}

		// The Order reports after its internal and external OrderListeners have finished
	}

	@Override
    public void onOrderRested(long time, Order order, long restSize, long restPrice) {

		checkExternalListenerReentrancy("onOrderRested");
	    
		int size = listeners.size();

		for(int i = 0; i < size; i++) {
			OrderBookListener listener = listeners.get(i);
			enterExternalListenerCallback();
			try {
				listener.onOrderRested(OrderBook.this, time, order, restSize, restPrice);
			} catch(Exception e) {
				collectListenerException(listener, OrderBookListenerException.Callback.ON_ORDER_RESTED, time, order, e);
			} finally {
				exitExternalListenerCallback();
			}
		}

		// Resting is part of the operation that accepts and processes an order, so listener exceptions
		// must not be reported here. That operation must report after the complete OrderBook operation
		// has finished.
	}
	
	@Override
	public void onOrderTerminated(long time, Order order) {

		checkExternalListenerReentrancy("onOrderTerminated");
		
		int size = listeners.size();
		
		for(int i = 0; i < size; i++) {
			OrderBookListener listener = listeners.get(i);
			enterExternalListenerCallback();
			try {
				listener.onOrderTerminated(OrderBook.this, time, order);
			} catch(Exception e) {
				collectListenerException(listener, OrderBookListenerException.Callback.ON_ORDER_TERMINATED, time, order, e);
			} finally {
				exitExternalListenerCallback();
			}
		}

		// The Order reports after its internal and external OrderListeners have finished
	}

	@Override
	public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {

		// Internal OrderListeners do not receive external listener exception reports
	}
	}
	
	@Override
	public String toString() {
		return security;
	}
}
