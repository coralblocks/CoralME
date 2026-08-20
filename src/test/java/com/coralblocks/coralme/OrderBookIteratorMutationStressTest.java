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

import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;
import com.coralblocks.coralme.OrderBook.TraversalOrder;

public class OrderBookIteratorMutationStressTest {

	private static enum Removal {
		CANCEL {
			@Override
			void remove(Order order) {
				order.cancel();
			}
		},
		TERMINAL_REDUCTION {
			@Override
			void remove(Order order) {
				order.reduceTo(0);
			}
		};

		abstract void remove(Order order);
	}

	@Test
	public void test_ExhaustiveRemovalSubsetsAtEveryCursorPosition() {
		for(Side side : Side.values()) {
			for(TraversalOrder traversalOrder : TraversalOrder.values()) {
				for(Removal removal : Removal.values()) {
					OrderBook book = new OrderBook("AAPL");
					int[] traversalIds = traversalIds(traversalOrder);

					for(int consumed = 0; consumed <= traversalIds.length; consumed++) {
						for(int removalMask = 0; removalMask < 1 << traversalIds.length; removalMask++) {
							for(int removalDirection = 0; removalDirection < 2; removalDirection++) {
								populateEightOrders(book, side, TimeInForce.GTC);
								Iterator<Order> iterator = book.iterator(side, traversalOrder);
								String scenario = "side=" + side + ", traversal=" + traversalOrder
										+ ", removal=" + removal + ", consumed=" + consumed
										+ ", mask=" + removalMask + ", removalDirection=" + removalDirection;

								for(int i = 0; i < consumed; i++) {
									assertTrue(scenario, iterator.hasNext());
									assertEquals(scenario, traversalIds[i], iterator.next().getId());
								}

								for(int i = 0; i < traversalIds.length; i++) {
									int id = removalDirection == 0 ? i + 1 : traversalIds.length - i;
									if ((removalMask & 1 << (id - 1)) == 0) continue;
									removal.remove(book.getOrder(id));
								}

								for(int i = consumed; i < traversalIds.length; i++) {
									int expectedId = traversalIds[i];
									if ((removalMask & 1 << (expectedId - 1)) != 0) continue;
									assertTrue(scenario, iterator.hasNext());
									assertEquals(scenario, expectedId, iterator.next().getId());
								}

								assertFalse(scenario, iterator.hasNext());
								book.purge();
								assertTrue(scenario, book.isEmpty());
							}
						}
					}
				}
			}
		}
	}

	@Test
	public void test_RemovedNextOrderAndPriceLevelCanBeReusedBeforeTraversalResumes() {
		OrderBook book = new OrderBook("AAPL");
		book.createLimit(1, "best", 1, Side.BUY, 100, 101, TimeInForce.GTC);
		Order removedOrder = book.createLimit(2, "removed", 2, Side.BUY, 100, 100, TimeInForce.GTC);
		book.createLimit(3, "remaining", 3, Side.BUY, 100, 99, TimeInForce.GTC);
		PriceLevel removedPriceLevel = removedOrder.getPriceLevel();
		Iterator<Order> iterator = book.iterator(Side.BUY);
		assertEquals(1, iterator.next().getId());

		removedOrder.cancel();
		Order reusedOrder = book.createLimit(4, "new-best", 4, Side.BUY, 100, 102, TimeInForce.GTC);

		assertSame(removedOrder, reusedOrder);
		assertSame(removedPriceLevel, reusedOrder.getPriceLevel());
		assertRemainingIds(iterator, 3);
	}

	@Test
	public void test_ReusedNextOrderAndPriceLevelCanBeLinkedAfterTheCursor() {
		OrderBook book = new OrderBook("AAPL");
		book.createLimit(1, "best", 1, Side.BUY, 100, 101, TimeInForce.GTC);
		Order removedOrder = book.createLimit(2, "removed", 2, Side.BUY, 100, 100, TimeInForce.GTC);
		book.createLimit(3, "remaining", 3, Side.BUY, 100, 99, TimeInForce.GTC);
		PriceLevel removedPriceLevel = removedOrder.getPriceLevel();
		Iterator<Order> iterator = book.iterator(Side.BUY);
		assertEquals(1, iterator.next().getId());

		removedOrder.cancel();
		Order reusedOrder = book.createLimit(4, "new-worst", 4, Side.BUY, 100, 98, TimeInForce.GTC);

		assertSame(removedOrder, reusedOrder);
		assertSame(removedPriceLevel, reusedOrder.getPriceLevel());
		assertRemainingIds(iterator, 3, 4);
	}

