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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Iterator;

import org.junit.Test;

import com.coralblocks.coralme.Order.RejectReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;
import com.coralblocks.coralme.Order.Type;

public class OrderRejectionLifecycleTest {

	@Test
	public void test_RestingOrderCannotBeRejectedAndBookStateDoesNotChange() {
		int[] orderRejections = new int[1];
		int[] bookRejections = new int[1];
		int[] orderReports = new int[1];
		int[] bookReports = new int[1];
		OrderBook book = new OrderBook("AAPL", new OrderBookAdapter() {
			@Override
			public void onOrderRejected(OrderBook orderBook, long time, Order order, RejectReason reason) {
				bookRejections[0]++;
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				bookReports[0]++;
			}
		});
		Order order = book.createLimit(1, "resting", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		order.addListener(new ListenerSafetyTestSupport.OrderListenerAdapter() {
			@Override
			public void onOrderRejected(long time, Order order, RejectReason reason) {
				orderRejections[0]++;
			}

			@Override
			public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
				orderReports[0]++;
			}
		});
		PriceLevel priceLevel = order.getPriceLevel();

		assertRejectionProhibited(order);

		assertTrue(order.isAccepted());
		assertTrue(order.isResting());
		assertFalse(order.isTerminal());
		assertEquals(100, order.getTotalSize());
		assertEquals(-1, order.getRejectTime());
		assertSame(order, book.getOrder(1));
		assertEquals(1, book.getNumberOfOrders());
		assertEquals(1, priceLevel.getOrders());
		assertEquals(100, priceLevel.getSize());
		assertSame(order, priceLevel.head());
		assertSame(order, priceLevel.tail());
		assertEquals(0, orderRejections[0]);
		assertEquals(0, bookRejections[0]);
		assertEquals(0, orderReports[0]);
		assertEquals(0, bookReports[0]);
	}

	@Test
	public void test_RejectingSavedNextOrderCannotDamageIterator() {
		OrderBook book = new OrderBook("AAPL");
		book.createLimit(1, "first", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		Order protectedOrder = book.createLimit(2, "protected", 2, Side.BUY, 100, 100, TimeInForce.GTC);
		book.createLimit(3, "last", 3, Side.BUY, 100, 99, TimeInForce.GTC);
		Iterator<Order> iterator = book.iterator(Side.BUY);
		assertEquals(1, iterator.next().getId());

		assertRejectionProhibited(protectedOrder);

		assertSame(protectedOrder, book.getOrder(2));
		assertEquals(2, iterator.next().getId());
		assertEquals(3, iterator.next().getId());
		assertFalse(iterator.hasNext());
		assertEquals(3, book.getNumberOfOrders());
	}

	@Test
	public void test_AcceptedNonRestingOrderCannotBeRejected() {
		OrderBook book = new OrderBook("AAPL");
		Order order = new Order();
		order.init(book, book.getTimestamper(), 1, "accepted", 0, book.getSecurity(), Side.BUY, 100, 100,
				Type.LIMIT, TimeInForce.GTC);
		order.accept(1);

		assertRejectionProhibited(order);

		assertTrue(order.isAccepted());
		assertFalse(order.isResting());
		assertFalse(order.isTerminal());
		assertEquals(100, order.getTotalSize());
		assertEquals(-1, order.getRejectTime());
	}

	@Test
	public void test_OrderCannotBeRejectedTwice() {
		OrderBook book = new OrderBook("AAPL");
		Order order = new Order();
		order.init(book, book.getTimestamper(), 1, "rejected", 0, book.getSecurity(), Side.BUY, 100, 100,
				Type.LIMIT, TimeInForce.GTC);
		order.reject(RejectReason.BAD_TYPE);
		long firstRejectTime = order.getRejectTime();

		assertRejectionProhibited(order);

		assertTrue(order.isTerminal());
		assertEquals(firstRejectTime, order.getRejectTime());
	}

	@Test
	public void test_AcceptedTerminalOrderCannotBeRejectedAndReleasedToPoolTwice() {
		OrderBook book = new OrderBook("AAPL");
		Order terminalOrder = book.createMarket(1, "terminal", 1, Side.BUY, 100);

		assertRejectionProhibited(terminalOrder);
		Order firstRestingOrder = book.createLimit(2, "first", 2, Side.BUY, 100, 100, TimeInForce.GTC);
		Order secondRestingOrder = book.createLimit(3, "second", 3, Side.BUY, 100, 99, TimeInForce.GTC);

		assertNotSame(firstRestingOrder, secondRestingOrder);
		assertSame(firstRestingOrder, book.getOrder(2));
		assertSame(secondRestingOrder, book.getOrder(3));
		assertEquals(2, book.getNumberOfOrders());
	}

	@Test
	public void test_ValidationCanStillRejectOrderBeforeAcceptance() {
		int[] rejections = new int[1];
		OrderBook book = new OrderBook("AAPL", new OrderBookAdapter() {
			@Override
			public void onOrderRejected(OrderBook orderBook, long time, Order order, RejectReason reason) {
				rejections[0]++;
				assertEquals(RejectReason.BAD_TYPE, reason);
			}
		}) {
			@Override
			protected RejectReason validateOrder(Order order) {
				return RejectReason.BAD_TYPE;
			}
		};

		Order rejected = book.createLimit(1, "invalid", 1, Side.BUY, 100, 100, TimeInForce.GTC);

		assertTrue(rejected.isTerminal());
		assertFalse(rejected.isAccepted());
		assertTrue(rejected.getRejectTime() >= 0);
		assertEquals(1, rejections[0]);
		assertTrue(book.isEmpty());
	}

	@Test
	public void test_NullSideIsRejectedBeforeAcceptanceAndReturnedToPool() {
		int[] acceptances = new int[1];
		int[] rejections = new int[1];
		RejectReason[] reportedReason = new RejectReason[1];
		OrderBook book = new OrderBook("AAPL", new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				acceptances[0]++;
			}

			@Override
			public void onOrderRejected(OrderBook orderBook, long time, Order order, RejectReason reason) {
				rejections[0]++;
				reportedReason[0] = reason;
			}
		});

