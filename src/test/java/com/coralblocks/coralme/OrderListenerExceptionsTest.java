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

import static org.junit.Assert.*;

import org.junit.Test;

import com.coralblocks.coralme.Order.CancelReason;
import com.coralblocks.coralme.Order.ExecuteSide;
import com.coralblocks.coralme.Order.RejectReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;
import com.coralblocks.coralme.OrderListenerException.Callback;

public class OrderListenerExceptionsTest {

	@Test
	public void test_ExecutionExceptionsAreReportedAfterBothOrdersAndAllCallbacksComplete() {
		final RuntimeException executionFailure = new RuntimeException("execution");
		final RuntimeException terminationFailure = new RuntimeException("termination");
		final int[] throwingListenerReports = new int[1];
		final int[] observerReports = new int[1];
		final int[] internalListenerReports = new int[1];
		final int[] observerExecutions = new int[1];
		final int[] observerTerminations = new int[1];
		final OrderListenerExceptions[] reported = new OrderListenerExceptions[1];
		final OrderBook book = new OrderBook("AAPL");
		final Order[] maker = new Order[1];
		final Order[] taker = new Order[1];

		book.addListener(new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				if (order.getId() == 2) taker[0] = order;
			}
		});

		OrderListener throwingListener = new OrderListenerAdapter() {
			@Override
			public void onOrderExecuted(long time, Order order, ExecuteSide executeSide, long executeSize,
					long executePrice, long executeId, long executeMatchId) {
				throw executionFailure;
			}

			@Override
			public void onOrderTerminated(long time, Order order) {
				throw terminationFailure;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				throwingListenerReports[0]++;
				assertSame(maker[0], order);
				throw new RuntimeException("report failure must be ignored");
			}
		};

		OrderListener observer = new OrderListenerAdapter() {
			@Override
			public void onOrderExecuted(long time, Order order, ExecuteSide executeSide, long executeSize,
					long executePrice, long executeId, long executeMatchId) {
				observerExecutions[0]++;
				assertTrue(book.isEmpty());
			}

			@Override
			public void onOrderTerminated(long time, Order order) {
				observerTerminations[0]++;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				observerReports[0]++;
				reported[0] = exceptions;
				assertSame(maker[0], order);
				assertEquals(100, maker[0].getExecutedSize());
				assertEquals(100, taker[0].getExecutedSize());
				assertTrue(maker[0].isTerminal());
				assertTrue(taker[0].isTerminal());
				assertTrue(book.isEmpty());
			}
		};

		maker[0] = book.createLimit(1, "maker", 1, Side.SELL, 100, 100, TimeInForce.GTC);
		maker[0].addInternalListener(new OrderListenerAdapter() {
			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				internalListenerReports[0]++;
			}
		});
		maker[0].addListener(throwingListener);
		maker[0].addListener(observer);
		Order returnedTaker = book.createLimit(2, "taker", 2, Side.BUY, 100, 100, TimeInForce.GTC);

		assertSame(taker[0], returnedTaker);
		assertEquals(1, observerExecutions[0]);
		assertEquals(1, observerTerminations[0]);
		assertEquals(1, throwingListenerReports[0]);
		assertEquals(1, observerReports[0]);
		assertEquals(0, internalListenerReports[0]);
		assertEquals(2, reported[0].size());
		assertFailure(reported[0].get(0), throwingListener, executionFailure, Callback.ON_ORDER_EXECUTED, 1);
		assertFailure(reported[0].get(1), throwingListener, terminationFailure, Callback.ON_ORDER_TERMINATED, 1);
	}

	@Test
	public void test_DirectReductionReportsAfterInternalStateAndAllExternalListenersAreUpdated() {
		final RuntimeException failure = new RuntimeException("reduction");
		final int[] reductions = new int[1];
		final int[] reports = new int[1];
		final OrderListenerExceptions[] reported = new OrderListenerExceptions[1];
		final OrderBook book = new OrderBook("AAPL");
		final Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);

		OrderListener throwingListener = new OrderListenerAdapter() {
			@Override
			public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize, CancelReason cancelReason) {
				throw failure;
			}
		};

		OrderListener observer = new OrderListenerAdapter() {
			@Override
			public void onOrderReduced(long time, Order reducedOrder, long canceledSize, long reduceNewTotalSize, CancelReason cancelReason) {
				reductions[0]++;
				assertSame(order, book.getOrder(order.getId()));
				assertEquals(60, order.getPriceLevel().getSize());
			}

			@Override
			public void onExceptionsThrown(Order reportedOrder, OrderListenerExceptions exceptions) {
				reports[0]++;
				reported[0] = exceptions;
				assertSame(order, reportedOrder);
				assertEquals(1, reductions[0]);
				assertEquals(60, order.getTotalSize());
				assertEquals(60, order.getPriceLevel().getSize());
			}
		};

		order.addListener(throwingListener);
		order.addListener(observer);
		order.reduceTo(60);

		assertEquals(1, reductions[0]);
		assertEquals(1, reports[0]);
		assertEquals(1, reported[0].size());
		assertFailure(reported[0].get(0), throwingListener, failure, Callback.ON_ORDER_REDUCED, 1);
	}

	@Test
	public void test_CancellationAndTerminationExceptionsAreReportedAfterTerminalListenersAreCleared() {
		final RuntimeException cancellationFailure = new RuntimeException("cancellation");
		final RuntimeException terminationFailure = new RuntimeException("termination");
		final int[] reports = new int[1];
		final OrderListenerExceptions[] reported = new OrderListenerExceptions[1];
		final OrderBook book = new OrderBook("AAPL");
		final Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);

		OrderListener throwingListener = new OrderListenerAdapter() {
			@Override
			public void onOrderCanceled(long time, Order order, long canceledSize, CancelReason cancelReason) {
				throw cancellationFailure;
			}

			@Override
			public void onOrderTerminated(long time, Order order) {
				throw terminationFailure;
			}
		};

		OrderListener observer = new OrderListenerAdapter() {
			@Override
			public void onExceptionsThrown(Order reportedOrder, OrderListenerExceptions exceptions) {
				reports[0]++;
				reported[0] = exceptions;
				assertSame(order, reportedOrder);
				assertTrue(book.isEmpty());
			}
		};

		order.addListener(throwingListener);
		order.addListener(observer);
		order.cancel();

		assertTrue(order.isTerminal());
		assertTrue(book.isEmpty());
		assertEquals(1, reports[0]);
		assertEquals(2, reported[0].size());
		assertFailure(reported[0].get(0), throwingListener, cancellationFailure, Callback.ON_ORDER_CANCELED, 1);
		assertFailure(reported[0].get(1), throwingListener, terminationFailure, Callback.ON_ORDER_TERMINATED, 1);
	}

	@Test
	public void test_OnExceptionsThrownIsNotCalledWhenNoExternalOrderListenerThrows() {
		final int[] reports = new int[1];
		OrderBook book = new OrderBook("AAPL");
		Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);

		order.addListener(new OrderListenerAdapter() {
			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				reports[0]++;
			}
		});

		order.reduceTo(80);
		order.cancel();

		assertEquals(0, reports[0]);
	}

	@Test
	public void test_EachOrderOwnsAndReportsItsOwnListenerExceptions() {
		final RuntimeException failure = new RuntimeException("execution");
		final int[] reports = new int[1];
		final Order[] reportedOrders = new Order[2];
		final OrderListenerExceptions[] reportedExceptions = new OrderListenerExceptions[2];
		final Order[] taker = new Order[1];
		final OrderBook book = new OrderBook("AAPL");

		book.addListener(new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				if (order.getId() == 3) taker[0] = order;
			}
		});

		OrderListener listener = new OrderListenerAdapter() {
			@Override
			public void onOrderExecuted(long time, Order order, ExecuteSide executeSide, long executeSize,
					long executePrice, long executeId, long executeMatchId) {
				throw failure;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				int index = reports[0]++;
				reportedOrders[index] = order;
				reportedExceptions[index] = exceptions;
				assertEquals(100, taker[0].getExecutedSize());
				assertTrue(book.isEmpty());
			}
		};

		Order maker1 = book.createLimit(1, "maker-1", 1, Side.SELL, 50, 100, TimeInForce.GTC);
		Order maker2 = book.createLimit(1, "maker-2", 2, Side.SELL, 50, 101, TimeInForce.GTC);
		maker1.addListener(listener);
		maker2.addListener(listener);

		book.createLimit(2, "taker", 3, Side.BUY, 100, 101, TimeInForce.GTC);

		assertEquals(2, reports[0]);
		assertSame(maker1, reportedOrders[0]);
		assertSame(maker2, reportedOrders[1]);
		assertEquals(1, reportedExceptions[0].size());
		assertEquals(1, reportedExceptions[1].size());
		assertFailure(reportedExceptions[0].get(0), listener, failure, Callback.ON_ORDER_EXECUTED, maker1.getId());
		assertFailure(reportedExceptions[1].get(0), listener, failure, Callback.ON_ORDER_EXECUTED, maker2.getId());
	}

	@Test
	public void test_InternalFailurePropagatesAndDiscardsEarlierExternalOrderListenerExceptions() {
		final RuntimeException externalFailure = new RuntimeException("external listener");
		final RuntimeException internalFailure = new RuntimeException("internal listener");
		final int[] reports = new int[1];

		OrderListener throwingExternalListener = new OrderListenerAdapter() {
			@Override
			public void onOrderExecuted(long time, Order order, ExecuteSide executeSide, long executeSize,
					long executePrice, long executeId, long executeMatchId) {
				throw externalFailure;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				reports[0]++;
			}
		};

		OrderListener throwingInternalListener = new OrderListenerAdapter() {
			@Override
			public void onOrderExecuted(long time, Order order, ExecuteSide executeSide, long executeSize,
					long executePrice, long executeId, long executeMatchId) {
				throw internalFailure;
			}
		};

		OrderBook book = new OrderBook("AAPL");
		Order maker1 = book.createLimit(1, "maker-1", 1, Side.SELL, 50, 100, TimeInForce.GTC);
		Order maker2 = book.createLimit(1, "maker-2", 2, Side.SELL, 50, 101, TimeInForce.GTC);
		maker1.addListener(throwingExternalListener);
		maker2.addInternalListener(throwingInternalListener);

		try {
			book.createLimit(2, "taker", 3, Side.BUY, 100, 101, TimeInForce.GTC);
			fail("Expected the internal listener exception");
		} catch(RuntimeException e) {
			assertSame(internalFailure, e);
		}

		assertEquals(0, reports[0]);

		book.createLimit(3, "unrelated", 4, Side.BUY, 100, 90, TimeInForce.GTC);
		assertEquals(0, reports[0]);
	}

	private void assertFailure(OrderListenerException exception, OrderListener listener,
			Exception cause, Callback callback, long orderId) {
		assertSame(listener, exception.getListener());
		assertSame(cause, exception.getListenerException());
		assertEquals(callback, exception.getCallback());
		assertEquals(orderId, exception.getOrderId());
	}

	private static class OrderListenerAdapter implements OrderListener {

		@Override
		public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize, CancelReason cancelReason) {
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
