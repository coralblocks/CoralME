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

/**
 * Thrown when an external {@link OrderBookListener} or {@link OrderListener}
 * tries to mutate the same {@link OrderBook} while one of its callbacks is executing.
 */
public final class ReentrantOrderBookOperationException extends IllegalStateException {

	private final OrderBook orderBook;
	private final String operation;

	ReentrantOrderBookOperationException(OrderBook orderBook, String operation) {
		super("Cannot perform " + operation + " on OrderBook " + orderBook + " from an external listener callback");
		this.orderBook = orderBook;
		this.operation = operation;
	}

	public OrderBook getOrderBook() {
		return orderBook;
	}

	public String getOperation() {
		return operation;
	}
}
