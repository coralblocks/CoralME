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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.coralblocks.coralme.ListenerSafetyTestSupport.Mutation;
import com.coralblocks.coralme.ListenerSafetyTestSupport.OrderListenerAdapter;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;

@RunWith(Parameterized.class)
public class ListenerExceptionReportReentrancyTest {

	@Parameters(name = "{0}")
	public static Collection<Object[]> parameters() {
		return Arrays.asList(Arrays.stream(Mutation.values())
				.map(mutation -> new Object[] { mutation })
				.toArray(Object[][]::new));
	}

	private final Mutation mutation;

	public ListenerExceptionReportReentrancyTest(Mutation mutation) {
		this.mutation = mutation;
	}

	@Test
	public void test_OrderBookListenerReportBlocksMutationWithoutRecursiveReport() {
		final RuntimeException originalFailure = new RuntimeException("original OrderBookListener failure");
		final int[] reports = new int[1];
		final Exception[] reportFailure = new Exception[1];
		final boolean[] mutationReturned = new boolean[1];
		final OrderBookListenerExceptions[] reported = new OrderBookListenerExceptions[1];
		final OrderBook book = new OrderBook("AAPL");
		final OrderBookListener[] listener = new OrderBookListener[1];

		listener[0] = new OrderBookAdapter() {
			@Override
			public void onOrderReduced(OrderBook orderBook, long time, Order order, long canceledSize,
					long reduceNewTotalSize) {
				throw originalFailure;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reports[0]++;
				reported[0] = exceptions;
				try {
					mutation.execute(orderBook, orderBook.getOrder(1), listener[0]);
					mutationReturned[0] = true;
				} catch(Exception e) {
					reportFailure[0] = e;
					throw e;
				}
			}
		};

		book.addListener(listener[0]);
		Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		order.reduceTo(60);

		assertEquals(1, reports[0]);
		assertFalse(mutationReturned[0]);
		assertEquals(1, reported[0].size());
		assertSame(originalFailure, reported[0].get(0).getListenerException());
		assertReentrantFailure(reportFailure[0], book);
		assertEquals(60, order.getTotalSize());

		book.addListener(new OrderBookAdapter());
	}

	@Test
	public void test_OrderListenerReportBlocksMutationWithoutRecursiveReport() {
		final RuntimeException originalFailure = new RuntimeException("original OrderListener failure");
		final int[] reports = new int[1];
		final Exception[] reportFailure = new Exception[1];
		final boolean[] mutationReturned = new boolean[1];
		final OrderListenerExceptions[] reported = new OrderListenerExceptions[1];
		final OrderBook book = new OrderBook("AAPL");
		final OrderBookListener registeredBookListener = new OrderBookAdapter();
		book.addListener(registeredBookListener);
		final Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);

		order.addListener(new OrderListenerAdapter() {
			@Override
			public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize) {
				throw originalFailure;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				reports[0]++;
				reported[0] = exceptions;
				try {
					mutation.execute(book, order, registeredBookListener);
					mutationReturned[0] = true;
				} catch(Exception e) {
					reportFailure[0] = e;
					throw e;
				}
			}
		});

		order.reduceTo(60);

		assertEquals(1, reports[0]);
		assertFalse(mutationReturned[0]);
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
		assertEquals(mutation.expectedOperation(), reentrantException.getOperation());
	}
}
