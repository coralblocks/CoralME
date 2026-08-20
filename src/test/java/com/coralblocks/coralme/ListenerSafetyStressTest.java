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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.coralblocks.coralme.ListenerSafetyTestSupport.OrderListenerAdapter;
import com.coralblocks.coralme.Order.ExecuteSide;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;

public class ListenerSafetyStressTest {

	private static final int ITERATIONS = 250;

	@Test
	public void test_RepeatedMatchingReentryExceptionsReportingAndPoolReuse() {
		final RuntimeException bookFailure = new RuntimeException("book listener");
		final RuntimeException orderFailure = new RuntimeException("order listener");
		final int[] expectedTakerId = new int[1];
		final int[] orderReports = new int[1];
		final int[] bookReports = new int[1];
		final Order[] currentMaker = new Order[1];
		final Order[] currentTaker = new Order[1];
		final OrderBook book = new OrderBook("AAPL");

		book.addListener(new OrderBookAdapter() {
			@Override
			public void onOrderExecuted(OrderBook orderBook, long time, Order order, ExecuteSide executeSide,
					long executeSize, long executePrice, long executeId, long executeMatchId) {
				throw bookFailure;
			}

			@Override
			public void onOrderTerminated(OrderBook orderBook, long time, Order order) {
				throw bookFailure;
			}
		});
		book.addListener(new OrderBookAdapter() {
			@Override
			public void onOrderExecuted(OrderBook orderBook, long time, Order order, ExecuteSide executeSide,
					long executeSize, long executePrice, long executeId, long executeMatchId) {
				orderBook.purge();
			}

			@Override
			public void onOrderTerminated(OrderBook orderBook, long time, Order order) {
				orderBook.purge();
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				orderBook.createLimit(999, "blocked-book-report", 999, Side.BUY, 1, 1, TimeInForce.GTC);
			}
		});
		book.addListener(new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				if (order.getId() == expectedTakerId[0]) currentTaker[0] = order;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				bookReports[0]++;
				assertEquals(8, exceptions.size());
				assertEquals(bookReports[0], orderReports[0]);
				assertTrue(orderBook.isEmpty());
				assertEquals(100, currentMaker[0].getExecutedSize());
				assertEquals(100, currentTaker[0].getExecutedSize());
			}
		});

		OrderListener throwingOrderListener = new OrderListenerAdapter() {
			@Override
			public void onOrderExecuted(long time, Order order, ExecuteSide executeSide, long executeSize,
					long executePrice, long executeId, long executeMatchId) {
				throw orderFailure;
			}

			@Override
			public void onOrderTerminated(long time, Order order) {
				throw orderFailure;
			}
		};
		OrderListener reentrantOrderListener = new OrderListenerAdapter() {
			@Override
			public void onOrderExecuted(long time, Order order, ExecuteSide executeSide, long executeSize,
					long executePrice, long executeId, long executeMatchId) {
				book.purge();
			}

			@Override
			public void onOrderTerminated(long time, Order order) {
				book.purge();
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				book.createLimit(998, "blocked-order-report", 998, Side.BUY, 1, 1, TimeInForce.GTC);
			}
		};
		OrderListener observingOrderListener = new OrderListenerAdapter() {
			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				orderReports[0]++;
				assertSame(currentMaker[0], order);
				assertEquals(4, exceptions.size());
				assertTrue(currentMaker[0].isTerminal());
				assertTrue(currentTaker[0].isTerminal());
				assertTrue(book.isEmpty());
			}
		};

		for (int i = 0; i < ITERATIONS; i++) {
			int makerId = i * 2 + 1;
			int takerId = makerId + 1;
			expectedTakerId[0] = takerId;
			currentTaker[0] = null;
			currentMaker[0] = book.createLimit(1, "maker-" + makerId, makerId, Side.SELL, 100, 100, TimeInForce.GTC);
			currentMaker[0].addListener(throwingOrderListener);
			currentMaker[0].addListener(reentrantOrderListener);
			currentMaker[0].addListener(observingOrderListener);

			Order returnedTaker = book.createLimit(2, "taker-" + takerId, takerId, Side.BUY, 100, 100, TimeInForce.GTC);

			assertSame(currentTaker[0], returnedTaker);
			assertTrue(currentMaker[0].isTerminal());
			assertTrue(returnedTaker.isTerminal());
			assertTrue(book.isEmpty());
			assertEquals(i + 1, orderReports[0]);
			assertEquals(i + 1, bookReports[0]);
		}

		assertEquals(ITERATIONS, orderReports[0]);
		assertEquals(ITERATIONS, bookReports[0]);
	}
}
