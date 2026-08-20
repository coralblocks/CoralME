/*
 * Copyright 2015-2024 (c) CoralBlocks LLC - http://www.coralblocks.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

import java.lang.management.ManagementFactory;

import org.junit.Assume;
import org.junit.Test;

import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;

/**
 * Verifies that capacity-stable {@link OrderBook} operations allocate no heap memory after warmup.
 * Per-thread allocated bytes are checked instead of collection counts because a large heap can hide
 * garbage creation without triggering the garbage collector.
 */
public class OrderBookAllocationTest {

	private static final long CLIENT_ID = 1002L;
	private static final int WARMUP_ITERATIONS = 20_000;
	private static final int MEASURED_ITERATIONS = 100_000;

	private final StringBuilder clientOrderId = new StringBuilder(32);
	private final OrderBook book = new OrderBook("AAPL", new OrderBookAdapter());
	private long nextOrderId = 1;

	@Test
	public void testSteadyStateOperationsAllocateNoGarbage() {
		java.lang.management.ThreadMXBean standardBean = ManagementFactory.getThreadMXBean();
		Assume.assumeTrue(standardBean instanceof com.sun.management.ThreadMXBean);
		com.sun.management.ThreadMXBean allocationBean = (com.sun.management.ThreadMXBean) standardBean;
		Assume.assumeTrue(allocationBean.isThreadAllocatedMemorySupported());

		boolean allocationMeasurementWasEnabled = allocationBean.isThreadAllocatedMemoryEnabled();
		if (!allocationMeasurementWasEnabled) allocationBean.setThreadAllocatedMemoryEnabled(true);

		try {
			runIterations(WARMUP_ITERATIONS);
			assertTrue(book.isEmpty());

			long threadId = Thread.currentThread().getId();
			allocationBean.getThreadAllocatedBytes(threadId);
			long allocatedBytesBefore = allocationBean.getThreadAllocatedBytes(threadId);

			runIterations(MEASURED_ITERATIONS);

			long allocatedBytesAfter = allocationBean.getThreadAllocatedBytes(threadId);
			assertTrue(book.isEmpty());
			assertEquals(0, allocatedBytesAfter - allocatedBytesBefore);
		} finally {
			if (!allocationMeasurementWasEnabled) allocationBean.setThreadAllocatedMemoryEnabled(false);
		}
	}

	private void runIterations(int iterations) {
		for(int i = 0; i < iterations; i++) runIteration();
	}

	private void runIteration() {
		createLimit(Side.BUY, 1000, 100.00, TimeInForce.DAY);
		createLimit(Side.BUY, 900, 99.00, TimeInForce.DAY);
		createLimit(Side.BUY, 800, 98.00, TimeInForce.DAY);
		createLimit(Side.BUY, 700, 97.00, TimeInForce.DAY);
		createLimit(Side.BUY, 500, 95.00, TimeInForce.DAY);

		createLimit(Side.SELL, 500, 102.00, TimeInForce.DAY);
		createLimit(Side.SELL, 400, 104.00, TimeInForce.DAY);
		createLimit(Side.SELL, 800, 105.00, TimeInForce.DAY);
		createLimit(Side.SELL, 700, 108.00, TimeInForce.DAY);
		createLimit(Side.SELL, 500, 115.00, TimeInForce.DAY);

		createLimit(Side.BUY, 600, 103.00, TimeInForce.IOC);
		createLimit(Side.SELL, 900, 96.00, TimeInForce.IOC);

		Order bidOrder = book.getBestBidOrder();
		Order askOrder = book.getBestAskOrder();
		bidOrder.reduceTo(100);
		askOrder.reduceTo(100);
		bidOrder.cancel();
		askOrder.cancel();

		createLimit(Side.BUY, 620, 103.00, TimeInForce.DAY);
		createLimit(Side.SELL, 940, 96.00, TimeInForce.DAY);
		createLimit(Side.BUY, 600, 96.00, TimeInForce.DAY);
		createLimit(Side.SELL, 990, 111.00, TimeInForce.DAY);

		createMarket(Side.BUY, 15000);
		createMarket(Side.SELL, 15000);

		if (!book.isEmpty()) throw new IllegalStateException("Book must be empty after each iteration");
	}

	private void createLimit(Side side, long size, double price, TimeInForce tif) {
		long orderId = nextOrderId++;
		book.createLimit(CLIENT_ID, clientOrderId(orderId), orderId, side, size, price, tif);
	}

	private void createMarket(Side side, long size) {
		long orderId = nextOrderId++;
		book.createMarket(CLIENT_ID, clientOrderId(orderId), orderId, side, size);
	}

	private CharSequence clientOrderId(long orderId) {
		clientOrderId.setLength(0);
		clientOrderId.append(orderId);
		return clientOrderId;
	}
}
