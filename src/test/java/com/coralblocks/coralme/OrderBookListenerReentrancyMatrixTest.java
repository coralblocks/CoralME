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

import com.coralblocks.coralme.ListenerSafetyTestSupport.GuardedOperation;
import com.coralblocks.coralme.Order.CancelReason;
import com.coralblocks.coralme.Order.ExecuteSide;
import com.coralblocks.coralme.Order.RejectReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;

@RunWith(Parameterized.class)
public class OrderBookListenerReentrancyMatrixTest {

	private static enum Callback {
		ACCEPTED(OrderBookListenerException.Callback.ON_ORDER_ACCEPTED),
		RESTED(OrderBookListenerException.Callback.ON_ORDER_RESTED),
		REDUCED(OrderBookListenerException.Callback.ON_ORDER_REDUCED),
		EXECUTED(OrderBookListenerException.Callback.ON_ORDER_EXECUTED),
		CANCELED(OrderBookListenerException.Callback.ON_ORDER_CANCELED),
		REJECTED(OrderBookListenerException.Callback.ON_ORDER_REJECTED),
		TERMINATED(OrderBookListenerException.Callback.ON_ORDER_TERMINATED);

		private final OrderBookListenerException.Callback exceptionCallback;

		private Callback(OrderBookListenerException.Callback exceptionCallback) {
			this.exceptionCallback = exceptionCallback;
		}
	}

	@Parameters(name = "{0} -> {1}")
	public static Collection<Object[]> parameters() {
		List<Object[]> parameters = new ArrayList<Object[]>();
		for(Callback callback : Callback.values()) {
			for(GuardedOperation operation : GuardedOperation.values()) {
				parameters.add(new Object[] { callback, operation });
			}
		}
		return parameters;
	}

	private final Callback callback;
	private final GuardedOperation operation;

	public OrderBookListenerReentrancyMatrixTest(Callback callback, GuardedOperation operation) {
		this.callback = callback;
		this.operation = operation;
	}

	@Test
	public void test_SameOrderBookGuardedOperationIsBlockedFromEveryCallback() {
		Harness harness = new Harness(callback, operation);

		harness.trigger();

		assertTrue(harness.attempted);
		assertEquals(1, harness.reports);
		assertEquals(1, harness.reported.size());
		OrderBookListenerException listenerException = harness.reported.get(0);
		assertSame(harness.listener, listenerException.getListener());
		assertEquals(callback.exceptionCallback, listenerException.getCallback());
		assertTrue(listenerException.getListenerException() instanceof ReentrantOrderBookOperationException);
		ReentrantOrderBookOperationException reentrantException =
				(ReentrantOrderBookOperationException) listenerException.getListenerException();
		assertSame(harness.book, reentrantException.getOrderBook());
		assertEquals(operation.expectedOperation(), reentrantException.getOperation());
		harness.assertConsistentState();

		// The guard must always be cleared after the callback and after exception reporting.
		harness.book.addListener(new OrderBookAdapter());
	}

	private static class Harness extends OrderBookAdapter {

		private final Callback callback;
		private final GuardedOperation operation;
		private final OrderBook book;
		private final OrderBookListener listener = this;
		private boolean attempted;
		private int reports;
		private OrderBookListenerExceptions reported;
		private Order primaryOrder;
		private Order secondaryOrder;

		private Harness(Callback callback, GuardedOperation operation) {
			this.callback = callback;
			this.operation = operation;
			this.book = callback == Callback.REJECTED ? new RejectingOrderBook() : new OrderBook("AAPL");
			this.book.addListener(this);
		}

		private void trigger() {
			switch(callback) {
			case ACCEPTED:
			case RESTED:
				primaryOrder = book.createLimit(1, "resting", 1, Side.BUY, 100, 100, TimeInForce.GTC);
				break;
			case REDUCED:
				primaryOrder = book.createLimit(1, "reduced", 1, Side.BUY, 100, 100, TimeInForce.GTC);
				primaryOrder.reduceTo(60);
				break;
			case EXECUTED:
				primaryOrder = book.createLimit(1, "maker", 1, Side.SELL, 100, 100, TimeInForce.GTC);
				secondaryOrder = book.createLimit(2, "taker", 2, Side.BUY, 100, 100, TimeInForce.GTC);
				break;
			case CANCELED:
			case TERMINATED:
				primaryOrder = book.createLimit(1, "canceled", 1, Side.BUY, 100, 100, TimeInForce.GTC);
				primaryOrder.cancel();
				break;
			case REJECTED:
				primaryOrder = book.createLimit(1, "rejected", 1, Side.BUY, 100, 100, TimeInForce.GTC);
				break;
			}
		}

		private void assertConsistentState() {
			switch(callback) {
			case ACCEPTED:
			case RESTED:
				assertFalse(primaryOrder.isTerminal());
				assertTrue(primaryOrder.isResting());
				assertEquals(100, primaryOrder.getTotalSize());
				assertSame(primaryOrder, book.getOrder(1));
				assertEquals(1, book.getNumberOfOrders());
				break;
			case REDUCED:
				assertFalse(primaryOrder.isTerminal());
				assertEquals(60, primaryOrder.getTotalSize());
				assertSame(primaryOrder, book.getOrder(1));
				assertEquals(1, book.getNumberOfOrders());
				break;
			case EXECUTED:
				assertTrue(primaryOrder.isTerminal());
				assertTrue(secondaryOrder.isTerminal());
				assertEquals(100, primaryOrder.getExecutedSize());
				assertEquals(100, secondaryOrder.getExecutedSize());
				assertTrue(book.isEmpty());
				break;
			case CANCELED:
			case REJECTED:
			case TERMINATED:
				assertTrue(primaryOrder.isTerminal());
				assertTrue(book.isEmpty());
				break;
			}
		}

		private void attempt(Callback currentCallback, Order order) {
			if (attempted || callback != currentCallback) return;
			attempted = true;
			operation.execute(book, order, listener);
			throw new IllegalStateException("Reentrant operation was not blocked: " + operation);
		}

		@Override
		public void onOrderReduced(OrderBook orderBook, long time, Order order, long canceledSize,
				long reduceNewTotalSize) {
			attempt(Callback.REDUCED, order);
		}

		@Override
		public void onOrderCanceled(OrderBook orderBook, long time, Order order, long canceledSize,
				CancelReason cancelReason) {
			attempt(Callback.CANCELED, order);
		}

		@Override
		public void onOrderExecuted(OrderBook orderBook, long time, Order order, ExecuteSide executeSide,
				long executeSize, long executePrice, long executeId, long executeMatchId) {
			attempt(Callback.EXECUTED, order);
		}

		@Override
		public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
			attempt(Callback.ACCEPTED, order);
		}

		@Override
		public void onOrderRejected(OrderBook orderBook, long time, Order order, RejectReason rejectReason) {
			attempt(Callback.REJECTED, order);
		}

		@Override
		public void onOrderRested(OrderBook orderBook, long time, Order order, long restSize, long restPrice) {
			attempt(Callback.RESTED, order);
		}

		@Override
		public void onOrderTerminated(OrderBook orderBook, long time, Order order) {
			attempt(Callback.TERMINATED, order);
		}

		@Override
		public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
			reports++;
			reported = exceptions;
		}
	}

	private static class RejectingOrderBook extends OrderBook {

		private RejectingOrderBook() {
			super("AAPL");
		}

		@Override
		protected RejectReason validateOrder(Order order) {
			return RejectReason.BAD_TYPE;
		}
	}
}
