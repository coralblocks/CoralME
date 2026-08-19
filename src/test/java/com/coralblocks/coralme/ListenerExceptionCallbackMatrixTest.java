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
import java.util.Collection;
import java.util.List;

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
public class ListenerExceptionCallbackMatrixTest {

	private static enum Callback {
		ACCEPTED(OrderBookListenerException.Callback.ON_ORDER_ACCEPTED,
				OrderListenerException.Callback.ON_ORDER_ACCEPTED),
		RESTED(OrderBookListenerException.Callback.ON_ORDER_RESTED,
				OrderListenerException.Callback.ON_ORDER_RESTED),
		REDUCED(OrderBookListenerException.Callback.ON_ORDER_REDUCED,
				OrderListenerException.Callback.ON_ORDER_REDUCED),
		EXECUTED(OrderBookListenerException.Callback.ON_ORDER_EXECUTED,
				OrderListenerException.Callback.ON_ORDER_EXECUTED),
		CANCELED(OrderBookListenerException.Callback.ON_ORDER_CANCELED,
				OrderListenerException.Callback.ON_ORDER_CANCELED),
		REJECTED(OrderBookListenerException.Callback.ON_ORDER_REJECTED,
				OrderListenerException.Callback.ON_ORDER_REJECTED),
		TERMINATED(OrderBookListenerException.Callback.ON_ORDER_TERMINATED,
				OrderListenerException.Callback.ON_ORDER_TERMINATED);

		private final OrderBookListenerException.Callback bookCallback;
		private final OrderListenerException.Callback orderCallback;

		private Callback(OrderBookListenerException.Callback bookCallback,
				OrderListenerException.Callback orderCallback) {
			this.bookCallback = bookCallback;
			this.orderCallback = orderCallback;
		}
	}

	@Parameters(name = "{0}")
	public static Collection<Object[]> parameters() {
		return Arrays.asList(Arrays.stream(Callback.values())
				.map(callback -> new Object[] { callback })
				.toArray(Object[][]::new));
	}

	private final Callback callback;

	public ListenerExceptionCallbackMatrixTest(Callback callback) {
		this.callback = callback;
	}

	@Test
	public void test_MultipleOrderBookListenersAreIsolatedAggregatedAndReportedForEveryCallback() {
		List<String> events = new ArrayList<String>();
		RuntimeException failure1 = new RuntimeException("book listener 1");
		RuntimeException failure2 = new RuntimeException("book listener 2");
		ThrowingOrderBookListener listener1 = new ThrowingOrderBookListener("book-1", callback, failure1, events);
		ThrowingOrderBookListener listener2 = new ThrowingOrderBookListener("book-2", callback, failure2, events);
		OrderBook book = callback == Callback.REJECTED ? new RejectingOrderBook() : new OrderBook("AAPL");
		book.addListener(listener1);
		book.addListener(listener2);

		Order affectedOrder = triggerOrderBookCallback(book);

		assertEquals(1, listener1.reportCalls);
		assertEquals(1, listener2.reportCalls);
		assertSame(listener1.reported, listener2.reported);
		assertEquals(2, listener1.reported.size());
		assertBookFailure(listener1.reported.get(0), listener1, failure1, affectedOrder);
		assertBookFailure(listener1.reported.get(1), listener2, failure2, affectedOrder);
		assertEquals("book-2-report", events.get(events.size() - 1));
		assertOrderBookStateAfterCallback(book, affectedOrder);
	}

