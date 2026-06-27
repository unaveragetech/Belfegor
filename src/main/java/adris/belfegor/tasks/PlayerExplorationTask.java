package adris.belfegor.tasks;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.Settings;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.memory.LocationMemory;
import adris.belfegor.llm.LlmAdvisor;
import adris.belfegor.tasks.construction.BuildCampsiteTask;
import adris.belfegor.tasks.container.ShulkerInteractionTask;
import adris.belfegor.tasks.movement.GetToBlockTask;
import adris.belfegor.tasks.movement.PickupDroppedItemTask;
import adris.belfegor.tasks.movement.TimeoutWanderTask;
import adris.belfegor.tasks.resources.KillAndLootTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.trackers.BlockTracker;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.helpers.WorldHelper;
import adris.belfegor.util.time.TimerGame;
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
    protected void onStart(Belfegor mod) {
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
    protected Task onTick(Belfegor mod) {
        if (shouldFleeDanger(mod)) {
            setDebugState("Fleeing danger!");
            LlmAdvisor.getInstance().recordAction("player_mode:flee_danger", "nearby danger or low health");
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
            LlmAdvisor.getInstance().recordAction("player_mode:shulker_sort", "inventory pressure triggered shulker management");
            return shulkerTask;
        }

        if (maybeUseAdvisor(mod)) {
            return null;
        }

        if (!mod.getItemStorage().hasItem(Items.WOODEN_PICKAXE)) {
            setDebugState("Getting first pickaxe");
            LlmAdvisor.getInstance().recordAction("player_mode:get_wooden_pickaxe", "required starter tool missing");
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

    private Task doExplore(Belfegor mod) {
        _explorationCounter++;

        if (_phaseTimer.elapsed() || _explorationCounter > 20) {
            _phase = Phase.GATHER;
            _phaseTimer.reset();
            _explorationCounter = 0;
            return null;
        }

        setDebugState("Exploring #" + _explorationCounter);
        LlmAdvisor.getInstance().setPlannedAction("wander/explore nearby terrain");
        return new TimeoutWanderTask(true);
    }

    private Task doGather(Belfegor mod) {
        Task pickupTask = findPickupTask(mod);
        if (pickupTask != null) {
            setDebugState("Picking up items");
            LlmAdvisor.getInstance().recordAction("player_mode:pickup_items", "valuable or food drops nearby");
            return pickupTask;
        }

        Task killTask = findKillTask(mod);
        if (killTask != null) {
            setDebugState("Hunting mobs");
            LlmAdvisor.getInstance().recordAction("player_mode:hunt_mobs", "useful mob target nearby");
            return killTask;
        }

        Task mineTask = findMineTask(mod);
        if (mineTask != null) {
            setDebugState("Mining blocks");
            LlmAdvisor.getInstance().recordAction("player_mode:mine_blocks", "useful tracked ore/block nearby");
            return mineTask;
        }

        if (_phaseTimer.elapsed()) {
            _phase = Phase.CRAFT;
            _phaseTimer.reset();
            return null;
        }

        return new TimeoutWanderTask(true);
    }

    private Task doCraft(Belfegor mod) {
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
                        LlmAdvisor.getInstance().recordAction("player_mode:practice_craft " + item,
                                "craft target selected from curated practice list");
                        return craftTask;
                    }
                }
            }
        }

        _phase = Phase.TOOLS;
        return null;
    }

    private Task doHome(Belfegor mod) {
        if (_homeBase == null) {
            _homeBase = mod.getModSettings().getHomeBasePosition();
        }
        if (_homeBase != null && mod.getPlayer() != null
                && _homeBase.getSquaredDistance(mod.getPlayer().getBlockPos()) > 48 * 48) {
            setDebugState("Returning to home base");
            LlmAdvisor.getInstance().recordAction("player_mode:return_home", "too far from home base");
            return cacheTask("goto-home:" + _homeBase, new GetToBlockTask(_homeBase));
        }

        if (!mod.getItemStorage().hasItem(Items.CRAFTING_TABLE)) {
            setDebugState("Preparing campsite crafting table");
            LlmAdvisor.getInstance().recordAction("player_mode:prepare_crafting_table", "home base lacks crafting table");
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
        if (!mod.getItemStorage().hasItem(Items.WHEAT_SEEDS)) {
            Task seeds = TaskCatalogue.getItemTask("wheat_seeds", 8);
            if (seeds != null) {
                setDebugState("Preparing starter farm seeds");
                return seeds;
            }
        }
        if (!mod.getItemStorage().hasItem(Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE,
                Items.GOLDEN_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE)) {
            Task hoe = TaskCatalogue.getItemTask("wooden_hoe", 1);
            if (hoe != null) {
                setDebugState("Preparing starter farm hoe");
                return hoe;
            }
        }

        int radius = Math.min(10, 4 + _campBuildCount);
        Task build = cacheTask("camp:" + radius + ":" + _homeBase, new BuildCampsiteTask(_homeBase, radius));
        if (!build.isFinished(mod)) {
            setDebugState("Building/expanding home campsite radius " + radius);
            LlmAdvisor.getInstance().recordAction("player_mode:build_campsite radius=" + radius,
                    "expanding remembered home base");
            return build;
        }

        _campBuildCount++;
        _phase = Phase.EXPLORE;
        return null;
    }

    private Task doSurvive(Belfegor mod) {
        if (!isHungry(mod)) {
            _phase = Phase.EXPLORE;
            return null;
        }

        setDebugState("Getting food");
        LlmAdvisor.getInstance().recordAction("player_mode:get_food", "hunger below threshold");
        Task foodTask = TaskCatalogue.getItemTask("cooked_beef", 1);
        if (foodTask != null) {
            return foodTask;
        }
        return TaskCatalogue.getItemTask("beef", 1);
    }

    private Task doTools(Belfegor mod) {
        if (!mod.getItemStorage().hasItem(Items.STONE_PICKAXE) && mod.getItemStorage().hasItem(Items.WOODEN_PICKAXE)) {
            setDebugState("Upgrading to stone pickaxe");
            LlmAdvisor.getInstance().recordAction("player_mode:upgrade_stone_pickaxe", "wooden pickaxe exists");
            return TaskCatalogue.getItemTask(Items.STONE_PICKAXE, 1);
        }
        if (!mod.getItemStorage().hasItem(Items.IRON_PICKAXE) && mod.getItemStorage().hasItem(Items.STONE_PICKAXE)) {
            setDebugState("Upgrading to iron pickaxe");
            LlmAdvisor.getInstance().recordAction("player_mode:upgrade_iron_pickaxe", "stone pickaxe exists");
            return TaskCatalogue.getItemTask(Items.IRON_PICKAXE, 1);
        }

        _phase = Phase.EXPLORE;
        return null;
    }

    private Task maybeUseShulkers(Belfegor mod) {
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

    private boolean maybeUseAdvisor(Belfegor mod) {
        // Don't interrupt an actively running task (e.g. inventory screen open,
        // crafting in progress, movement pathing). Only poll for decisions that
        // arrived BEFORE we started this task.
        boolean taskInProgress = _activeTask != null && !_activeTask.stopped();
        boolean inventoryOpen = StorageHelper.isPlayerInventoryOpen();
        boolean bigCraftOpen = StorageHelper.isBigCraftingOpen();

        var decision = LlmAdvisor.getInstance().pollDecision();
        if (decision.isPresent()) {
            var result = decision.get();
            if (!result.goal().isBlank()) {
                LlmAdvisor.getInstance().setGoal(result.goal());
            }
            if (!result.chat().isBlank()) {
                mod.log("AI: " + result.chat());
            }
            if (result.valid() && !result.command().isBlank()) {
                if (taskInProgress || inventoryOpen || bigCraftOpen) {
                    LlmAdvisor.getInstance().recordAction("llm_skipped_busy",
                            "skipped command " + result.command() + " — task active: " + taskInProgress
                                    + " inventoryOpen=" + inventoryOpen + " bigCraftOpen=" + bigCraftOpen);
                    return false;
                }
                mod.log("AI selected next command: " + result.command());
                LlmAdvisor.getInstance().recordAction("llm_execute " + result.command(), result.reason());
                mod.getCommandExecutor().execute(result.command());
                return true;
            }
        }

        // Don't request new LLM decisions while a task is actively running
        if (taskInProgress || inventoryOpen || bigCraftOpen) {
            return false;
        }

        String fallback = switch (_phase) {
            case EXPLORE -> "wander/explore nearby terrain";
            case GATHER -> "pick up items, hunt useful mobs, or mine tracked resources";
            case CRAFT -> "practice useful starter crafts";
            case SURVIVE -> "get/eat food and avoid danger";
            case TOOLS -> "upgrade tools";
            case HOME -> "return home and build/expand campsite";
        };
        return LlmAdvisor.getInstance().requestAutomaticPlayerDecision(mod, _phase.name(), fallback);
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

    private boolean canCraftItem(Belfegor mod, String itemName) {
        if (TaskCatalogue.taskExists(itemName)) {
            return !mod.getItemStorage().hasItem(
                    net.minecraft.registry.Registries.ITEM.get(
                            net.minecraft.util.Identifier.of("minecraft", itemName)));
        }
        return false;
    }

    private boolean isHungry(Belfegor mod) {
        if (mod.getPlayer() == null) return false;
        return mod.getPlayer().getHungerManager().getFoodLevel() < 14;
    }

    private boolean shouldFleeDanger(Belfegor mod) {
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

    private Task findPickupTask(Belfegor mod) {
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

    private Task findKillTask(Belfegor mod) {
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

    private Task findMineTask(Belfegor mod) {
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
    protected void onStop(Belfegor mod, Task interruptTask) {
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
    public boolean isFinished(Belfegor mod) {
        return false;
    }
}
