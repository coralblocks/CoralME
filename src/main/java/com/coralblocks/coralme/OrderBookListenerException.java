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

import com.coralblocks.coralme.Order.Side;

/**
 * Describes an exception thrown by an external {@link OrderBookListener}.
 *
 * <p>The order information is copied when the exception is captured because
 * {@link Order} instances are pooled and may later be reused.</p>
 */
public final class OrderBookListenerException extends RuntimeException {

	public static enum Callback {
		ON_ORDER_REDUCED,
		ON_ORDER_CANCELED,
		ON_ORDER_EXECUTED,
		ON_ORDER_ACCEPTED,
		ON_ORDER_REJECTED,
		ON_ORDER_RESTED,
		ON_ORDER_TERMINATED
	}

	private final OrderBookListener listener;
	private final Callback callback;
	private final long time;
	private final long orderId;
	private final long clientId;
	private final String clientOrderId;
	private final String security;
	private final Side side;
	private final long executionId;
	private final long matchId;
	private final Exception listenerException;

	OrderBookListenerException(OrderBookListener listener, Callback callback, long time, Order order, Exception cause) {
		this(listener, callback, time, order, -1, -1, cause);
	}

	OrderBookListenerException(OrderBookListener listener, Callback callback, long time, Order order, long executionId, long matchId, Exception cause) {
		super(message(listener, callback, order), cause);
		this.listener = listener;
		this.callback = callback;
		this.time = time;
		this.orderId = order.getId();
		this.clientId = order.getClientId();
		this.clientOrderId = order.getClientOrderId().toString();
		this.security = order.getSecurity();
		this.side = order.getSide();
		this.executionId = executionId;
		this.matchId = matchId;
		this.listenerException = cause;
	}

	private static String message(OrderBookListener listener, Callback callback, Order order) {
		String listenerIdentity = listener.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(listener));
		return "OrderBookListener " + listenerIdentity + " threw from " + callback + " for orderId=" + order.getId();
	}

	public OrderBookListener getListener() {
		return listener;
	}

	public Callback getCallback() {
		return callback;
	}

	public long getTime() {
		return time;
	}

	public long getOrderId() {
		return orderId;
	}

	public long getClientId() {
		return clientId;
	}

	public String getClientOrderId() {
		return clientOrderId;
	}

	public String getSecurity() {
		return security;
	}

	public Side getSide() {
		return side;
	}

	/**
	 * Returns the execution identifier, or {@code -1} when the failing callback
	 * was not {@link Callback#ON_ORDER_EXECUTED}.
	 */
	public long getExecutionId() {
		return executionId;
	}

	/**
	 * Returns the match identifier, or {@code -1} when the failing callback was
	 * not {@link Callback#ON_ORDER_EXECUTED}.
	 */
	public long getMatchId() {
		return matchId;
	}

	public Exception getListenerException() {
		return listenerException;
	}
}
