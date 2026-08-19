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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.Test;

import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;
import com.coralblocks.coralme.OrderBook.TraversalOrder;

public class OrderBookIteratorTest {

	@Test
	public void test_PriceTimePriorityAndReversePriorityForBothSides() {
		OrderBook book = new OrderBook("AAPL");
		book.createLimit(1, "buy-best-old", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		book.createLimit(2, "buy-best-new", 2, Side.BUY, 100, 100, TimeInForce.GTC);
		book.createLimit(3, "buy-worst", 3, Side.BUY, 100, 99, TimeInForce.GTC);
		book.createLimit(4, "sell-best-old", 4, Side.SELL, 100, 101, TimeInForce.GTC);
		book.createLimit(5, "sell-best-new", 5, Side.SELL, 100, 101, TimeInForce.GTC);
		book.createLimit(6, "sell-worst", 6, Side.SELL, 100, 102, TimeInForce.GTC);

		assertOrderIds(book.iterator(Side.BUY, TraversalOrder.PRICE_TIME_PRIORITY), 1, 2, 3);
		assertOrderIds(book.iterator(Side.BUY, TraversalOrder.REVERSE_PRICE_TIME_PRIORITY), 3, 2, 1);
		assertOrderIds(book.iterator(Side.SELL, TraversalOrder.PRICE_TIME_PRIORITY), 4, 5, 6);
		assertOrderIds(book.iterator(Side.SELL, TraversalOrder.REVERSE_PRICE_TIME_PRIORITY), 6, 5, 4);
		assertOrderIds(book.iterator(Side.BUY), 1, 2, 3);
	}

	@Test
	public void test_OneIteratorInstanceIsCachedForEachDirection() {
		OrderBook book = new OrderBook("AAPL");

		Iterator<Order> priority = book.iterator(Side.BUY, TraversalOrder.PRICE_TIME_PRIORITY);
		Iterator<Order> samePriority = book.iterator(Side.SELL, TraversalOrder.PRICE_TIME_PRIORITY);
		Iterator<Order> reverse = book.iterator(Side.BUY, TraversalOrder.REVERSE_PRICE_TIME_PRIORITY);
		Iterator<Order> sameReverse = book.iterator(Side.SELL, TraversalOrder.REVERSE_PRICE_TIME_PRIORITY);

		assertSame(priority, samePriority);
		assertSame(reverse, sameReverse);
		assertNotSame(priority, reverse);
	}

	@Test
	public void test_EmptyIteratorAndUnsupportedRemoveFollowIteratorContract() {
		OrderBook book = new OrderBook("AAPL");
		Iterator<Order> iterator = book.iterator(Side.BUY);

		assertFalse(iterator.hasNext());

		try {
			iterator.next();
			fail("Expected NoSuchElementException");
		} catch(NoSuchElementException expected) {
		}

		try {
			iterator.remove();
			fail("Expected UnsupportedOperationException");
		} catch(UnsupportedOperationException expected) {
		}
	}

	@Test
	public void test_CancelingEveryCurrentOrderContinuesInBothDirections() {
		for(TraversalOrder traversalOrder : TraversalOrder.values()) {
			OrderBook book = createBuyBook();
			List<Long> visited = new ArrayList<Long>();
			Iterator<Order> iterator = book.iterator(Side.BUY, traversalOrder);

			while(iterator.hasNext()) {
				Order order = iterator.next();
				visited.add(order.getId());
				order.cancel();
			}

			if (traversalOrder == TraversalOrder.PRICE_TIME_PRIORITY) {
				assertEquals(List.of(1L, 2L, 3L, 4L), visited);
			} else {
				assertEquals(List.of(4L, 3L, 2L, 1L), visited);
			}
			assertTrue(book.isEmpty());
		}
	}

	@Test
	public void test_TerminalReductionOfEveryCurrentOrderContinuesTraversal() {
		OrderBook book = createBuyBook();
		Iterator<Order> iterator = book.iterator(Side.BUY);
		int visited = 0;

		while(iterator.hasNext()) {
			Order order = iterator.next();
			visited++;
			order.reduceTo(0);
		}

		assertEquals(4, visited);
		assertTrue(book.isEmpty());
	}

	@Test
	public void test_PartialReductionOfCurrentAndOtherOrdersDoesNotInvalidateLinks() {
		OrderBook book = createBuyBook();
		Order otherOrder = book.getOrder(4);
		Iterator<Order> iterator = book.iterator(Side.BUY);

		Order first = iterator.next();
		first.reduceTo(80);
		otherOrder.reduceTo(70);

		assertOrderIds(iterator, 2, 3, 4);
		assertEquals(80, first.getTotalSize());
		assertEquals(70, otherOrder.getTotalSize());
	}

	@Test
	public void test_CancelingAFutureOrderSkipsItAndContinuesTraversal() {
		OrderBook book = new OrderBook("AAPL");
		book.createLimit(1, "first", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		book.createLimit(2, "second", 2, Side.BUY, 100, 100, TimeInForce.GTC);
		book.createLimit(3, "third", 3, Side.BUY, 100, 100, TimeInForce.GTC);
		book.createLimit(4, "worst", 4, Side.BUY, 100, 99, TimeInForce.GTC);
		Iterator<Order> iterator = book.iterator(Side.BUY);
		assertEquals(1, iterator.next().getId());

		book.getOrder(3).cancel();

		assertOrderIds(iterator, 2, 4);
	}

	@Test
	public void test_CancelingAnAlreadyVisitedOrderContinuesTraversal() {
		OrderBook book = createBuyBook();
		Iterator<Order> iterator = book.iterator(Side.BUY);
		Order first = iterator.next();
		assertEquals(2, iterator.next().getId());

		first.cancel();

		assertOrderIds(iterator, 3, 4);
	}

	@Test
	public void test_AddingAnOrderBeforeTheCursorDoesNotDamageTraversal() {
		OrderBook book = createBuyBook();
		Iterator<Order> iterator = book.iterator(Side.BUY);
		Order current = iterator.next();
		current.cancel();

		book.createLimit(5, "new", 5, Side.BUY, 100, 101, TimeInForce.GTC);

		assertOrderIds(iterator, 2, 3, 4);
		assertEquals(4, book.getNumberOfOrders());
	}

	@Test
	public void test_CancelingTheNextOrderAdvancesTheSavedPosition() {
		OrderBook book = createBuyBook();
		Iterator<Order> iterator = book.iterator(Side.BUY);
		assertEquals(1, iterator.next().getId());

		book.getOrder(2).cancel();

		assertOrderIds(iterator, 3, 4);
	}

	@Test
	public void test_CancelingTheNextOrderAdvancesTheSavedPositionInReverse() {
		OrderBook book = createBuyBook();
		Iterator<Order> iterator = book.iterator(Side.BUY, TraversalOrder.REVERSE_PRICE_TIME_PRIORITY);
		assertEquals(4, iterator.next().getId());

		book.getOrder(3).cancel();

		assertOrderIds(iterator, 2, 1);
	}

	@Test
	public void test_CancelingAFuturePriceLevelReconnectsTraversal() {
		OrderBook book = createBuyBook();
		Iterator<Order> iterator = book.iterator(Side.BUY);
		assertEquals(1, iterator.next().getId());

		book.getOrder(3).cancel();

		assertOrderIds(iterator, 2, 4);
	}

	@Test
	public void test_CancelingAndReusingTheNextOrderDoesNotDamageTraversal() {
		OrderBook book = createBuyBook();
		Iterator<Order> iterator = book.iterator(Side.BUY);
		assertEquals(1, iterator.next().getId());
		book.getOrder(2).cancel();

		Order transientOrder = book.createMarket(5, "transient", 5, Side.BUY, 100);

		assertTrue(transientOrder.isTerminal());
		assertOrderIds(iterator, 3, 4);
	}

	@Test
	public void test_AddingAnOrderAfterTheCursorCanBeVisited() {
		OrderBook book = createBuyBook();
		Iterator<Order> iterator = book.iterator(Side.BUY);
		assertEquals(1, iterator.next().getId());

		book.createLimit(5, "same-price-new", 5, Side.BUY, 100, 100, TimeInForce.GTC);

		assertOrderIds(iterator, 2, 5, 3, 4);
	}

	@Test
	public void test_ReusingCanceledCurrentOrderDoesNotDamageSavedNextPriceLevel() {
		OrderBook book = new OrderBook("AAPL");
		book.createLimit(1, "best", 1, Side.BUY, 100, 101, TimeInForce.GTC);
		book.createLimit(2, "next", 2, Side.BUY, 100, 100, TimeInForce.GTC);
		Iterator<Order> iterator = book.iterator(Side.BUY);
		Order current = iterator.next();
		current.cancel();

		// This transient order can reuse the canceled Order, but it does not change
		// the resting-order links followed by the iterator.
		Order transientOrder = book.createMarket(3, "transient", 3, Side.BUY, 100);

		assertTrue(transientOrder.isTerminal());
		assertOrderIds(iterator, 2);
		assertEquals(1, book.getNumberOfOrders());
	}

	@Test
	public void test_ResettingCachedIteratorStartsANewTraversalIncludingNewOrders() {
		OrderBook book = createBuyBook();
		Iterator<Order> iterator = book.iterator(Side.BUY);
		iterator.next();
		book.createLimit(5, "new", 5, Side.BUY, 100, 101, TimeInForce.GTC);
		assertOrderIds(iterator, 2, 3, 4);

		Iterator<Order> resetIterator = book.iterator(Side.BUY);

		assertSame(iterator, resetIterator);
		assertOrderIds(resetIterator, 5, 1, 2, 3, 4);
	}

	@Test
	public void test_MatchingAndRemovingTheCurrentOrderContinuesTraversal() {
		OrderBook book = new OrderBook("AAPL");
		book.createLimit(1, "best", 1, Side.SELL, 100, 100, TimeInForce.GTC);
		book.createLimit(2, "next", 2, Side.SELL, 100, 101, TimeInForce.GTC);
		Iterator<Order> iterator = book.iterator(Side.SELL);
		assertEquals(1, iterator.next().getId());

		Order taker = book.createMarket(3, "taker", 3, Side.BUY, 100);

		assertTrue(taker.isTerminal());
		assertNull(book.getOrder(1));
		assertOrderIds(iterator, 2);
	}

	@Test
	public void test_MatchingAndRemovingMultipleSavedOrdersEndsTraversalCleanly() {
		OrderBook book = new OrderBook("AAPL");
		book.createLimit(1, "best", 1, Side.SELL, 100, 100, TimeInForce.GTC);
		book.createLimit(2, "next", 2, Side.SELL, 100, 101, TimeInForce.GTC);
		Iterator<Order> iterator = book.iterator(Side.SELL);
		assertEquals(1, iterator.next().getId());

		Order taker = book.createMarket(3, "taker", 3, Side.BUY, 200);

		assertTrue(taker.isTerminal());
		assertFalse(iterator.hasNext());
		assertTrue(book.isEmpty());
	}

	@Test
	public void test_IteratorAcquisitionFromListenerCallbackIsBlockedAndReported() {
		OrderBookListenerExceptions[] reported = new OrderBookListenerExceptions[1];
		OrderBookAdapter listener = new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				orderBook.iterator(Side.BUY);
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reported[0] = exceptions;
			}
		};
		OrderBook book = new OrderBook("AAPL", listener);

		Order order = book.createLimit(1, "1", 1, Side.BUY, 100, 100, TimeInForce.GTC);

		assertSame(order, book.getOrder(1));
		assertEquals(1, reported[0].size());
		assertTrue(reported[0].get(0).getListenerException() instanceof ReentrantOrderBookOperationException);
		ReentrantOrderBookOperationException failure =
				(ReentrantOrderBookOperationException) reported[0].get(0).getListenerException();
		assertSame(book, failure.getOrderBook());
		assertEquals("iterator", failure.getOperation());
	}

