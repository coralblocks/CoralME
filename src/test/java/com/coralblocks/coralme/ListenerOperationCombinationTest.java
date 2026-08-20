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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.coralblocks.coralme.ListenerSafetyTestSupport.OrderListenerAdapter;
import com.coralblocks.coralme.Order.CancelReason;
import com.coralblocks.coralme.Order.ExecuteSide;
import com.coralblocks.coralme.Order.RejectReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;
import com.coralblocks.coralme.Order.Type;

public class ListenerOperationCombinationTest {

	@Test
	public void test_IocWithoutLiquidityAggregatesAcceptedCanceledAndTerminatedFailures() {
		assertNoLiquidityCancellation(false);
	}

	@Test
	public void test_MarketWithoutLiquidityAggregatesAcceptedCanceledAndTerminatedFailures() {
		assertNoLiquidityCancellation(true);
	}

	@Test
	public void test_PartialMatchReportsTerminalMakerBeforeFinalBookReportAndRestsTaker() {
		final RuntimeException failure = new RuntimeException("listener");
		final List<String> events = new ArrayList<String>();
		final OrderBookListenerExceptions[] bookReport = new OrderBookListenerExceptions[1];
		final OrderListenerExceptions[] makerReport = new OrderListenerExceptions[1];
		OrderBook book = new OrderBook("AAPL");
		Order maker = book.createLimit(1, "maker", 1, Side.SELL, 60, 100, TimeInForce.GTC);
		maker.addListener(new OrderListenerAdapter() {
			@Override
			public void onOrderExecuted(long time, Order order, ExecuteSide executeSide, long executeSize,
					long executePrice, long executeId, long executeMatchId) {
				events.add("maker-executed");
				throw failure;
			}

			@Override
			public void onOrderTerminated(long time, Order order) {
				events.add("maker-terminated");
				throw failure;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				events.add("maker-report");
				makerReport[0] = exceptions;
			}
		});

		book.addListener(new OrderBookAdapter() {
			private void fail(String event) {
				events.add(event);
				throw failure;
			}

			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				fail("book-accepted-" + order.getId());
			}

			@Override
			public void onOrderExecuted(OrderBook orderBook, long time, Order order, ExecuteSide executeSide,
					long executeSize, long executePrice, long executeId, long executeMatchId) {
				fail("book-executed-" + order.getId());
			}

			@Override
			public void onOrderTerminated(OrderBook orderBook, long time, Order order) {
				fail("book-terminated-" + order.getId());
			}

			@Override
			public void onOrderRested(OrderBook orderBook, long time, Order order, long restSize, long restPrice) {
				fail("book-rested-" + order.getId());
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				events.add("book-report");
				bookReport[0] = exceptions;
			}
		});

		Order taker = book.createLimit(2, "taker", 2, Side.BUY, 100, 100, TimeInForce.GTC);

