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
package com.coralblocks.coralme;

import com.coralblocks.coralme.Order.CancelReason;
import com.coralblocks.coralme.Order.ExecuteSide;
import com.coralblocks.coralme.Order.RejectReason;
import com.coralblocks.coralme.Order.Side;

public class PriceLevel implements OrderListener {
    
    private long price;
    
    private Side side;
    
    private String security;
    
    private long size;
    
    private int orders;
    
    private Order head = null;
    
    private Order tail = null;
    
    PriceLevel next = null;
    
    PriceLevel prev = null;
    
    PriceLevel() {
    	
    }
    
    void init(String security, Side side, long price) {
    	
    	this.security = security;
    	
    	this.price = price;
    	
    	this.side = side;
    	
    	this.size = 0;
    	
    	this.orders = 0;
    	
    	this.head = this.tail = null;
    	
    	this.next = this.prev = null;
    }
    
    public final long getPrice() {
    	
    	return price;
    }

    public final Side getSide() {
    	
    	return side;
    }
    
    public final String getSecurity() {
    	
    	return security;
    }
    
    public final int getOrders() {
    	
    	return orders;
    }
    
    public final boolean isEmpty() {
    	
    	return orders == 0;
    }
    
    public final Order head() {
    	
    	return head;
    }
    
    public final Order tail() {
    	
    	return tail;
    }
    
    void addOrder(Order order) {
        
        if (head == null) {
            
            head = tail = order;
            
            order.prev = order.next = null;
            
        } else {
            
            tail.next = order;
            
            order.prev = tail;
            
            tail = order;
            
            order.next = null;
        }
        
        orders++;
        
        order.addListener(this);
    }
    
    private void removeOrder(Order order) {
        
        if (order.prev != null) {
            
            order.prev.next = order.next;
        }
        
        if (order.next != null) {
            
            order.next.prev = order.prev;
        }
        
        if (tail == order) {
            
            tail = order.prev;
        }
        
        if (head == order) {
            
            head = order.next;
        }

        orders--;
        
        // no need to remove listener here because the order object clears its listeners when it is recycled
    }
    
    public final long getSize() {

    	return size;
    }

    @Override
    public void onOrderReduced(long time, Order order, long canceledSize, long newTotaSize) {

        size -= canceledSize;
    }

    @Override
    public void onOrderCanceled(long time, Order order, long canceledSize, CancelReason reason) {
    	
    	size -= canceledSize;

        removeOrder(order);
    }

    @Override
    public void onOrderExecuted(long time, Order order, ExecuteSide execSide, long sizeExecuted, long priceExecuted, long executionId, long matchId) {

    	size -= sizeExecuted;
    	
        if (order.isTerminal()) {
        	
        	removeOrder(order);
        	
        }
    }
    
	@Override
	public void onOrderAccepted(long time, Order order) {
		
		// NOOP
	}

	@Override
    public void onOrderRejected(long time, Order order, RejectReason reason) {
		
		// NOOP
    }

	@Override
    public void onOrderRested(long time, Order order, long restSize, long restPrice) {

		size += restSize;
    }

	@Override
	public void onOrderTerminated(long time, Order order) {
		
		// NOOP
	}
}
