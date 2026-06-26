package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.Settings;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.memory.LocationMemory;
import adris.altoclef.tasks.construction.BuildCampsiteTask;
import adris.altoclef.tasks.container.ShulkerInteractionTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasks.resources.KillAndLootTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.BlockTracker;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.passive.*;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Autonomous exploration routine that plays the game to discover faster paths.
 *
 * The bot wanders, gathers resources, kills mobs, mines blocks, and tries crafting
 * different items. It records observations in CraftingPathRegistry to learn what
 * works best over time.
 *
 * Phases:
 *   EXPLORE  -> Wander to new areas, discover structures and resource clusters
 *   GATHER   -> Pick up nearby items, mine interesting blocks, kill mobs
 *   CRAFT    -> Try crafting items, record success/failure in path registry
 *   SURVIVE  -> Eat food, manage health, avoid danger
 *
 * The bot starts with basic survival, then progressively explores further.
 * Each session builds on previous learning.
 */
public class PlayerExplorationTask extends Task {

    private Phase _phase = Phase.EXPLORE;
    private Task _activeTask = null;
    private String _activeTaskKey = null;
    private int _explorationCounter = 0;
    private int _campBuildCount = 0;
    private BlockPos _homeBase;

    private final TimerGame _phaseTimer = new TimerGame(30);
    private final TimerGame _foodCheckTimer = new TimerGame(10);
    private final TimerGame _craftTestTimer = new TimerGame(20);
    private final TimerGame _homeCheckTimer = new TimerGame(90);
    private final TimerGame _shulkerSortTimer = new TimerGame(20);

    private enum Phase {
        EXPLORE,
        GATHER,
        CRAFT,
        SURVIVE,
        TOOLS,
        HOME
    }

    @Override
    protected void onStart(AltoClef mod) {
        _phase = Phase.EXPLORE;
        _explorationCounter = 0;
        _campBuildCount = 0;
        _homeBase = mod.getPlayer() == null ? mod.getModSettings().getHomeBasePosition() : mod.getPlayer().getBlockPos();
        mod.getModSettings().setHomeBasePosition(_homeBase);
        mod.getModSettings().setReturnHomeOnIdle(true);
        mod.getModSettings().setDefendHomeBase(true);
        mod.getModSettings().setHomeBaseDefenseRadius(32);
        Settings.save(mod.getModSettings());
        LocationMemory.getInstance().remember("home_base",
                _homeBase.getX(), _homeBase.getY(), _homeBase.getZ(),
                WorldHelper.getCurrentDimension().name(), "set_by_player_mode");
        LocationMemory.getInstance().save();
        Debug.logInternal("PlayerExplorationTask: Starting autonomous exploration");
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (shouldFleeDanger(mod)) {
            setDebugState("Fleeing danger!");
            return new TimeoutWanderTask(true);
        }

        if (_foodCheckTimer.elapsed()) {
            _foodCheckTimer.reset();
            if (isHungry(mod)) {
                _phase = Phase.SURVIVE;
            }
        }

        Task shulkerTask = maybeUseShulkers(mod);
        if (shulkerTask != null) {
            setDebugState("Managing carried shulker inventory");
            return shulkerTask;
        }

        if (!mod.getItemStorage().hasItem(Items.WOODEN_PICKAXE)) {
            setDebugState("Getting first pickaxe");
            return TaskCatalogue.getItemTask(Items.WOODEN_PICKAXE, 1);
        }

        if ((_campBuildCount == 0 || _homeCheckTimer.elapsed()) && _phase != Phase.SURVIVE) {
            _phase = Phase.HOME;
            _homeCheckTimer.reset();
        }

        switch (_phase) {
            case EXPLORE: return doExplore(mod);
            case GATHER: return doGather(mod);
            case CRAFT: return doCraft(mod);
            case SURVIVE: return doSurvive(mod);
            case TOOLS: return doTools(mod);
            case HOME: return doHome(mod);
        }
        return null;
    }

    private Task doExplore(AltoClef mod) {
        _explorationCounter++;

        if (_phaseTimer.elapsed() || _explorationCounter > 20) {
            _phase = Phase.GATHER;
            _phaseTimer.reset();
            _explorationCounter = 0;
            return null;
        }

        setDebugState("Exploring #" + _explorationCounter);
        return new TimeoutWanderTask(true);
    }

    private Task doGather(AltoClef mod) {
        Task pickupTask = findPickupTask(mod);
        if (pickupTask != null) {
            setDebugState("Picking up items");
            return pickupTask;
        }

        Task killTask = findKillTask(mod);
        if (killTask != null) {
            setDebugState("Hunting mobs");
            return killTask;
        }

        Task mineTask = findMineTask(mod);
        if (mineTask != null) {
            setDebugState("Mining blocks");
            return mineTask;
        }

        if (_phaseTimer.elapsed()) {
            _phase = Phase.CRAFT;
            _phaseTimer.reset();
            return null;
        }

        return new TimeoutWanderTask(true);
    }

