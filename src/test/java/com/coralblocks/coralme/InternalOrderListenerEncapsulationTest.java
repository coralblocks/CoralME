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

import static org.junit.Assert.assertFalse;

import java.lang.reflect.Method;

import org.junit.Test;

public class InternalOrderListenerEncapsulationTest {

	@Test
	public void test_InternalOrderListenersAreNotPublicApi() {
		assertInternalOrderListenerIsEncapsulated(OrderBook.class);
		assertInternalOrderListenerIsEncapsulated(PriceLevel.class);
	}

	private static void assertInternalOrderListenerIsEncapsulated(Class<?> type) {
		assertFalse(OrderListener.class.isAssignableFrom(type));

		for(Method method : type.getMethods()) {
			assertFalse(isOrderListenerCallback(method.getName()));
		}
	}

	private static boolean isOrderListenerCallback(String methodName) {
		return methodName.equals("onOrderReduced")
				|| methodName.equals("onOrderCanceled")
				|| methodName.equals("onOrderExecuted")
				|| methodName.equals("onOrderAccepted")
				|| methodName.equals("onOrderRejected")
				|| methodName.equals("onOrderRested")
				|| methodName.equals("onOrderTerminated")
				|| methodName.equals("onExceptionsThrown");
	}
}
