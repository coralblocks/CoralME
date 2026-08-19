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
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.coralblocks.coralme.ListenerSafetyTestSupport.OrderListenerAdapter;
import com.coralblocks.coralme.Order.CancelReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;

public class ListenerFailureCombinationTest {

	@Test
	public void test_OrdinaryAndReentrantFailuresStayInTheirOwningListenerContainers() {
		final RuntimeException bookFailure = new RuntimeException("ordinary book failure");
		final RuntimeException orderFailure = new RuntimeException("ordinary order failure");
		final OrderBookListenerExceptions[] bookReport = new OrderBookListenerExceptions[1];
		final OrderListenerExceptions[] orderReport = new OrderListenerExceptions[1];
		final List<String> reports = new ArrayList<String>();
		final OrderBook book = new OrderBook("AAPL");

		OrderBookListener reentrantBookListener = new OrderBookAdapter() {
			@Override
			public void onOrderReduced(OrderBook orderBook, long time, Order order, long canceledSize,
					long reduceNewTotalSize) {
				order.cancel();
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reports.add("book-reentrant-report");
				bookReport[0] = exceptions;
			}
		};
		OrderBookListener ordinaryBookListener = new OrderBookAdapter() {
			@Override
			public void onOrderReduced(OrderBook orderBook, long time, Order order, long canceledSize,
					long reduceNewTotalSize) {
				throw bookFailure;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reports.add("book-ordinary-report");
				assertSame(bookReport[0], exceptions);
			}
		};
		book.addListener(reentrantBookListener);
		book.addListener(ordinaryBookListener);

		Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		OrderListener reentrantOrderListener = new OrderListenerAdapter() {
			@Override
			public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize) {
				book.purge();
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				reports.add("order-reentrant-report");
				orderReport[0] = exceptions;
			}
		};
		OrderListener ordinaryOrderListener = new OrderListenerAdapter() {
			@Override
			public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize) {
				throw orderFailure;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				reports.add("order-ordinary-report");
				assertSame(orderReport[0], exceptions);
			}
		};
		order.addListener(reentrantOrderListener);
		order.addListener(ordinaryOrderListener);

		order.reduceTo(60);

		assertEquals(Arrays.asList(
				"order-reentrant-report",
				"order-ordinary-report",
				"book-reentrant-report",
				"book-ordinary-report"), reports);
		assertEquals(2, orderReport[0].size());
		assertSame(ordinaryOrderListener, orderReport[0].get(0).getListener());
		assertSame(orderFailure, orderReport[0].get(0).getListenerException());
		assertSame(reentrantOrderListener, orderReport[0].get(1).getListener());
		assertTrue(orderReport[0].get(1).getListenerException() instanceof ReentrantOrderBookOperationException);
		assertEquals(2, bookReport[0].size());
		assertSame(reentrantBookListener, bookReport[0].get(0).getListener());
		assertTrue(bookReport[0].get(0).getListenerException() instanceof ReentrantOrderBookOperationException);
		assertSame(ordinaryBookListener, bookReport[0].get(1).getListener());
		assertSame(bookFailure, bookReport[0].get(1).getListenerException());
		assertEquals(60, order.getTotalSize());
		assertEquals(60, order.getPriceLevel().getSize());
	}

	@Test
	public void test_InternalTerminationFailureDiscardsReentrantFailuresCollectedDuringCancellation() {
		final RuntimeException internalFailure = new RuntimeException("fatal internal termination failure");
		final int[] bookReports = new int[1];
		final int[] orderReports = new int[1];
		final OrderBook book = new OrderBook("AAPL");
		book.addListener(new OrderBookAdapter() {
			@Override
			public void onOrderCanceled(OrderBook orderBook, long time, Order order, long canceledSize,
					CancelReason cancelReason) {
				order.reduceTo(1);
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				bookReports[0]++;
			}
		});

		Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		order.addListener(new OrderListenerAdapter() {
			@Override
			public void onOrderCanceled(long time, Order order, long canceledSize, CancelReason cancelReason) {
				book.createLimit(2, "reentrant", 2, Side.BUY, 100, 99, TimeInForce.GTC);
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				orderReports[0]++;
			}
		});
		order.addInternalListener(new OrderListenerAdapter() {
			@Override
			public void onOrderTerminated(long time, Order order) {
				throw internalFailure;
			}
		});

		try {
			order.cancel();
			fail("Expected internal listener failure");
		} catch(RuntimeException e) {
			assertSame(internalFailure, e);
		}

		assertEquals(0, bookReports[0]);
		assertEquals(0, orderReports[0]);
		assertTrue(order.isTerminal());
		assertTrue(book.isEmpty());

		book.createLimit(3, "unrelated", 3, Side.BUY, 100, 98, TimeInForce.GTC);
		assertEquals(0, bookReports[0]);
		assertEquals(0, orderReports[0]);
	}

	@Test
	public void test_ReportCallbackFailuresDoNotStopLaterReportsOrChangeFinalOrdering() {
		final List<String> events = new ArrayList<String>();
		final RuntimeException callbackFailure = new RuntimeException("normal callback");
		final OrderBook book = new OrderBook("AAPL");
		book.addListener(new OrderBookAdapter() {
			@Override
			public void onOrderReduced(OrderBook orderBook, long time, Order order, long canceledSize,
					long reduceNewTotalSize) {
				throw callbackFailure;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				events.add("book-throwing-report");
				throw new RuntimeException("ignored book report failure");
			}
		});
		book.addListener(new OrderBookAdapter() {
			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				events.add("book-observer-report");
			}
		});

		Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		order.addListener(new OrderListenerAdapter() {
			@Override
			public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize) {
				throw callbackFailure;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				events.add("order-throwing-report");
				throw new RuntimeException("ignored order report failure");
			}
		});
		order.addListener(new OrderListenerAdapter() {
			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				events.add("order-observer-report");
			}
		});

		order.reduceTo(60);

		assertEquals(Arrays.asList(
				"order-throwing-report",
				"order-observer-report",
				"book-throwing-report",
				"book-observer-report"), events);
		assertEquals(60, order.getTotalSize());
	}

	@Test
	public void test_ExceptionReportsCanMutateDifferentOrderBooks() {
		final RuntimeException failure = new RuntimeException("normal callback");
		final OrderBook orderReportTarget = new OrderBook("ORDER-REPORT-TARGET");
		final OrderBook bookReportTarget = new OrderBook("BOOK-REPORT-TARGET");
		final List<String> reports = new ArrayList<String>();
		OrderBook book = new OrderBook("AAPL", new OrderBookAdapter() {
			@Override
			public void onOrderReduced(OrderBook orderBook, long time, Order order, long canceledSize,
					long reduceNewTotalSize) {
				throw failure;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reports.add("book-report");
				bookReportTarget.createLimit(2, "book-report", 2, Side.BUY, 100, 100, TimeInForce.GTC);
			}
		});
		Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		order.addListener(new OrderListenerAdapter() {
			@Override
			public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize) {
				throw failure;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				reports.add("order-report");
				orderReportTarget.createLimit(3, "order-report", 3, Side.BUY, 100, 100, TimeInForce.GTC);
			}
		});

		order.reduceTo(60);

		assertEquals(Arrays.asList("order-report", "book-report"), reports);
		assertEquals(1, orderReportTarget.getNumberOfOrders());
		assertEquals(1, bookReportTarget.getNumberOfOrders());
		assertEquals(60, order.getTotalSize());
	}
}
