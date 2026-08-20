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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.coralblocks.coralme.Order.CancelReason;
import com.coralblocks.coralme.Order.ExecuteSide;
import com.coralblocks.coralme.Order.RejectReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;
import com.coralblocks.coralme.OrderBookListenerException.Callback;

public class OrderBookReentrancyTest {

	@Test
	public void test_ReentrantCreateDuringExecutionIsBlockedAndReportedAfterMatchingCompletes() {
		final OrderBookListenerExceptions[] reported = new OrderBookListenerExceptions[1];
		final int[] attempts = new int[1];

		OrderBookAdapter listener = new OrderBookAdapter() {
			@Override
			public void onOrderExecuted(OrderBook orderBook, long time, Order order, ExecuteSide executeSide,
					long executeSize, long executePrice, long executeId, long executeMatchId) {
				if (order.getId() == 1) {
					attempts[0]++;
					orderBook.createLimit(3, "reentrant", 3, Side.BUY, 100, 99, TimeInForce.GTC);
				}
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reported[0] = exceptions;
			}
		};

		OrderBook book = new OrderBook("AAPL", listener);
		Order maker = book.createLimit(1, "maker", 1, Side.SELL, 100, 100, TimeInForce.GTC);
		Order taker = book.createLimit(2, "taker", 2, Side.BUY, 100, 100, TimeInForce.GTC);

		assertEquals(1, attempts[0]);
		assertEquals(100, maker.getExecutedSize());
		assertEquals(100, taker.getExecutedSize());
		assertTrue(maker.isTerminal());
		assertTrue(taker.isTerminal());
		assertTrue(book.isEmpty());
		assertNull(book.getOrder(3));

		assertEquals(1, reported[0].size());
		OrderBookListenerException listenerException = reported[0].get(0);
		assertSame(listener, listenerException.getListener());
		assertEquals(Callback.ON_ORDER_EXECUTED, listenerException.getCallback());
		assertEquals(1, listenerException.getOrderId());
		assertReentrantFailure(listenerException, book, "createLimit");
	}

