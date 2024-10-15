/* 
 * Copyright 2023 (c) CoralBlocks - http://www.coralblocks.com
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

import com.coralblocks.coralme.util.ArrayObjectPool;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.coralblocks.coralme.Order.CancelReason;
import com.coralblocks.coralme.Order.ExecuteSide;
import com.coralblocks.coralme.Order.RejectReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;
import com.coralblocks.coralme.Order.Type;
import com.coralblocks.coralme.util.DoubleUtils;
import com.coralblocks.coralme.util.LinkedObjectPool;
import com.coralblocks.coralme.util.LongMap;
import com.coralblocks.coralme.util.ObjectPool;
import com.coralblocks.coralme.util.SystemTimestamper;
import com.coralblocks.coralme.util.Timestamper;

public class OrderBook implements OrderListener {
	
	private static final boolean DEFAULT_ALLOW_TRADE_TO_SELF = true;
	
	private static final Timestamper TIMESTAMPER = new SystemTimestamper();
	
	public static enum State { NORMAL, LOCKED, CROSSED, ONESIDED, EMPTY }
	
	private final ObjectPool<Order> orderPool = new ArrayObjectPool<Order>(32, Order::new);
	
	private final ObjectPool<PriceLevel> priceLevelPool = new ArrayObjectPool<PriceLevel>(32, PriceLevel::new);
	
	private long execId = 0;
	
	private long matchId = 0;
	
	private PriceLevel[] head = new PriceLevel[2];
	
	private PriceLevel[] tail = new PriceLevel[2];
	
	private int[] levels = new int[] { 0, 0 };
	
	private final LongMap<Order> orders = new LongMap<Order>();
	
	private final String security;
	
	private long lastExecutedPrice = Long.MAX_VALUE;
	
	private final List<OrderBookListener> listeners = new ArrayList<OrderBookListener>(8);
	
	private final Timestamper timestamper;
	
	private final boolean allowTradeToSelf;
	
	
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
		 List<OrderBookListener> listeners = orderBook.getListeners();
		 for(int i = 0; i < listeners.size(); i++) {
			 addListener(listeners.get(i));
		 }
	}

	public OrderBook(String security, Timestamper timestamper, OrderBookListener listener, boolean allowTradeToSelf) {
		
		this.security = security;
		
		this.timestamper = timestamper;
		
		this.allowTradeToSelf = allowTradeToSelf;
		
		if (listener != null) listeners.add(listener);
	}
	
	public void addListener(OrderBookListener listener) {
		if (!listeners.contains(listener)) listeners.add(listener);
	}
	
	public void removeListener(OrderBookListener listener) {
		listeners.remove(listener);
	}
	
	public List<OrderBookListener> getListeners() {
		return listeners;
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
	
	public final LongMap<Order> getOrders() {
		return orders;
	}
	
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
	
	public final long getSpread() {
		
		PriceLevel bestBid = head[Side.BUY.index()];
		
		PriceLevel bestAsk = head[Side.SELL.index()];
		
		assert bestBid != null && bestAsk != null;
		
		return bestAsk.getPrice() - bestBid.getPrice();
	}
	
	public final State getState() {

		PriceLevel bestBid = head[Side.BUY.index()];
		
		PriceLevel bestAsk = head[Side.SELL.index()];

		if (bestBid != null && bestAsk != null) {
			
			long spread = bestAsk.getPrice() - bestBid.getPrice();
			
			if (spread == 0) return State.LOCKED;
			
			if (spread < 0) return State.CROSSED;
			
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
		
		assert head[index] != null;
		
		return head[index].getPrice();
	}
	
	public final long getBestAskPrice() {
		
		int index = Side.SELL.index();
		
		assert head[index] != null;
		
		return head[index].getPrice();
	}
	
	public final long getBestSize(Side side) {
		
		return side.isBuy() ? getBestBidSize() : getBestAskSize();
	}
	
	public final long getBestBidSize() {
		
		int index = Side.BUY.index();
		
		assert head[index] != null;
		
		return head[index].getSize();
	}
	
	public final long getBestAskSize() {
		
		int index = Side.SELL.index();
		
		assert head[index] != null;
		
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
		for(PriceLevel pl = head[index]; pl != null; pl = pl.next) {
			
			if (order.getType() != Type.MARKET && order.getSide().isOutside(order.getPrice(), pl.getPrice())) break;
			
			for(Order o = pl.head(); o != null; o = o.next) {
				
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
		return createOrder(clientId, clientOrderId, exchangeOrderId, side, size, price, Type.LIMIT, tif);
	}
	
	public Order createMarket(long clientId, CharSequence clientOrderId, long exchangeOrderId, Side side, long size) {
		return createOrder(clientId, clientOrderId, exchangeOrderId, side, size, 0, Type.MARKET, null);
	}
	
	protected RejectReason validateOrder(Order order) {
		return null;
	}
	
	private final Order fillOrCancel(Order order, long exchangeOrderId) {
		
		Type type = order.getType();
		
		if (type == Type.MARKET && order.getPrice() != 0) {
			
			order.reject(timestamper.nanoEpoch(), RejectReason.BAD_PRICE); // remember... the OrderListener callback will return the order to the pool...
			
			return order;
		}
		
		RejectReason rejectReason = validateOrder(order);

		if (rejectReason != null) {
		
			order.reject(timestamper.nanoEpoch(), rejectReason); // remember... the OrderListener callback will return the order to the pool...
			
			return order;
		}

		// always accept first...
		order.accept(timestamper.nanoEpoch(), exchangeOrderId);
		
		// walk through the book matching:

		match(order);
		
		// check if there is quantity left that needs to be canceled:

		if (!order.isTerminal()) {
			
			if (type == Type.MARKET) {
				
				order.cancel(timestamper.nanoEpoch(), CancelReason.NO_LIQUIDITY);
					
			} else {
				
				CancelReason cancelReason = CancelReason.MISSED;
				
				if (!hasTop(order.getOtherSide())) {
					cancelReason = CancelReason.NO_LIQUIDITY;
				}
			
				order.cancel(timestamper.nanoEpoch(), cancelReason);
			}
		}
		
		return order;
	}
	
	private Order fillOrRest(Order order, long exchangeOrderId) {
		
		RejectReason rejectReason = validateOrder(order);

		if (rejectReason != null) {
		
			order.reject(timestamper.nanoEpoch(), rejectReason); // remember... the OrderListener callback will return the order to the pool...
			
			return order;
		}
		
		// always accept first:
		order.accept(timestamper.nanoEpoch(), exchangeOrderId);
		
		// something needs to be executed first...
			
		match(order);
			
		if (order.isTerminal()) {
				
			return order;
		}
		
		// rest the remaining in the book:
		// but first check if it will not cross its own order
		if (!allowTradeToSelf && hasTop(order.getOtherSide()) && order.getSide().isInside(order.getPrice(), getBestPrice(order.getOtherSide()))) {
			
			CancelReason cancelReason = CancelReason.CROSSED;
			
			order.cancel(timestamper.nanoEpoch(), cancelReason);
			
		} else {
		
			rest(order);
			
		}
		
		return order;
	}
	
	final Order createOrder(long clientId, CharSequence clientOrderId, long exchangeOrderId, Side side, long size, long price, Type type,TimeInForce tif) {
		
		Order order = getOrder(clientId, clientOrderId, security, side, size, price, type, tif);
		
		if (tif == TimeInForce.IOC || type == Type.MARKET) {
			
			return fillOrCancel(order, exchangeOrderId);
			
		} else {
			
			return fillOrRest(order, exchangeOrderId);
		}
		
	}
	
	public long rollTo(OrderBook newOrderBook) {
		return rollTo(newOrderBook, 1);
	}
	
	public long rollTo(OrderBook newOrderBook, long firstExchangeOrderId) {
		
		if (hasBids()) {
			
			for(PriceLevel pl = head(Side.BUY); pl != null; pl = pl.next) {
				
				for(Order o = pl.head(); o != null; o = o.next) {
					
					if (o.getTimeInForce() != TimeInForce.GTC) continue;
					
					newOrderBook.createLimit(o.getClientId(), o.getClientOrderId(), firstExchangeOrderId++, o.getSide(), o.getOpenSize(), o.getPrice(), TimeInForce.GTC);
					
					o.cancel(timestamper.nanoEpoch(), CancelReason.ROLLED);
				}
			}
		}
		
		if (hasAsks()) {
			
			for(PriceLevel pl = head(Side.SELL); pl != null; pl = pl.next) {
				
				for(Order o = pl.head(); o != null; o = o.next) {
					
					if (o.getTimeInForce() != TimeInForce.GTC) continue;
					
					newOrderBook.createLimit(o.getClientId(), o.getClientOrderId(), firstExchangeOrderId++, o.getSide(), o.getOpenSize(), o.getPrice(), TimeInForce.GTC);
					
					o.cancel(timestamper.nanoEpoch(), CancelReason.ROLLED);
				}
			}
		}
		
		return firstExchangeOrderId;
	}
	
	public void expire() {
		
		Iterator<Order> iter = orders.iterator();
		
		while(iter.hasNext()) {
			
			Order order = iter.next();
			
			assert order.isTerminal() == false;
			
			if (order.getTimeInForce() != TimeInForce.DAY) continue;
			
			iter.remove(); // important otherwise you get a ConcurrentModificationException!
			
			order.cancel(timestamper.nanoEpoch(), CancelReason.EXPIRED);
		}
	}
	
	public final void purge() {
		
		Iterator<Order> iter = orders.iterator();
		
		while(iter.hasNext()) {
			
			Order order = iter.next();
			
			assert order.isTerminal() == false;
			
			iter.remove(); // important otherwise you get a ConcurrentModificationException!
			
			order.cancel(timestamper.nanoEpoch(), CancelReason.PURGED);
		}
	}
	
	private final void rest(Order order) {
		
		PriceLevel priceLevel = findPriceLevel(order.getSide(), order.getPrice());
		
		order.setPriceLevel(priceLevel);
		
		priceLevel.addOrder(order);
		
		orders.put(order.getId(), order);
		
		order.rest(timestamper.nanoEpoch());
	}
	
	private Order getOrder(long clientId, CharSequence clientOrderId, String security, Side side, long size, long price, Type type, TimeInForce tif) {
		
		Order order = orderPool.get();
		
		order.init(clientId, clientOrderId, 0, security, side, size, price, type, tif);
		
		order.addListener(this);
		
		return order;
	}
	
	private void removeOrder(Order order) {
		
		PriceLevel priceLevel = order.getPriceLevel();
		
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
	
	@Override
    public void onOrderReduced(long time, Order order, long newSize) {
		
		int size = listeners.size();

		for(int i = 0; i < size; i++) {
			listeners.get(i).onOrderReduced(this, time, order, newSize);
		}
    }

	@Override
    public void onOrderCanceled(long time, Order order, CancelReason reason) {
		
		removeOrder(order);
		
		int size = listeners.size();

		for(int i = 0; i < size; i++) {
			listeners.get(i).onOrderCanceled(this, time, order, reason);
		}
    }

	@Override
    public void onOrderExecuted(long time, Order order, ExecuteSide execSide, long sizeExecuted, long priceExecuted, long executionId, long matchId) {
		
		if (order.isTerminal()) {
			
			removeOrder(order);
		}
		
		int size = listeners.size();

		for(int i = 0; i < size; i++) {
			listeners.get(i).onOrderExecuted(this, time, order, execSide, sizeExecuted, priceExecuted, executionId, matchId);
		}
    }

	@Override
	public void onOrderAccepted(long time, Order order) {
		
		int size = listeners.size();

		for(int i = 0; i < size; i++) {
			listeners.get(i).onOrderAccepted(this, time, order);
		}
		
	}
	
	@Override
	public void onOrderRejected(long time, Order order, Order.RejectReason reason) {
	
		removeOrder(order);
		
		int size = listeners.size();

		for(int i = 0; i < size; i++) {
			listeners.get(i).onOrderRejected(this, time, order, reason);
		}
	}

	@Override
    public void onOrderRested(long time, Order order, long restSize, long restPrice) {
	    
		int size = listeners.size();

		for(int i = 0; i < size; i++) {
			listeners.get(i).onOrderRested(this, time, order, restSize, restPrice);
		}
    }
	
	@Override
	public void onOrderTerminated(long time, Order order) {
		
		int size = listeners.size();
		
		for(int i = 0; i < size; i++) {
			listeners.get(i).onOrderTerminated(this, time, order);
		}
	}
	
	@Override
	public String toString() {
		return security;
	}
}
