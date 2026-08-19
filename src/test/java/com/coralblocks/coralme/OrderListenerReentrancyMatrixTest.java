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
import java.util.Collection;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.coralblocks.coralme.ListenerSafetyTestSupport.Mutation;
import com.coralblocks.coralme.ListenerSafetyTestSupport.OrderListenerAdapter;
import com.coralblocks.coralme.Order.CancelReason;
import com.coralblocks.coralme.Order.ExecuteSide;
import com.coralblocks.coralme.Order.RejectReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;
import com.coralblocks.coralme.Order.Type;

@RunWith(Parameterized.class)
public class OrderListenerReentrancyMatrixTest {

	private static enum Callback {
		ACCEPTED(OrderListenerException.Callback.ON_ORDER_ACCEPTED),
		RESTED(OrderListenerException.Callback.ON_ORDER_RESTED),
		REDUCED(OrderListenerException.Callback.ON_ORDER_REDUCED),
		EXECUTED(OrderListenerException.Callback.ON_ORDER_EXECUTED),
		CANCELED(OrderListenerException.Callback.ON_ORDER_CANCELED),
		REJECTED(OrderListenerException.Callback.ON_ORDER_REJECTED),
		TERMINATED(OrderListenerException.Callback.ON_ORDER_TERMINATED);

		private final OrderListenerException.Callback exceptionCallback;

		private Callback(OrderListenerException.Callback exceptionCallback) {
			this.exceptionCallback = exceptionCallback;
		}
	}

	@Parameters(name = "{0} -> {1}")
	public static Collection<Object[]> parameters() {
		List<Object[]> parameters = new ArrayList<Object[]>();
		for(Callback callback : Callback.values()) {
			for(Mutation mutation : Mutation.values()) {
				parameters.add(new Object[] { callback, mutation });
			}
		}
		return parameters;
	}

	private final Callback callback;
	private final Mutation mutation;

	public OrderListenerReentrancyMatrixTest(Callback callback, Mutation mutation) {
		this.callback = callback;
		this.mutation = mutation;
	}

	@Test
	public void test_SameOrderBookMutationIsBlockedFromEveryCallback() {
		Harness harness = new Harness(callback, mutation);

		harness.trigger();

		assertTrue(harness.attempted);
		assertEquals(1, harness.reports);
		assertEquals(1, harness.reported.size());
		OrderListenerException listenerException = harness.reported.get(0);
		assertSame(harness, listenerException.getListener());
		assertEquals(callback.exceptionCallback, listenerException.getCallback());
		assertTrue(listenerException.getListenerException() instanceof ReentrantOrderBookOperationException);
		ReentrantOrderBookOperationException reentrantException =
				(ReentrantOrderBookOperationException) listenerException.getListenerException();
		assertSame(harness.book, reentrantException.getOrderBook());
		assertEquals(mutation.expectedOperation(), reentrantException.getOperation());
		harness.assertConsistentState();

		// The guard must always be cleared after the callback and after exception reporting.
		harness.book.addListener(new OrderBookAdapter());
	}

	private static class Harness extends OrderListenerAdapter {

		private final Callback callback;
		private final Mutation mutation;
		private final OrderBook book = new OrderBook("AAPL");
		private final OrderBookListener registeredBookListener = new OrderBookAdapter();
		private final Order order = new Order();
		private boolean attempted;
		private int reports;
		private OrderListenerExceptions reported;

		private Harness(Callback callback, Mutation mutation) {
			this.callback = callback;
			this.mutation = mutation;
			book.addListener(registeredBookListener);
			order.init(book, book.getTimestamper(), 1, "1", 1, "AAPL", Side.BUY, 100, 100,
					Type.LIMIT, TimeInForce.GTC);
			order.addListener(this);
		}

		private void trigger() {
			switch(callback) {
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

		private void attempt(Callback currentCallback) {
			if (attempted || callback != currentCallback) return;
			attempted = true;
			mutation.execute(book, order, registeredBookListener);
			throw new IllegalStateException("Reentrant mutation was not blocked: " + mutation);
		}

		private void assertConsistentState() {
			switch(callback) {
			case ACCEPTED:
				assertTrue(order.isAccepted());
				assertFalse(order.isTerminal());
				assertEquals(100, order.getTotalSize());
				break;
			case RESTED:
				assertTrue(order.isResting());
				assertFalse(order.isTerminal());
				assertEquals(100, order.getTotalSize());
				break;
			case REDUCED:
				assertFalse(order.isTerminal());
				assertEquals(60, order.getTotalSize());
				break;
			case EXECUTED:
				assertFalse(order.isTerminal());
				assertEquals(50, order.getExecutedSize());
				assertEquals(50, order.getOpenSize());
				break;
			case CANCELED:
			case REJECTED:
			case TERMINATED:
				assertTrue(order.isTerminal());
				break;
			}
		}

		@Override
		public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize) {
			attempt(Callback.REDUCED);
		}

		@Override
		public void onOrderCanceled(long time, Order order, long canceledSize, CancelReason cancelReason) {
			attempt(Callback.CANCELED);
		}

		@Override
		public void onOrderExecuted(long time, Order order, ExecuteSide executeSide, long executeSize,
				long executePrice, long executeId, long executeMatchId) {
			attempt(Callback.EXECUTED);
		}

		@Override
		public void onOrderAccepted(long time, Order order) {
			attempt(Callback.ACCEPTED);
		}

		@Override
		public void onOrderRejected(long time, Order order, RejectReason rejectReason) {
			attempt(Callback.REJECTED);
		}

		@Override
		public void onOrderRested(long time, Order order, long restSize, long restPrice) {
			attempt(Callback.RESTED);
		}

		@Override
		public void onOrderTerminated(long time, Order order) {
			attempt(Callback.TERMINATED);
		}

		@Override
		public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
			reports++;
			reported = exceptions;
		}
	}
}
