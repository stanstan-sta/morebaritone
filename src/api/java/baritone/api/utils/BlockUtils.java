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

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.*;

public class BlockUtils {

    private static transient Map<String, Block> resourceCache = new HashMap<>();

    // Maps a block name to a list of variant block names that should be included when mining/finding
    // Each key is expanded to include all the listed variant names as additional targets
    private static final Map<String, List<String>> BLOCK_VARIANTS = new HashMap<>();

    // Maps category shortcut names to lists of actual block names
    // When user types "#mine wood", it expands to all wood/log types in all variants
    private static final Map<String, List<String>> CATEGORY_ALIASES = new HashMap<>();

    static {
        // Wood/log variants - when mining wood, also mine the logs (and stripped variants)
        // When mining logs, also mine the wood (and stripped variants)
        String[] woodTypes = {
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
            "mangrove", "cherry", "crimson", "warped", "bamboo"
        };

        for (String wood : woodTypes) {
            String stemOrLog = wood.equals("crimson") || wood.equals("warped") ? "stem" : "log";
            String hyphaeOrWood = wood.equals("crimson") || wood.equals("warped") ? "hyphae" : "wood";

            // _wood includes _log, stripped_wood, stripped_log
            BLOCK_VARIANTS.put(wood + "_" + hyphaeOrWood, Arrays.asList(
                wood + "_" + stemOrLog,
                "stripped_" + wood + "_" + hyphaeOrWood,
                "stripped_" + wood + "_" + stemOrLog
            ));

            // _log includes _wood, stripped_log, stripped_wood
            BLOCK_VARIANTS.put(wood + "_" + stemOrLog, Arrays.asList(
                wood + "_" + hyphaeOrWood,
                "stripped_" + wood + "_" + stemOrLog,
                "stripped_" + wood + "_" + hyphaeOrWood
            ));

            // stripped_wood includes stripped_log, wood, log
            BLOCK_VARIANTS.put("stripped_" + wood + "_" + hyphaeOrWood, Arrays.asList(
                "stripped_" + wood + "_" + stemOrLog,
                wood + "_" + hyphaeOrWood,
                wood + "_" + stemOrLog
            ));

            // stripped_log includes stripped_wood, log, wood
            BLOCK_VARIANTS.put("stripped_" + wood + "_" + stemOrLog, Arrays.asList(
                "stripped_" + wood + "_" + hyphaeOrWood,
                wood + "_" + stemOrLog,
                wood + "_" + hyphaeOrWood
            ));
        }

        // Stone variants: cobblestone is the drop of stone and vice versa.
        // stone_bricks/smooth_stone are crafted building blocks, not stone.
        BLOCK_VARIANTS.put("stone", Arrays.asList("cobblestone"));
        BLOCK_VARIANTS.put("cobblestone", Arrays.asList("stone"));

        // Deepslate variants
        BLOCK_VARIANTS.put("deepslate", Arrays.asList(
            "cobbled_deepslate", "polished_deepslate", "deepslate_bricks",
            "deepslate_tiles", "chiseled_deepslate"
        ));
        BLOCK_VARIANTS.put("cobbled_deepslate", Arrays.asList(
            "deepslate", "polished_deepslate", "deepslate_bricks",
            "deepslate_tiles", "chiseled_deepslate"
        ));

        // Basalt variants
        BLOCK_VARIANTS.put("basalt", Arrays.asList("polished_basalt", "smooth_basalt"));
        BLOCK_VARIANTS.put("polished_basalt", Arrays.asList("basalt", "smooth_basalt"));
        BLOCK_VARIANTS.put("smooth_basalt", Arrays.asList("basalt", "polished_basalt"));

        // Quartz variants
        BLOCK_VARIANTS.put("quartz_block", Arrays.asList("quartz_pillar", "chiseled_quartz_block", "smooth_quartz"));
        BLOCK_VARIANTS.put("quartz_pillar", Arrays.asList("quartz_block", "chiseled_quartz_block", "smooth_quartz"));
        BLOCK_VARIANTS.put("smooth_quartz", Arrays.asList("quartz_block", "quartz_pillar", "chiseled_quartz_block"));

        // Sandstone variants
        BLOCK_VARIANTS.put("sandstone", Arrays.asList("smooth_sandstone", "chiseled_sandstone", "cut_sandstone"));
        BLOCK_VARIANTS.put("red_sandstone", Arrays.asList("smooth_red_sandstone", "chiseled_red_sandstone", "cut_red_sandstone"));

        // Prismarine variants
        BLOCK_VARIANTS.put("prismarine", Arrays.asList("prismarine_bricks", "dark_prismarine"));
        BLOCK_VARIANTS.put("prismarine_bricks", Arrays.asList("prismarine", "dark_prismarine"));
        BLOCK_VARIANTS.put("dark_prismarine", Arrays.asList("prismarine", "prismarine_bricks"));

        // Blackstone variants
        BLOCK_VARIANTS.put("blackstone", Arrays.asList("polished_blackstone", "chiseled_polished_blackstone",
            "polished_blackstone_bricks", "gilded_blackstone"));
        BLOCK_VARIANTS.put("polished_blackstone", Arrays.asList("blackstone", "chiseled_polished_blackstone",
            "polished_blackstone_bricks"));

        // Purpur variants
        BLOCK_VARIANTS.put("purpur_block", Arrays.asList("purpur_pillar"));

        // Nether brick variants
        BLOCK_VARIANTS.put("nether_bricks", Arrays.asList("red_nether_bricks", "chiseled_nether_bricks", "cracked_nether_bricks"));

        // Mushroom stem / mushroom blocks
        BLOCK_VARIANTS.put("mushroom_stem", Arrays.asList("brown_mushroom_block", "red_mushroom_block"));

        // ---- CATEGORY ALIASES ----

        List<String> allWoodAny = new ArrayList<>();
        List<String> allLogs = new ArrayList<>();
        List<String> allPlanks = new ArrayList<>();

        for (String wood : woodTypes) {
            String stemOrLog = wood.equals("crimson") || wood.equals("warped") ? "stem" : "log";
            String hyphaeOrWood = wood.equals("crimson") || wood.equals("warped") ? "hyphae" : "wood";
            String woodBlock = wood + "_" + hyphaeOrWood;
            String logBlock = wood + "_" + stemOrLog;
            String strippedWood = "stripped_" + woodBlock;
            String strippedLog = "stripped_" + logBlock;

            allWoodAny.add(woodBlock);
            allWoodAny.add(logBlock);
            allWoodAny.add(strippedWood);
            allWoodAny.add(strippedLog);

            allLogs.add(logBlock);
            allLogs.add(strippedLog);

            // Planks are their own crafted blocks (oak_planks, ...), not logs.
            allPlanks.add(wood + "_planks");
        }
        // "wood" mines all wood-type blocks (wood, log, stripped variants, all species)
        CATEGORY_ALIASES.put("wood", allWoodAny);
        CATEGORY_ALIASES.put("planks", allPlanks);
        // "logs" mines only log-type blocks (log, stripped_log, all species)
        CATEGORY_ALIASES.put("logs", allLogs);
        CATEGORY_ALIASES.put("stripped_logs", allLogs);

        // Ore shortcuts - mine every overworld/nether ore
        CATEGORY_ALIASES.put("ores", Arrays.asList(
            "coal_ore", "deepslate_coal_ore",
            "iron_ore", "deepslate_iron_ore",
            "copper_ore", "deepslate_copper_ore",
            "gold_ore", "deepslate_gold_ore",
            "redstone_ore", "deepslate_redstone_ore",
            "lapis_ore", "deepslate_lapis_ore",
            "diamond_ore", "deepslate_diamond_ore",
            "emerald_ore", "deepslate_emerald_ore",
            "nether_quartz_ore", "nether_gold_ore",
            "ancient_debris"
        ));
        // Deepslate ores only
        CATEGORY_ALIASES.put("deepslate_ores", Arrays.asList(
            "deepslate_coal_ore", "deepslate_iron_ore", "deepslate_copper_ore",
            "deepslate_gold_ore", "deepslate_redstone_ore", "deepslate_lapis_ore",
            "deepslate_diamond_ore", "deepslate_emerald_ore"
        ));

        // Coral shortcuts
        List<String> allCoral = new ArrayList<>();
        List<String> allCoralBlocks = new ArrayList<>();
        for (String color : Arrays.asList("tube", "brain", "bubble", "fire", "horn")) {
            for (String suffix : Arrays.asList("coral", "coral_fan", "coral_wall_fan")) {
                allCoral.add(color + "_" + suffix);
            }
            allCoralBlocks.add(color + "_coral_block");
        }
        CATEGORY_ALIASES.put("coral", allCoral);
        CATEGORY_ALIASES.put("coral_blocks", allCoralBlocks);
    }

