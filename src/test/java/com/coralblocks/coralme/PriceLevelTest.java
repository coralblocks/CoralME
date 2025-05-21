package com.coralblocks.coralme;

import org.junit.Assert;
import org.junit.Test;

import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;
import com.coralblocks.coralme.Order.Type;
import com.coralblocks.coralme.util.SystemTimestamper;

public class PriceLevelTest {

	private final PriceLevel level = new PriceLevel("TEST", Side.BUY, 100);

	@Test
	public void testSizeDirtyReset() {

		Order order = new Order();
		order.init(new SystemTimestamper(), 1, "1", 1, "TEST", Side.BUY, 50, 100, Type.LIMIT, TimeInForce.DAY);

		Assert.assertFalse(level.isSizeDirty());
		Assert.assertFalse(level.isSizeDirty());
		level.addOrder(order);
		Assert.assertTrue(level.isSizeDirty());

		Assert.assertEquals(50, level.getSize());
		Assert.assertFalse(level.isSizeDirty());
	}
}
