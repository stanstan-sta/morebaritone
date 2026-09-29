/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.api.utils;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;

import static junit.framework.TestCase.*;

public class BlockUtilsTest {

    @Test
    public void planksExpandToPlankBlocksNotLogs() {
        List<String> planks = BlockUtils.getCategoryExpansion("planks");
        assertNotNull(planks);
        assertEquals(11, planks.size());
        assertEquals(11, new HashSet<>(planks).size());
        assertTrue(planks.contains("oak_planks"));
        assertTrue(planks.contains("crimson_planks"));
        assertTrue(planks.contains("warped_planks"));
        assertTrue(planks.contains("bamboo_planks"));
        for (String name : planks) {
            assertTrue("planks expansion must not target logs: " + name,
                    name.endsWith("_planks") && !name.contains("log") && !name.contains("wood")
                            && !name.contains("stem") && !name.contains("hyphae"));
        }
    }

    @Test
    public void logsAndWoodExpansionsAreUnchanged() {
        List<String> logs = BlockUtils.getCategoryExpansion("logs");
        assertNotNull(logs);
        assertTrue(logs.contains("oak_log"));
        assertTrue(logs.contains("stripped_oak_log"));
        for (String name : logs) {
            assertFalse("logs expansion must not contain planks: " + name, name.endsWith("_planks"));
        }
        List<String> wood = BlockUtils.getCategoryExpansion("wood");
        assertNotNull(wood);
        assertTrue(wood.contains("oak_log"));
        assertTrue(wood.contains("oak_wood"));
        for (String name : wood) {
            assertFalse("wood expansion must not contain planks: " + name, name.endsWith("_planks"));
        }
    }

    @Test
    public void stoneVariantsAreLimitedToTheDropPair() {
        // stone_bricks/smooth_stone are crafted building blocks; mining
        // "stone" must not target player structures built from them.
        assertEquals(java.util.Collections.singletonList("cobblestone"), BlockUtils.getVariants("stone"));
        assertEquals(java.util.Collections.singletonList("stone"), BlockUtils.getVariants("cobblestone"));
    }

    @Test
    public void logVariantsStillIncludeWood() {
        List<String> variants = BlockUtils.getVariants("oak_log");
        assertTrue(variants.contains("oak_wood"));
        assertTrue(variants.contains("stripped_oak_log"));
    }
}
