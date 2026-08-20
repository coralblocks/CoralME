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
import com.coralblocks.coralme.Order.Type;
import com.coralblocks.coralme.OrderBookListenerException.Callback;
import com.coralblocks.coralme.util.DoubleUtils;

public class OrderBookListenerExceptionsTest {

	@Test
	public void test_OnExceptionsThrownIsNotCalledWhenNoListenerThrows() {
		final int[] reports = new int[1];

		OrderBookAdapter listener = new OrderBookAdapter() {
			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reports[0]++;
			}
		};

		OrderBook book = new OrderBook("AAPL", listener);
		Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		assertEquals(0, reports[0]);

		order.reduceTo(80);
		assertEquals(0, reports[0]);

		order.cancel();
		assertEquals(0, reports[0]);

		book.createLimit(1, "2", 2, Side.BUY, 100, 99, TimeInForce.DAY);
		book.createLimit(1, "3", 3, Side.BUY, 100, 98, TimeInForce.GTC);
		book.expire();
		assertEquals(0, reports[0]);

		book.purge();
		assertEquals(0, reports[0]);

		OrderBook rejectingBook = new OrderBook("AAPL", listener) {
			@Override
			protected RejectReason validateOrder(Order order) {
				return RejectReason.TRADING_HALTED;
			}
		};
		rejectingBook.createLimit(1, "4", 4, Side.BUY, 100, 100, TimeInForce.GTC);
		assertEquals(0, reports[0]);
	}

	@Test
	public void test_NestedCreateOrderDefersReportingToEnclosingCreateOrder() {
		final RuntimeException failure = new RuntimeException("acceptance");
		final int[] acceptances = new int[1];
		final int[] rests = new int[1];
		final int[] reports = new int[1];
		final OrderBookListenerExceptions[] reported = new OrderBookListenerExceptions[1];

		OrderBook book = new OrderBook("AAPL") {
			private boolean createNestedOrder = true;

			@Override
			protected RejectReason validateOrder(Order order) {
				if (createNestedOrder) {
					createNestedOrder = false;
					createOrder(2, "nested", 2, Side.BUY, 100, 99, Type.LIMIT, TimeInForce.GTC);
				}
				return null;
			}
		};

		OrderBookAdapter throwingListener = new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				throw failure;
			}
		};

		OrderBookAdapter observer = new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				acceptances[0]++;
			}

			@Override
			public void onOrderRested(OrderBook orderBook, long time, Order order, long restSize, long restPrice) {
				rests[0]++;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reports[0]++;
				reported[0] = exceptions;
				assertEquals(2, acceptances[0]);
				assertEquals(2, rests[0]);
				assertEquals(2, orderBook.getNumberOfOrders());
			}
		};

		book.addListener(throwingListener);
		book.addListener(observer);

		book.createLimit(1, "enclosing", 1, Side.BUY, 100, 100, TimeInForce.GTC);

		assertEquals(1, reports[0]);
		assertEquals(2, reported[0].size());
		assertEquals(1, countFailures(reported[0], throwingListener, failure, Callback.ON_ORDER_ACCEPTED, 1));
		assertEquals(1, countFailures(reported[0], throwingListener, failure, Callback.ON_ORDER_ACCEPTED, 2));
	}

	@Test
	public void test_InternalListenerExceptionPropagatesAndDiscardsCollectedExternalExceptions() {
		final RuntimeException externalFailure = new RuntimeException("external listener");
		final RuntimeException internalFailure = new RuntimeException("internal listener");
		final int[] reports = new int[1];

		final OrderListener internalListener = new InternalOrderListenerAdapter() {
			@Override
			public void onOrderExecuted(long time, Order order, ExecuteSide executeSide, long executeSize,
					long executePrice, long executeId, long executeMatchId) {
				throw internalFailure;
			}
		};

		OrderBookAdapter externalListener = new OrderBookAdapter() {
			@Override
			public void onOrderExecuted(OrderBook orderBook, long time, Order order, ExecuteSide executeSide,
					long executeSize, long executePrice, long executeId, long executeMatchId) {
				throw externalFailure;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reports[0]++;
			}
		};

		OrderBook book = new OrderBook("AAPL", externalListener);
		book.createLimit(1, "maker-1", 1, Side.SELL, 50, 100, TimeInForce.GTC);
		Order maker2 = book.createLimit(1, "maker-2", 2, Side.SELL, 50, 101, TimeInForce.GTC);
		maker2.addInternalListener(internalListener);

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

	@Test
	public void test_ExecutionListenerExceptionsAreAggregatedAfterBothSidesComplete() {
		final RuntimeException failure1 = new RuntimeException("listener-1");
		final RuntimeException failure2 = new RuntimeException("listener-2");
		final int[] listenerReports = new int[2];

		OrderBookAdapter listener1 = new OrderBookAdapter() {
			@Override
			public void onOrderExecuted(OrderBook orderBook, long time, Order order, ExecuteSide executeSide,
					long executeSize, long executePrice, long executeId, long executeMatchId) {
				throw failure1;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				listenerReports[0]++;
				throw new RuntimeException("report failure must be ignored");
			}
		};

		OrderBookAdapter listener2 = new OrderBookAdapter() {
			@Override
			public void onOrderExecuted(OrderBook orderBook, long time, Order order, ExecuteSide executeSide,
					long executeSize, long executePrice, long executeId, long executeMatchId) {
				throw failure2;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				listenerReports[1]++;
			}
		};

		final Order[] taker = new Order[1];
		final int[] executions = new int[1];
		final int[] terminations = new int[1];
		final OrderBookListenerExceptions[] reported = new OrderBookListenerExceptions[1];

		OrderBookAdapter observer = new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				if (order.getId() == 2) taker[0] = order;
			}

			@Override
			public void onOrderExecuted(OrderBook orderBook, long time, Order order, ExecuteSide executeSide,
					long executeSize, long executePrice, long executeId, long executeMatchId) {
				executions[0]++;
			}

			@Override
			public void onOrderTerminated(OrderBook orderBook, long time, Order order) {
				terminations[0]++;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reported[0] = exceptions;
				assertEquals(2, executions[0]);
				assertEquals(2, terminations[0]);
				assertEquals(100, taker[0].getExecutedSize());
				assertTrue(taker[0].isTerminal());
				assertTrue(orderBook.isEmpty());
			}
		};

		OrderBook book = new OrderBook("AAPL");
		book.addListener(listener1);
		book.addListener(listener2);
		book.addListener(observer);

		long price = DoubleUtils.toLong(100.25);
		Order maker = book.createLimit(1, "maker", 1, Side.SELL, 100, price, TimeInForce.GTC);
		Order returnedTaker = book.createLimit(2, "taker", 2, Side.BUY, 100, price, TimeInForce.GTC);

		assertSame(taker[0], returnedTaker);
		assertEquals(100, maker.getExecutedSize());
		assertEquals(100, returnedTaker.getExecutedSize());
		assertTrue(maker.isTerminal());
		assertTrue(returnedTaker.isTerminal());
		assertTrue(book.isEmpty());
		assertEquals(price, book.getLastExecutedPrice());
		assertEquals(2, executions[0]);
		assertEquals(2, terminations[0]);
		assertEquals(1, listenerReports[0]);
		assertEquals(1, listenerReports[1]);

		OrderBookListenerExceptions exceptions = reported[0];
		assertEquals(4, exceptions.size());
		assertFailure(exceptions.get(0), listener1, failure1, 1, 1);
		assertFailure(exceptions.get(1), listener2, failure2, 1, 1);
		assertFailure(exceptions.get(2), listener1, failure1, 2, 1);
		assertFailure(exceptions.get(3), listener2, failure2, 2, 1);
	}

	@Test
	public void test_CancelAndTerminationExceptionsAreReportedTogetherAfterCancellation() {
		final RuntimeException cancelFailure = new RuntimeException("cancel");
		final RuntimeException terminationFailure = new RuntimeException("termination");
		final OrderBookListenerExceptions[] reported = new OrderBookListenerExceptions[1];

		OrderBookAdapter throwingListener = new OrderBookAdapter() {
			@Override
			public void onOrderCanceled(OrderBook orderBook, long time, Order order, long canceledSize, CancelReason cancelReason) {
				throw cancelFailure;
			}

			@Override
			public void onOrderTerminated(OrderBook orderBook, long time, Order order) {
				throw terminationFailure;
			}
		};

		OrderBookAdapter observer = new OrderBookAdapter() {
			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reported[0] = exceptions;
				assertTrue(orderBook.isEmpty());
			}
		};

		OrderBook book = new OrderBook("AAPL");
		book.addListener(throwingListener);
		book.addListener(observer);

		Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		assertFalse(order.isTerminal());

		order.cancel();

		assertTrue(order.isTerminal());
		assertTrue(book.isEmpty());
		assertEquals(2, reported[0].size());
		assertSame(throwingListener, reported[0].get(0).getListener());
		assertSame(cancelFailure, reported[0].get(0).getListenerException());
		assertEquals(OrderBookListenerException.Callback.ON_ORDER_CANCELED, reported[0].get(0).getCallback());
		assertSame(throwingListener, reported[0].get(1).getListener());
		assertSame(terminationFailure, reported[0].get(1).getListenerException());
		assertEquals(OrderBookListenerException.Callback.ON_ORDER_TERMINATED, reported[0].get(1).getCallback());
	}

	@Test
	public void test_DirectReductionExceptionIsReportedAfterAllReductionListenersAreCalled() {
		final RuntimeException failure = new RuntimeException("reduction");
		final int[] reductionCallbacks = new int[1];
		final int[] reports = new int[1];
		final OrderBookListenerExceptions[] reported = new OrderBookListenerExceptions[1];

		OrderBookAdapter throwingListener = new OrderBookAdapter() {
			@Override
			public void onOrderReduced(OrderBook orderBook, long time, Order order, long canceledSize, long reduceNewTotalSize, CancelReason cancelReason) {
				throw failure;
			}
		};

		OrderBookAdapter observer = new OrderBookAdapter() {
			@Override
			public void onOrderReduced(OrderBook orderBook, long time, Order order, long canceledSize, long reduceNewTotalSize, CancelReason cancelReason) {
				reductionCallbacks[0]++;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reports[0]++;
				reported[0] = exceptions;
				assertEquals(1, reductionCallbacks[0]);
				assertEquals(60, orderBook.getOrder(1).getTotalSize());
			}
		};

		OrderBook book = new OrderBook("AAPL");
		book.addListener(throwingListener);
		book.addListener(observer);

		Order order = book.createLimit(1, "reduce", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		order.reduceTo(60);

		assertEquals(60, order.getTotalSize());
		assertEquals(1, book.getNumberOfOrders());
		assertEquals(1, reports[0]);
		assertEquals(1, reported[0].size());
		assertFailure(reported[0].get(0), throwingListener, failure, Callback.ON_ORDER_REDUCED, 1);
	}

	@Test
	public void test_ReductionToExecutedSizeReportsCancellationAndTerminationExceptionsTogether() {
		final RuntimeException cancelFailure = new RuntimeException("terminal reduction cancellation");
		final RuntimeException terminationFailure = new RuntimeException("terminal reduction termination");
		final int[] reductions = new int[1];
		final int[] cancellations = new int[1];
		final int[] terminations = new int[1];
		final int[] reports = new int[1];
		final OrderBookListenerExceptions[] reported = new OrderBookListenerExceptions[1];

		OrderBook book = new OrderBook("AAPL");
		final Order maker = book.createLimit(1, "maker", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		book.createLimit(2, "taker", 2, Side.SELL, 40, 100, TimeInForce.GTC);

		assertEquals(40, maker.getExecutedSize());
		assertEquals(60, maker.getOpenSize());
		assertEquals(1, book.getNumberOfOrders());

		OrderBookAdapter throwingListener = new OrderBookAdapter() {
			@Override
			public void onOrderCanceled(OrderBook orderBook, long time, Order order, long canceledSize, CancelReason cancelReason) {
				throw cancelFailure;
			}

			@Override
			public void onOrderTerminated(OrderBook orderBook, long time, Order order) {
				throw terminationFailure;
			}
		};

		OrderBookAdapter observer = new OrderBookAdapter() {
			@Override
			public void onOrderReduced(OrderBook orderBook, long time, Order order, long canceledSize, long reduceNewTotalSize, CancelReason cancelReason) {
				reductions[0]++;
			}

			@Override
			public void onOrderCanceled(OrderBook orderBook, long time, Order order, long canceledSize, CancelReason cancelReason) {
				cancellations[0]++;
			}

			@Override
			public void onOrderTerminated(OrderBook orderBook, long time, Order order) {
				terminations[0]++;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reports[0]++;
				reported[0] = exceptions;
				assertEquals(0, reductions[0]);
				assertEquals(1, cancellations[0]);
				assertEquals(1, terminations[0]);
				assertTrue(maker.isTerminal());
				assertTrue(orderBook.isEmpty());
			}
		};

		book.addListener(throwingListener);
		book.addListener(observer);

		maker.reduceTo(maker.getExecutedSize());

		assertEquals(0, reductions[0]);
		assertEquals(1, cancellations[0]);
		assertEquals(1, terminations[0]);
		assertEquals(1, reports[0]);
		assertEquals(2, reported[0].size());
		assertFailure(reported[0].get(0), throwingListener, cancelFailure, Callback.ON_ORDER_CANCELED, maker.getId());
		assertFailure(reported[0].get(1), throwingListener, terminationFailure, Callback.ON_ORDER_TERMINATED, maker.getId());
	}

	@Test
	public void test_DirectRejectionExceptionIsReportedAfterAllRejectionListenersAreCalled() {
		final RuntimeException failure = new RuntimeException("rejection");
		final int[] rejectionCallbacks = new int[1];
		final int[] reports = new int[1];
		final OrderBookListenerExceptions[] reported = new OrderBookListenerExceptions[1];

		OrderBookAdapter throwingListener = new OrderBookAdapter() {
			@Override
			public void onOrderRejected(OrderBook orderBook, long time, Order order, RejectReason rejectReason) {
				throw failure;
			}
		};

		OrderBookAdapter observer = new OrderBookAdapter() {
			@Override
			public void onOrderRejected(OrderBook orderBook, long time, Order order, RejectReason rejectReason) {
				rejectionCallbacks[0]++;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reports[0]++;
				reported[0] = exceptions;
				assertEquals(1, rejectionCallbacks[0]);
				assertTrue(orderBook.isEmpty());
			}
		};

		OrderBook book = new OrderBook("AAPL");
		book.addListener(throwingListener);
		book.addListener(observer);

		Order order = new Order();
		order.init(book, book.getTimestamper(), 7, "reject", 11, book.getSecurity(), Side.SELL, 100, 100, Type.LIMIT, TimeInForce.GTC);
		order.addInternalListener(book);
		order.reject(RejectReason.TRADING_HALTED);

		assertTrue(order.isTerminal());
		assertEquals(1, reports[0]);
		assertEquals(1, reported[0].size());
		assertFailure(reported[0].get(0), throwingListener, failure, Callback.ON_ORDER_REJECTED, 11);
	}

	@Test
	public void test_PurgeAggregatesAllExceptionsAndReportsAfterEveryOrderIsCanceled() {
		final RuntimeException cancelFailure = new RuntimeException("purge cancel");
		final RuntimeException terminationFailure = new RuntimeException("purge termination");
		final int[] reports = new int[1];
		final OrderBookListenerExceptions[] reported = new OrderBookListenerExceptions[1];
		final Order[] orders = new Order[3];

		OrderBookAdapter throwingListener = cancellationThrowingListener(cancelFailure, terminationFailure);
		OrderBookAdapter observer = new OrderBookAdapter() {
			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reports[0]++;
				reported[0] = exceptions;
				assertTrue(orderBook.isEmpty());
				for(Order order : orders) assertTrue(order.isTerminal());
			}
		};

		OrderBook book = new OrderBook("AAPL");
		book.addListener(throwingListener);
		book.addListener(observer);

		orders[0] = book.createLimit(1, "1", 1, Side.BUY, 100, 99, TimeInForce.DAY);
		orders[1] = book.createLimit(1, "2", 2, Side.BUY, 200, 98, TimeInForce.GTC);
		orders[2] = book.createLimit(1, "3", 3, Side.SELL, 300, 101, TimeInForce.GTC);

		book.purge();

		assertEquals(1, reports[0]);
		assertEquals(6, reported[0].size());
		assertCancellationFailures(reported[0], throwingListener, cancelFailure, terminationFailure, orders);
	}

	@Test
	public void test_ExpireAggregatesDayOrderExceptionsAndReportsAfterEveryDayOrderIsCanceled() {
		final RuntimeException cancelFailure = new RuntimeException("expiration cancel");
		final RuntimeException terminationFailure = new RuntimeException("expiration termination");
		final int[] reports = new int[1];
		final OrderBookListenerExceptions[] reported = new OrderBookListenerExceptions[1];
		final Order[] dayOrders = new Order[2];
		final Order[] gtcOrder = new Order[1];

		OrderBookAdapter throwingListener = cancellationThrowingListener(cancelFailure, terminationFailure);
		OrderBookAdapter observer = new OrderBookAdapter() {
			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reports[0]++;
				reported[0] = exceptions;
				assertEquals(1, orderBook.getNumberOfOrders());
				assertSame(gtcOrder[0], orderBook.getOrder(gtcOrder[0].getId()));
				assertFalse(gtcOrder[0].isTerminal());
				for(Order order : dayOrders) assertTrue(order.isTerminal());
			}
		};

		OrderBook book = new OrderBook("AAPL");
		book.addListener(throwingListener);
		book.addListener(observer);

		dayOrders[0] = book.createLimit(1, "1", 1, Side.BUY, 100, 99, TimeInForce.DAY);
		gtcOrder[0] = book.createLimit(1, "2", 2, Side.BUY, 200, 98, TimeInForce.GTC);
		dayOrders[1] = book.createLimit(1, "3", 3, Side.SELL, 300, 101, TimeInForce.DAY);

		book.expire();

		assertEquals(1, reports[0]);
		assertEquals(4, reported[0].size());
		assertCancellationFailures(reported[0], throwingListener, cancelFailure, terminationFailure, dayOrders);
	}

	private OrderBookAdapter cancellationThrowingListener(final RuntimeException cancelFailure,
			final RuntimeException terminationFailure) {
		return new OrderBookAdapter() {
			@Override
			public void onOrderCanceled(OrderBook orderBook, long time, Order order, long canceledSize, CancelReason cancelReason) {
				throw cancelFailure;
			}

			@Override
			public void onOrderTerminated(OrderBook orderBook, long time, Order order) {
				throw terminationFailure;
			}
		};
	}

	private void assertCancellationFailures(OrderBookListenerExceptions exceptions, OrderBookListener listener,
			RuntimeException cancelFailure, RuntimeException terminationFailure, Order[] orders) {
		for(Order order : orders) {
			assertEquals(1, countFailures(exceptions, listener, cancelFailure, Callback.ON_ORDER_CANCELED, order.getId()));
			assertEquals(1, countFailures(exceptions, listener, terminationFailure, Callback.ON_ORDER_TERMINATED, order.getId()));
		}
	}

	private int countFailures(OrderBookListenerExceptions exceptions, OrderBookListener listener,
			RuntimeException cause, Callback callback, long orderId) {
		int count = 0;
		for(OrderBookListenerException exception : exceptions) {
			if (exception.getListener() == listener && exception.getListenerException() == cause
					&& exception.getCallback() == callback && exception.getOrderId() == orderId) count++;
		}
		return count;
	}

	private void assertFailure(OrderBookListenerException exception, OrderBookListener listener,
			RuntimeException cause, Callback callback, long orderId) {
		assertSame(listener, exception.getListener());
		assertSame(cause, exception.getListenerException());
		assertEquals(callback, exception.getCallback());
		assertEquals(orderId, exception.getOrderId());
	}

	private void assertFailure(OrderBookListenerException exception, OrderBookListener listener,
			RuntimeException cause, long orderId, long matchId) {
		assertSame(listener, exception.getListener());
		assertSame(cause, exception.getListenerException());
		assertEquals(OrderBookListenerException.Callback.ON_ORDER_EXECUTED, exception.getCallback());
		assertEquals(orderId, exception.getOrderId());
		assertTrue(exception.getExecutionId() > 0);
		assertEquals(matchId, exception.getMatchId());
	}

	private static class InternalOrderListenerAdapter implements OrderListener {

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
