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

import org.junit.Test;

import com.coralblocks.coralme.Order.RejectReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;

public class ClientOrderIdValidationTest {

	@Test
	public void test_ClientOrderIdLengthIsEnforcedWithoutGrowingPooledBuffer() {
		int[] acceptances = new int[1];
		int[] rejections = new int[1];
		int[] validations = new int[1];
		OrderBook book = new OrderBook("AAPL", new OrderBookAdapter() {
			@Override
			public void onOrderAccepted(OrderBook orderBook, long time, Order order) {
				acceptances[0]++;
			}

			@Override
			public void onOrderRejected(OrderBook orderBook, long time, Order order, RejectReason reason) {
				rejections[0]++;
				assertSame(RejectReason.BAD_CLIENT_ORDER_ID, reason);
				assertEquals(Order.CLIENT_ORDER_ID_MAX_LENGTH, order.getClientOrderId().length());
				assertEquals('x', order.getClientOrderId().charAt(Order.CLIENT_ORDER_ID_MAX_LENGTH - 1));
			}
		}) {
			@Override
			protected RejectReason validateOrder(Order order) {
				validations[0]++;
				return null;
			}
		};
		StringBuilder overlongClientOrderId = clientOrderId(Order.CLIENT_ORDER_ID_MAX_LENGTH + 1);
		overlongClientOrderId.setCharAt(Order.CLIENT_ORDER_ID_MAX_LENGTH, 'y');

		Order rejected = book.createLimit(1, overlongClientOrderId, 1, Side.BUY, 100, 100, TimeInForce.GTC);

		assertTrue(rejected.isTerminal());
		assertFalse(rejected.isAccepted());
		assertEquals(0, acceptances[0]);
		assertEquals(1, rejections[0]);
		assertEquals(0, validations[0]);
		assertTrue(book.isEmpty());

		StringBuilder maximumLengthClientOrderId = clientOrderId(Order.CLIENT_ORDER_ID_MAX_LENGTH);
		Order accepted = book.createLimit(2, maximumLengthClientOrderId, 2, Side.BUY, 100, 100, TimeInForce.GTC);

		assertSame(rejected, accepted);
		assertEquals(Order.CLIENT_ORDER_ID_MAX_LENGTH, accepted.getClientOrderId().length());
		assertEquals(1, acceptances[0]);
		assertEquals(1, rejections[0]);
		assertEquals(1, validations[0]);
		assertSame(accepted, book.getOrder(2));
	}

	private StringBuilder clientOrderId(int length) {
		StringBuilder clientOrderId = new StringBuilder(length);
		for(int i = 0; i < length; i++) clientOrderId.append('x');
		return clientOrderId;
	}
}
