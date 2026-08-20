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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.mockito.Mockito;

import com.coralblocks.coralme.Order.CancelReason;
import com.coralblocks.coralme.Order.RejectReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;

public class OrderValidationFailureTest {

	@Test
	public void test_LimitValidationFailurePropagatesAndReleasesOrder() {
		assertValidationFailurePropagatesAndReleasesOrder(false);
	}

	@Test
	public void test_MarketValidationFailurePropagatesAndReleasesOrder() {
		assertValidationFailurePropagatesAndReleasesOrder(true);
	}

	private static void assertValidationFailurePropagatesAndReleasesOrder(boolean market) {
		RuntimeException failure = new RuntimeException("validation failure");
		OrderListener discardedListener = Mockito.mock(OrderListener.class);
		Order[] failedOrder = new Order[1];
		int[] validations = new int[1];
		int[] acceptances = new int[1];
		int[] rejections = new int[1];
		int[] rests = new int[1];
		int[] cancellations = new int[1];
		OrderBook book = new OrderBook("AAPL", new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				acceptances[0]++;
			}

			@Override
			public void onOrderRejected(OrderBook orderBook, long time, Order order, RejectReason rejectReason) {
				rejections[0]++;
			}

			@Override
			public void onOrderRested(OrderBook orderBook, long time, Order order, long restSize, long restPrice) {
				rests[0]++;
			}

			@Override
			public void onOrderCanceled(OrderBook orderBook, long time, Order order, long canceledSize,
					CancelReason cancelReason) {
				cancellations[0]++;
			}
		}) {
			@Override
			protected RejectReason validateOrder(Order order) {
				if (validations[0]++ == 0) {
					failedOrder[0] = order;
					order.addListener(discardedListener);
					throw failure;
				}
				return null;
			}
		};

		RuntimeException propagated = assertThrows(RuntimeException.class, () -> {
			if (market) {
				book.createMarket(1, "failed", 1, Side.BUY, 100);
			} else {
				book.createLimit(1, "failed", 1, Side.BUY, 100, 100, TimeInForce.GTC);
			}
		});

		assertSame(failure, propagated);
		assertTrue(book.isEmpty());
		assertEquals(0, book.getBidLevels());
		assertEquals(0, acceptances[0]);
		assertEquals(0, rejections[0]);
		assertEquals(0, rests[0]);
		assertEquals(0, cancellations[0]);

		Order reused;
		if (market) {
			reused = book.createMarket(2, "valid", 2, Side.BUY, 100);
		} else {
			reused = book.createLimit(2, "valid", 2, Side.BUY, 100, 100, TimeInForce.GTC);
		}

		assertSame(failedOrder[0], reused);
		assertEquals(2, validations[0]);
		assertEquals(1, acceptances[0]);
		assertEquals(0, rejections[0]);
		assertEquals(market ? 0 : 1, rests[0]);
		assertEquals(market ? 1 : 0, cancellations[0]);
		Mockito.verifyNoInteractions(discardedListener);
	}
}
