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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.coralblocks.coralme.Order.ExecuteSide;
import com.coralblocks.coralme.Order.RejectReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;

public class DuplicateExchangeOrderIdTest {

	@Test
	public void test_DuplicateRestingOrderIsRejectedWithoutChangingBook() {
		int[] acceptances = new int[1];
		int[] rejections = new int[1];
		int[] rests = new int[1];
		OrderBook book = new OrderBook("AAPL", new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				acceptances[0]++;
			}

			@Override
			public void onOrderRejected(OrderBook orderBook, long time, Order order, RejectReason rejectReason) {
				rejections[0]++;
				assertSame(RejectReason.DUPLICATE_EXCHANGE_ORDER_ID, rejectReason);
			}

			@Override
			public void onOrderRested(OrderBook orderBook, long time, Order order, long restSize, long restPrice) {
				rests[0]++;
			}
		});
		Order original = book.createLimit(1, "original", 1, Side.BUY, 100, 100, TimeInForce.GTC);

		Order duplicate = book.createLimit(2, "duplicate", 1, Side.BUY, 200, 99, TimeInForce.GTC);

		assertTrue(duplicate.isTerminal());
		assertFalse(duplicate.isAccepted());
		assertSame(original, book.getOrder(1));
		assertEquals(1, book.getNumberOfOrders());
		assertEquals(1, book.getBidLevels());
		assertEquals(100, book.getBestBidSize());
		assertEquals(1, acceptances[0]);
		assertEquals(1, rejections[0]);
		assertEquals(1, rests[0]);

		book.purge();

		assertTrue(book.isEmpty());
		assertEquals(0, book.getBidLevels());
		assertTrue(original.isTerminal());
	}

	@Test
	public void test_FullyFilledTakerWithDuplicateIdIsRejectedBeforeMatching() {
		int[] acceptances = new int[1];
		int[] rejections = new int[1];
		int[] executions = new int[1];
		OrderBook book = new OrderBook("AAPL", new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				acceptances[0]++;
			}

			@Override
			public void onOrderRejected(OrderBook orderBook, long time, Order order, RejectReason rejectReason) {
				rejections[0]++;
				assertSame(RejectReason.DUPLICATE_EXCHANGE_ORDER_ID, rejectReason);
			}

			@Override
			public void onOrderExecuted(OrderBook orderBook, long time, Order order, ExecuteSide executeSide,
					long executeSize, long executePrice, long executeId, long executeMatchId) {
				executions[0]++;
			}
		});
		Order protectedOrder = book.createLimit(1, "protected", 2, Side.BUY, 100, 90, TimeInForce.GTC);
		Order maker = book.createLimit(2, "maker", 1, Side.SELL, 100, 100, TimeInForce.GTC);

		Order duplicateTaker = book.createMarket(3, "duplicate-taker", 2, Side.BUY, 100);

		assertTrue(duplicateTaker.isTerminal());
		assertFalse(duplicateTaker.isAccepted());
		assertSame(protectedOrder, book.getOrder(2));
		assertSame(maker, book.getOrder(1));
		assertEquals(2, book.getNumberOfOrders());
		assertEquals(0, maker.getExecutedSize());
		assertEquals(100, maker.getOpenSize());
		assertEquals(Long.MAX_VALUE, book.getLastExecutedPrice());
		assertEquals(2, acceptances[0]);
		assertEquals(1, rejections[0]);
		assertEquals(0, executions[0]);

		book.purge();

		assertTrue(book.isEmpty());
		assertEquals(0, book.getBidLevels());
		assertEquals(0, book.getAskLevels());
	}

	@Test
	public void test_ExchangeOrderIdCanBeReusedAfterOrderBecomesTerminal() {
		OrderBook book = new OrderBook("AAPL");
		Order original = book.createLimit(1, "original", 1, Side.BUY, 100, 100, TimeInForce.GTC);

		original.cancel();
		Order reusedId = book.createLimit(2, "reused", 1, Side.BUY, 200, 99, TimeInForce.GTC);

		assertTrue(reusedId.isAccepted());
		assertTrue(reusedId.isResting());
		assertSame(reusedId, book.getOrder(1));
		assertEquals(1, book.getNumberOfOrders());
	}

	@Test
	public void test_RollSkipsExchangeOrderIdsUsedByDestination() {
		int[] destinationRejections = new int[1];
		OrderBook source = new OrderBook("AAPL");
		OrderBook destination = new OrderBook("AAPL", new OrderBookAdapter() {
			@Override
			public void onOrderRejected(OrderBook orderBook, long time, Order order, RejectReason rejectReason) {
				destinationRejections[0]++;
			}
		});
		Order destinationOne = destination.createLimit(1, "destination-1", 1, Side.BUY, 100, 90, TimeInForce.GTC);
		Order destinationThree = destination.createLimit(1, "destination-3", 3, Side.BUY, 100, 89, TimeInForce.GTC);
		source.createLimit(2, "source-1", 10, Side.BUY, 200, 100, TimeInForce.GTC);
		source.createLimit(2, "source-2", 11, Side.BUY, 300, 99, TimeInForce.GTC);

		long nextExchangeOrderId = source.rollTo(destination);

		assertEquals(5, nextExchangeOrderId);
		assertTrue(source.isEmpty());
		assertSame(destinationOne, destination.getOrder(1));
		assertSame(destinationThree, destination.getOrder(3));
		assertEquals(200, destination.getOrder(2).getOpenSize());
		assertEquals(300, destination.getOrder(4).getOpenSize());
		assertEquals(4, destination.getNumberOfOrders());
		assertEquals(0, destinationRejections[0]);
		assertNull(destination.getOrder(5));
	}
}
