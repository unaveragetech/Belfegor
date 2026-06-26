package adris.altoclef.memory;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.*;

/**
 * Decision engine that evaluates multiple options for achieving a goal
 * and picks the best one based on distance, resources, time, and learned experience.
 *
 * For every decision, the bot evaluates N options, scores them, and picks the best.
 */
public class DecisionEngine {

    private static DecisionEngine INSTANCE = new DecisionEngine();
    private final Map<String, List<DecisionOption>> _decisionHistory = new LinkedHashMap<>();
    private int _maxHistoryPerDecision = 50;

    public static class DecisionOption {
        public String name;
        public double distanceCost;
        public double resourceCost;
        public double timeCost;
        public double successRate;
        public double totalScore;

        public DecisionOption(String name) {
            this.name = name;
        }

        public DecisionOption score(double weightDistance, double weightResource, double weightTime, double weightSuccess) {
            totalScore = (distanceCost * weightDistance) +
                         (resourceCost * weightResource) +
                         (timeCost * weightTime) +
                         ((1.0 - successRate) * weightSuccess);
            return this;
        }
    }

    public static DecisionEngine getInstance() { return INSTANCE; }

    /**
     * Decide the best way to get an item given multiple acquisition methods.
     * Options: mine, craft, find in chest, pick up from ground.
     */
    public String decideItemAcquisition(AltoClef mod, String itemName, Item item) {
        List<DecisionOption> options = new ArrayList<>();

        PlayerEntity player = mod.getPlayer();
        if (player == null) return itemName;

        double playerX = player.getX();
        double playerZ = player.getZ();

        // Option 1: Mine the block (if it's a block)
        DecisionOption mine = new DecisionOption("mine");
        mine.distanceCost = getNearestBlockDistance(mod, item) / 128.0;
        mine.resourceCost = 0;
        mine.timeCost = estimateMiningTime(item);
        mine.successRate = getSuccessRate("mine:" + itemName);

        // Option 2: Craft from raw materials
        DecisionOption craft = new DecisionOption("craft");
        craft.distanceCost = getNearestMaterialDistance(mod, item) / 128.0;
        craft.resourceCost = getMaterialCost(item);
        craft.timeCost = estimateCraftingTime(item);
        craft.successRate = getSuccessRate("craft:" + itemName);

        // Option 3: Find in chest
        DecisionOption chest = new DecisionOption("chest");
        chest.distanceCost = getNearestChestDistance(mod, item) / 500.0;
        chest.resourceCost = 0;
        chest.timeCost = 5.0;
        chest.successRate = getSuccessRate("chest:" + itemName);

        // Option 4: Pick up from ground
        DecisionOption pickup = new DecisionOption("pickup");
        pickup.distanceCost = getNearestDropDistance(mod, item) / 64.0;
        pickup.resourceCost = 0;
        pickup.timeCost = 1.0;
        pickup.successRate = 0.8;

        options.add(mine);
        options.add(craft);
        options.add(chest);
        options.add(pickup);

        // Score with weights favoring proximity and success
        for (DecisionOption opt : options) {
            opt.score(0.4, 0.2, 0.2, 0.2);
        }

        options.sort(Comparator.comparingDouble(o -> o.totalScore));

        String best = options.isEmpty() ? "craft" : options.get(0).name;
        recordDecision("acquire:" + itemName, best);
        return best;
    }

    /**
     * Decide the best tool for a job.
     * Returns the tool item to use/craft.
     */
    public Item decideBestTool(AltoClef mod, String jobType) {
        CraftingMemory memory = CraftingMemory.getInstance();

        Item[] toolPriority = switch (jobType) {
            case "chop" -> new Item[]{
                    Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE,
                    Items.STONE_AXE, Items.WOODEN_AXE
            };
            case "mine_stone" -> new Item[]{
                    Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE,
                    Items.STONE_PICKAXE, Items.WOODEN_PICKAXE
            };
            case "mine_ore" -> new Item[]{
                    Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE,
                    Items.STONE_PICKAXE
            };
            case "dig" -> new Item[]{
                    Items.NETHERITE_SHOVEL, Items.DIAMOND_SHOVEL, Items.IRON_SHOVEL,
                    Items.STONE_SHOVEL, Items.WOODEN_SHOVEL
            };
            default -> new Item[]{Items.WOODEN_PICKAXE};
        };

        for (Item tool : toolPriority) {
            if (mod.getItemStorage().hasItem(tool)) {
                return tool;
            }
        }

        CraftingMemory.ToolStats stats = memory.getToolStats(jobType + "_tool");
        if (stats != null && stats.timesCrafted > 3) {
            return toolPriority[toolPriority.length - 1];
        }

        return toolPriority[toolPriority.length - 1];
    }

