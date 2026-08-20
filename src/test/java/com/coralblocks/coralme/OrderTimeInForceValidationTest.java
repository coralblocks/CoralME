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

import com.coralblocks.coralme.Order.RejectReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;

public class OrderTimeInForceValidationTest {

	@Test
	public void test_NullLimitTimeInForceIsRejectedBeforeAcceptanceAndCustomValidation() {
		int[] acceptances = new int[1];
		int[] rejections = new int[1];
		int[] rests = new int[1];
		int[] validations = new int[1];
		RejectReason[] reportedReason = new RejectReason[1];
		OrderBook book = new OrderBook("AAPL", new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				acceptances[0]++;
			}

			@Override
			public void onOrderRejected(OrderBook orderBook, long time, Order order, RejectReason rejectReason) {
				rejections[0]++;
				reportedReason[0] = rejectReason;
			}

			@Override
			public void onOrderRested(OrderBook orderBook, long time, Order order, long restSize, long restPrice) {
				rests[0]++;
			}
		}) {
			@Override
			protected RejectReason validateOrder(Order order) {
				validations[0]++;
				return null;
			}
		};

		Order rejected = book.createLimit(1, "invalid", 1, Side.BUY, 100, 100, null);

		assertTrue(rejected.isTerminal());
		assertFalse(rejected.isAccepted());
		assertSame(RejectReason.BAD_TIF, reportedReason[0]);
		assertEquals(0, acceptances[0]);
		assertEquals(1, rejections[0]);
		assertEquals(0, rests[0]);
		assertEquals(0, validations[0]);
		assertTrue(book.isEmpty());
		assertEquals(0, book.getBidLevels());

		Order reused = book.createLimit(2, "valid", 2, Side.BUY, 100, 100, TimeInForce.GTC);

		assertSame(rejected, reused);
		assertTrue(reused.isAccepted());
		assertTrue(reused.isResting());
		assertSame(reused, book.getOrder(2));
		assertEquals(1, acceptances[0]);
		assertEquals(1, rejections[0]);
		assertEquals(1, rests[0]);
		assertEquals(1, validations[0]);
	}

	@Test
	public void test_MarketOrderStillUsesNullTimeInForce() {
		int[] acceptances = new int[1];
		int[] rejections = new int[1];
		OrderBook book = new OrderBook("AAPL", new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				acceptances[0]++;
			}

			@Override
			public void onOrderRejected(OrderBook orderBook, long time, Order order, RejectReason rejectReason) {
				rejections[0]++;
			}
		});

		Order market = book.createMarket(1, "market", 1, Side.BUY, 100);

		assertTrue(market.isAccepted());
		assertTrue(market.isTerminal());
		assertTrue(market.isMarket());
		assertNull(market.getTimeInForce());
		assertEquals(1, acceptances[0]);
		assertEquals(0, rejections[0]);
		assertTrue(book.isEmpty());
	}
}
