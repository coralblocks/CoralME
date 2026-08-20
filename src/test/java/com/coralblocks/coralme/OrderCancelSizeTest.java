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
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.mockito.Mockito;

import com.coralblocks.coralme.Order.CancelReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;

public class OrderCancelSizeTest {

	@Test
	public void test_NonPositiveCancelSizeThrowsWithoutChangingOrder() {
		OrderBookListener orderBookListener = Mockito.mock(OrderBookListener.class);
		OrderListener orderListener = Mockito.mock(OrderListener.class);
		OrderBook book = new OrderBook("AAPL", orderBookListener);
		Order first = book.createLimit(1, "first", 1, Side.BUY, 100, 100, TimeInForce.GTC);
		Order second = book.createLimit(2, "second", 2, Side.BUY, 100, 100, TimeInForce.GTC);
		PriceLevel priceLevel = first.getPriceLevel();
		first.addListener(orderListener);
		Mockito.clearInvocations(orderBookListener);

		IllegalArgumentException negativeFailure = assertThrows(IllegalArgumentException.class,
				() -> first.cancel(-100));
		IllegalArgumentException zeroFailure = assertThrows(IllegalArgumentException.class,
				() -> first.cancel(0, CancelReason.PRICE));

		assertEquals("sizeToCancel must be positive: -100", negativeFailure.getMessage());
		assertEquals("sizeToCancel must be positive: 0", zeroFailure.getMessage());
		assertTrue(first.isAccepted());
		assertTrue(first.isResting());
		assertFalse(first.isTerminal());
		assertEquals(100, first.getOriginalSize());
		assertEquals(100, first.getTotalSize());
		assertEquals(100, first.getOpenSize());
		assertEquals(0, first.getExecutedSize());
		assertEquals(0, first.getCanceledSize());
		assertEquals(-1, first.getReduceTime());
		assertEquals(-1, first.getCancelTime());
		assertEquals(200, priceLevel.getSize());
		assertEquals(2, priceLevel.getOrders());
		assertSame(first, priceLevel.head());
		assertSame(second, priceLevel.tail());
		assertSame(first, book.getOrder(1));
		assertEquals(2, book.getNumberOfOrders());
		Mockito.verifyNoInteractions(orderBookListener, orderListener);
	}

	@Test
	public void test_NegativeReduceToPerformsFullCancel() {
		int[] reductions = new int[1];
		int[] cancellations = new int[1];
		OrderBook book = new OrderBook("AAPL", new OrderBookAdapter() {
			@Override
			public void onOrderReduced(OrderBook orderBook, long time, Order order, long canceledSize,
					long reduceNewTotalSize, CancelReason cancelReason) {
				reductions[0]++;
			}

			@Override
			public void onOrderCanceled(OrderBook orderBook, long time, Order order, long canceledSize,
					CancelReason cancelReason) {
				cancellations[0]++;
				assertEquals(100, canceledSize);
				assertSame(CancelReason.USER, cancelReason);
			}
		});
		Order order = book.createLimit(1, "order", 1, Side.BUY, 100, 100, TimeInForce.GTC);

		order.reduceTo(-1);

		assertTrue(order.isTerminal());
		assertFalse(order.isResting());
		assertTrue(book.isEmpty());
		assertEquals(0, book.getBidLevels());
		assertEquals(0, reductions[0]);
		assertEquals(1, cancellations[0]);
	}
}
