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

import static org.junit.Assert.assertEquals;

import java.lang.management.ManagementFactory;

import org.junit.Assume;
import org.junit.Test;

import com.coralblocks.coralme.Order.ExecuteSide;
import com.coralblocks.coralme.Order.Side;
import com.coralblocks.coralme.Order.TimeInForce;
import com.coralblocks.coralme.Order.Type;

public class OrderFixCodeAllocationTest {

	private static final int WARMUP_ITERATIONS = 100_000;
	private static final int MEASURED_ITERATIONS = 100_000;

	private final StringBuilder timeInForceFixCode = new StringBuilder("3");
	private final StringBuilder typeFixCode = new StringBuilder("2");
	private final StringBuilder executeSideFixCode = new StringBuilder("N");
	private final StringBuilder sideFixCode = new StringBuilder("2");
	private long checksum;

	@Test
	public void testFixCodeLookupsAllocateNoGarbage() {
		java.lang.management.ThreadMXBean standardBean = ManagementFactory.getThreadMXBean();
		Assume.assumeTrue(standardBean instanceof com.sun.management.ThreadMXBean);
		com.sun.management.ThreadMXBean allocationBean = (com.sun.management.ThreadMXBean) standardBean;
		Assume.assumeTrue(allocationBean.isThreadAllocatedMemorySupported());

		boolean allocationMeasurementWasEnabled = allocationBean.isThreadAllocatedMemoryEnabled();
		if (!allocationMeasurementWasEnabled) allocationBean.setThreadAllocatedMemoryEnabled(true);

		try {
			runIterations(WARMUP_ITERATIONS);
			assertEquals(WARMUP_ITERATIONS * 4L, checksum);

			long threadId = Thread.currentThread().getId();
			allocationBean.getThreadAllocatedBytes(threadId);
			long allocatedBytesBefore = allocationBean.getThreadAllocatedBytes(threadId);

			runIterations(MEASURED_ITERATIONS);

			long allocatedBytesAfter = allocationBean.getThreadAllocatedBytes(threadId);
			assertEquals((WARMUP_ITERATIONS + MEASURED_ITERATIONS) * 4L, checksum);
			assertEquals(0, allocatedBytesAfter - allocatedBytesBefore);
		} finally {
			if (!allocationMeasurementWasEnabled) allocationBean.setThreadAllocatedMemoryEnabled(false);
		}
	}

	private void runIterations(int iterations) {
		for(int i = 0; i < iterations; i++) {
			checksum += TimeInForce.fromFixCode(timeInForceFixCode).ordinal();
			checksum += Type.fromFixCode(typeFixCode).ordinal();
			checksum += ExecuteSide.fromFixCode(executeSideFixCode).ordinal();
			checksum += Side.fromFixCode(sideFixCode).ordinal();
		}
	}
}
