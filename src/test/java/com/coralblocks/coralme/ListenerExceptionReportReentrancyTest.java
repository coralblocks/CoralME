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

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.coralblocks.coralme.ListenerSafetyTestSupport.GuardedOperation;
import com.coralblocks.coralme.ListenerSafetyTestSupport.OrderListenerAdapter;
import com.coralblocks.coralme.Order.CancelReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;

@RunWith(Parameterized.class)
public class ListenerExceptionReportReentrancyTest {

	@Parameters(name = "{0}")
	public static Collection<Object[]> parameters() {
		return Arrays.asList(
				Arrays.stream(GuardedOperation.values()).map(operation -> new Object[] { operation }).toArray(
						Object[][]::new));
	}

	private final GuardedOperation operation;

	public ListenerExceptionReportReentrancyTest(GuardedOperation operation) {
		this.operation = operation;
	}

	@Test
	public void test_OrderBookListenerReportBlocksGuardedOperationWithoutRecursiveReport() {
		final RuntimeException originalFailure = new RuntimeException("original OrderBookListener failure");
		final int[] reports = new int[1];
		final Exception[] reportFailure = new Exception[1];
		final boolean[] operationReturned = new boolean[1];
		final OrderBookListenerExceptions[] reported = new OrderBookListenerExceptions[1];
		final OrderBook book = new OrderBook("AAPL");
		final Iterator<Order> iterator = book.iterator(Side.BUY);
		final OrderBookListener[] listener = new OrderBookListener[1];

		listener[0] = new OrderBookAdapter() {
			@Override
			public void onOrderReduced(OrderBook orderBook, long time, Order order, long canceledSize,
					long reduceNewTotalSize, CancelReason cancelReason) {
				throw originalFailure;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reports[0]++;
				reported[0] = exceptions;
				try {
					operation.execute(orderBook, orderBook.getOrder(1), listener[0], iterator);
					operationReturned[0] = true;
				} catch (Exception e) {
					reportFailure[0] = e;
					throw e;
				}
			}
		};

		book.addListener(listener[0]);
		Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		order.reduceTo(60);

		assertEquals(1, reports[0]);
		assertFalse(operationReturned[0]);
		assertEquals(1, reported[0].size());
		assertSame(originalFailure, reported[0].get(0).getListenerException());
		assertReentrantFailure(reportFailure[0], book);
		assertEquals(60, order.getTotalSize());

		book.addListener(new OrderBookAdapter());
	}

	@Test
	public void test_OrderListenerReportBlocksGuardedOperationWithoutRecursiveReport() {
		final RuntimeException originalFailure = new RuntimeException("original OrderListener failure");
		final int[] reports = new int[1];
		final Exception[] reportFailure = new Exception[1];
		final boolean[] operationReturned = new boolean[1];
		final OrderListenerExceptions[] reported = new OrderListenerExceptions[1];
		final OrderBook book = new OrderBook("AAPL");
		final OrderBookListener registeredBookListener = new OrderBookAdapter();
		book.addListener(registeredBookListener);
		final Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		final Iterator<Order> iterator = book.iterator(Side.BUY);

		order.addListener(new OrderListenerAdapter() {
			@Override
			public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize,
					CancelReason cancelReason) {
				throw originalFailure;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				reports[0]++;
				reported[0] = exceptions;
				try {
					operation.execute(book, order, registeredBookListener, iterator);
					operationReturned[0] = true;
				} catch (Exception e) {
					reportFailure[0] = e;
					throw e;
				}
			}
		});

		order.reduceTo(60);

		assertEquals(1, reports[0]);
		assertFalse(operationReturned[0]);
		assertEquals(1, reported[0].size());
		assertSame(originalFailure, reported[0].get(0).getListenerException());
		assertReentrantFailure(reportFailure[0], book);
		assertEquals(60, order.getTotalSize());

		book.addListener(new OrderBookAdapter());
	}

	private void assertReentrantFailure(Exception failure, OrderBook book) {
		assertTrue(failure instanceof ReentrantOrderBookOperationException);
		ReentrantOrderBookOperationException reentrantException = (ReentrantOrderBookOperationException) failure;
		assertSame(book, reentrantException.getOrderBook());
		assertEquals(operation.expectedOperation(), reentrantException.getOperation());
	}
}
