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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.coralblocks.coralme.Order.CancelReason;
import com.coralblocks.coralme.Order.ExecuteSide;
import com.coralblocks.coralme.Order.RejectReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;

public class ListenerExceptionOrderingTest {

	@Test
	public void test_DirectReductionReportsOrderListenersBeforeOrderBookListeners() {
		final List<String> events = new ArrayList<String>();
		final RuntimeException orderBookFailure = new RuntimeException("OrderBookListener reduction");
		final RuntimeException orderFailure = new RuntimeException("OrderListener reduction");
		final OrderBookListenerExceptions[] orderBookReport = new OrderBookListenerExceptions[1];
		final OrderListenerExceptions[] orderReport = new OrderListenerExceptions[1];

		OrderBookAdapter throwingOrderBookListener = new OrderBookAdapter() {
			@Override
			public void onOrderReduced(OrderBook orderBook, long time, Order order, long canceledSize,
					long reduceNewTotalSize, CancelReason cancelReason) {
				events.add("book-throwing-reduced");
				throw orderBookFailure;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				events.add("book-throwing-report");
				orderBookReport[0] = exceptions;
			}
		};

		OrderBookAdapter observingOrderBookListener = new OrderBookAdapter() {
			@Override
			public void onOrderReduced(OrderBook orderBook, long time, Order order, long canceledSize,
					long reduceNewTotalSize, CancelReason cancelReason) {
				events.add("book-observer-reduced");
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				events.add("book-observer-report");
				assertSame(orderBookReport[0], exceptions);
			}
		};

		OrderBook book = new OrderBook("AAPL");
		book.addListener(throwingOrderBookListener);
		book.addListener(observingOrderBookListener);
		Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);

