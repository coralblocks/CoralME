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

import com.coralblocks.coralme.Order.CancelReason;
import com.coralblocks.coralme.Order.ExecuteSide;
import com.coralblocks.coralme.Order.RejectReason;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;

final class ListenerSafetyTestSupport {

	static enum Mutation {
		CREATE_LIMIT("createLimit") {
			@Override
			void execute(OrderBook book, Order order, OrderBookListener registeredBookListener) {
				book.createLimit(90, "reentrant-limit", 90, Side.BUY, 10, 1, TimeInForce.GTC);
			}
		},
		CREATE_MARKET("createMarket") {
			@Override
			void execute(OrderBook book, Order order, OrderBookListener registeredBookListener) {
				book.createMarket(91, "reentrant-market", 91, Side.BUY, 10);
			}
		},
		CANCEL_ORDER("Order.cancel") {
			@Override
			void execute(OrderBook book, Order order, OrderBookListener registeredBookListener) {
				order.cancel();
			}
		},
		REDUCE_ORDER("Order.reduceTo") {
			@Override
			void execute(OrderBook book, Order order, OrderBookListener registeredBookListener) {
				order.reduceTo(order.getTotalSize() - 1);
			}
		},
		REJECT_ORDER("Order.reject") {
			@Override
			void execute(OrderBook book, Order order, OrderBookListener registeredBookListener) {
				order.reject(RejectReason.BAD_TYPE);
			}
		},
		ADD_ORDER_BOOK_LISTENER("addListener") {
			@Override
			void execute(OrderBook book, Order order, OrderBookListener registeredBookListener) {
				book.addListener(new OrderBookAdapter());
			}
		},
		REMOVE_ORDER_BOOK_LISTENER("removeListener") {
			@Override
			void execute(OrderBook book, Order order, OrderBookListener registeredBookListener) {
				book.removeListener(registeredBookListener);
			}
		},
		ADD_ORDER_LISTENER("Order.addListener") {
			@Override
			void execute(OrderBook book, Order order, OrderBookListener registeredBookListener) {
				order.addListener(new OrderListenerAdapter());
			}
		},
		PURGE("purge") {
			@Override
			void execute(OrderBook book, Order order, OrderBookListener registeredBookListener) {
				book.purge();
			}
		},
		EXPIRE("expire") {
			@Override
			void execute(OrderBook book, Order order, OrderBookListener registeredBookListener) {
				book.expire();
			}
		},
		ROLL_TO("rollTo") {
			@Override
			void execute(OrderBook book, Order order, OrderBookListener registeredBookListener) {
				book.rollTo(new OrderBook("OTHER"));
			}
		};

		private final String expectedOperation;

		private Mutation(String expectedOperation) {
			this.expectedOperation = expectedOperation;
		}

		final String expectedOperation() {
			return expectedOperation;
		}

		abstract void execute(OrderBook book, Order order, OrderBookListener registeredBookListener);
	}

	static class OrderListenerAdapter implements OrderListener {

		@Override
		public void onOrderReduced(long time, Order order, long canceledSize, long reduceNewTotalSize) {
		}

		@Override
		public void onOrderCanceled(long time, Order order, long canceledSize, CancelReason cancelReason) {
		}

		@Override
		public void onOrderExecuted(long time, Order order, ExecuteSide executeSide, long executeSize,
				long executePrice, long executeId, long executeMatchId) {
		}

		@Override
		public void onOrderAccepted(long time, Order order) {
		}

		@Override
		public void onOrderRejected(long time, Order order, RejectReason rejectReason) {
		}

		@Override
		public void onOrderRested(long time, Order order, long restSize, long restPrice) {
		}

		@Override
		public void onOrderTerminated(long time, Order order) {
		}

		@Override
		public void onExceptionsThrown(Order order, OrderListenerExceptions exceptions) {
		}
	}

	private ListenerSafetyTestSupport() {
	}
}