	@Test
	public void test_PreviouslyAcquiredIteratorCannotAdvanceFromListenerCallback() {
		OrderBook book = createBuyBook();
		Iterator<Order> iterator = book.iterator(Side.BUY);
		OrderBookListenerExceptions[] reported = new OrderBookListenerExceptions[1];
		OrderBookAdapter listener = new OrderBookAdapter() {
			@Override
			public void onOrderReduced(OrderBook orderBook, long time, Order order, long canceledSize,
					long reduceNewTotalSize) {
				iterator.hasNext();
			}

			@Override
			public void onExceptionsThrown(OrderBook orderBook, OrderBookListenerExceptions exceptions) {
				reported[0] = exceptions;
			}
		};
		book.addListener(listener);

		book.getOrder(1).reduceTo(80);

		assertEquals(1, reported[0].size());
		ReentrantOrderBookOperationException failure =
				(ReentrantOrderBookOperationException) reported[0].get(0).getListenerException();
		assertEquals("Iterator.hasNext", failure.getOperation());
		assertOrderIds(iterator, 1, 2, 3, 4);
	}

	@Test
	public void test_RepeatedTraversalCancellationAndPoolReuse() {
		OrderBook book = new OrderBook("AAPL");

		for(int iteration = 0; iteration < 250; iteration++) {
			long firstId = iteration * 4L + 1;
			book.createLimit(firstId, "1", firstId, Side.BUY, 100, 100, TimeInForce.GTC);
			book.createLimit(firstId + 1, "2", firstId + 1, Side.BUY, 100, 100, TimeInForce.GTC);
			book.createLimit(firstId + 2, "3", firstId + 2, Side.BUY, 100, 99, TimeInForce.GTC);
			book.createLimit(firstId + 3, "4", firstId + 3, Side.BUY, 100, 98, TimeInForce.GTC);
			TraversalOrder traversalOrder = iteration % 2 == 0
					? TraversalOrder.PRICE_TIME_PRIORITY
					: TraversalOrder.REVERSE_PRICE_TIME_PRIORITY;
			Iterator<Order> iterator = book.iterator(Side.BUY, traversalOrder);
			int visited = 0;

			while(iterator.hasNext()) {
				iterator.next().cancel();
				visited++;
			}

			assertEquals(4, visited);
			assertTrue(book.isEmpty());
		}
	}

	private static OrderBook createBuyBook() {
		OrderBook book = new OrderBook("AAPL");
		book.createLimit(1, "best-old", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		book.createLimit(2, "best-new", 2, Side.BUY, 100, 100, TimeInForce.GTC);
		book.createLimit(3, "middle", 3, Side.BUY, 100, 99, TimeInForce.GTC);
		book.createLimit(4, "worst", 4, Side.BUY, 100, 98, TimeInForce.GTC);
		return book;
	}

	private static void assertOrderIds(Iterator<Order> iterator, long... expectedIds) {
		List<Long> actualIds = new ArrayList<Long>();
		while(iterator.hasNext()) actualIds.add(iterator.next().getId());

		List<Long> expected = new ArrayList<Long>();
		for(long expectedId : expectedIds) expected.add(expectedId);
		assertEquals(expected, actualIds);
	}

}