		OrderListener throwingOrderListener = new OrderListenerAdapter() {
			@Override
			public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize,
					CancelReason cancelReason) {
				events.add("order-throwing-reduced");
				throw orderFailure;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				events.add("order-throwing-report");
				orderReport[0] = exceptions;
			}
		};

		OrderListener observingOrderListener = new OrderListenerAdapter() {
			@Override
			public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize,
					CancelReason cancelReason) {
				events.add("order-observer-reduced");
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				events.add("order-observer-report");
				assertSame(orderReport[0], exceptions);
			}
		};

		order.addListener(throwingOrderListener);
		order.addListener(observingOrderListener);
		events.clear();

		order.reduceTo(60);

		assertEquals(Arrays.asList("book-throwing-reduced", "book-observer-reduced", "order-observer-reduced",
				"order-throwing-reduced", "order-throwing-report", "order-observer-report", "book-throwing-report",
				"book-observer-report"), events);
		assertNotNull(orderReport[0]);
		assertNotNull(orderBookReport[0]);
		assertEquals(1, orderReport[0].size());
		assertEquals(1, orderBookReport[0].size());
		assertSame(orderFailure, orderReport[0].get(0).getListenerException());
		assertSame(orderBookFailure, orderBookReport[0].get(0).getListenerException());
	}

	@Test
	public void test_CancellationReportsAfterTerminationAndOrderBookReportIsLast() {
		final List<String> events = new ArrayList<String>();
		final RuntimeException bookCancelFailure = new RuntimeException("book cancel");
		final RuntimeException bookTerminateFailure = new RuntimeException("book terminate");
		final RuntimeException orderCancelFailure = new RuntimeException("order cancel");
		final RuntimeException orderTerminateFailure = new RuntimeException("order terminate");
		final OrderBookListenerExceptions[] bookReport = new OrderBookListenerExceptions[1];
		final OrderListenerExceptions[] orderReport = new OrderListenerExceptions[1];

		OrderBookAdapter bookListener = new OrderBookAdapter() {
			@Override
			public void onOrderCanceled(OrderBook orderBook, long time, Order order, long canceledSize,
					CancelReason cancelReason) {
				events.add("book-canceled");
				throw bookCancelFailure;
			}

			@Override
			public void onOrderTerminated(OrderBook orderBook, long time, Order order) {
				events.add("book-terminated");
				throw bookTerminateFailure;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				events.add("book-report");
				bookReport[0] = exceptions;
			}
		};

		OrderBook book = new OrderBook("AAPL", bookListener);
		Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		order.addListener(new OrderListenerAdapter() {
			@Override
			public void onOrderCanceled(long time, Order order, long canceledSize, CancelReason cancelReason) {
				events.add("order-canceled");
				throw orderCancelFailure;
			}

			@Override
			public void onOrderTerminated(long time, Order order) {
				events.add("order-terminated");
				throw orderTerminateFailure;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				events.add("order-report");
				orderReport[0] = exceptions;
			}
		});
		events.clear();

		order.cancel();

		assertEquals(Arrays.asList("book-canceled", "order-canceled", "book-terminated", "order-terminated",
				"order-report", "book-report"), events);
		assertEquals(2, orderReport[0].size());
		assertEquals(2, bookReport[0].size());
		assertSame(orderCancelFailure, orderReport[0].get(0).getListenerException());
		assertSame(orderTerminateFailure, orderReport[0].get(1).getListenerException());
		assertSame(bookCancelFailure, bookReport[0].get(0).getListenerException());
		assertSame(bookTerminateFailure, bookReport[0].get(1).getListenerException());
	}

	@Test
	public void test_MatchingReportsEveryOrderBeforeFinalOrderBookReport() {
		final List<String> events = new ArrayList<String>();
		final RuntimeException bookFailure = new RuntimeException("book");
		final RuntimeException orderFailure = new RuntimeException("order");
		final OrderBookListenerExceptions[] bookReport = new OrderBookListenerExceptions[1];
		final OrderListenerExceptions[] orderReport = new OrderListenerExceptions[1];

		OrderBook book = new OrderBook("AAPL");
		Order maker = book.createLimit(1, "maker", 1, Side.SELL, 100, 100, TimeInForce.GTC);
		maker.addListener(new OrderListenerAdapter() {
			@Override
			public void onOrderExecuted(long time, Order order, ExecuteSide executeSide, long executeSize,
					long executePrice, long executeId, long executeMatchId) {
				events.add("order-executed-" + order.getId());
				throw orderFailure;
			}

			@Override
			public void onOrderTerminated(long time, Order order) {
				events.add("order-terminated-" + order.getId());
				throw orderFailure;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				events.add("order-report-" + order.getId());
				orderReport[0] = exceptions;
			}
		});

		book.addListener(new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				events.add("book-accepted-" + order.getId());
				throw bookFailure;
			}

			@Override
			public void onOrderExecuted(OrderBook orderBook, long time, Order order, ExecuteSide executeSide,
					long executeSize, long executePrice, long executeId, long executeMatchId) {
				events.add("book-executed-" + order.getId());
				throw bookFailure;
			}

			@Override
			public void onOrderTerminated(OrderBook orderBook, long time, Order order) {
				events.add("book-terminated-" + order.getId());
				throw bookFailure;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				events.add("book-report");
				bookReport[0] = exceptions;
			}
		});

		Order taker = book.createLimit(2, "taker", 2, Side.BUY, 100, 100, TimeInForce.GTC);

		assertEquals(
				Arrays.asList("book-accepted-2", "book-executed-1", "order-executed-1", "book-terminated-1",
						"order-terminated-1", "book-executed-2", "book-terminated-2", "order-report-1", "book-report"),
				events);
		assertTrue(maker.isTerminal());
		assertTrue(taker.isTerminal());
		assertTrue(book.isEmpty());
		assertEquals(2, orderReport[0].size());
		assertEquals(5, bookReport[0].size());
	}

	@Test
	public void test_PurgeNestsEachOrderReportInsideFinalOrderBookReport() {
		final List<String> events = new ArrayList<String>();
		final RuntimeException failure = new RuntimeException("listener");
		final int[] orderReports = new int[1];
		final int[] bookReports = new int[1];

		OrderBook book = new OrderBook("AAPL");
		Order order1 = book.createLimit(1, "1", 1, Side.BUY, 100, 99, TimeInForce.GTC);
		Order order2 = book.createLimit(1, "2", 2, Side.SELL, 100, 101, TimeInForce.GTC);
		OrderListener orderListener = new OrderListenerAdapter() {
			@Override
			public void onOrderCanceled(long time, Order order, long canceledSize, CancelReason cancelReason) {
				events.add("order-canceled-" + order.getId());
				throw failure;
			}

			@Override
			public void onOrderTerminated(long time, Order order) {
				events.add("order-terminated-" + order.getId());
				throw failure;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				events.add("order-report-" + order.getId());
				orderReports[0]++;
				assertEquals(2, exceptions.size());
			}
		};
		order1.addListener(orderListener);
		order2.addListener(orderListener);

		book.addListener(new OrderBookAdapter() {
			@Override
			public void onOrderCanceled(OrderBook orderBook, long time, Order order, long canceledSize,
					CancelReason cancelReason) {
				events.add("book-canceled-" + order.getId());
				throw failure;
			}

			@Override
			public void onOrderTerminated(OrderBook orderBook, long time, Order order) {
				events.add("book-terminated-" + order.getId());
				throw failure;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				events.add("book-report");
				bookReports[0]++;
				assertEquals(4, exceptions.size());
			}
		});

		book.purge();

		assertTrue(book.isEmpty());
		assertEquals(2, orderReports[0]);
		assertEquals(1, bookReports[0]);
		assertEquals("book-report", events.get(events.size() - 1));
		assertOrderCancellationNested(events, order1.getId());
		assertOrderCancellationNested(events, order2.getId());
		int lastNormalCallback = Math.max(events.lastIndexOf("order-terminated-1"),
				events.lastIndexOf("order-terminated-2"));
		assertTrue(events.indexOf("order-report-1") > lastNormalCallback);
		assertTrue(events.indexOf("order-report-2") > lastNormalCallback);
		assertTrue(events.indexOf("book-report") > events.indexOf("order-report-1"));
		assertTrue(events.indexOf("book-report") > events.indexOf("order-report-2"));
	}

	@Test
	public void test_NoExceptionReportsAcrossMixedSuccessfulOperations() {
		final int[] bookReports = new int[1];
		final int[] orderReports = new int[1];
		OrderBook book = new OrderBook("AAPL", new OrderBookAdapter() {
			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				bookReports[0]++;
			}
		});

		Order bid = book.createLimit(1, "bid", 1, Side.BUY, 100, 99, TimeInForce.GTC);
		Order ask = book.createLimit(1, "ask", 2, Side.SELL, 100, 101, TimeInForce.DAY);
		OrderListener listener = new OrderListenerAdapter() {
			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				orderReports[0]++;
			}
		};
		bid.addListener(listener);
		ask.addListener(listener);

		bid.reduceTo(80);
		bid.cancel(20);
		book.expire();
		book.createMarket(2, "market", 3, Side.SELL, 60);
		book.purge();

		assertEquals(0, bookReports[0]);
		assertEquals(0, orderReports[0]);
		assertTrue(book.isEmpty());
		assertFalse(bid.getTotalSize() > bid.getOriginalSize());
	}

	private void assertOrderCancellationNested(List<String> events, long orderId) {
		String id = Long.toString(orderId);
		assertTrue(events.indexOf("book-canceled-" + id) < events.indexOf("order-canceled-" + id));
		assertTrue(events.indexOf("order-canceled-" + id) < events.indexOf("book-terminated-" + id));
		assertTrue(events.indexOf("book-terminated-" + id) < events.indexOf("order-terminated-" + id));
	}

	private static class OrderListenerAdapter implements OrderListener {

		@Override
		public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize,
				CancelReason cancelReason) {
		}

		@Override
		public void onOrderCanceled(long time, Order order, long canceledSize, CancelReason cancelReason) {
		}

		@Override
		public void onOrderExecuted(long time, Order order, ExecuteSide executeSide, long executeSize,
				long executePrice, long executeId, long executeMatchId) {
		}

		@Override
		public void onOrderAccepted(long time, Order order) {
		}

		@Override
		public void onOrderRejected(long time, Order order, RejectReason rejectReason) {
		}

		@Override
		public void onOrderRested(long time, Order order, long restSize, long restPrice) {
		}

		@Override
		public void onOrderTerminated(long time, Order order) {
		}

		@Override
		public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
		}
	}
}