	@Test
	public void test_RepeatedNextOrderRemovalAndImmediatePoolReuseInBothDirections() {
		for(TraversalOrder traversalOrder : TraversalOrder.values()) {
			OrderBook book = new OrderBook("AAPL");
			populateEightOrders(book, Side.BUY, TimeInForce.GTC);
			Iterator<Order> iterator = book.iterator(Side.BUY, traversalOrder);
			int[] ids = traversalIds(traversalOrder);
			assertEquals(ids[0], iterator.next().getId());

			for(int i = 1; i < ids.length - 1; i++) {
				Order removedOrder = book.getOrder(ids[i]);
				removedOrder.cancel();
				Order reusedOrder = book.createMarket(100 + ids[i], "transient", 100 + ids[i], Side.BUY, 1);
				assertSame(removedOrder, reusedOrder);
				assertTrue(reusedOrder.isTerminal());
			}

			assertRemainingIds(iterator, ids[ids.length - 1]);
			book.purge();
		}
	}

	@Test
	public void test_PurgeRepairsSimultaneousForwardAndReverseCursors() {
		OrderBook book = new OrderBook("AAPL");
		populateEightOrders(book, Side.BUY, TimeInForce.GTC);
		Iterator<Order> forward = book.iterator(Side.BUY, TraversalOrder.PRICE_TIME_PRIORITY);
		Iterator<Order> reverse = book.iterator(Side.BUY, TraversalOrder.REVERSE_PRICE_TIME_PRIORITY);
		assertEquals(1, forward.next().getId());
		assertEquals(8, reverse.next().getId());

		book.purge();

		assertFalse(forward.hasNext());
		assertFalse(reverse.hasNext());
		assertTrue(book.isEmpty());
	}

	@Test
	public void test_ExpireSkipsEveryRemovedDayOrderInBothDirections() {
		for(TraversalOrder traversalOrder : TraversalOrder.values()) {
			OrderBook book = createMixedTimeInForceBook();
			Iterator<Order> iterator = book.iterator(Side.BUY, traversalOrder);
			int[] ids = traversalIds(traversalOrder);
			assertEquals(ids[0], iterator.next().getId());

			book.expire();

			if (traversalOrder == TraversalOrder.PRICE_TIME_PRIORITY) {
				assertRemainingIds(iterator, 2, 4, 6, 8);
			} else {
				assertRemainingIds(iterator, 6, 4, 2);
			}
			assertEquals(4, book.getNumberOfOrders());
		}
	}

	@Test
	public void test_RollSkipsEveryRemovedGtcOrderInBothDirections() {
		for(TraversalOrder traversalOrder : TraversalOrder.values()) {
			OrderBook source = createMixedTimeInForceBook();
			OrderBook destination = new OrderBook("AAPL");
			Iterator<Order> iterator = source.iterator(Side.BUY, traversalOrder);
			int[] ids = traversalIds(traversalOrder);
			assertEquals(ids[0], iterator.next().getId());

			source.rollTo(destination);

			if (traversalOrder == TraversalOrder.PRICE_TIME_PRIORITY) {
				assertRemainingIds(iterator, 3, 5, 7);
			} else {
				assertRemainingIds(iterator, 7, 5, 3, 1);
			}
			assertEquals(4, source.getNumberOfOrders());
			assertEquals(4, destination.getNumberOfOrders());
		}
	}

	@Test
	public void test_MatchingAcrossPriceLevelsRepairsBothTraversalDirections() {
		for(TraversalOrder traversalOrder : TraversalOrder.values()) {
			OrderBook book = new OrderBook("AAPL");
			book.createLimit(1, "best-old", 1, Side.SELL, 100, 100, TimeInForce.GTC);
			book.createLimit(2, "best-new", 2, Side.SELL, 100, 100, TimeInForce.GTC);
			book.createLimit(3, "middle", 3, Side.SELL, 100, 101, TimeInForce.GTC);
			book.createLimit(4, "worst", 4, Side.SELL, 100, 102, TimeInForce.GTC);
			Iterator<Order> iterator = book.iterator(Side.SELL, traversalOrder);
			long firstExpected = traversalOrder == TraversalOrder.PRICE_TIME_PRIORITY ? 1 : 4;
			assertEquals(firstExpected, iterator.next().getId());

			book.createMarket(5, "taker", 5, Side.BUY, 250);

			if (traversalOrder == TraversalOrder.PRICE_TIME_PRIORITY) {
				assertRemainingIds(iterator, 3, 4);
			} else {
				assertRemainingIds(iterator, 3);
			}
			assertEquals(50, book.getOrder(3).getOpenSize());
			assertEquals(2, book.getNumberOfOrders());
		}
	}

