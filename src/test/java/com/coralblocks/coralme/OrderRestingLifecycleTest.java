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
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.coralblocks.coralme;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.coralblocks.coralme.Order.CancelReason;
import com.coralblocks.coralme.Order.ExecuteSide;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;

public class OrderRestingLifecycleTest {

	@Test
	public void testCancellationClearsRestingBeforeCallbacks() {
		boolean[] orderCallbacks = new boolean[2];
		boolean[] bookCallbacks = new boolean[2];
		OrderBook book = new OrderBook("AAPL");
		book.addListener(new OrderBookAdapter() {
			@Override
			public void onOrderCanceled(OrderBook orderBook, long time, Order order, long canceledSize,
					CancelReason cancelReason) {
				assertFalse(order.isResting());
				bookCallbacks[0] = true;
			}

			@Override
			public void onOrderTerminated(OrderBook orderBook, long time, Order order) {
				assertFalse(order.isResting());
				bookCallbacks[1] = true;
			}
		});

		Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		order.addListener(new ListenerSafetyTestSupport.OrderListenerAdapter() {
			@Override
			public void onOrderCanceled(long time, Order order, long canceledSize, CancelReason cancelReason) {
				assertFalse(order.isResting());
				orderCallbacks[0] = true;
			}

			@Override
			public void onOrderTerminated(long time, Order order) {
				assertFalse(order.isResting());
				orderCallbacks[1] = true;
			}
		});

		assertTrue(order.isResting());
		order.cancel();

		assertFalse(order.isResting());
		assertTrue(orderCallbacks[0]);
		assertTrue(orderCallbacks[1]);
		assertTrue(bookCallbacks[0]);
		assertTrue(bookCallbacks[1]);
	}

	@Test
	public void testFullExecutionClearsRestingBeforeCallbacks() {
		boolean[] orderCallbacks = new boolean[2];
		boolean[] bookCallbacks = new boolean[2];
		OrderBook book = new OrderBook("AAPL");
		Order maker = book.createLimit(1, "maker", 1, Side.SELL, 100, 100, TimeInForce.GTC);

		book.addListener(new OrderBookAdapter() {
			@Override
			public void onOrderExecuted(OrderBook orderBook, long time, Order order, ExecuteSide execSide,
					long sizeExecuted, long priceExecuted, long executionId, long matchId) {
				if (order != maker) return;
				assertFalse(order.isResting());
				bookCallbacks[0] = true;
			}

			@Override
			public void onOrderTerminated(OrderBook orderBook, long time, Order order) {
				if (order != maker) return;
				assertFalse(order.isResting());
				bookCallbacks[1] = true;
			}
		});

		maker.addListener(new ListenerSafetyTestSupport.OrderListenerAdapter() {
			@Override
			public void onOrderExecuted(long time, Order order, ExecuteSide execSide, long sizeExecuted,
					long priceExecuted, long executionId, long matchId) {
				assertFalse(order.isResting());
				orderCallbacks[0] = true;
			}

			@Override
			public void onOrderTerminated(long time, Order order) {
				assertFalse(order.isResting());
				orderCallbacks[1] = true;
			}
		});

		assertTrue(maker.isResting());
		book.createLimit(2, "taker", 2, Side.BUY, 100, 100, TimeInForce.GTC);

		assertFalse(maker.isResting());
		assertTrue(orderCallbacks[0]);
		assertTrue(orderCallbacks[1]);
		assertTrue(bookCallbacks[0]);
		assertTrue(bookCallbacks[1]);
	}
}
