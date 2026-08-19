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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.coralblocks.coralme.ListenerSafetyTestSupport.OrderListenerAdapter;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;

public class ListenerSameBookCrossOrderReentrancyTest {

	@Test
	public void test_OrderBookListenerCannotCancelDifferentOrderInSameBook() {
		final OrderBook book = new OrderBook("AAPL");
		final Order protectedOrder = book.createLimit(1, "protected", 1, Side.BUY, 100, 99, TimeInForce.GTC);
		final OrderBookListenerExceptions[] reported = new OrderBookListenerExceptions[1];
		book.addListener(new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				protectedOrder.cancel();
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reported[0] = exceptions;
			}
		});

		Order triggeringOrder = book.createLimit(2, "trigger", 2, Side.BUY, 100, 98, TimeInForce.GTC);

		assertReentrantFailure(reported[0].get(0).getListenerException(), book, "Order.cancel");
		assertFalse(protectedOrder.isTerminal());
		assertSame(protectedOrder, book.getOrder(1));
		assertSame(triggeringOrder, book.getOrder(2));
		assertEquals(2, book.getNumberOfOrders());
	}

	@Test
	public void test_OrderBookListenerCannotAddListenerToDifferentOrderInSameBook() {
		final OrderBook book = new OrderBook("AAPL");
		final Order protectedOrder = book.createLimit(1, "protected", 1, Side.BUY, 100, 99, TimeInForce.GTC);
		final OrderBookListenerExceptions[] reported = new OrderBookListenerExceptions[1];
		book.addListener(new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				protectedOrder.addListener(new OrderListenerAdapter());
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reported[0] = exceptions;
			}
		});

		book.createLimit(2, "trigger", 2, Side.BUY, 100, 98, TimeInForce.GTC);

		assertReentrantFailure(reported[0].get(0).getListenerException(), book, "Order.addListener");
		assertFalse(protectedOrder.isTerminal());
		assertEquals(2, book.getNumberOfOrders());
	}

	@Test
	public void test_OrderListenerCannotCancelDifferentOrderInSameBook() {
		final OrderBook book = new OrderBook("AAPL");
		final Order protectedOrder = book.createLimit(1, "protected", 1, Side.BUY, 100, 99, TimeInForce.GTC);
		Order triggeringOrder = book.createLimit(2, "trigger", 2, Side.BUY, 100, 98, TimeInForce.GTC);
		final OrderListenerExceptions[] reported = new OrderListenerExceptions[1];
		triggeringOrder.addListener(new OrderListenerAdapter() {
			@Override
			public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize) {
				protectedOrder.cancel();
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				reported[0] = exceptions;
			}
		});

		triggeringOrder.reduceTo(60);

		assertReentrantFailure(reported[0].get(0).getListenerException(), book, "Order.cancel");
		assertFalse(protectedOrder.isTerminal());
		assertSame(protectedOrder, book.getOrder(1));
		assertEquals(60, triggeringOrder.getTotalSize());
		assertEquals(2, book.getNumberOfOrders());
	}

	@Test
	public void test_OrderListenerCannotAddListenerToDifferentOrderInSameBook() {
		final OrderBook book = new OrderBook("AAPL");
		final Order protectedOrder = book.createLimit(1, "protected", 1, Side.BUY, 100, 99, TimeInForce.GTC);
		Order triggeringOrder = book.createLimit(2, "trigger", 2, Side.BUY, 100, 98, TimeInForce.GTC);
		final OrderListenerExceptions[] reported = new OrderListenerExceptions[1];
		triggeringOrder.addListener(new OrderListenerAdapter() {
			@Override
			public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize) {
				protectedOrder.addListener(new OrderListenerAdapter());
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				reported[0] = exceptions;
			}
		});

		triggeringOrder.reduceTo(60);

		assertReentrantFailure(reported[0].get(0).getListenerException(), book, "Order.addListener");
		assertFalse(protectedOrder.isTerminal());
		assertEquals(2, book.getNumberOfOrders());
	}

	private void assertReentrantFailure(Exception failure, OrderBook book, String operation) {
		assertTrue(failure instanceof ReentrantOrderBookOperationException);
		ReentrantOrderBookOperationException reentrantException = (ReentrantOrderBookOperationException) failure;
		assertSame(book, reentrantException.getOrderBook());
		assertEquals(operation, reentrantException.getOperation());
	}
}