    private Task doCraft(AltoClef mod) {
        if (_craftTestTimer.elapsed()) {
            _craftTestTimer.reset();

            String[] testItems = {
                    "crafting_table", "torch", "chest", "furnace", "campfire",
                    "wooden_pickaxe", "wooden_axe", "wooden_shovel", "wooden_sword",
                    "stone_pickaxe", "stone_axe", "stone_shovel", "stone_sword",
                    "iron_pickaxe", "iron_shovel", "shield",
                    "stick", "ladder", "oak_planks", "bread", "bucket"
            };

            for (String item : testItems) {
                if (canCraftItem(mod, item)) {
                    setDebugState("Testing craft: " + item);
                    Task craftTask = TaskCatalogue.getItemTask(item, 1);
                    if (craftTask != null) {
                        return craftTask;
                    }
                }
            }
        }

        _phase = Phase.TOOLS;
        return null;
    }

    private Task doHome(AltoClef mod) {
        if (_homeBase == null) {
            _homeBase = mod.getModSettings().getHomeBasePosition();
        }
        if (_homeBase != null && mod.getPlayer() != null
                && _homeBase.getSquaredDistance(mod.getPlayer().getBlockPos()) > 48 * 48) {
            setDebugState("Returning to home base");
            return cacheTask("goto-home:" + _homeBase, new GetToBlockTask(_homeBase));
        }

        if (!mod.getItemStorage().hasItem(Items.CRAFTING_TABLE)) {
            setDebugState("Preparing campsite crafting table");
            return TaskCatalogue.getItemTask("crafting_table", 1);
        }
        if (!mod.getItemStorage().hasItem(Items.FURNACE)
                && mod.getItemStorage().getItemCount(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE) >= 8) {
            setDebugState("Preparing campsite furnace");
            Task furnace = TaskCatalogue.getItemTask("furnace", 1);
            if (furnace != null) return furnace;
        }
        if (!mod.getItemStorage().hasItem(Items.CHEST)) {
            setDebugState("Preparing campsite chest");
            Task chest = TaskCatalogue.getItemTask("chest", 1);
            if (chest != null) return chest;
        }

        int radius = Math.min(8, 4 + _campBuildCount);
        Task build = cacheTask("camp:" + radius + ":" + _homeBase, new BuildCampsiteTask(_homeBase, radius));
        if (!build.isFinished(mod)) {
            setDebugState("Building/expanding home campsite radius " + radius);
            return build;
        }

        _campBuildCount++;
        _phase = Phase.EXPLORE;
        return null;
    }

    private Task doSurvive(AltoClef mod) {
        if (!isHungry(mod)) {
            _phase = Phase.EXPLORE;
            return null;
        }

        setDebugState("Getting food");
        Task foodTask = TaskCatalogue.getItemTask("cooked_beef", 1);
        if (foodTask != null) {
            return foodTask;
        }
        return TaskCatalogue.getItemTask("beef", 1);
    }

    private Task doTools(AltoClef mod) {
        if (!mod.getItemStorage().hasItem(Items.STONE_PICKAXE) && mod.getItemStorage().hasItem(Items.WOODEN_PICKAXE)) {
            setDebugState("Upgrading to stone pickaxe");
            return TaskCatalogue.getItemTask(Items.STONE_PICKAXE, 1);
        }
        if (!mod.getItemStorage().hasItem(Items.IRON_PICKAXE) && mod.getItemStorage().hasItem(Items.STONE_PICKAXE)) {
            setDebugState("Upgrading to iron pickaxe");
            return TaskCatalogue.getItemTask(Items.IRON_PICKAXE, 1);
        }

        _phase = Phase.EXPLORE;
        return null;
    }

    private Task maybeUseShulkers(AltoClef mod) {
        if (!_shulkerSortTimer.elapsed()) return null;
        _shulkerSortTimer.reset();
        if (!ShulkerInteractionTask.hasCarriedShulker(mod)) return null;
        int occupied = 0;
        for (var stack : mod.getPlayer().getInventory().main) {
            if (!stack.isEmpty()) occupied++;
        }
        if (occupied < 30) return null;
        ItemTarget[] targets = ShulkerInteractionTask.getAutoStoreTargets(mod);
        if (targets.length == 0) return null;
        String key = "player-shulker-store:" + Arrays.toString(targets);
        return cacheTask(key, new ShulkerInteractionTask(ShulkerInteractionTask.Mode.STORE, targets));
    }

    private Task cacheTask(String key, Task task) {
        if (_activeTask != null
                && key.equals(_activeTaskKey)
                && !_activeTask.stopped()) {
            return _activeTask;
        }
        _activeTaskKey = key;
        _activeTask = task;
        return _activeTask;
    }

    private boolean canCraftItem(AltoClef mod, String itemName) {
        if (TaskCatalogue.taskExists(itemName)) {
            return !mod.getItemStorage().hasItem(
                    net.minecraft.registry.Registries.ITEM.get(
                            net.minecraft.util.Identifier.of("minecraft", itemName)));
        }
        return false;
    }