	@Test
	public void test_ReentrantOrderCancellationIsBlockedBeforeOrderStateChanges() {
		final OrderBookListenerExceptions[] reported = new OrderBookListenerExceptions[1];

		OrderBookAdapter listener = new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				order.cancel();
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reported[0] = exceptions;
			}
		};

		OrderBook book = new OrderBook("AAPL", listener);
		Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);

		assertFalse(order.isTerminal());
		assertTrue(order.isResting());
		assertEquals(100, order.getTotalSize());
		assertEquals(100, order.getOpenSize());
		assertSame(order, book.getOrder(1));

		assertEquals(1, reported[0].size());
		OrderBookListenerException listenerException = reported[0].get(0);
		assertSame(listener, listenerException.getListener());
		assertEquals(Callback.ON_ORDER_ACCEPTED, listenerException.getCallback());
		assertReentrantFailure(listenerException, book, "Order.cancel");
	}

	@Test
	public void test_OperationOnDifferentOrderBookIsAllowed() {
		final int[] reports = new int[1];
		final OrderBook otherBook = new OrderBook("MSFT");

		OrderBookAdapter listener = new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				assertEquals(0, orderBook.getNumberOfOrders());
				otherBook.createLimit(2, "other", 2, Side.SELL, 200, 200, TimeInForce.GTC);
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reports[0]++;
			}
		};

		OrderBook book = new OrderBook("AAPL", listener);
		book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);

		assertEquals(0, reports[0]);
		assertEquals(1, book.getNumberOfOrders());
		assertEquals(1, otherBook.getNumberOfOrders());
	}

	@Test
	public void test_ReentryFromExceptionReportIsBlockedWithoutRecursiveReporting() {
		final RuntimeException listenerFailure = new RuntimeException("listener");
		final OrderBookListenerExceptions[] reported = new OrderBookListenerExceptions[1];
		final int[] reports = new int[1];

		OrderBookAdapter listener = new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				throw listenerFailure;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reports[0]++;
				reported[0] = exceptions;
				orderBook.createLimit(2, "reentrant", 2, Side.BUY, 100, 99, TimeInForce.GTC);
			}
		};

		OrderBook book = new OrderBook("AAPL", listener);
		Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);

		assertEquals(1, reports[0]);
		assertEquals(1, reported[0].size());
		assertSame(listenerFailure, reported[0].get(0).getListenerException());
		assertSame(order, book.getOrder(1));
		assertNull(book.getOrder(2));
		assertEquals(1, book.getNumberOfOrders());
	}

	@Test
	public void test_ReentrantListenerRegistrationIsBlocked() {
		final OrderBookListenerExceptions[] reported = new OrderBookListenerExceptions[1];
		final int[] markerAcceptances = new int[1];
		final OrderBookAdapter marker = new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				markerAcceptances[0]++;
			}
		};

		OrderBookAdapter listener = new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				orderBook.addListener(marker);
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reported[0] = exceptions;
			}
		};

		OrderBook book = new OrderBook("AAPL", listener);
		book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);

		assertEquals(0, markerAcceptances[0]);
		assertEquals(1, reported[0].size());
		assertReentrantFailure(reported[0].get(0), book, "addListener");
	}

	@Test
	public void test_ReentrantOrderBookOperationFromOrderListenerIsBlockedAndReported() {
		final OrderListenerExceptions[] reported = new OrderListenerExceptions[1];
		final OrderBook book = new OrderBook("AAPL");
		final Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);

		OrderListener listener = new OrderListenerAdapter() {
			@Override
			public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize,
					CancelReason cancelReason) {
				book.createLimit(2, "reentrant", 2, Side.BUY, 100, 99, TimeInForce.GTC);
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				reported[0] = exceptions;
			}
		};

		order.addListener(listener);
		order.reduceTo(60);

		assertEquals(60, order.getTotalSize());
		assertFalse(order.isTerminal());
		assertSame(order, book.getOrder(1));
		assertNull(book.getOrder(2));
		assertEquals(1, reported[0].size());
		OrderListenerException listenerException = reported[0].get(0);
		assertSame(listener, listenerException.getListener());
		assertEquals(OrderListenerException.Callback.ON_ORDER_REDUCED, listenerException.getCallback());
		assertReentrantFailure(listenerException, book, "createLimit");
	}

	@Test
	public void test_ReentrantOrderMutationFromOrderListenerIsBlockedBeforeStateChanges() {
		final OrderListenerExceptions[] reported = new OrderListenerExceptions[1];
		final OrderBook book = new OrderBook("AAPL");
		final Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);

		OrderListener listener = new OrderListenerAdapter() {
			@Override
			public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize,
					CancelReason cancelReason) {
				order.cancel();
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				reported[0] = exceptions;
			}
		};

		order.addListener(listener);
		order.reduceTo(60);

		assertEquals(60, order.getTotalSize());
		assertEquals(60, order.getOpenSize());
		assertFalse(order.isTerminal());
		assertSame(order, book.getOrder(1));
		assertEquals(1, reported[0].size());
		assertReentrantFailure(reported[0].get(0), book, "Order.cancel");
	}

	@Test
	public void test_ReentrantOrderListenerRegistrationFromOrderListenerIsBlocked() {
		final OrderListenerExceptions[] reported = new OrderListenerExceptions[1];
		final OrderBook book = new OrderBook("AAPL");
		final Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		final OrderListener marker = new OrderListenerAdapter();

		OrderListener listener = new OrderListenerAdapter() {
			@Override
			public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize,
					CancelReason cancelReason) {
				order.addListener(marker);
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				reported[0] = exceptions;
			}
		};

		order.addListener(listener);
		order.reduceTo(60);

		assertEquals(1, reported[0].size());
		assertReentrantFailure(reported[0].get(0), book, "Order.addListener");
	}

	@Test
	public void test_OperationOnDifferentOrderBookFromOrderListenerIsAllowed() {
		final int[] reports = new int[1];
		final OrderBook otherBook = new OrderBook("MSFT");
		OrderBook book = new OrderBook("AAPL");
		Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);

		order.addListener(new OrderListenerAdapter() {
			@Override
			public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize,
					CancelReason cancelReason) {
				otherBook.createLimit(2, "other", 2, Side.SELL, 200, 200, TimeInForce.GTC);
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				reports[0]++;
			}
		});

		order.reduceTo(60);

		assertEquals(0, reports[0]);
		assertEquals(1, book.getNumberOfOrders());
		assertEquals(1, otherBook.getNumberOfOrders());
	}

	@Test
	public void test_ReentryFromOrderListenerExceptionReportIsBlockedWithoutRecursiveReporting() {
		final RuntimeException listenerFailure = new RuntimeException("listener");
		final OrderListenerExceptions[] reported = new OrderListenerExceptions[1];
		final int[] reports = new int[1];
		final OrderBook book = new OrderBook("AAPL");
		Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);

		order.addListener(new OrderListenerAdapter() {
			@Override
			public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize,
					CancelReason cancelReason) {
				throw listenerFailure;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				reports[0]++;
				reported[0] = exceptions;
				book.createLimit(2, "reentrant", 2, Side.BUY, 100, 99, TimeInForce.GTC);
			}
		});

		order.reduceTo(60);

		assertEquals(1, reports[0]);
		assertEquals(1, reported[0].size());
		assertSame(listenerFailure, reported[0].get(0).getListenerException());
		assertNull(book.getOrder(2));
		book.createLimit(3, "after-report", 3, Side.BUY, 100, 98, TimeInForce.GTC);
		assertEquals(2, book.getNumberOfOrders());
	}

	private void assertReentrantFailure(OrderBookListenerException listenerException, OrderBook book,
			String operation) {
		assertTrue(listenerException.getListenerException() instanceof ReentrantOrderBookOperationException);
		ReentrantOrderBookOperationException reentrantException = (ReentrantOrderBookOperationException) listenerException.getListenerException();
		assertSame(book, reentrantException.getOrderBook());
		assertEquals(operation, reentrantException.getOperation());
	}

	private void assertReentrantFailure(OrderListenerException listenerException, OrderBook book, String operation) {
		assertTrue(listenerException.getListenerException() instanceof ReentrantOrderBookOperationException);
		ReentrantOrderBookOperationException reentrantException = (ReentrantOrderBookOperationException) listenerException.getListenerException();
		assertSame(book, reentrantException.getOrderBook());
		assertEquals(operation, reentrantException.getOperation());
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