    /**
     * Decide tool set progression - what tools to make next.
     * Returns a list of tools in priority order.
     */
    public List<Item> decideToolProgression(AltoClef mod) {
        List<Item> toMake = new ArrayList<>();
        CraftingMemory memory = CraftingMemory.getInstance();

        boolean hasWoodPickaxe = mod.getItemStorage().hasItem(Items.WOODEN_PICKAXE);
        boolean hasWoodAxe = mod.getItemStorage().hasItem(Items.WOODEN_AXE);
        boolean hasWoodSword = mod.getItemStorage().hasItem(Items.WOODEN_SWORD);
        boolean hasWoodShovel = mod.getItemStorage().hasItem(Items.WOODEN_SHOVEL);

        boolean hasStonePickaxe = mod.getItemStorage().hasItem(Items.STONE_PICKAXE);
        boolean hasStoneAxe = mod.getItemStorage().hasItem(Items.STONE_AXE);
        boolean hasStoneSword = mod.getItemStorage().hasItem(Items.STONE_SWORD);
        boolean hasStoneShovel = mod.getItemStorage().hasItem(Items.STONE_SHOVEL);

        boolean hasIronPickaxe = mod.getItemStorage().hasItem(Items.IRON_PICKAXE);

        if (!hasWoodAxe && memory.shouldMakeAxeFirst()) {
            toMake.add(Items.WOODEN_AXE);
        }
        if (!hasWoodPickaxe) {
            toMake.add(Items.WOODEN_PICKAXE);
        }
        if (!hasWoodSword) {
            toMake.add(Items.WOODEN_SWORD);
        }
        if (!hasWoodShovel) {
            toMake.add(Items.WOODEN_SHOVEL);
        }

        if (hasWoodPickaxe && !hasStonePickaxe && memory.shouldMakeStoneTools()) {
            toMake.add(Items.STONE_PICKAXE);
            toMake.add(Items.STONE_AXE);
            toMake.add(Items.STONE_SWORD);
            toMake.add(Items.STONE_SHOVEL);
        }

        if (hasStonePickaxe && !hasIronPickaxe) {
            toMake.add(Items.IRON_PICKAXE);
        }

        return toMake;
    }

    private double getNearestBlockDistance(AltoClef mod, Item item) {
        var locations = mod.getMemory().getAllInRange("resource:" + item.getName().getString(),
                mod.getPlayer().getX(), mod.getPlayer().getY(), mod.getPlayer().getZ(), 128 * 128);
        if (locations.isEmpty()) return 128;
        return Math.sqrt(locations.get(0).distanceTo(
                mod.getPlayer().getX(), mod.getPlayer().getY(), mod.getPlayer().getZ()));
    }

    private double getNearestMaterialDistance(AltoClef mod, Item item) {
        return 20;
    }

    private double getNearestChestDistance(AltoClef mod, Item item) {
        var locations = mod.getMemory().getAllInRange("chest",
                mod.getPlayer().getX(), mod.getPlayer().getY(), mod.getPlayer().getZ(), 500 * 500);
        if (locations.isEmpty()) return 500;
        return Math.sqrt(locations.get(0).distanceTo(
                mod.getPlayer().getX(), mod.getPlayer().getY(), mod.getPlayer().getZ()));
    }

    private double getNearestDropDistance(AltoClef mod, Item item) {
        return 64;
    }

    private double getMaterialCost(Item item) {
        CraftingMemory.ItemPlan plan = CraftingMemory.getInstance().getPlan(item.getName().getString()).orElse(null);
        if (plan == null) return 1.0;
        return plan.steps.size() * 0.5;
    }

    private double estimateMiningTime(Item item) {
        return 5.0;
    }

    private double estimateCraftingTime(Item item) {
        CraftingMemory.ItemPlan plan = CraftingMemory.getInstance().getPlan(item.getName().getString()).orElse(null);
        if (plan != null && plan.avgTimeSeconds > 0) return plan.avgTimeSeconds;
        return 10.0;
    }

    private double getSuccessRate(String key) {
        CraftingMemory.ItemPlan plan = CraftingMemory.getInstance().getPlan(key).orElse(null);
        if (plan == null) return 0.5;
        return plan.getSuccessRate();
    }

    private void recordDecision(String decisionKey, String chosen) {
        _decisionHistory.computeIfAbsent(decisionKey, k -> new ArrayList<>());
        List<DecisionOption> history = _decisionHistory.get(decisionKey);
        DecisionOption opt = new DecisionOption(chosen);
        opt.successRate = 1.0;
        history.add(opt);
        if (history.size() > _maxHistoryPerDecision) {
            history.remove(0);
        }
    }

    public void save() {
        CraftingMemory.getInstance().save();
    }
}
