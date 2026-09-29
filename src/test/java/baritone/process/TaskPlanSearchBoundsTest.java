/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.process;

import org.junit.Test;

import static junit.framework.TestCase.assertEquals;

public class TaskPlanSearchBoundsTest {

    @Test
    public void liveFallbackNeverUsesTheHistorical256ChunkRadius() {
        assertEquals(32, TaskPlanProcess.loadedChunkRadiusForRegionDistance(0));
        assertEquals(40, TaskPlanProcess.loadedChunkRadiusForRegionDistance(1));
        assertEquals(40, TaskPlanProcess.loadedChunkRadiusForRegionDistance(2));
        assertEquals(40, TaskPlanProcess.loadedChunkRadiusForRegionDistance(256));
    }

    @Test
    public void negativeRadiusIsClampedSafely() {
        assertEquals(32, TaskPlanProcess.loadedChunkRadiusForRegionDistance(-1));
    }
}
