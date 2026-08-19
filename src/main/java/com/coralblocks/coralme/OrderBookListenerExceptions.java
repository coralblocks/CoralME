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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Collects all exceptions thrown by external {@link OrderBookListener}s during
 * one complete {@link OrderBook} operation.
 */
public final class OrderBookListenerExceptions extends RuntimeException implements Iterable<OrderBookListenerException> {

	private final List<OrderBookListenerException> exceptions = new ArrayList<OrderBookListenerException>(4);
	private final List<OrderBookListenerException> readOnlyExceptions = Collections.unmodifiableList(exceptions);

	OrderBookListenerExceptions() {
		super("One or more OrderBookListener callbacks threw an exception");
	}

	void add(OrderBookListenerException exception) {
		exceptions.add(exception);
	}

	public int size() {
		return exceptions.size();
	}

	public boolean isEmpty() {
		return exceptions.isEmpty();
	}

	public OrderBookListenerException get(int index) {
		return exceptions.get(index);
	}

	public List<OrderBookListenerException> getExceptions() {
		return readOnlyExceptions;
	}

	@Override
	public Iterator<OrderBookListenerException> iterator() {
		return readOnlyExceptions.iterator();
	}
}
