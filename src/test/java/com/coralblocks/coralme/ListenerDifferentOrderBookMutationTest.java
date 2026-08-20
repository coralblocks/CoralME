/*
 * Copyright 2015-2024 (c) CoralBlocks LLC - http://www.coralblocks.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.coralblocks.coralme;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.coralblocks.coralme.ListenerSafetyTestSupport.OrderListenerAdapter;
import com.coralblocks.coralme.Order.CancelReason;
import com.coralblocks.coralme.Order.ExecuteSide;
import com.coralblocks.coralme.Order.RejectReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;
import com.coralblocks.coralme.Order.Type;

@RunWith(Parameterized.class)
public class ListenerDifferentOrderBookMutationTest {

	private static enum Callback {
		ACCEPTED,
		RESTED,
		REDUCED,
		EXECUTED,
		CANCELED,
		REJECTED,
		TERMINATED
	}

	@Parameters(name = "{0}")
	public static Collection<Object[]> parameters() {
		return Arrays.asList(Arrays.stream(Callback.values())
				.map(callback -> new Object[] { callback })
				.toArray(Object[][]::new));
	}

	private final Callback callback;

	public ListenerDifferentOrderBookMutationTest(Callback callback) {
		this.callback = callback;
	}

	@Test
	public void test_OrderBookListenerCanMutateDifferentOrderBookFromEveryCallback() {
		final OrderBook otherBook = new OrderBook("MSFT");
		final int[] mutations = new int[1];
		final int[] traversals = new int[1];
		final int[] reports = new int[1];
		OrderBook book = callback == Callback.REJECTED ? new RejectingOrderBook() : new OrderBook("AAPL");

		book.addListener(new OrderBookAdapter() {
			private void mutate(Callback currentCallback) {
				if (mutations[0] != 0 || callback != currentCallback) return;
				mutations[0]++;
				otherBook.createLimit(1, "other", 1, Side.BUY, 100, 100, TimeInForce.GTC);
				if (otherBook.iterator(Side.BUY).next() == otherBook.getOrder(1)) traversals[0]++;
			}

			@Override
			public void onOrderReduced(OrderBook orderBook, long time, Order order, long canceledSize,
					long reduceNewTotalSize, CancelReason cancelReason) {
				mutate(Callback.REDUCED);
			}

			@Override
			public void onOrderCanceled(OrderBook orderBook, long time, Order order, long canceledSize,
					CancelReason cancelReason) {
				mutate(Callback.CANCELED);
			}

			@Override
			public void onOrderExecuted(OrderBook orderBook, long time, Order order, ExecuteSide executeSide,
					long executeSize, long executePrice, long executeId, long executeMatchId) {
				mutate(Callback.EXECUTED);
			}

			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				mutate(Callback.ACCEPTED);
			}

			@Override
			public void onOrderRejected(OrderBook orderBook, long time, Order order, RejectReason rejectReason) {
				mutate(Callback.REJECTED);
			}

			@Override
			public void onOrderRested(OrderBook orderBook, long time, Order order, long restSize, long restPrice) {
				mutate(Callback.RESTED);
			}

			@Override
			public void onOrderTerminated(OrderBook orderBook, long time, Order order) {
				mutate(Callback.TERMINATED);
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reports[0]++;
			}
		});

		triggerOrderBookCallback(book);

		assertEquals(1, mutations[0]);
		assertEquals(1, traversals[0]);
		assertEquals(0, reports[0]);
		assertEquals(1, otherBook.getNumberOfOrders());
	}

	@Test
	public void test_OrderListenerCanMutateDifferentOrderBookFromEveryCallback() {
		final OrderBook otherBook = new OrderBook("MSFT");
		final int[] mutations = new int[1];
		final int[] traversals = new int[1];
		final int[] reports = new int[1];
		OrderBook book = new OrderBook("AAPL");
		Order order = new Order();
		order.init(book, book.getTimestamper(), 1, "1", 1, "AAPL", Side.BUY, 100, 100,
				Type.LIMIT, TimeInForce.GTC);
		order.addListener(new OrderListenerAdapter() {
			private void mutate(Callback currentCallback) {
				if (mutations[0] != 0 || callback != currentCallback) return;
				mutations[0]++;
				otherBook.createLimit(1, "other", 1, Side.BUY, 100, 100, TimeInForce.GTC);
				if (otherBook.iterator(Side.BUY).next() == otherBook.getOrder(1)) traversals[0]++;
			}

			@Override
			public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize, CancelReason cancelReason) {
				mutate(Callback.REDUCED);
			}

			@Override
			public void onOrderCanceled(long time, Order order, long canceledSize, CancelReason cancelReason) {
				mutate(Callback.CANCELED);
			}

			@Override
			public void onOrderExecuted(long time, Order order, ExecuteSide executeSide, long executeSize,
					long executePrice, long executeId, long executeMatchId) {
				mutate(Callback.EXECUTED);
			}

			@Override
			public void onOrderAccepted(long time, Order order) {
				mutate(Callback.ACCEPTED);
			}

			@Override
			public void onOrderRejected(long time, Order order, RejectReason rejectReason) {
				mutate(Callback.REJECTED);
			}

			@Override
			public void onOrderRested(long time, Order order, long restSize, long restPrice) {
				mutate(Callback.RESTED);
			}

			@Override
			public void onOrderTerminated(long time, Order order) {
				mutate(Callback.TERMINATED);
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				reports[0]++;
			}
		});

		triggerOrderCallback(order);

		assertEquals(1, mutations[0]);
		assertEquals(1, traversals[0]);
		assertEquals(0, reports[0]);
		assertEquals(1, otherBook.getNumberOfOrders());
	}

	private void triggerOrderBookCallback(OrderBook book) {
		switch(callback) {
		case ACCEPTED:
		case RESTED:
			book.createLimit(1, "resting", 1, Side.BUY, 100, 100, TimeInForce.GTC);
			break;
		case REDUCED:
			book.createLimit(1, "reduced", 1, Side.BUY, 100, 100, TimeInForce.GTC).reduceTo(60);
			break;
		case EXECUTED:
			book.createLimit(1, "maker", 1, Side.SELL, 100, 100, TimeInForce.GTC);
			book.createLimit(2, "taker", 2, Side.BUY, 100, 100, TimeInForce.GTC);
			break;
		case CANCELED:
		case TERMINATED:
			book.createLimit(1, "canceled", 1, Side.BUY, 100, 100, TimeInForce.GTC).cancel();
			break;
		case REJECTED:
			book.createLimit(1, "rejected", 1, Side.BUY, 100, 100, TimeInForce.GTC);
			break;
		}
	}

	private void triggerOrderCallback(Order order) {
		switch(callback) {
		case ACCEPTED:
			order.accept(1);
			break;
		case RESTED:
			order.rest();
			break;
		case REDUCED:
			order.reduceTo(60);
			break;
		case EXECUTED:
			order.execute(1, 50);
			break;
		case CANCELED:
		case TERMINATED:
			order.cancel();
			break;
		case REJECTED:
			order.reject(RejectReason.BAD_TYPE);
			break;
		}
	}

	private static class RejectingOrderBook extends OrderBook {

		private RejectingOrderBook() {
			super("AAPL");
		}

		@Override
		protected RejectReason validateOrder(Order order) {
			return RejectReason.BAD_TYPE;
		}
	}
}
