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
 * Collects all exceptions thrown by external {@link OrderListener}s for one
 * {@link Order} during a complete {@link OrderBook} operation. The report is
 * delivered to every external listener registered on that order.
 */
public final class OrderListenerExceptions extends RuntimeException implements Iterable<OrderListenerException> {

	private final List<OrderListenerException> exceptions = new ArrayList<OrderListenerException>(4);
	private final List<OrderListenerException> readOnlyExceptions = Collections.unmodifiableList(exceptions);

	OrderListenerExceptions() {
		super("One or more OrderListener callbacks threw an exception");
	}

	void add(OrderListenerException exception) {
		exceptions.add(exception);
	}

	public int size() {
		return exceptions.size();
	}

	public boolean isEmpty() {
		return exceptions.isEmpty();
	}

	public OrderListenerException get(int index) {
		return exceptions.get(index);
	}

	public List<OrderListenerException> getExceptions() {
		return readOnlyExceptions;
	}

	@Override
	public Iterator<OrderListenerException> iterator() {
		return readOnlyExceptions.iterator();
	}
}
