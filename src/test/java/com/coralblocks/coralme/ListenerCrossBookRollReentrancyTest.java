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
import com.coralblocks.coralme.Order.CancelReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;

public class ListenerCrossBookRollReentrancyTest {

	@Test
	public void test_OrderBookListenerCannotRollAnotherBookIntoCallbackBook() {
		final OrderBook source = new OrderBook("SOURCE");
		final Order sourceOrder = source.createLimit(1, "source", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		final OrderBookListenerExceptions[] reported = new OrderBookListenerExceptions[1];
		OrderBook callbackBook = new OrderBook("CALLBACK", new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				source.rollTo(orderBook);
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reported[0] = exceptions;
			}
		});

		Order callbackOrder = callbackBook.createLimit(2, "callback", 2, Side.SELL, 100, 200, TimeInForce.GTC);

		assertEquals(1, reported[0].size());
		assertReentrantRoll(reported[0].get(0).getListenerException(), callbackBook);
		assertSame(sourceOrder, source.getOrder(1));
		assertFalse(sourceOrder.isTerminal());
		assertEquals(1, source.getNumberOfOrders());
		assertSame(callbackOrder, callbackBook.getOrder(2));
		assertEquals(1, callbackBook.getNumberOfOrders());
	}

	@Test
	public void test_OrderListenerCannotRollAnotherBookIntoCallbackBook() {
		final OrderBook source = new OrderBook("SOURCE");
		final Order sourceOrder = source.createLimit(1, "source", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		final OrderBook callbackBook = new OrderBook("CALLBACK");
		final Order callbackOrder = callbackBook.createLimit(2, "callback", 2, Side.SELL, 100, 200, TimeInForce.GTC);
		final OrderListenerExceptions[] reported = new OrderListenerExceptions[1];
		callbackOrder.addListener(new OrderListenerAdapter() {
			@Override
			public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize, CancelReason cancelReason) {
				source.rollTo(callbackBook);
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				reported[0] = exceptions;
			}
		});

		callbackOrder.reduceTo(60);

		assertEquals(1, reported[0].size());
		assertReentrantRoll(reported[0].get(0).getListenerException(), callbackBook);
		assertSame(sourceOrder, source.getOrder(1));
		assertFalse(sourceOrder.isTerminal());
		assertEquals(1, source.getNumberOfOrders());
		assertSame(callbackOrder, callbackBook.getOrder(2));
		assertEquals(60, callbackOrder.getTotalSize());
		assertEquals(1, callbackBook.getNumberOfOrders());
	}

	private void assertReentrantRoll(Exception failure, OrderBook callbackBook) {
		assertTrue(failure instanceof ReentrantOrderBookOperationException);
		ReentrantOrderBookOperationException reentrantException = (ReentrantOrderBookOperationException) failure;
		assertSame(callbackBook, reentrantException.getOrderBook());
		assertEquals("rollTo", reentrantException.getOperation());
	}
}
