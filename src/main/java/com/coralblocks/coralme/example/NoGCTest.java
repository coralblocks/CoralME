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
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.coralblocks.coralme.example;

import com.coralblocks.coralme.Order;
import com.coralblocks.coralme.Order.RejectReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;
import com.coralblocks.coralme.OrderBook;
import com.coralblocks.coralme.OrderBookAdapter;

/**
 * <p>Manual garbage-collection smoke test for a repeated, capacity-stable order book workload.</p> 
 * <p>Build with <code>mvn package</code>, then run:</p>
 * 
 * <p><code>java -Xlog:gc -Xms32m -Xmx64m -cp target/coralme-all.jar
 * com.coralblocks.coralme.example.NoGCTest false 1000000</code></p>
 * 
 * <p>The normal run must show no collection pauses. Pass <code>true</code> as the first argument
 * to enable a negative control that deliberately creates garbage and produces collection activity.</p>
 */
public class NoGCTest {
	
	private static final long CLIENT_ID = 1002L;
	private static final int PROGRESS_INTERVAL = 10_000;

	private static final boolean USE_BAD_SYSTEM_OUT_PRINT = false; // turn this on and you will see a lot of garbage from System.out.print
	private static final StringBuilder sb = new StringBuilder(1024);
	private static volatile Object garbageSink;
	private static long orderId = 1;

	private static class WorkloadListener extends OrderBookAdapter {
		private long rejectedOrders;

		@Override
		public void onOrderRejected(OrderBook orderBook, long time, Order order, RejectReason rejectReason) {
			rejectedOrders++;
		}
	}
	
	private static CharSequence getClientOrderId(long id) {
		sb.setLength(0);
		sb.append(id);
		return sb;
	}

	private static void createLimit(OrderBook book, Side side, long size, double price, TimeInForce tif) {
		long id = orderId++;
		book.createLimit(CLIENT_ID, getClientOrderId(id), id, side, size, price, tif);
	}

	private static void createMarket(OrderBook book, Side side, long size) {
		long id = orderId++;
		book.createMarket(CLIENT_ID, getClientOrderId(id), id, side, size);
	}
	
	private static void printWithoutGarbage(CharSequence cs) {
		int size = cs.length();
		for(int i = 0; i < size; i++) System.out.write(cs.charAt(i));
		System.out.flush();
	}
	
	private static void printIteration(int x) {
		
		sb.setLength(0);
		sb.append('\r').append(x); // does not produce garbage
		
		if (USE_BAD_SYSTEM_OUT_PRINT) {
			System.out.print(sb); // produces garbage!
		} else {
			printWithoutGarbage(sb);
		}
	}
	
	public static void main(String[] args) {
		
		boolean createGarbage = args.length >= 1 ? Boolean.parseBoolean(args[0]) : false;
		int iterations = args.length >= 2 ? Integer.parseInt(args[1]) : 1000000;
		if (iterations <= 0) throw new IllegalArgumentException("Iterations must be positive: " + iterations);
		
		WorkloadListener listener = new WorkloadListener();
		
		OrderBook book = new OrderBook("AAPL", listener) {
			@Override
			protected RejectReason validateOrder(Order order) {
				return order.getOriginalSize() % 100 == 0 ? null : RejectReason.BAD_LOT;
			}
		};
		
		for(int i = 1; i <= iterations; i++) {
			
			// Bids:
			createLimit(book, Side.BUY, 1000, 100.00, TimeInForce.DAY);
			createLimit(book, Side.BUY, 900, 99.00, TimeInForce.DAY);
			createLimit(book, Side.BUY, 800, 98.00, TimeInForce.DAY);
			createLimit(book, Side.BUY, 700, 97.00, TimeInForce.DAY);
			createLimit(book, Side.BUY, 500, 95.00, TimeInForce.DAY);
			
			// Asks:
			createLimit(book, Side.SELL, 500, 102.00, TimeInForce.DAY);
			createLimit(book, Side.SELL, 400, 104.00, TimeInForce.DAY);
			createLimit(book, Side.SELL, 800, 105.00, TimeInForce.DAY);
			createLimit(book, Side.SELL, 700, 108.00, TimeInForce.DAY);
			createLimit(book, Side.SELL, 500, 115.00, TimeInForce.DAY);
			
			// Hit top of book with IOCs:
			createLimit(book, Side.BUY, 600, 103.00, TimeInForce.IOC);
			createLimit(book, Side.SELL, 800, 96.00, TimeInForce.IOC);
			
			// Reduce and cancel top of book orders
			Order bidOrder = book.getBestBidOrder();
			Order askOrder = book.getBestAskOrder();
			
			if (createGarbage) {
				// create some garbage for the garbage collector
				sb.setLength(0);
				sb.append("someGarbage"); // appending a CharSequence does not produce garbage
				for(int x = 0; x < 10; x++) garbageSink = sb.toString(); // this produces garbage
			}
			
			bidOrder.reduceTo(900);
			askOrder.reduceTo(100);
			
			bidOrder.cancel();
			askOrder.cancel();
			
			// Reject orders that are not round lots
			createLimit(book, Side.BUY, 620, 103.00, TimeInForce.DAY);
			createLimit(book, Side.SELL, 940, 96.00, TimeInForce.DAY);
			
			// Add a couple of more orders in the middle of the book
			createLimit(book, Side.BUY, 600, 96.00, TimeInForce.DAY);
			createLimit(book, Side.SELL, 900, 111.00, TimeInForce.DAY);
			
			// Now use a market order to remove all liquidity from both sides
			createMarket(book, Side.BUY, 15000);
			createMarket(book, Side.SELL, 15000);
			
			// Book must now be empty
			if (!book.isEmpty()) throw new IllegalStateException("Book must be empty here!");

			if (i % PROGRESS_INTERVAL == 0 || i == iterations) printIteration(i);
		}

		long expectedRejectedOrders = iterations * 2L;
		if (listener.rejectedOrders != expectedRejectedOrders) {
			throw new IllegalStateException("Expected " + expectedRejectedOrders
					+ " rejected orders but found " + listener.rejectedOrders);
		}
		
		System.out.println(" ... DONE!");
	}
}
