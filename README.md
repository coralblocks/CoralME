# CoralME
A simple, fast and garbage-free matching engine order book that you can use as a starting point for your matching engines.

## What is it?
CoralME is an order book data-structure that matches orders based on price-time priority. It maintains limit orders resting on an order book until they are either canceled or filled.

## Quick Start
Refer to [Example.java](https://github.com/coralblocks/CoralME/blob/main/src/main/java/com/coralblocks/coralme/example/Example.java) for a bunch of order matching use-cases.

The [OrderBookTest.java](https://github.com/coralblocks/CoralME/blob/main/src/test/java/com/coralblocks/coralme/OrderBookTest.java) might give you some good ideas as well but I find the [Example.java](https://github.com/coralblocks/CoralME/blob/main/src/main/java/com/coralblocks/coralme/example/Example.java) easier to follow.

## Features
- Fast
- Garbage-free
- Callback oriented
- Price levels
- Price improvement for fills
- MARKET and LIMIT order types
- IOC, GTC and DAY
- MAKER (of liquidity) and TAKER (of liquidity) execution sides
- NORMAL, CROSSED, LOCKED, ONESIDED and EMPTY book states
- ClientOrderID and OrderID
- ExecutionID and ExecutionMatchID

## Callbacks Supported
```Java
public interface OrderBookListener {
    
    public void onOrderReduced(OrderBook orderBook, long time, Order order, 
                                 long reduceNewTotalSize);
    
    public void onOrderCanceled(OrderBook orderBook, long time, Order order, 
                                  CancelReason cancelReason);
    
    public void onOrderExecuted(OrderBook orderBook, long time, Order order, 
                                  ExecuteSide executeSide, long executeSize, 
                                  long executePrice, long executeId, long executeMatchId);
    
    public void onOrderAccepted(OrderBook orderBook, long time, Order order);
    
    public void onOrderRejected(OrderBook orderBook, long time, Order order, 
                                  RejectReason rejectReason);
    
    public void onOrderRested(OrderBook orderBook, long time, Order order,
                                long restSize, long restPrice);
    
}
```
## Code Snippet
```Java

    // This OrderBookListener will print all callbacks to System.out
    OrderBookLogger orderBookLogger = new OrderBookLogger();

    OrderBook orderBook = new OrderBook("AAPL", orderBookLogger);

    orderBookLogger.off(); // omit callbacks logging for clarity

    orderBook.createLimit("1", 1, Side.BUY, 200, 150.44, TimeInForce.DAY);
    orderBook.createLimit("2", 2, Side.BUY, 500, 149.44, TimeInForce.DAY);
    orderBook.createLimit("3", 3, Side.BUY, 100, 149.44, TimeInForce.GTC);
    orderBook.createLimit("4", 4, Side.BUY, 100, 148.14, TimeInForce.DAY);
    orderBook.createLimit("5", 5, Side.SELL, 300, 153.24, TimeInForce.GTC);
    orderBook.createLimit("6", 6, Side.SELL, 500, 156.43, TimeInForce.DAY);
    orderBook.createLimit("7", 7, Side.SELL, 1500, 158.54, TimeInForce.DAY);

    orderBook.showLevels();

    /*
       100 @    148.14 (orders=1)
       600 @    149.44 (orders=2)
       200 @    150.44 (orders=1)
    --------      2.80
       300 @    153.24 (orders=1)
       500 @    156.43 (orders=1)
      1500 @    158.54 (orders=1)		
    */

    orderBook.showOrders();

    /*
       100 @    148.14 (id=4)
       500 @    149.44 (id=2)
       100 @    149.44 (id=3)
       200 @    150.44 (id=1)
    --------      2.80
       300 @    153.24 (id=5)
       500 @    156.43 (id=6)
      1500 @    158.54 (id=7)
    */
    
    Order order = orderBook.getOrder(7);
    System.out.println(order);
    
    /*
    Order [id=7, clientOrderId=7, side=SELL, security=AAPL, originalSize=1500, 
            openSize=1500, executedSize=0, canceledSize=0, price=158.54, type=LIMIT, tif=DAY]
    */
    
    order.cancel(orderBook.getTimestamper().nanoEpoch());
    System.out.println(order);
    
    /*
    Order [id=7, clientOrderId=7, side=SELL, security=AAPL, originalSize=1500,
            openSize=0, executedSize=0, canceledSize=1500, price=158.54, type=LIMIT, tif=DAY]
    */
    
    orderBook.showOrders();
    
    /*
       100 @    148.14 (id=4)
       500 @    149.44 (id=2)
       100 @    149.44 (id=3)
       200 @    150.44 (id=1)
    --------      2.80
       300 @    153.24 (id=5)
       500 @    156.43 (id=6)
    */
    
    orderBookLogger.on(); // turn logging back on so we can see the callbacks
	    
    orderBook.createLimit("8", 8, Side.BUY, 1500, 155.00, TimeInForce.DAY);
    
    /*
        -----> onOrderAccepted called:
          orderBook=AAPL
          time=1682765163100000000
          order=Order [id=8, clientOrderId=8, side=BUY, security=AAPL, originalSize=1500, openSize=1500, executedSize=0, canceledSize=0, price=155.0, type=LIMIT, tif=DAY]

        -----> onOrderExecuted called:
          orderBook=AAPL
          time=1682765163100000000
          order=Order [id=5, clientOrderId=5, side=SELL, security=AAPL, originalSize=300, openSize=0, executedSize=300, canceledSize=0, price=153.24, type=LIMIT, tif=GTC]
          executeSide=MAKER
          executeSize=300
          executePrice=153.24
          executeId=1
          executeMatchId=1

        -----> onOrderExecuted called:
          orderBook=AAPL
          time=1682765163100000000
          order=Order [id=8, clientOrderId=8, side=BUY, security=AAPL, originalSize=1500, openSize=1200, executedSize=300, canceledSize=0, price=155.0, type=LIMIT, tif=DAY]
          executeSide=TAKER
          executeSize=300
          executePrice=153.24
          executeId=2
          executeMatchId=1

        -----> onOrderRested called:
          orderBook=AAPL
          time=1682765163101000000
          order=Order [id=8, clientOrderId=8, side=BUY, security=AAPL, originalSize=1500, openSize=1200, executedSize=300, canceledSize=0, price=155.0, type=LIMIT, tif=DAY]
          restSize=1200
          restPrice=155.0    
    */
	    
    orderBook.showOrders();
    
    /*
       100 @    148.14 (id=4)
       500 @    149.44 (id=2)
       100 @    149.44 (id=3)
       200 @    150.44 (id=1)
      1200 @    155.00 (id=8)       <==== Your order sat here after hitting some asks...  
    --------      1.43
       500 @    156.43 (id=6)    
    */
    
 ```
 

 
 






