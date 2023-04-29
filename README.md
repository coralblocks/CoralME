# CoralME
A simple, fast and garbage-free matching engine order book that you can use as a starting point for your matching engines.

## What is it?
CoralME is an order book data-structure that matches orders based on price-time priority. It maintains limit orders resting on an order book until they are either canceled or filled.

## Quick Start
Refer to [Example.java](https://github.com/coralblocks/CoralME/blob/main/src/main/java/com/coralblocks/coralme/example/Example.java).

## Features
- Fast
- Garbage-free
- Callback oriented
- Price levels
- Price improvement for fills
- MARKET and LIMIT order types
- IOC, GTC and DAY
- MAKER and TAKER execution side
- NORMAL, CROSSED, LOCKED, ONESIDED and EMPTY book states
- ClientOrderID and OrderID
- ExecutionID and ExecutionMatchID

## Callbacks Supported
```Java
public interface OrderBookListener {
    
    public void onOrderReduced(OrderBook orderBook, long time, Order order, long reduceNewTotalSize);
    
    public void onOrderCanceled(OrderBook orderBook, long time, Order order, CancelReason cancelReason);
    
    public void onOrderExecuted(OrderBook orderBook, long time, Order order, ExecuteSide executeSide, long executeSize, long executePrice, long executeId, long executeMatchId);
    
    public void onOrderAccepted(OrderBook orderBook, long time, Order order);
    
    public void onOrderRejected(OrderBook orderBook, long time, Order order, RejectReason rejectReason);
    
    public void onOrderRested(OrderBook orderBook, long time, Order order, long restSize, long restPrice);
    
}
```