    /**
     * Returns variant block names for the given block name.
     * When a user targets a block like "acacia_wood", this method returns
     * related blocks like "acacia_log", "stripped_acacia_wood", etc.
     *
     * @param blockName The block name to get variants for (e.g. "acacia_wood")
     * @return An unmodifiable list of variant block names, or an empty list if none
     */
    public static List<String> getVariants(String blockName) {
        List<String> variants = BLOCK_VARIANTS.get(blockName);
        if (variants == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(variants);
    }

    /**
     * Returns a list of actual block names for a category shortcut.
     * When a user types "#mine wood", it expands to all wood/log types.
     * Returns null if the name is not a known category shortcut.
     *
     * @param name The potential category shortcut (e.g. "wood", "logs", "ores", "coral")
     * @return List of all block names in the category, or null if not a recognized category
     */
    public static List<String> getCategoryExpansion(String name) {
        return CATEGORY_ALIASES.get(name);
    }

    public static String blockToString(Block block) {
        Identifier loc = BuiltInRegistries.BLOCK.getKey(block);
        String name = loc.getPath(); // normally, only write the part after the minecraft:
        if (!loc.getNamespace().equals("minecraft")) {
            // Baritone is running on top of forge with mods installed, perhaps?
            name = loc.toString(); // include the namespace with the colon
        }
        return name;
    }

    public static Block stringToBlockRequired(String name) {
        Block block = stringToBlockNullable(name);

        if (block == null) {
            throw new IllegalArgumentException(String.format("Invalid block name %s", name));
        }

        return block;
    }

    public static Block stringToBlockNullable(String name) {
        // do NOT just replace this with a computeWithAbsent, it isn't thread safe
        Block block = resourceCache.get(name); // map is never mutated in place so this is safe
        if (block != null) {
            return block;
        }
        if (resourceCache.containsKey(name)) {
            return null; // cached as null
        }
        block = BuiltInRegistries.BLOCK.getOptional(Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name)).orElse(null);
        Map<String, Block> copy = new HashMap<>(resourceCache); // read only copy is safe, wont throw concurrentmodification
        copy.put(name, block);
        resourceCache = copy;
        return block;
    }

    private BlockUtils() {}
}