	@Test
	public void test_MultipleOrderListenersAreIsolatedAggregatedAndReportedForEveryCallback() {
		List<String> events = new ArrayList<String>();
		RuntimeException failure1 = new RuntimeException("order listener 1");
		RuntimeException failure2 = new RuntimeException("order listener 2");
		OrderBook book = new OrderBook("AAPL");
		Order order = new Order();
		order.init(book, book.getTimestamper(), 1, "1", 1, "AAPL", Side.BUY, 100, 100,
				Type.LIMIT, TimeInForce.GTC);
		ThrowingOrderListener listener1 = new ThrowingOrderListener("order-1", callback, failure1, events);
		ThrowingOrderListener listener2 = new ThrowingOrderListener("order-2", callback, failure2, events);
		order.addListener(listener1);
		order.addListener(listener2);

		triggerOrderCallback(order);

		assertEquals(1, listener1.reportCalls);
		assertEquals(1, listener2.reportCalls);
		assertSame(listener1.reported, listener2.reported);
		assertEquals(2, listener1.reported.size());
		// Normal OrderListener callbacks execute in reverse registration order.
		assertOrderFailure(listener1.reported.get(0), listener2, failure2, order);
		assertOrderFailure(listener1.reported.get(1), listener1, failure1, order);
		// Exception reports execute in registration order and are last for this Order.
		assertEquals("order-2-report", events.get(events.size() - 1));
		assertOrderStateAfterCallback(order);
	}