	@Test
	public void test_ForwardAndReverseCursorsAreRepairedIndependently() {
		OrderBook book = new OrderBook("AAPL");
		populateEightOrders(book, Side.BUY, TimeInForce.GTC);
		Iterator<Order> forward = book.iterator(Side.BUY, TraversalOrder.PRICE_TIME_PRIORITY);
		Iterator<Order> reverse = book.iterator(Side.BUY, TraversalOrder.REVERSE_PRICE_TIME_PRIORITY);
		assertEquals(1, forward.next().getId());
		assertEquals(8, reverse.next().getId());

		book.getOrder(2).cancel();
		book.getOrder(7).reduceTo(0);
		book.getOrder(3).cancel();
		book.getOrder(6).reduceTo(0);

		assertRemainingIds(forward, 4, 5, 8);
		assertRemainingIds(reverse, 5, 4, 1);
	}

	@Test
	public void test_AddedSuccessorIsPreservedWhenTheSavedNextOrderIsRemoved() {
		OrderBook book = new OrderBook("AAPL");
		book.createLimit(1, "first", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		book.createLimit(2, "saved-next", 2, Side.BUY, 100, 100, TimeInForce.GTC);
		book.createLimit(3, "worse", 3, Side.BUY, 100, 99, TimeInForce.GTC);
		Iterator<Order> iterator = book.iterator(Side.BUY);
		assertEquals(1, iterator.next().getId());

		book.createLimit(4, "new-successor", 4, Side.BUY, 100, 100, TimeInForce.GTC);
		book.getOrder(2).cancel();

		assertRemainingIds(iterator, 4, 3);
	}

	@Test
	public void test_DeterministicRandomMutationStressNeverReturnsStaleOrDuplicateOrders() {
		for(Side side : Side.values()) {
			for(TraversalOrder traversalOrder : TraversalOrder.values()) {
				for(int seed = 0; seed < 100; seed++) {
					runRandomMutationScenario(side, traversalOrder, seed);
				}
			}
		}
	}

	private static void runRandomMutationScenario(Side side, TraversalOrder traversalOrder, int seed) {
		OrderBook book = new OrderBook("AAPL");
		Random random = new Random(seed * 31L + side.index() * 7L + traversalOrder.ordinal());
		long nextId = 1;
		for(int i = 0; i < 32; i++) {
			long price = 90 + random.nextInt(21);
			TimeInForce timeInForce = random.nextBoolean() ? TimeInForce.GTC : TimeInForce.DAY;
			book.createLimit(nextId, "random-resting", nextId, side, 25 + random.nextInt(176), price,
					timeInForce);
			nextId++;
		}

		Iterator<Order> iterator = book.iterator(side, traversalOrder);
		Set<Long> returnedIds = new HashSet<Long>();
		long lastPrice = 0;
		long lastId = 0;
		boolean returnedAny = false;

		for(int step = 0; step < 300; step++) {
			switch(random.nextInt(7)) {
			case 0:
				removeRandomOrder(book, nextId, random, false);
				break;
			case 1:
				removeRandomOrder(book, nextId, random, true);
				break;
			case 2:
				partiallyReduceRandomOrder(book, nextId, random);
				break;
			case 3:
				long price = 90 + random.nextInt(21);
				TimeInForce timeInForce = random.nextBoolean() ? TimeInForce.GTC : TimeInForce.DAY;
				book.createLimit(nextId, "random-added", nextId, side, 25 + random.nextInt(176), price,
						timeInForce);
				nextId++;
				break;
			case 4:
				Side takerSide = side == Side.BUY ? Side.SELL : Side.BUY;
				book.createMarket(nextId, "random-taker", nextId, takerSide, 1 + random.nextInt(400));
				nextId++;
				break;
			case 5:
				book.expire();
				break;
			case 6:
				break;
			}

			if (step % 2 != 0 || !iterator.hasNext()) continue;
			Order returned = iterator.next();
			String scenario = "side=" + side + ", traversal=" + traversalOrder + ", seed=" + seed
					+ ", step=" + step + ", returnedId=" + returned.getId();
			assertSame(scenario, returned, book.getOrder(returned.getId()));
			assertTrue(scenario, returned.isResting());
			assertTrue(scenario, returnedIds.add(returned.getId()));
			if (returnedAny) assertFollowsTraversalOrder(scenario, side, traversalOrder,
					lastPrice, lastId, returned.getPrice(), returned.getId());
			lastPrice = returned.getPrice();
			lastId = returned.getId();
			returnedAny = true;
		}

		while(iterator.hasNext()) {
			Order returned = iterator.next();
			String scenario = "side=" + side + ", traversal=" + traversalOrder + ", seed=" + seed
					+ ", drainId=" + returned.getId();
			assertSame(scenario, returned, book.getOrder(returned.getId()));
			assertTrue(scenario, returned.isResting());
			assertTrue(scenario, returnedIds.add(returned.getId()));
			if (returnedAny) assertFollowsTraversalOrder(scenario, side, traversalOrder,
					lastPrice, lastId, returned.getPrice(), returned.getId());
			lastPrice = returned.getPrice();
			lastId = returned.getId();
			returnedAny = true;
		}

		book.purge();
		assertTrue(book.isEmpty());
	}

	private static void removeRandomOrder(OrderBook book, long nextId, Random random, boolean reduce) {
		Order order = findRandomOrder(book, nextId, random);
		if (order == null) return;
		if (reduce) {
			order.reduceTo(0);
		} else {
			order.cancel();
		}
	}

	private static void partiallyReduceRandomOrder(OrderBook book, long nextId, Random random) {
		Order order = findRandomOrder(book, nextId, random);
		if (order == null || order.getOpenSize() <= 1) return;
		order.reduceTo(order.getExecutedSize() + Math.max(1, order.getOpenSize() / 2));
	}

	private static Order findRandomOrder(OrderBook book, long nextId, Random random) {
		for(int attempt = 0; attempt < 16; attempt++) {
			Order order = book.getOrder(1 + random.nextInt((int) nextId - 1));
			if (order != null) return order;
		}
		return null;
	}

	private static void assertFollowsTraversalOrder(String scenario, Side side, TraversalOrder traversalOrder,
			long previousPrice, long previousId, long price, long id) {
		if (price == previousPrice) {
			if (traversalOrder == TraversalOrder.PRICE_TIME_PRIORITY) {
				assertTrue(scenario, id > previousId);
			} else {
				assertTrue(scenario, id < previousId);
			}
			return;
		}

		boolean pricesIncrease = side == Side.SELL;
		if (traversalOrder == TraversalOrder.REVERSE_PRICE_TIME_PRIORITY) pricesIncrease = !pricesIncrease;
		assertTrue(scenario, pricesIncrease ? price > previousPrice : price < previousPrice);
	}

	private static OrderBook createMixedTimeInForceBook() {
		OrderBook book = new OrderBook("AAPL");
		for(int id = 1; id <= 8; id++) {
			TimeInForce timeInForce = id % 2 == 0 ? TimeInForce.GTC : TimeInForce.DAY;
			long price = 105 - (id + 1) / 2;
			book.createLimit(id, "mixed", id, Side.BUY, 100, price, timeInForce);
		}
		return book;
	}

	private static void populateEightOrders(OrderBook book, Side side, TimeInForce timeInForce) {
		long[] prices = side == Side.BUY
				? new long[] { 103, 103, 103, 102, 101, 101, 100, 100 }
				: new long[] { 100, 100, 100, 101, 102, 102, 103, 103 };
		for(int i = 0; i < prices.length; i++) {
			long id = i + 1;
			book.createLimit(id, "stress", id, side, 100, prices[i], timeInForce);
		}
	}

	private static int[] traversalIds(TraversalOrder traversalOrder) {
		return traversalOrder == TraversalOrder.PRICE_TIME_PRIORITY
				? new int[] { 1, 2, 3, 4, 5, 6, 7, 8 }
				: new int[] { 8, 7, 6, 5, 4, 3, 2, 1 };
	}

	private static void assertRemainingIds(Iterator<Order> iterator, long... expectedIds) {
		for(long expectedId : expectedIds) {
			assertTrue("Missing order " + expectedId, iterator.hasNext());
			assertEquals(expectedId, iterator.next().getId());
		}
		assertFalse(iterator.hasNext());
	}
}
