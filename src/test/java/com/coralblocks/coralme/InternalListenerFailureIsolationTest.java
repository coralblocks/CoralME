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
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.coralblocks.coralme.ListenerSafetyTestSupport.OrderListenerAdapter;
import com.coralblocks.coralme.Order.CancelReason;
import com.coralblocks.coralme.Order.ExecuteSide;
import com.coralblocks.coralme.Order.RejectReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;
import com.coralblocks.coralme.Order.Type;

@RunWith(Parameterized.class)
public class InternalListenerFailureIsolationTest {

	private static enum Callback {
		ACCEPTED, RESTED, REDUCED, EXECUTED, CANCELED, REJECTED, TERMINATED
	}

	@Parameters(name = "{0}")
	public static Collection<Object[]> parameters() {
		return Arrays.asList(
				Arrays.stream(Callback.values()).map(callback -> new Object[] { callback }).toArray(Object[][]::new));
	}

	private final Callback callback;

	public InternalListenerFailureIsolationTest(Callback callback) {
		this.callback = callback;
	}

	@Test
	public void test_InternalFailurePropagatesAndDiscardsOrderBookListenerFailureForEveryCallback() {
		final RuntimeException externalFailure = new RuntimeException("external");
		final RuntimeException internalFailure = new RuntimeException("internal");
		final int[] bookReports = new int[1];
		final int[] orderReports = new int[1];
		OrderBook book = new OrderBook("AAPL");
		book.addListener(new OneShotThrowingOrderBookListener(callback, externalFailure, bookReports));

		Order order = new Order();
		order.init(book, book.getTimestamper(), 1, "1", 1, "AAPL", Side.BUY, 100, 100, Type.LIMIT, TimeInForce.GTC);
		// Internal callbacks execute in reverse registration order. The OrderBook runs
		// first,
		// collects the external failure, and the fatal internal listener then
		// interrupts processing.
		order.addInternalListener(new ThrowingInternalOrderListener(callback, internalFailure));
		order.addInternalListener(book.internalOrderListener());
		order.addListener(new OrderListenerAdapter() {
			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				orderReports[0]++;
			}
		});

		try {
			trigger(order);
			fail("Expected internal listener failure");
		} catch (RuntimeException e) {
			assertSame(internalFailure, e);
		}

		assertEquals(0, bookReports[0]);
		assertEquals(0, orderReports[0]);