	private Order triggerOrderBookCallback(OrderBook book) {
		switch(callback) {
		case ACCEPTED:
		case RESTED:
			return book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		case REDUCED:
			Order reduced = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);
			reduced.reduceTo(60);
			return reduced;
		case EXECUTED:
			Order maker = book.createLimit(1, "maker", 1, Side.SELL, 100, 100, TimeInForce.GTC);
			book.createLimit(2, "taker", 2, Side.BUY, 100, 100, TimeInForce.GTC);
			return maker;
		case CANCELED:
		case TERMINATED:
			Order canceled = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);
			canceled.cancel();
			return canceled;
		case REJECTED:
			return book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		default:
			throw new IllegalStateException("Unsupported callback: " + callback);
		}
	}

	private void triggerOrderCallback(Order order) {
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

	private void assertBookFailure(OrderBookListenerException exception, OrderBookListener listener,
			RuntimeException failure, Order order) {
		assertSame(listener, exception.getListener());
		assertSame(failure, exception.getListenerException());
		assertEquals(callback.bookCallback, exception.getCallback());
		assertEquals(((ThrowingOrderBookListener) listener).failureTime, exception.getTime());
		assertSnapshot(exception.getOrderId(), exception.getClientId(), exception.getClientOrderId(),
				exception.getSecurity(), exception.getSide(), order);
		if (callback == Callback.EXECUTED) {
			assertTrue(exception.getExecutionId() > 0);
			assertTrue(exception.getMatchId() > 0);
		} else {
			assertEquals(-1, exception.getExecutionId());
			assertEquals(-1, exception.getMatchId());
		}
	}

	private void assertOrderFailure(OrderListenerException exception, OrderListener listener,
			RuntimeException failure, Order order) {
		assertSame(listener, exception.getListener());
		assertSame(failure, exception.getListenerException());
		assertEquals(callback.orderCallback, exception.getCallback());
		assertEquals(((ThrowingOrderListener) listener).failureTime, exception.getTime());
		assertSnapshot(exception.getOrderId(), exception.getClientId(), exception.getClientOrderId(),
				exception.getSecurity(), exception.getSide(), order);
		assertEquals(-1, exception.getExecutionId());
		assertEquals(-1, exception.getMatchId());
	}

	private void assertSnapshot(long orderId, long clientId, String clientOrderId, String security,
			Side side, Order order) {
		assertEquals(order.getId(), orderId);
		assertEquals(order.getClientId(), clientId);
		assertEquals(order.getClientOrderId().toString(), clientOrderId);
		assertEquals(order.getSecurity(), security);
		assertEquals(order.getSide(), side);
	}

	private void assertOrderBookStateAfterCallback(OrderBook book, Order order) {
		switch(callback) {
		case ACCEPTED:
		case RESTED:
		case REDUCED:
			assertSame(order, book.getOrder(order.getId()));
			assertFalse(order.isTerminal());
			break;
		case EXECUTED:
		case CANCELED:
		case REJECTED:
		case TERMINATED:
			assertTrue(order.isTerminal());
			break;
		}
	}

	private void assertOrderStateAfterCallback(Order order) {
		switch(callback) {
		case ACCEPTED:
			assertTrue(order.isAccepted());
			assertFalse(order.isTerminal());
			break;
		case RESTED:
			assertTrue(order.isResting());
			assertFalse(order.isTerminal());
			break;
		case REDUCED:
			assertEquals(60, order.getTotalSize());
			assertFalse(order.isTerminal());
			break;
		case EXECUTED:
			assertEquals(50, order.getExecutedSize());
			assertFalse(order.isTerminal());
			break;
		case CANCELED:
		case REJECTED:
		case TERMINATED:
			assertTrue(order.isTerminal());
			break;
		}
	}

	private static class ThrowingOrderBookListener extends OrderBookAdapter {

		private final String name;
		private final Callback callback;
		private final RuntimeException failure;
		private final List<String> events;
		private int failures;
		private long failureTime;
		private int reportCalls;
		private OrderBookListenerExceptions reported;

		private ThrowingOrderBookListener(String name, Callback callback, RuntimeException failure,
				List<String> events) {
			this.name = name;
			this.callback = callback;
			this.failure = failure;
			this.events = events;
		}

		private void fail(Callback currentCallback, long time) {
			if (callback != currentCallback || failures != 0) return;
			failures++;
			failureTime = time;
			events.add(name + "-" + currentCallback);
			throw failure;
		}

		@Override
		public void onOrderReduced(OrderBook orderBook, long time, Order order, long canceledSize,
				long reduceNewTotalSize) {
			fail(Callback.REDUCED, time);
		}

		@Override
		public void onOrderCanceled(OrderBook orderBook, long time, Order order, long canceledSize,
				CancelReason cancelReason) {
			fail(Callback.CANCELED, time);
		}

		@Override
		public void onOrderExecuted(OrderBook orderBook, long time, Order order, ExecuteSide executeSide,
				long executeSize, long executePrice, long executeId, long executeMatchId) {
			fail(Callback.EXECUTED, time);
		}

		@Override
		public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
			fail(Callback.ACCEPTED, time);
		}

		@Override
		public void onOrderRejected(OrderBook orderBook, long time, Order order, RejectReason rejectReason) {
			fail(Callback.REJECTED, time);
		}

		@Override
		public void onOrderRested(OrderBook orderBook, long time, Order order, long restSize, long restPrice) {
			fail(Callback.RESTED, time);
		}

		@Override
		public void onOrderTerminated(OrderBook orderBook, long time, Order order) {
			fail(Callback.TERMINATED, time);
		}

		@Override
		public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
			reportCalls++;
			reported = exceptions;
			events.add(name + "-report");
		}
	}

	private static class ThrowingOrderListener extends OrderListenerAdapter {

		private final String name;
		private final Callback callback;
		private final RuntimeException failure;
		private final List<String> events;
		private int reportCalls;
		private long failureTime;
		private OrderListenerExceptions reported;

		private ThrowingOrderListener(String name, Callback callback, RuntimeException failure,
				List<String> events) {
			this.name = name;
			this.callback = callback;
			this.failure = failure;
			this.events = events;
		}

		private void fail(Callback currentCallback, long time) {
			if (callback != currentCallback) return;
			failureTime = time;
			events.add(name + "-" + currentCallback);
			throw failure;
		}

		@Override
		public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize) {
			fail(Callback.REDUCED, time);
		}

		@Override
		public void onOrderCanceled(long time, Order order, long canceledSize, CancelReason cancelReason) {
			fail(Callback.CANCELED, time);
		}

		@Override
		public void onOrderExecuted(long time, Order order, ExecuteSide executeSide, long executeSize,
				long executePrice, long executeId, long executeMatchId) {
			fail(Callback.EXECUTED, time);
		}

		@Override
		public void onOrderAccepted(long time, Order order) {
			fail(Callback.ACCEPTED, time);
		}

		@Override
		public void onOrderRejected(long time, Order order, RejectReason rejectReason) {
			fail(Callback.REJECTED, time);
		}

		@Override
		public void onOrderRested(long time, Order order, long restSize, long restPrice) {
			fail(Callback.RESTED, time);
		}

		@Override
		public void onOrderTerminated(long time, Order order) {
			fail(Callback.TERMINATED, time);
		}

		@Override
		public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
			reportCalls++;
			reported = exceptions;
			events.add(name + "-report");
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
