# CoralME
A simple, fast and garbage-free matching engine order book that you can use as a starting point for your matching engines.

<pre>
<b>Note:</b> For a detailed discussion of how a <b>first-class electronic exchange</b> can be built
from the ground up using the <a href="https://www.coralblocks.com/index.php/state-of-the-art-distributed-systems-with-coralmq/">sequencer architecture</a> you should refer to <a href="https://www.coralblocks.com/index.php/building-a-first-class-exchange-architecture-with-coralsequencer/">this article</a>.
</pre>

## What is it?
CoralME is an order book data-structure that matches orders based on price-time priority. It maintains limit orders resting in an order book until they are either canceled or filled. Whenever an order changes its state, a callback is issued to registered listeners.

## What people usually mean by the term _Matching Engine_?
Usually when people talk about a _Matching Engine_, what they are really referring to is the full solution for an electronic exchange. That would include gateways, drop copies, market data, balances, reports, monitors, margins, compliance, fees, etc. Plus the _messaging middleware_ to tie all these pieces together. In that context, **the matching engine is really just one of the many parts of an electronic exchange**. It is an important part, the central nervous systems of an exchange, which maintains orders resting inside order books, and matches them when liquidity takers meet liquidity providers (i.e. market makers).

<!-- For a detailed discussion of how a **first-class electronic exchange** can be built from the ground up using the sequencer architecture you should refer to [this article](https://www.coralblocks.com/index.php/building-a-first-class-exchange-architecture-with-coralsequencer/). -->


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
- ClientID, ClientOrderID and OrderID
- ExecutionID and ExecutionMatchID
- Can optionally check and disallow trade to self
- Supports cancelation of open size as well as [reduction of total size](https://chatgpt.com/c/6808fb7a-c6b0-8013-89d2-6de1c2d190dc) (executed + open)

## How can I check that it is zero garbage?
Check [NoGCTest.java](https://github.com/coralblocks/CoralME/blob/main/src/main/java/com/coralblocks/coralme/example/NoGCTest.java) to see that it creates a book and populates this book with 10 orders _one million times_. And on each of these one million times it does a bunch of executions, rejects, cancelations, reduces, etc. Run this test with `-verbose:gc -Xms128m -Xmx256m` and you will always see **zero GC activity**. _No matter how many iterations you perform, the gc activity is always zero_. If you want to see some GC activity, you can turn on a flag that forces the creation of garbage by [producing some strings](https://github.com/coralblocks/CoralME/blob/bb9461313537987db43339e429b7314e58bbb784/src/main/java/com/coralblocks/coralme/example/NoGCTest.java#L103) in the middle of the loop.

##### Creating ZERO garbage
```
$ ./bin/runGCTest.sh
java -verbose:gc -Xms128m -Xmx256m -cp target/classes com.coralblocks.coralme.example.NoGCTest false 1000000
1000000 ... DONE!
```
##### Forcing the creation of garbage (pass true to runGCTest.sh)
```
$ ./bin/runGCTest.sh true
java -verbose:gc -Xms128m -Xmx256m -cp target/classes com.coralblocks.coralme.example.NoGCTest true 1000000
60870[GC (Allocation Failure)  33280K->1224K(125952K), 0.0005392 secs]
146061[GC (Allocation Failure)  34504K->1200K(125952K), 0.0005032 secs]
231254[GC (Allocation Failure)  34480K->1240K(125952K), 0.0003991 secs]
316449[GC (Allocation Failure)  34520K->1208K(125952K), 0.0004686 secs]
401642[GC (Allocation Failure)  34488K->1264K(125952K), 0.0004315 secs]
486832[GC (Allocation Failure)  34544K->1200K(129536K), 0.0004712 secs]
590373[GC (Allocation Failure)  41648K->1128K(129536K), 0.0005286 secs]
693917[GC (Allocation Failure)  41576K->1128K(128512K), 0.0002270 secs]
794836[GC (Allocation Failure)  40552K->1128K(129024K), 0.0002390 secs]
895759[GC (Allocation Failure)  40552K->1128K(129024K), 0.0002202 secs]
996679[GC (Allocation Failure)  40552K->1128K(129024K), 0.0002492 secs]
1000000 ... DONE!
```

## Callbacks Supported
```Java
public interface OrderBookListener {
    
    public void onOrderReduced(OrderBook orderBook, long time, Order order, 
                                 long canceledSize, long reduceNewTotalSize);
    
    public void onOrderCanceled(OrderBook orderBook, long time, Order order, 
                                  long canceledSize, CancelReason cancelReason);
    
    public void onOrderExecuted(OrderBook orderBook, long time, Order order, 
                                  ExecuteSide executeSide, long executeSize, 
                                  long executePrice, long executeId, long executeMatchId);
    
    public void onOrderAccepted(OrderBook orderBook, long time, Order order);
    
    public void onOrderRejected(OrderBook orderBook, long time, Order order, 
                                  RejectReason rejectReason);
    
    public void onOrderRested(OrderBook orderBook, long time, Order order,
                                long restSize, long restPrice);

    public void onOrderTerminated(OrderBook orderBook, long time, Order order);
    
}
```

## Code Snippet
```Java

final long CLIENT_ID = 1001L;

long orderId = 0;

// This OrderBookListener will print all callbacks to System.out
OrderBookLogger orderBookLogger = new OrderBookLogger();

OrderBook orderBook = new OrderBook("AAPL", orderBookLogger);

orderBook.createLimit(CLIENT_ID, String.valueOf(++orderId), orderId, Side.BUY, 200, 150.44, TimeInForce.DAY);

/*
	-----> onOrderAccepted called:
	  orderBook=AAPL
	  time=1700598048045000000
	  order=Order [id=1, clientId=1001, clientOrderId=1, side=BUY, security=AAPL, originalSize=200, openSize=200, 
					executedSize=0, canceledSize=0, price=150.44, type=LIMIT, tif=DAY]
	
	-----> onOrderRested called:
	  orderBook=AAPL
	  time=1700598048047000000
	  order=Order [id=1, clientId=1001, clientOrderId=1, side=BUY, security=AAPL, originalSize=200, openSize=200, 
					executedSize=0, canceledSize=0, price=150.44, type=LIMIT, tif=DAY]
	  restSize=200
	  restPrice=150.44
*/

orderBook.showLevels();

/*
   200 @    150.44 (orders=1)
-------- 
*/

orderBook.showOrders();

/*
   200 @    150.44 (id=1)
-------- 			  
*/

orderBook.createLimit(CLIENT_ID, String.valueOf(++orderId), orderId, Side.BUY, 500, 149.44, TimeInForce.DAY);

/* 
	-----> onOrderAccepted called:
	  orderBook=AAPL
	  time=1700598048049000000
	  order=Order [id=2, clientId=1001, clientOrderId=2, side=BUY, security=AAPL, originalSize=500, openSize=500, 
					executedSize=0, canceledSize=0, price=149.44, type=LIMIT, tif=DAY]
	
	-----> onOrderRested called:
	  orderBook=AAPL
	  time=1700598048050000000
	  order=Order [id=2, clientId=1001, clientOrderId=2, side=BUY, security=AAPL, originalSize=500, openSize=500, 
					executedSize=0, canceledSize=0, price=149.44, type=LIMIT, tif=DAY]
	  restSize=500
	  restPrice=149.44	
*/

orderBookLogger.off(); // omit callbacks output for clarity

orderBook.createLimit(CLIENT_ID, String.valueOf(++orderId), orderId, Side.BUY, 100, 149.44, TimeInForce.GTC);
orderBook.createLimit(CLIENT_ID, String.valueOf(++orderId), orderId, Side.BUY, 100, 148.14, TimeInForce.DAY);

orderBook.createLimit(CLIENT_ID, String.valueOf(++orderId), orderId, Side.SELL, 300, 153.24, TimeInForce.GTC);
orderBook.createLimit(CLIENT_ID, String.valueOf(++orderId), orderId, Side.SELL, 500, 156.43, TimeInForce.DAY);
orderBook.createLimit(CLIENT_ID, String.valueOf(++orderId), orderId, Side.SELL, 1500, 158.54, TimeInForce.DAY);

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

orderBookLogger.on();

// Buy 100 @ market

orderBook.createMarket(CLIENT_ID, String.valueOf(++orderId), orderId, Side.BUY, 100);

/*
	-----> onOrderAccepted called:
	  orderBook=AAPL
	  time=1700598048051000000
	  order=Order [id=8, clientId=1001, clientOrderId=8, side=BUY, security=AAPL, originalSize=100, openSize=100, 
					executedSize=0, canceledSize=0, type=MARKET]
	
	-----> onOrderExecuted called:
	  orderBook=AAPL
	  time=1700598048051000000
	  order=Order [id=5, clientId=1001, clientOrderId=5, side=SELL, security=AAPL, originalSize=300, openSize=200, 
					executedSize=100, canceledSize=0, price=153.24, type=LIMIT, tif=GTC]
	  executeSide=MAKER
	  executeSize=100
	  executePrice=153.24
	  executeId=1
	  executeMatchId=1
	
	-----> onOrderExecuted called:
	  orderBook=AAPL
	  time=1700598048051000000
	  order=Order [id=8, clientId=1001, clientOrderId=8, side=BUY, security=AAPL, originalSize=100, openSize=0, 
					executedSize=100, canceledSize=0, type=MARKET]
	  executeSide=TAKER
	  executeSize=100
	  executePrice=153.24
	  executeId=2
	  executeMatchId=1
	
	-----> onOrderTerminated called:
	  orderBook=AAPL
	  time=1700598048051000000
	  order=Order [id=8, clientId=1001, clientOrderId=8, side=BUY, security=AAPL, originalSize=100, openSize=0, 
					executedSize=100, canceledSize=0, type=MARKET]
*/

orderBook.showLevels();

/*
   100 @    148.14 (orders=1)
   600 @    149.44 (orders=2)
   200 @    150.44 (orders=1)
--------      2.80
   200 @    153.24 (orders=1)
   500 @    156.43 (orders=1)
  1500 @    158.54 (orders=1) 
*/

// cancel 100 shares from order with id = 1

Order order = orderBook.getOrder(1);
order.cancel(100);

/*
	-----> onOrderReduced called:
	  orderBook=AAPL
	  time=1700598048053000000
	  order=Order [id=1, clientId=1001, clientOrderId=1, side=BUY, security=AAPL, originalSize=200, openSize=100, 
					executedSize=0, canceledSize=100, price=150.44, type=LIMIT, tif=DAY]
	  canceledSize=100
	  reduceNewTotalSize=100	
*/

orderBook.showLevels();

/*
   100 @    148.14 (orders=1)
   600 @    149.44 (orders=2)
   100 @    150.44 (orders=1)
--------      2.80
   200 @    153.24 (orders=1)
   500 @    156.43 (orders=1)
  1500 @    158.54 (orders=1)	 
*/

// now cancel the order

order.cancel();

/*
	-----> onOrderCanceled called:
	  orderBook=AAPL
	  time=1700598048053000000
	  order=Order [id=1, clientId=1001, clientOrderId=1, side=BUY, security=AAPL, originalSize=200, openSize=0, 
					executedSize=0, canceledSize=200, price=150.44, type=LIMIT, tif=DAY]
	  canceledSize=100
	  cancelReason=USER
	
	-----> onOrderTerminated called:
	  orderBook=AAPL
	  time=1700598048053000000
	  order=Order [id=1, clientId=1001, clientOrderId=1, side=BUY, security=AAPL, originalSize=200, openSize=0, 
					executedSize=0, canceledSize=200, price=150.44, type=LIMIT, tif=DAY]
*/

orderBook.showLevels();

/*
   100 @    148.14 (orders=1)
   600 @    149.44 (orders=2)
--------      3.80
   200 @    153.24 (orders=1)
   500 @    156.43 (orders=1)
  1500 @    158.54 (orders=1)	
*/

// hit the sell side of the book with a LIMIT IOC and notice your price improvement

orderBook.createLimit(CLIENT_ID, String.valueOf(++orderId), orderId, Side.BUY, 3000, 155.00, TimeInForce.IOC);

/*
	-----> onOrderAccepted called:
	  orderBook=AAPL
	  time=1700598048054000000
	  order=Order [id=9, clientId=1001, clientOrderId=9, side=BUY, security=AAPL, originalSize=3000, openSize=3000, 
					executedSize=0, canceledSize=0, price=155.0, type=LIMIT, tif=IOC]
	
	-----> onOrderExecuted called:
	  orderBook=AAPL
	  time=1700598048054000000
	  order=Order [id=5, clientId=1001, clientOrderId=5, side=SELL, security=AAPL, originalSize=300, openSize=0, 
					executedSize=300, canceledSize=0, price=153.24, type=LIMIT, tif=GTC]
	  executeSide=MAKER
	  executeSize=200
	  executePrice=153.24
	  executeId=3
	  executeMatchId=2
	
	-----> onOrderTerminated called:
	  orderBook=AAPL
	  time=1700598048054000000
	  order=Order [id=5, clientId=1001, clientOrderId=5, side=SELL, security=AAPL, originalSize=300, openSize=0, 
					executedSize=300, canceledSize=0, price=153.24, type=LIMIT, tif=GTC]
	
	-----> onOrderExecuted called:
	  orderBook=AAPL
	  time=1700598048054000000
	  order=Order [id=9, clientId=1001, clientOrderId=9, side=BUY, security=AAPL, originalSize=3000, openSize=2800, 
					executedSize=200, canceledSize=0, price=155.0, type=LIMIT, tif=IOC]
	  executeSide=TAKER
	  executeSize=200
	  executePrice=153.24
	  executeId=4
	  executeMatchId=2
	
	-----> onOrderCanceled called:
	  orderBook=AAPL
	  time=1700598048055000000
	  order=Order [id=9, clientId=1001, clientOrderId=9, side=BUY, security=AAPL, originalSize=3000, openSize=0, 
					executedSize=200, canceledSize=2800, price=155.0, type=LIMIT, tif=IOC]
	  canceledSize=2800
	  cancelReason=MISSED
	
	-----> onOrderTerminated called:
	  orderBook=AAPL
	  time=1700598048055000000
	  order=Order [id=9, clientId=1001, clientOrderId=9, side=BUY, security=AAPL, originalSize=3000, openSize=0, 
					executedSize=200, canceledSize=2800, price=155.0, type=LIMIT, tif=IOC]
*/

orderBook.showLevels();

/*
   100 @    148.14 (orders=1)
   600 @    149.44 (orders=2)
--------      6.99
   500 @    156.43 (orders=1)
  1500 @    158.54 (orders=1)		 
*/

orderBookLogger.off();

orderBook.createLimit(CLIENT_ID, String.valueOf(++orderId), orderId, Side.SELL, 3000, 160.00, TimeInForce.DAY);

orderBook.showLevels();

/*
   100 @    148.14 (orders=1)
   600 @    149.44 (orders=2)
--------      6.99
   500 @    156.43 (orders=1)
  1500 @    158.54 (orders=1)
  3000 @    160.00 (orders=1)
*/

// now hit two ask levels, price improve and sit on the book at 159.00

orderBookLogger.on();

orderBook.createLimit(CLIENT_ID, String.valueOf(++orderId), orderId, Side.BUY, 3900, 159.00, TimeInForce.DAY);

/*
	-----> onOrderAccepted called:
	  orderBook=AAPL
	  time=1700598048056000000
	  order=Order [id=11, clientId=1001, clientOrderId=11, side=BUY, security=AAPL, originalSize=3900, openSize=3900, 
					executedSize=0, canceledSize=0, price=159.0, type=LIMIT, tif=DAY]
	
	-----> onOrderExecuted called:
	  orderBook=AAPL
	  time=1700598048056000000
	  order=Order [id=6, clientId=1001, clientOrderId=6, side=SELL, security=AAPL, originalSize=500, openSize=0, 
					executedSize=500, canceledSize=0, price=156.43, type=LIMIT, tif=DAY]
	  executeSide=MAKER
	  executeSize=500
	  executePrice=156.43
	  executeId=5
	  executeMatchId=3
	
	-----> onOrderTerminated called:
	  orderBook=AAPL
	  time=1700598048056000000
	  order=Order [id=6, clientId=1001, clientOrderId=6, side=SELL, security=AAPL, originalSize=500, openSize=0, 
					executedSize=500, canceledSize=0, price=156.43, type=LIMIT, tif=DAY]
	
	-----> onOrderExecuted called:
	  orderBook=AAPL
	  time=1700598048056000000
	  order=Order [id=11, clientId=1001, clientOrderId=11, side=BUY, security=AAPL, originalSize=3900, openSize=3400, 
					executedSize=500, canceledSize=0, price=159.0, type=LIMIT, tif=DAY]
	  executeSide=TAKER
	  executeSize=500
	  executePrice=156.43
	  executeId=6
	  executeMatchId=3
	
	-----> onOrderExecuted called:
	  orderBook=AAPL
	  time=1700598048056000000
	  order=Order [id=7, clientId=1001, clientOrderId=7, side=SELL, security=AAPL, originalSize=1500, openSize=0, 
					executedSize=1500, canceledSize=0, price=158.54, type=LIMIT, tif=DAY]
	  executeSide=MAKER
	  executeSize=1500
	  executePrice=158.54
	  executeId=7
	  executeMatchId=4
	
	-----> onOrderTerminated called:
	  orderBook=AAPL
	  time=1700598048056000000
	  order=Order [id=7, clientId=1001, clientOrderId=7, side=SELL, security=AAPL, originalSize=1500, openSize=0, 
					executedSize=1500, canceledSize=0, price=158.54, type=LIMIT, tif=DAY]
	
	-----> onOrderExecuted called:
	  orderBook=AAPL
	  time=1700598048056000000
	  order=Order [id=11, clientId=1001, clientOrderId=11, side=BUY, security=AAPL, originalSize=3900, openSize=1900, 
					executedSize=2000, canceledSize=0, price=159.0, type=LIMIT, tif=DAY]
	  executeSide=TAKER
	  executeSize=1500
	  executePrice=158.54
	  executeId=8
	  executeMatchId=4
	
	-----> onOrderRested called:
	  orderBook=AAPL
	  time=1700598048056000000
	  order=Order [id=11, clientId=1001, clientOrderId=11, side=BUY, security=AAPL, originalSize=3900, openSize=1900, 
					executedSize=2000, canceledSize=0, price=159.0, type=LIMIT, tif=DAY]
	  restSize=1900
	  restPrice=159.0
*/

orderBook.showOrders();

/*
   100 @    148.14 (id=4)
   500 @    149.44 (id=2)
   100 @    149.44 (id=3)
  1900 @    159.00 (id=11)    <==== You order sat here after hitting some asks...
--------      1.00
  3000 @    160.00 (id=10)	 
*/
    
 ```
 

 
 






