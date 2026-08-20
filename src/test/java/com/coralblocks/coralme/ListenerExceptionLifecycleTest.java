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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.junit.Test;

import com.coralblocks.coralme.ListenerSafetyTestSupport.OrderListenerAdapter;
import com.coralblocks.coralme.Order.CancelReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;

public class ListenerExceptionLifecycleTest {

	@Test
	public void test_TerminalListenersAreClearedAndExceptionSnapshotsSurviveOrderReuse() {
		final RuntimeException failure = new RuntimeException("listener");
		final int[] orderCallbacks = new int[1];
		final int[] orderReports = new int[1];
		final int[] bookReports = new int[1];
		final OrderListenerExceptions[] orderReport = new OrderListenerExceptions[1];
		final OrderBookListenerExceptions[] bookReport = new OrderBookListenerExceptions[1];
		OrderBook book = new OrderBook("AAPL");
		book.addListener(new OrderBookAdapter() {
			private boolean thrown;

			@Override
			public void onOrderCanceled(OrderBook orderBook, long time, Order order, long canceledSize,
					CancelReason cancelReason) {
				if (!thrown) {
					thrown = true;
					throw failure;
				}
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				bookReports[0]++;
				bookReport[0] = exceptions;
			}
		});

		Order firstUse = book.createLimit(10, "first", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		firstUse.addListener(new OrderListenerAdapter() {
			@Override
			public void onOrderCanceled(long time, Order order, long canceledSize, CancelReason cancelReason) {
				orderCallbacks[0]++;
				throw failure;
			}

			@Override
			public void onOrderTerminated(long time, Order order) {
				orderCallbacks[0]++;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				orderReports[0]++;
				orderReport[0] = exceptions;
			}
		});

		firstUse.cancel();

		assertEquals(2, orderCallbacks[0]);
		assertEquals(1, orderReports[0]);
		assertEquals(1, bookReports[0]);
		assertEquals(1, orderReport[0].get(0).getOrderId());
		assertEquals(10, orderReport[0].get(0).getClientId());
		assertEquals("first", orderReport[0].get(0).getClientOrderId());
		assertEquals(1, bookReport[0].get(0).getOrderId());

		Order secondUse = book.createLimit(20, "second", 2, Side.BUY, 100, 99, TimeInForce.GTC);
		assertSame(firstUse, secondUse);
		secondUse.reduceTo(80);
		secondUse.cancel();

		assertEquals(2, orderCallbacks[0]);
		assertEquals(1, orderReports[0]);
		assertEquals(1, bookReports[0]);
		assertEquals(1, orderReport[0].get(0).getOrderId());
		assertEquals(10, orderReport[0].get(0).getClientId());
		assertEquals("first", orderReport[0].get(0).getClientOrderId());
		assertEquals(1, bookReport[0].get(0).getOrderId());
	}

	@Test
	public void test_TerminalListenersAreClearedWhenNoExceptionWasCollected() {
		final int[] callbacks = new int[1];
		final int[] reports = new int[1];
		OrderBook book = new OrderBook("AAPL");
		Order firstUse = book.createLimit(1, "first", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		firstUse.addListener(new OrderListenerAdapter() {
			@Override
			public void onOrderCanceled(long time, Order order, long canceledSize, CancelReason cancelReason) {
				callbacks[0]++;
			}

			@Override
			public void onOrderTerminated(long time, Order order) {
				callbacks[0]++;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				reports[0]++;
			}
		});

		firstUse.cancel();
		assertEquals(2, callbacks[0]);
		assertEquals(0, reports[0]);

		Order secondUse = book.createLimit(2, "second", 2, Side.BUY, 100, 99, TimeInForce.GTC);
		assertSame(firstUse, secondUse);
		secondUse.cancel();
		assertEquals(2, callbacks[0]);
		assertEquals(0, reports[0]);
	}

	@Test
	public void test_ExceptionContainersAreReadOnlyAndDoNotUseSuppressedExceptions() {
		final RuntimeException failure = new RuntimeException("listener");
		final OrderBookListenerExceptions[] bookReport = new OrderBookListenerExceptions[1];
		final OrderListenerExceptions[] orderReport = new OrderListenerExceptions[1];
		OrderBook book = new OrderBook("AAPL", new OrderBookAdapter() {
			@Override
			public void onOrderReduced(OrderBook orderBook, long time, Order order, long canceledSize,
					long reduceNewTotalSize, CancelReason cancelReason) {
				throw failure;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				bookReport[0] = exceptions;
			}
		});
		Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		order.addListener(new OrderListenerAdapter() {
			@Override
			public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize, CancelReason cancelReason) {
				throw failure;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				orderReport[0] = exceptions;
			}
		});

		order.reduceTo(60);

		assertEquals(0, bookReport[0].getSuppressed().length);
		assertEquals(0, orderReport[0].getSuppressed().length);
		assertEquals(0, bookReport[0].get(0).getSuppressed().length);
		assertEquals(0, orderReport[0].get(0).getSuppressed().length);
		try {
			bookReport[0].getExceptions().add(bookReport[0].get(0));
			fail("Expected read-only OrderBookListener exception list");
		} catch(UnsupportedOperationException expected) {
		}
		try {
			orderReport[0].getExceptions().clear();
			fail("Expected read-only OrderListener exception list");
		} catch(UnsupportedOperationException expected) {
		}
		Iterator<OrderListenerException> iterator = orderReport[0].iterator();
		iterator.next();
		try {
			iterator.remove();
			fail("Expected read-only OrderListener exception iterator");
		} catch(UnsupportedOperationException expected) {
		}
	}

	@Test
	public void test_EachOperationReceivesFreshContainersWithoutExceptionLeakage() {
		final RuntimeException failure = new RuntimeException("listener");
		final List<OrderBookListenerExceptions> bookReports = new ArrayList<OrderBookListenerExceptions>();
		final List<OrderListenerExceptions> orderReports = new ArrayList<OrderListenerExceptions>();
		OrderBook book = new OrderBook("AAPL", new OrderBookAdapter() {
			@Override
			public void onOrderReduced(OrderBook orderBook, long time, Order order, long canceledSize,
					long reduceNewTotalSize, CancelReason cancelReason) {
				throw failure;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				bookReports.add(exceptions);
			}
		});
		Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		order.addListener(new OrderListenerAdapter() {
			@Override
			public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize, CancelReason cancelReason) {
				throw failure;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				orderReports.add(exceptions);
			}
		});

		order.reduceTo(90);
		order.reduceTo(80);

		assertEquals(2, bookReports.size());
		assertEquals(2, orderReports.size());
		assertNotSame(bookReports.get(0), bookReports.get(1));
		assertNotSame(orderReports.get(0), orderReports.get(1));
		assertEquals(1, bookReports.get(0).size());
		assertEquals(1, bookReports.get(1).size());
		assertEquals(1, orderReports.get(0).size());
		assertEquals(1, orderReports.get(1).size());
	}
}