		assertEquals(Arrays.asList(
				"book-accepted-2",
				"book-executed-1",
				"maker-executed",
				"book-terminated-1",
				"maker-terminated",
				"book-executed-2",
				"book-rested-2",
				"maker-report",
				"book-report"), events);
		assertEquals(2, makerReport[0].size());
		assertEquals(5, bookReport[0].size());
		assertTrue(maker.isTerminal());
		assertFalse(taker.isTerminal());
		assertTrue(taker.isResting());
		assertEquals(60, taker.getExecutedSize());
		assertEquals(40, taker.getOpenSize());
		assertSame(taker, book.getOrder(2));
	}

	@Test
	public void test_MarketAcrossMultipleMakersReportsAllOrdersBeforeBookAndCancelsRemainder() {
		final RuntimeException failure = new RuntimeException("listener");
		final List<String> reports = new ArrayList<String>();
		final OrderBookListenerExceptions[] bookReport = new OrderBookListenerExceptions[1];
		final int[] orderReports = new int[1];
		OrderBook book = new OrderBook("AAPL");
		Order maker1 = book.createLimit(1, "maker-1", 1, Side.SELL, 30, 100, TimeInForce.GTC);
		Order maker2 = book.createLimit(1, "maker-2", 2, Side.SELL, 40, 101, TimeInForce.GTC);
		OrderListener orderListener = executionAndTerminationThrowingOrderListener(failure, reports, orderReports);
		maker1.addListener(orderListener);
		maker2.addListener(orderListener);

		book.addListener(new ThrowingLifecycleOrderBookListener(failure, reports, bookReport));

		Order taker = book.createMarket(2, "market", 3, Side.BUY, 100);

		assertTrue(maker1.isTerminal());
		assertTrue(maker2.isTerminal());
		assertTrue(taker.isTerminal());
		assertEquals(70, taker.getExecutedSize());
		assertEquals(0, taker.getOpenSize());
		assertTrue(book.isEmpty());
		assertEquals(2, orderReports[0]);
		assertEquals(9, bookReport[0].size());
		assertEquals("order-report-1", reports.get(reports.size() - 3));
		assertEquals("order-report-2", reports.get(reports.size() - 2));
		assertEquals("book-report", reports.get(reports.size() - 1));
	}

	@Test
	public void test_ExpireReportsEveryDayOrderBeforeBookAndLeavesGtcOrderUntouched() {
		final RuntimeException failure = new RuntimeException("listener");
		final List<String> reports = new ArrayList<String>();
		final int[] orderReports = new int[1];
		final OrderBookListenerExceptions[] bookReport = new OrderBookListenerExceptions[1];
		OrderBook book = new OrderBook("AAPL");
		Order day1 = book.createLimit(1, "day-1", 1, Side.BUY, 100, 99, TimeInForce.DAY);
		Order gtc = book.createLimit(1, "gtc", 2, Side.BUY, 100, 98, TimeInForce.GTC);
		Order day2 = book.createLimit(1, "day-2", 3, Side.SELL, 100, 101, TimeInForce.DAY);
		OrderListener listener = cancellationAndTerminationThrowingOrderListener(failure, reports, orderReports);
		day1.addListener(listener);
		day2.addListener(listener);
		gtc.addListener(listener);
		book.addListener(new ThrowingLifecycleOrderBookListener(failure, reports, bookReport));

		book.expire();

		assertTrue(day1.isTerminal());
		assertTrue(day2.isTerminal());
		assertFalse(gtc.isTerminal());
		assertSame(gtc, book.getOrder(2));
		assertEquals(1, book.getNumberOfOrders());
		assertEquals(2, orderReports[0]);
		assertEquals(4, bookReport[0].size());
		assertEquals("book-report", reports.get(reports.size() - 1));
		assertTrue(reports.indexOf("order-report-1") < reports.indexOf("book-report"));
		assertTrue(reports.indexOf("order-report-3") < reports.indexOf("book-report"));
	}

	@Test
	public void test_RollKeepsSourceAndDestinationExceptionsSeparate() {
		final RuntimeException failure = new RuntimeException("listener");
		final List<String> events = new ArrayList<String>();
		final int[] sourceOrderReports = new int[1];
		final int[] sourceBookReports = new int[1];
		final int[] destinationBookReports = new int[1];
		final List<OrderBookListenerExceptions> destinationReports = new ArrayList<OrderBookListenerExceptions>();
		OrderBook source = new OrderBook("AAPL");
		OrderBook destination = new OrderBook("AAPL");
		Order source1 = source.createLimit(1, "1", 1, Side.BUY, 100, 99, TimeInForce.GTC);
		Order source2 = source.createLimit(1, "2", 2, Side.SELL, 200, 101, TimeInForce.GTC);
		Order day = source.createLimit(1, "day", 3, Side.BUY, 300, 98, TimeInForce.DAY);
		OrderListener sourceOrderListener = cancellationAndTerminationThrowingOrderListener(
				failure, events, sourceOrderReports);
		source1.addListener(sourceOrderListener);
		source2.addListener(sourceOrderListener);

		source.addListener(new OrderBookAdapter() {
			@Override
			public void onOrderCanceled(OrderBook orderBook, long time, Order order, long canceledSize,
					CancelReason cancelReason) {
				events.add("source-canceled-" + order.getId());
				throw failure;
			}

			@Override
			public void onOrderTerminated(OrderBook orderBook, long time, Order order) {
				events.add("source-terminated-" + order.getId());
				throw failure;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				events.add("source-book-report");
				sourceBookReports[0]++;
				assertEquals(4, exceptions.size());
			}
		});

		destination.addListener(new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				events.add("destination-accepted-" + order.getId());
				throw failure;
			}

			@Override
			public void onOrderRested(OrderBook orderBook, long time, Order order, long restSize, long restPrice) {
				events.add("destination-rested-" + order.getId());
				throw failure;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				events.add("destination-book-report-" + exceptions.get(0).getOrderId());
				destinationBookReports[0]++;
				destinationReports.add(exceptions);
			}
		});

		long nextId = source.rollTo(destination, 10);

		assertEquals(12, nextId);
		assertTrue(source1.isTerminal());
		assertTrue(source2.isTerminal());
		assertFalse(day.isTerminal());
		assertSame(day, source.getOrder(3));
		assertEquals(1, source.getNumberOfOrders());
		assertEquals(2, destination.getNumberOfOrders());
		assertEquals(2, sourceOrderReports[0]);
		assertEquals(1, sourceBookReports[0]);
		assertEquals(2, destinationBookReports[0]);
		assertEquals(2, destinationReports.size());
		assertEquals(2, destinationReports.get(0).size());
		assertEquals(2, destinationReports.get(1).size());
		assertEquals("source-book-report", events.get(events.size() - 1));
		assertTrue(events.indexOf("order-report-1") < events.indexOf("source-book-report"));
		assertTrue(events.indexOf("order-report-2") < events.indexOf("source-book-report"));
	}

	@Test
	public void test_PreAcceptanceRejectionReportsOrderBeforeBookAndRemovesOrder() {
		final RuntimeException failure = new RuntimeException("listener");
		final List<String> events = new ArrayList<String>();
		final OrderBookListenerExceptions[] bookReport = new OrderBookListenerExceptions[1];
		final OrderListenerExceptions[] orderReport = new OrderListenerExceptions[1];
		OrderBook book = new OrderBook("AAPL");
		book.addListener(new OrderBookAdapter() {
			@Override
			public void onOrderRejected(OrderBook orderBook, long time, Order order, RejectReason rejectReason) {
				events.add("book-rejected");
				throw failure;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				events.add("book-report");
				bookReport[0] = exceptions;
			}
		});
		Order order = new Order();
		order.init(book, book.getTimestamper(), 1, "1", 0, book.getSecurity(), Side.BUY, 100, 100,
				Type.LIMIT, TimeInForce.GTC);
		order.addInternalListener(book);
		order.addListener(new OrderListenerAdapter() {
			@Override
			public void onOrderRejected(long time, Order order, RejectReason rejectReason) {
				events.add("order-rejected");
				throw failure;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				events.add("order-report");
				orderReport[0] = exceptions;
			}
		});

		order.reject(RejectReason.BAD_TYPE);

		assertEquals(Arrays.asList("book-rejected", "order-rejected", "order-report", "book-report"), events);
		assertEquals(1, orderReport[0].size());
		assertEquals(1, bookReport[0].size());
		assertTrue(order.isTerminal());
		assertTrue(book.isEmpty());
	}

	private void assertNoLiquidityCancellation(boolean market) {
		final RuntimeException failure = new RuntimeException("listener");
		final List<String> events = new ArrayList<String>();
		final OrderBookListenerExceptions[] reported = new OrderBookListenerExceptions[1];
		OrderBook book = new OrderBook("AAPL", new OrderBookAdapter() {
			private void fail(String event) {
				events.add(event);
				throw failure;
			}

			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				fail("accepted");
			}

			@Override
			public void onOrderCanceled(OrderBook orderBook, long time, Order order, long canceledSize,
					CancelReason cancelReason) {
				fail("canceled");
			}

			@Override
			public void onOrderTerminated(OrderBook orderBook, long time, Order order) {
				fail("terminated");
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				events.add("report");
				reported[0] = exceptions;
				assertTrue(orderBook.isEmpty());
			}
		});

		Order order = market
				? book.createMarket(1, "market", 1, Side.BUY, 100)
				: book.createLimit(1, "ioc", 1, Side.BUY, 100, 100, TimeInForce.IOC);

		assertEquals(Arrays.asList("accepted", "canceled", "terminated", "report"), events);
		assertEquals(3, reported[0].size());
		assertTrue(order.isTerminal());
		assertTrue(book.isEmpty());
	}

	private OrderListener executionAndTerminationThrowingOrderListener(RuntimeException failure,
			List<String> reports, int[] reportCount) {
		return new OrderListenerAdapter() {
			@Override
			public void onOrderExecuted(long time, Order order, ExecuteSide executeSide, long executeSize,
					long executePrice, long executeId, long executeMatchId) {
				throw failure;
			}

			@Override
			public void onOrderTerminated(long time, Order order) {
				throw failure;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				reports.add("order-report-" + order.getId());
				reportCount[0]++;
				assertEquals(2, exceptions.size());
			}
		};
	}

	private OrderListener cancellationAndTerminationThrowingOrderListener(RuntimeException failure,
			List<String> reports, int[] reportCount) {
		return new OrderListenerAdapter() {
			@Override
			public void onOrderCanceled(long time, Order order, long canceledSize, CancelReason cancelReason) {
				throw failure;
			}

			@Override
			public void onOrderTerminated(long time, Order order) {
				throw failure;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				reports.add("order-report-" + order.getId());
				reportCount[0]++;
				assertEquals(2, exceptions.size());
			}
		};
	}

	private static class ThrowingLifecycleOrderBookListener extends OrderBookAdapter {

		private final RuntimeException failure;
		private final List<String> reports;
		private final OrderBookListenerExceptions[] reported;

		private ThrowingLifecycleOrderBookListener(RuntimeException failure, List<String> reports,
				OrderBookListenerExceptions[] reported) {
			this.failure = failure;
			this.reports = reports;
			this.reported = reported;
		}

		@Override
		public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
			throw failure;
		}

		@Override
		public void onOrderCanceled(OrderBook orderBook, long time, Order order, long canceledSize,
				CancelReason cancelReason) {
			throw failure;
		}

		@Override
		public void onOrderExecuted(OrderBook orderBook, long time, Order order, ExecuteSide executeSide,
				long executeSize, long executePrice, long executeId, long executeMatchId) {
			throw failure;
		}

		@Override
		public void onOrderTerminated(OrderBook orderBook, long time, Order order) {
			throw failure;
		}

		@Override
		public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
			reports.add("book-report");
			reported[0] = exceptions;
		}
	}
}
