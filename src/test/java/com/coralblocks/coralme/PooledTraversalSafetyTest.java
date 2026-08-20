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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;

public class PooledTraversalSafetyTest {

	@Test
	public void test_MatchSnapshotsLinksBeforeReleasingMakersAndPriceLevels() {
		OrderBook book = new OrderBook("AAPL");
		Order maker1 = book.createLimit(1, "maker-1", 1, Side.SELL, 100, 100, TimeInForce.GTC);
		Order maker2 = book.createLimit(2, "maker-2", 2, Side.SELL, 100, 100, TimeInForce.GTC);
		Order maker3 = book.createLimit(3, "maker-3", 3, Side.SELL, 100, 101, TimeInForce.GTC);

		Order taker = book.createLimit(4, "taker", 4, Side.BUY, 300, 101, TimeInForce.IOC);

		assertTrue(maker1.isTerminal());
		assertTrue(maker2.isTerminal());
		assertTrue(maker3.isTerminal());
		assertTrue(taker.isTerminal());
		assertEquals(0, book.getNumberOfOrders());
		assertEquals(0, book.getLevels(Side.SELL));
	}

	@Test
	public void test_RollSnapshotsLinksBeforeReleasingOrdersAndPriceLevels() {
		OrderBook source = new OrderBook("AAPL");
		OrderBook destination = new OrderBook("AAPL");
		Order source1 = source.createLimit(1, "source-1", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		Order source2 = source.createLimit(2, "source-2", 2, Side.BUY, 100, 100, TimeInForce.GTC);
		Order source3 = source.createLimit(3, "source-3", 3, Side.BUY, 100, 99, TimeInForce.GTC);
		Order source4 = source.createLimit(4, "source-4", 4, Side.SELL, 100, 101, TimeInForce.GTC);
		Order source5 = source.createLimit(5, "source-5", 5, Side.SELL, 100, 101, TimeInForce.GTC);
		Order source6 = source.createLimit(6, "source-6", 6, Side.SELL, 100, 102, TimeInForce.GTC);

		long nextExchangeOrderId = source.rollTo(destination, 10);

		assertTrue(source1.isTerminal());
		assertTrue(source2.isTerminal());
		assertTrue(source3.isTerminal());
		assertTrue(source4.isTerminal());
		assertTrue(source5.isTerminal());
		assertTrue(source6.isTerminal());
		assertEquals(0, source.getNumberOfOrders());
		assertEquals(0, source.getLevels(Side.BUY));
		assertEquals(0, source.getLevels(Side.SELL));
		assertEquals(6, destination.getNumberOfOrders());
		assertEquals(100, destination.getOrder(10).getPrice());
		assertEquals(100, destination.getOrder(11).getPrice());
		assertEquals(99, destination.getOrder(12).getPrice());
		assertEquals(101, destination.getOrder(13).getPrice());
		assertEquals(101, destination.getOrder(14).getPrice());
		assertEquals(102, destination.getOrder(15).getPrice());
		assertEquals(16, nextExchangeOrderId);
	}
}