		// A later operation must not retain listeners or report failures discarded from
		// the interrupted operation, even when it reuses a terminal pooled order.
		Order unrelated;
		if (callback == Callback.REJECTED) {
			unrelated = book.createLimit(2, "unrelated", 2, Side.BUY, 0, 50, TimeInForce.GTC);
		} else {
			unrelated = book.createLimit(2, "unrelated", 2, Side.BUY, 100, 50, TimeInForce.GTC);
			if (callback == Callback.CANCELED || callback == Callback.TERMINATED) unrelated.cancel();
		}
		if (callback == Callback.CANCELED || callback == Callback.REJECTED || callback == Callback.TERMINATED) {
			assertSame(order, unrelated);
		}
		assertEquals(0, bookReports[0]);
		assertEquals(0, orderReports[0]);
	}

	@Test
	public void test_ReentrantExceptionThrownByInternalListenerRemainsFatalAndIsNeverAggregated() {
		final int[] bookReports = new int[1];
		final int[] orderReports = new int[1];
		OrderBook book = new OrderBook("AAPL", new OrderBookAdapter() {
			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				bookReports[0]++;
			}
		});
		Order order = new Order();
		order.init(book, book.getTimestamper(), 1, "1", 1, "AAPL", Side.BUY, 100, 100, Type.LIMIT, TimeInForce.GTC);
		final ReentrantOrderBookOperationException internalFailure = new ReentrantOrderBookOperationException(book,
				"synthetic-internal-failure");
		order.addInternalListener(new ThrowingInternalOrderListener(callback, internalFailure));
		order.addListener(new OrderListenerAdapter() {
			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				orderReports[0]++;
			}
		});

		try {
			trigger(order);
			fail("Expected internal reentrant exception");
		} catch (ReentrantOrderBookOperationException e) {
			assertSame(internalFailure, e);
		}

		assertEquals(0, bookReports[0]);
		assertEquals(0, orderReports[0]);
	}

	private void trigger(Order order) {
		switch (callback) {
		case ACCEPTED:
			order.accept(1);
			break;
		case RESTED:
			order.rest();
			break;
		case REDUCED:
			order.reduceTo(60);
			break;
		case EXECUTED:
			order.execute(1, 50);
			break;
		case CANCELED:
		case TERMINATED:
			order.cancel();
			break;
		case REJECTED:
			order.reject(RejectReason.BAD_TYPE);
			break;
		}
	}

	private static class OneShotThrowingOrderBookListener extends OrderBookAdapter {

		private final Callback callback;
		private final RuntimeException failure;
		private final int[] reports;
		private boolean thrown;

		private OneShotThrowingOrderBookListener(Callback callback, RuntimeException failure, int[] reports) {
			this.callback = callback;
			this.failure = failure;
			this.reports = reports;
		}

		private void fail(Callback currentCallback) {
			if (thrown || callback != currentCallback) return;
			thrown = true;
			throw failure;
		}

		@Override
		public void onOrderReduced(OrderBook orderBook, long time, Order order, long canceledSize,
				long reduceNewTotalSize, CancelReason cancelReason) {
			fail(Callback.REDUCED);
		}

		@Override
		public void onOrderCanceled(OrderBook orderBook, long time, Order order, long canceledSize,
				CancelReason cancelReason) {
			fail(Callback.CANCELED);
		}

		@Override
		public void onOrderExecuted(OrderBook orderBook, long time, Order order, ExecuteSide executeSide,
				long executeSize, long executePrice, long executeId, long executeMatchId) {
			fail(Callback.EXECUTED);
		}

		@Override
		public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
			fail(Callback.ACCEPTED);
		}

		@Override
		public void onOrderRejected(OrderBook orderBook, long time, Order order, RejectReason rejectReason) {
			fail(Callback.REJECTED);
		}

		@Override
		public void onOrderRested(OrderBook orderBook, long time, Order order, long restSize, long restPrice) {
			fail(Callback.RESTED);
		}

		@Override
		public void onOrderTerminated(OrderBook orderBook, long time, Order order) {
			fail(Callback.TERMINATED);
		}

		@Override
		public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
			reports[0]++;
		}
	}

	private static class ThrowingInternalOrderListener extends OrderListenerAdapter {

		private final Callback callback;
		private final RuntimeException failure;

		private ThrowingInternalOrderListener(Callback callback, RuntimeException failure) {
			this.callback = callback;
			this.failure = failure;
		}

		private void fail(Callback currentCallback) {
			if (callback == currentCallback) throw failure;
		}

		@Override
		public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize,
				CancelReason cancelReason) {
			fail(Callback.REDUCED);
		}

		@Override
		public void onOrderCanceled(long time, Order order, long canceledSize, CancelReason cancelReason) {
			fail(Callback.CANCELED);
		}

		@Override
		public void onOrderExecuted(long time, Order order, ExecuteSide executeSide, long executeSize,
				long executePrice, long executeId, long executeMatchId) {
			fail(Callback.EXECUTED);
		}

		@Override
		public void onOrderAccepted(long time, Order order) {
			fail(Callback.ACCEPTED);
		}

		@Override
		public void onOrderRejected(long time, Order order, RejectReason rejectReason) {
			fail(Callback.REJECTED);
		}

		@Override
		public void onOrderRested(long time, Order order, long restSize, long restPrice) {
			fail(Callback.RESTED);
		}

		@Override
		public void onOrderTerminated(long time, Order order) {
			fail(Callback.TERMINATED);
		}
	}
}