		Order rejected = book.createLimit(1, "invalid", 1, null, 100, 100, TimeInForce.GTC);

		assertTrue(rejected.isTerminal());
		assertFalse(rejected.isAccepted());
		assertEquals(0, acceptances[0]);
		assertEquals(1, rejections[0]);
		assertSame(RejectReason.BAD_SIDE, reportedReason[0]);
		assertTrue(book.isEmpty());

		Order reused = book.createLimit(2, "valid", 2, Side.BUY, 100, 100, TimeInForce.GTC);

		assertSame(rejected, reused);
		assertEquals(1, acceptances[0]);
		assertEquals(1, rejections[0]);
		assertSame(reused, book.getOrder(2));
	}

	@Test
	public void test_NonPositiveExchangeOrderIdsAreRejectedBeforeAcceptanceAndReturnedToPool() {
		int[] acceptances = new int[1];
		int[] rejections = new int[1];
		OrderBook book = new OrderBook("AAPL", new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				acceptances[0]++;
			}

			@Override
			public void onOrderRejected(OrderBook orderBook, long time, Order order, RejectReason reason) {
				rejections[0]++;
				assertSame(RejectReason.BAD_EXCHANGE_ORDER_ID, reason);
			}
		});
		Order previousRejected = null;
		long[] invalidExchangeOrderIds = { 0, -1 };

		for(long exchangeOrderId : invalidExchangeOrderIds) {
			Order rejected = book.createLimit(1, "invalid", exchangeOrderId, Side.BUY, 100, 100, TimeInForce.GTC);

			if (previousRejected != null) assertSame(previousRejected, rejected);
			assertTrue(rejected.isTerminal());
			assertFalse(rejected.isAccepted());
			assertNull(book.getOrder(exchangeOrderId));
			assertTrue(book.isEmpty());
			previousRejected = rejected;
		}

		assertEquals(0, acceptances[0]);
		assertEquals(2, rejections[0]);

		Order reused = book.createLimit(2, "valid", 1, Side.BUY, 100, 100, TimeInForce.GTC);

		assertSame(previousRejected, reused);
		assertEquals(1, acceptances[0]);
		assertEquals(2, rejections[0]);
		assertSame(reused, book.getOrder(1));
	}

	private static void assertRejectionProhibited(Order order) {
		try {
			order.reject(RejectReason.BAD_TYPE);
			fail("Expected IllegalStateException");
		} catch(IllegalStateException expected) {
		}
	}
}
