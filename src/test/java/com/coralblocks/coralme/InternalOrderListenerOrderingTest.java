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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.coralblocks.coralme.Order.CancelReason;
import com.coralblocks.coralme.Order.ExecuteSide;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;

public class InternalOrderListenerOrderingTest {

	@Test
	public void test_PriceLevelProcessesTerminalCancelBeforeOrderBookRemoval() {
		int[] cancellations = new int[1];
		OrderBook book = new OrderBook("AAPL", new OrderBookAdapter() {
			@Override
			public void onOrderCanceled(OrderBook orderBook, long time, Order order, long canceledSize,
					CancelReason cancelReason) {
				cancellations[0]++;
				assertEquals(0, orderBook.getBidLevels());
				assertNull(orderBook.head(Side.BUY));
				assertNull(orderBook.getOrder(order.getId()));
			}
		});
		Order order = book.createLimit(1, "order", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		PriceLevel priceLevel = order.getPriceLevel();

		order.cancel();

		assertEquals(1, cancellations[0]);
		assertTrue(priceLevel.isEmpty());
		assertEquals(0, priceLevel.getSize());
		assertTrue(book.isEmpty());
		assertEquals(0, book.getBidLevels());
	}

	@Test
	public void test_PriceLevelProcessesTerminalExecutionBeforeOrderBookRemoval() {
		int[] makerExecutions = new int[1];
		Order[] maker = new Order[1];
		OrderBook book = new OrderBook("AAPL", new OrderBookAdapter() {
			@Override
			public void onOrderExecuted(OrderBook orderBook, long time, Order order, ExecuteSide executeSide,
					long executeSize, long executePrice, long executeId, long executeMatchId) {
				if (order != maker[0]) return;
				makerExecutions[0]++;
				assertEquals(0, orderBook.getAskLevels());
				assertNull(orderBook.head(Side.SELL));
				assertNull(orderBook.getOrder(order.getId()));
			}
		});
		maker[0] = book.createLimit(1, "maker", 1, Side.SELL, 100, 100, TimeInForce.GTC);
		PriceLevel priceLevel = maker[0].getPriceLevel();

		book.createLimit(2, "taker", 2, Side.BUY, 100, 100, TimeInForce.IOC);

		assertEquals(1, makerExecutions[0]);
		assertTrue(priceLevel.isEmpty());
		assertEquals(0, priceLevel.getSize());
		assertTrue(book.isEmpty());
		assertEquals(0, book.getAskLevels());
	}
}