    private boolean isHungry(AltoClef mod) {
        if (mod.getPlayer() == null) return false;
        return mod.getPlayer().getHungerManager().getFoodLevel() < 14;
    }

    private boolean shouldFleeDanger(AltoClef mod) {
        if (mod.getPlayer() == null) return false;
        if (mod.getPlayer().getHealth() < 8) return true;
        List<Entity> hostiles = mod.getEntityTracker().getHostiles();
        int nearbyHostiles = 0;
        for (Entity hostile : hostiles) {
            if (hostile.isAlive() && hostile.distanceTo(mod.getPlayer()) < 8) {
                nearbyHostiles++;
            }
        }
        return nearbyHostiles >= 3;
    }

    private Task findPickupTask(AltoClef mod) {
        List<ItemEntity> drops = mod.getEntityTracker().getDroppedItems();
        Item[] highValue = {Items.DIAMOND, Items.IRON_INGOT, Items.GOLD_INGOT,
                Items.ENDER_PEARL, Items.BLAZE_ROD, Items.GOLDEN_APPLE,
                Items.EXPERIENCE_BOTTLE, Items.ENDER_EYE};

        for (ItemEntity entity : drops) {
            Item droppedItem = entity.getStack().getItem();
            for (Item valuable : highValue) {
                if (droppedItem == valuable) {
                    return new PickupDroppedItemTask(new ItemTarget(valuable, 1), true);
                }
            }
        }

        for (ItemEntity entity : drops) {
            if (entity.getStack().getItem().getComponents().contains(DataComponentTypes.FOOD)) {
                return new PickupDroppedItemTask(
                        new ItemTarget(entity.getStack().getItem(), 1), true);
            }
        }

        return null;
    }

    private Task findKillTask(AltoClef mod) {
        Predicate<Entity> notBaby = e -> e instanceof LivingEntity le && !le.isBaby();

        // Prefer cows
        var cow = mod.getEntityTracker().getClosestEntity(notBaby, CowEntity.class);
        if (cow.isPresent() && cow.get().distanceTo(mod.getPlayer()) < 16) {
            return new KillAndLootTask(CowEntity.class, notBaby, new ItemTarget(Items.BEEF, 1));
        }

        // Then pigs
        var pig = mod.getEntityTracker().getClosestEntity(notBaby, PigEntity.class);
        if (pig.isPresent() && pig.get().distanceTo(mod.getPlayer()) < 16) {
            return new KillAndLootTask(PigEntity.class, notBaby, new ItemTarget(Items.PORKCHOP, 1));
        }

        // Then chickens
        var chicken = mod.getEntityTracker().getClosestEntity(notBaby, ChickenEntity.class);
        if (chicken.isPresent() && chicken.get().distanceTo(mod.getPlayer()) < 16) {
            return new KillAndLootTask(ChickenEntity.class, notBaby, new ItemTarget(Items.CHICKEN, 1));
        }

        // Hostile mobs for drops
        List<Entity> hostiles = mod.getEntityTracker().getHostiles();
        for (Entity entity : hostiles) {
            if (entity.isAlive() && entity.distanceTo(mod.getPlayer()) < 12) {
                if (entity instanceof CreeperEntity) {
                    return new KillAndLootTask(CreeperEntity.class, e -> true, new ItemTarget(Items.GUNPOWDER, 1));
                }
                if (entity instanceof SkeletonEntity || entity instanceof StrayEntity) {
                    return new KillAndLootTask(entity.getClass(), e -> true, new ItemTarget(Items.BONE, 1));
                }
                if (entity instanceof SpiderEntity) {
                    return new KillAndLootTask(SpiderEntity.class, e -> true, new ItemTarget(Items.STRING, 1));
                }
            }
        }

        return null;
    }

    private Task findMineTask(AltoClef mod) {
        BlockTracker blockTracker = mod.getBlockTracker();

        if (blockTracker.isTracking(net.minecraft.block.Blocks.COAL_ORE)) {
            return TaskCatalogue.getItemTask("coal", 8);
        }

        if (mod.getItemStorage().hasItem(Items.STONE_PICKAXE) &&
                blockTracker.isTracking(net.minecraft.block.Blocks.IRON_ORE)) {
            return TaskCatalogue.getItemTask("raw_iron", 8);
        }

        if (mod.getItemStorage().hasItem(Items.IRON_PICKAXE) &&
                blockTracker.isTracking(net.minecraft.block.Blocks.DIAMOND_ORE)) {
            return TaskCatalogue.getItemTask("diamond", 3);
        }

        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        _activeTask = null;
        _activeTaskKey = null;
        Debug.logInternal("PlayerExplorationTask: Stopped after " + _explorationCounter + " exploration cycles");
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof PlayerExplorationTask;
    }

    @Override
    protected String toDebugString() {
        return "Exploration (" + _phase + ")";
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return false;
    }
}
