/*
 * Copyright 2015-2026 (c) CoralBlocks LLC - http://www.coralblocks.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.coralblocks.coralme.bench;

import com.coralblocks.coralme.Order;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;
import com.coralblocks.coralme.OrderBook;
import com.coralblocks.coralme.util.Timestamper;

/**
 * Deterministic throughput benchmark for a capacity-stable order book workload.
 * The two arguments are the warmup and measured operation counts in millions.
 * Standard output contains only the measured total in nanoseconds.
 */
public class Bench {

	private static final long CLIENT_ID = 1L;
	private static final String CLIENT_ORDER_ID = "bench";
	private static final int OPERATIONS_PER_CYCLE = 8;
	private static final int CYCLES_PER_MILLION = 1_000_000 / OPERATIONS_PER_CYCLE;
	private static final int ORDERS_PER_CYCLE = 6;
	private static final long BID_PRICE_1 = 10_000_000_000L;
	private static final long BID_PRICE_2 = 9_900_000_000L;
	private static final long ASK_PRICE_1 = 10_200_000_000L;
	private static final long ASK_PRICE_2 = 10_300_000_000L;

	private static class BenchmarkTimestamper implements Timestamper {

		private long time;

		@Override
		public long nanoEpoch() {

			return ++time;
		}
	}

	private Bench() {

	}

	private static long run(OrderBook book, int millions, long nextOrderId) {

		long cycles = (long) millions * CYCLES_PER_MILLION;

		// Each eight-operation cycle starts and ends with an empty order book.
		for (long cycle = 0; cycle < cycles; cycle++) {
			Order bestBid = book.createLimit(CLIENT_ID, CLIENT_ORDER_ID, nextOrderId++, Side.BUY, 100, BID_PRICE_1,
					TimeInForce.DAY);
			book.createLimit(CLIENT_ID, CLIENT_ORDER_ID, nextOrderId++, Side.BUY, 100, BID_PRICE_2,
					TimeInForce.DAY);
			Order bestAsk = book.createLimit(CLIENT_ID, CLIENT_ORDER_ID, nextOrderId++, Side.SELL, 100, ASK_PRICE_1,
					TimeInForce.DAY);
			book.createLimit(CLIENT_ID, CLIENT_ORDER_ID, nextOrderId++, Side.SELL, 100, ASK_PRICE_2,
					TimeInForce.DAY);

			bestBid.reduceTo(50);
			bestAsk.cancel();

			book.createMarket(CLIENT_ID, CLIENT_ORDER_ID, nextOrderId++, Side.SELL, 150);
			book.createMarket(CLIENT_ID, CLIENT_ORDER_ID, nextOrderId++, Side.BUY, 100);
		}

		return nextOrderId;
	}

	public static void main(String[] args) {

		if (args.length != 2) {
			throw new IllegalArgumentException("Expected W and M operation counts in millions");
		}

		int warmupMillions = Integer.parseInt(args[0]);
		int measuredMillions = Integer.parseInt(args[1]);
		if (warmupMillions < 0) throw new IllegalArgumentException("W must not be negative");
		if (measuredMillions <= 0) throw new IllegalArgumentException("M must be positive");

		BenchmarkTimestamper timestamper = new BenchmarkTimestamper();
		OrderBook book = new OrderBook("BENCH", timestamper);
		long nextOrderId = run(book, warmupMillions, 1L);

		long start = System.nanoTime();
		nextOrderId = run(book, measuredMillions, nextOrderId);
		long elapsed = System.nanoTime() - start;

		long totalMillions = (long) warmupMillions + measuredMillions;
		long expectedNextOrderId = totalMillions * CYCLES_PER_MILLION * ORDERS_PER_CYCLE + 1L;
		if (nextOrderId != expectedNextOrderId || !book.isEmpty() || timestamper.time == 0) {
			throw new IllegalStateException("Benchmark workload did not complete correctly");
		}

		System.out.println(elapsed);
	}
}
