package adris.belfegor.tasks;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.Settings;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.memory.BaseStorageMemory;
import adris.belfegor.memory.GamePlanMemory;
import adris.belfegor.memory.LocationMemory;
import adris.belfegor.memory.SpatialAwareness;
import adris.belfegor.llm.LlmAdvisor;
import adris.belfegor.tasks.construction.BuildBaseExpansionTask;
import adris.belfegor.tasks.construction.BuildBaseValidationTask;
import adris.belfegor.tasks.construction.BuildCampsiteTask;
import adris.belfegor.tasks.container.InventoryTriageTask;
import adris.belfegor.tasks.container.ShulkerInteractionTask;
import adris.belfegor.tasks.entity.ShootArrowSimpleProjectileTask;
import adris.belfegor.tasks.movement.GetToBlockTask;
import adris.belfegor.tasks.movement.PickupDroppedItemTask;
import adris.belfegor.tasks.movement.RunAwayFromHostilesTask;
import adris.belfegor.tasks.movement.TimeoutWanderTask;
import adris.belfegor.tasks.resources.CampArmoryTask;
import adris.belfegor.tasks.resources.CampStockpileTask;
import adris.belfegor.tasks.resources.EnsureToolReservesTask;
import adris.belfegor.tasks.resources.KillAndLootTask;
import adris.belfegor.tasks.resources.ToolSetTask;
import adris.belfegor.tasks.speedrun.GamePlanTask;
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
import java.util.Optional;
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
    private boolean _homeValidationComplete;

    private final TimerGame _phaseTimer = new TimerGame(30);
    private final TimerGame _foodCheckTimer = new TimerGame(10);
    private final TimerGame _craftTestTimer = new TimerGame(20);
    private final TimerGame _homeCheckTimer = new TimerGame(90);
    private final TimerGame _shulkerSortTimer = new TimerGame(20);
    private final TimerGame _stockpileTimer = new TimerGame(45);
    private final TimerGame _armoryTimer = new TimerGame(120);
    private final TimerGame _inventoryTriageTimer = new TimerGame(60);
    private final TimerGame _toolReserveTimer = new TimerGame(120);
    private boolean _inventoryTriageDone;

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
        _homeValidationComplete = false;
        _inventoryTriageDone = false;
        _homeBase = resolvePersistentHome(mod);
        _campBuildCount = estimateCompletedBaseCycles();
        mod.getModSettings().setHomeBasePosition(_homeBase);
        mod.getModSettings().setReturnHomeOnIdle(true);
        mod.getModSettings().setDefendHomeBase(true);
        mod.getModSettings().setHomeBaseDefenseRadius(32);
        Settings.save(mod.getModSettings());
        mod.getBehaviour().push();
        mod.getBehaviour().avoidBlockBreaking(pos -> isProtectedHomeStructureBlock(mod, pos));
        LocationMemory.getInstance().remember("home_base",
                _homeBase.getX(), _homeBase.getY(), _homeBase.getZ(),
                WorldHelper.getCurrentDimension().name(), "set_by_player_mode");
        rememberExpandedBasePlan();
        LocationMemory.getInstance().save();
        BaseMemory.getInstance().save();
        if (!isCoreComplete() || nextMissingExpansionType() != null) {
            _phase = Phase.HOME;
        }
        Debug.logInternal("PlayerExplorationTask: Starting autonomous exploration");
    }

    private BlockPos resolvePersistentHome(Belfegor mod) {
        String dim = WorldHelper.getCurrentDimension().name();
        BlockPos configured = mod.getModSettings().getHomeBasePosition();
        if (configured != null) {
            BaseMemory.getInstance().rememberBase(configured, dim, 8, 4, 5, "active_player_home");
            return configured;
        }
        if (mod.getPlayer() != null) {
            var nearest = BaseMemory.getInstance().nearestBase(mod.getPlayer().getBlockPos(), dim);
            // Only adopt a remembered base that is genuinely nearby. After
            // @drop home (or on a fresh world), a far-away stale base must not
            // pull the bot back to the old location; a new home is established
            // near the player instead.
            if (nearest.isPresent()
                    && nearest.get().distanceSq(mod.getPlayer().getBlockPos()) <= 48 * 48) {
                return nearest.get().center();
            }
            return mod.getPlayer().getBlockPos();
        }
        return new BlockPos(0, 64, 0);
    }

    private int estimateCompletedBaseCycles() {
        String dim = WorldHelper.getCurrentDimension().name();
        var base = BaseMemory.getInstance().nearestBase(_homeBase, dim);
        if (base.isEmpty()) return 0;
        boolean coreComplete = base.get().modules.stream()
                .anyMatch(module -> "core".equalsIgnoreCase(module.name)
                        && BaseMemory.getInstance().moduleComplete(module));
        int expansions = 0;
        for (BaseMemory.BaseModule module : base.get().modules) {
            if (BaseMemory.getInstance().moduleComplete(module)
                    && module.parent != null && !module.parent.isBlank()
                    && isManagedExpansionType(module.type)) {
                expansions++;
            }
        }
        return coreComplete ? Math.max(1, expansions + 1) : 0;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        // One @player-owned child has the lane until it finishes. Timers, LLM
        // advice, stockpiling, and phase changes must never replace a craft,
        // container transfer, or Baritone build halfway through its state.
        boolean taskJustFinished = false;
        if (_activeTask != null) {
            if (!_activeTask.stopped() && !_activeTask.isFinished(mod)) {
                LlmAdvisor.getInstance().setTaskStatus(
                        _activeTaskKey == null ? "active" : _activeTaskKey);
                return _activeTask;
            }
            taskJustFinished = true;
            if (_activeTask instanceof BuildBaseValidationTask && _activeTask.isFinished(mod)) {
                _homeValidationComplete = true;
            }
            _activeTask = null;
            _activeTaskKey = null;
        }
        if (taskJustFinished) {
            // Let the advisor chain the next goal shortly after a task ends.
            LlmAdvisor.getInstance().onTaskCompleted();
        }
        LlmAdvisor.getInstance().setTaskStatus("idle phase=" + _phase);

        if (shouldFleeDanger(mod)) {
            setDebugState("Fleeing danger!");
            LlmAdvisor.getInstance().recordAction("player_mode:flee_danger", "nearby danger or low health");
            return cacheTask("flee-hostiles", new RunAwayFromHostilesTask(24, true));
        }

        SpatialAwareness.SpatialSnapshot snapshot = SpatialAwareness.getInstance().scan(mod, 8);
        LlmAdvisor.getInstance().setPlannedAction("spatial:" + snapshot.summary);

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

        Task triageTask = maybeTriageInventory(mod);
        if (triageTask != null) {
            setDebugState("Triage inventory at home storage");
            LlmAdvisor.getInstance().recordAction("player_mode:inventory_triage",
                    "storing unused items into base storage and keeping field kit");
            return triageTask;
        }

        Task toolReserveTask = maybeMaintainToolReserves(mod);
        if (toolReserveTask != null) {
            setDebugState("Maintaining carried tool set and backup reserves");
            LlmAdvisor.getInstance().recordAction("player_mode:tool_reserves",
                    "ensuring full carried tool set plus backup set at base");
            return toolReserveTask;
        }

        Task stockpileTask = maybeMaintainCampStockpile(mod);
        if (stockpileTask != null) {
            setDebugState("Maintaining home stockpile");
            LlmAdvisor.getInstance().recordAction("player_mode:stockpile", "camp persistence requires stored surplus resources");
            return stockpileTask;
        }

        Task armoryTask = maybeMaintainArmory(mod);
        if (armoryTask != null) {
            setDebugState("Maintaining carried combat kit and armory reserves");
            LlmAdvisor.getInstance().recordAction("player_mode:armory",
                    "long-term survival gear reserve requires maintenance");
            return armoryTask;
        }

        if (maybeUseAdvisor(mod)) {
            return null;
        }

        if (!mod.getItemStorage().hasItem(
                Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE,
                Items.GOLDEN_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE)) {
            setDebugState("Getting first pickaxe");
            LlmAdvisor.getInstance().recordAction("player_mode:get_wooden_pickaxe", "required starter tool missing");
            return cacheTask("starter-pickaxe", TaskCatalogue.getItemTask(Items.WOODEN_PICKAXE, 1));
        }

        if ((_campBuildCount == 0 || _homeCheckTimer.elapsed()) && _phase != Phase.SURVIVE) {
            _phase = Phase.HOME;
            _homeValidationComplete = false;
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
        return cacheTask("explore-wander", new TimeoutWanderTask(true));
    }

    private void rememberExpandedBasePlan() {
        String dim = WorldHelper.getCurrentDimension().name();
        int radius = 8;
        int wallHeight = 4;
        int clearance = 5;
        LocationMemory.getInstance().remember("home_room_core",
                _homeBase.getX(), _homeBase.getY(), _homeBase.getZ(), dim,
                "locked player-mode home center");
        BaseMemory memory = BaseMemory.getInstance();
        memory.rememberBase(_homeBase, dim, radius, wallHeight, clearance, "set_by_player_mode");
        memory.rememberInspection(_homeBase, dim, "player_base_agenda", "plan",
                6, 0, Math.max(0, 6 - estimateCompletedBaseCycles()),
                estimateCompletedBaseCycles(), "active",
                "persistent order=core,storage,workshop,armory,farmland,mob_farm; completed modules are never downgraded");
    }

    private Task doGather(Belfegor mod) {
        Task pickupTask = findPickupTask(mod);
        if (pickupTask != null) {
            setDebugState("Picking up items");
            LlmAdvisor.getInstance().recordAction("player_mode:pickup_items", "valuable or food drops nearby");
            return cacheTask("gather-pickup:" + pickupTask, pickupTask);
        }

        Task killTask = findKillTask(mod);
        if (killTask != null) {
            setDebugState("Hunting mobs");
            LlmAdvisor.getInstance().recordAction("player_mode:hunt_mobs", "useful mob target nearby");
            return cacheTask("gather-kill:" + killTask, killTask);
        }

        Task mineTask = findMineTask(mod);
        if (mineTask != null) {
            setDebugState("Mining blocks");
            LlmAdvisor.getInstance().recordAction("player_mode:mine_blocks", "useful tracked ore/block nearby");
            return cacheTask("gather-mine:" + mineTask, mineTask);
        }

        if (_phaseTimer.elapsed()) {
            _phase = Phase.CRAFT;
            _phaseTimer.reset();
            return null;
        }

        return cacheTask("gather-wander", new TimeoutWanderTask(true));
    }

    private Task maybeMaintainCampStockpile(Belfegor mod) {
        if (!_stockpileTimer.elapsed() || _phase == Phase.SURVIVE) return null;
        _stockpileTimer.reset();
        if (_homeBase == null || mod.getPlayer() == null) return null;
        if (_campBuildCount <= 0) return null;
        if (firstMissingStockpileTarget(mod) == null) return null;

        CampStockpileTask.Profile profile = _campBuildCount >= 2
                ? CampStockpileTask.Profile.BUILD
                : CampStockpileTask.Profile.STARTER;
        return cacheTask("camp-stockpile:" + profile.name().toLowerCase(),
                new CampStockpileTask(ToolSetTask.Tier.STONE, profile));
    }

    /**
     * Player-like inventory management: near home, store the surplus the bot
     * is not using and keep the field kit it will need. Runs periodically so
     * the bot's inventory stays lean while exploring and gathering.
     */
    private Task maybeTriageInventory(Belfegor mod) {
        if (_phase == Phase.HOME || _phase == Phase.SURVIVE) return null;
        if (!isCoreComplete()) return null;
        if (!_inventoryTriageTimer.elapsed()) return null;
        _inventoryTriageTimer.reset();
        if (_homeBase == null || mod.getPlayer() == null) return null;
        if (_homeBase.getSquaredDistance(mod.getPlayer().getBlockPos()) > 40 * 40) return null;

        int occupied = 0;
        for (var stack : mod.getPlayer().getInventory().main) {
            if (!stack.isEmpty()) occupied++;
        }
        if (occupied <= 22 && _inventoryTriageDone) return null;

        ItemTarget[] surplus = surplusTargetsInInventory(mod);
        if (surplus.length == 0 && occupied <= 22) {
            _inventoryTriageDone = true;
            return null;
        }
        return cacheTask("inventory-triage",
                new InventoryTriageTask(12, InventoryTriageTask.fieldKit(),
                        InventoryTriageTask.standardSurplusTargets(), surplus));
    }

    /**
     * Keeps a full carried tool set and a backup set stored at base, like a
     * player who never leaves home without a spare pickaxe in the chest.
     */
    private Task maybeMaintainToolReserves(Belfegor mod) {
        if (_phase == Phase.HOME || _phase == Phase.SURVIVE) return null;
        if (!isCoreComplete()) return null;
        if (!_toolReserveTimer.elapsed()) return null;
        _toolReserveTimer.reset();
        if (_homeBase == null || mod.getPlayer() == null) return null;
        if (_homeBase.getSquaredDistance(mod.getPlayer().getBlockPos()) > 40 * 40) return null;
        return cacheTask("tool-reserves", new EnsureToolReservesTask(_homeBase));
    }

    private ItemTarget firstMissingStockpileTarget(Belfegor mod) {
        ItemTarget[] desired = {
                new ItemTarget(Items.COBBLESTONE, 128),
                new ItemTarget(Items.OAK_LOG, 48),
                new ItemTarget(Items.COAL, 32),
                new ItemTarget(Items.RAW_IRON, 18),
                new ItemTarget(Items.WHEAT_SEEDS, 24)
        };
        String dimension = WorldHelper.getCurrentDimension().name();
        for (ItemTarget target : desired) {
            int have = BaseStorageMemory.getInstance().availableAtBase(mod, _homeBase, dimension, target.getMatches());
            if (have < target.getTargetCount()) {
                int batch = Math.min(target.getTargetCount() - have, target.getMatches()[0].getMaxCount());
                return new ItemTarget(target.getMatches(), batch);
            }
        }
        return null;
    }

    private ItemTarget[] surplusTargetsInInventory(Belfegor mod) {
        java.util.ArrayList<ItemTarget> targets = new java.util.ArrayList<>();
        addSurplusIfCarried(mod, targets, Items.COBBLESTONE, 32);
        addSurplusIfCarried(mod, targets, Items.OAK_LOG, 16);
        addSurplusIfCarried(mod, targets, Items.COAL, 16);
        addSurplusIfCarried(mod, targets, Items.RAW_IRON, 8);
        addSurplusIfCarried(mod, targets, Items.WHEAT_SEEDS, 8);
        return targets.toArray(ItemTarget[]::new);
    }

    private void addSurplusIfCarried(Belfegor mod, java.util.List<ItemTarget> targets, Item item, int keepCarried) {
        int carried = mod.getItemStorage().getItemCountInventoryOnly(item);
        if (carried > keepCarried) {
            targets.add(new ItemTarget(item, carried - keepCarried));
        }
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
                        return cacheTask("practice-craft:" + item, craftTask);
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
            return cacheTask("goto-home:" + _homeBase, GetToBlockTask.baseAware(mod, _homeBase));
        }

        int radius = BaseMemory.getInstance().baseAt(_homeBase, WorldHelper.getCurrentDimension().name())
                .map(base -> Math.max(8, base.radius)).orElse(8);
        if (!isCoreComplete()) {
            setDebugState("Building or repairing locked core campsite radius " + radius);
            return cacheTask("camp:" + radius + ":" + _homeBase,
                    new BuildCampsiteTask(_homeBase, radius));
        }

        BuildBaseExpansionTask.RoomType missing = nextMissingExpansionType();
        if (missing != null) {
            String name = switch (missing) {
                case STORAGE -> "storage";
                case WORKSHOP -> "workshop";
                case ARMORY -> "armory";
                case FARMLAND -> "farmland";
                case MOBFARM -> "mob_farm";
                case EMPTY -> "room";
            };
            setDebugState("Building missing persistent base room " + name);
            LlmAdvisor.getInstance().recordAction("player_mode:build_base_expansion " + name,
                    "next missing room in persistent base agenda");
            return cacheTask("base-expansion:" + missing + ":" + name,
                    new BuildBaseExpansionTask(missing, name));
        }

        _campBuildCount = estimateCompletedBaseCycles();
        if (!_homeValidationComplete) {
            setDebugState("Validating completed base and remembered room routes");
            return cacheTask("base-validation", new BuildBaseValidationTask());
        }
        // Long-term play: once the base is complete, let the persistent game
        // plan drive the next goals (nether resources -> stronghold -> Ender
        // Dragon) automatically, so @player never runs out of direction.
        GamePlanMemory gamePlan = GamePlanMemory.getInstance();
        gamePlan.ensureStages();
        if (gamePlan.isActive() && gamePlan.nextStage().isPresent()) {
            setDebugState("Base complete; resuming long-term game plan");
            LlmAdvisor.getInstance().recordAction("player_mode:game_plan",
                    "base complete; resuming long-term plan at " + gamePlan.nextStage().get().id);
            return cacheTask("game-plan", new GamePlanTask());
        }
        // The bot is leaving home; refresh the triage pass so surplus gathered
        // during the build/validation window is stored before exploring again.
        _inventoryTriageDone = false;
        _phase = Phase.EXPLORE;
        return null;
    }

    private BuildBaseExpansionTask.RoomType nextMissingExpansionType() {
        BuildBaseExpansionTask.RoomType[] order = {
                BuildBaseExpansionTask.RoomType.STORAGE,
                BuildBaseExpansionTask.RoomType.WORKSHOP,
                BuildBaseExpansionTask.RoomType.ARMORY,
                BuildBaseExpansionTask.RoomType.FARMLAND,
                BuildBaseExpansionTask.RoomType.MOBFARM
        };
        for (BuildBaseExpansionTask.RoomType type : order) {
            if (!hasCompleteExpansion(type)) return type;
        }
        return null;
    }

    private boolean isCoreComplete() {
        if (_homeBase == null) return false;
        String dimension = WorldHelper.getCurrentDimension().name();
        return BaseMemory.getInstance().baseAt(_homeBase, dimension)
                .or(() -> BaseMemory.getInstance().nearestBase(_homeBase, dimension))
                .stream()
                .flatMap(base -> base.modules.stream())
                .anyMatch(module -> "core".equalsIgnoreCase(module.name)
                        && BaseMemory.getInstance().moduleComplete(module));
    }

    private boolean hasCompleteExpansion(BuildBaseExpansionTask.RoomType type) {
        if (_homeBase == null || type == null) return false;
        String dimension = WorldHelper.getCurrentDimension().name();
        String expected = type.name().toLowerCase(java.util.Locale.ROOT);
        return BaseMemory.getInstance().baseAt(_homeBase, dimension)
                .or(() -> BaseMemory.getInstance().nearestBase(_homeBase, dimension))
                .stream()
                .flatMap(base -> base.modules.stream())
                .anyMatch(module -> expected.equals(normalize(module.type))
                        && module.parent != null && !module.parent.isBlank()
                        && BaseMemory.getInstance().moduleComplete(module));
    }

    private boolean isManagedExpansionType(String type) {
        String normalized = normalize(type);
        return normalized.equals("storage")
                || normalized.equals("workshop")
                || normalized.equals("armory")
                || normalized.equals("farmland")
                || normalized.equals("mobfarm")
                || normalized.equals("mob_farm")
                || normalized.equals("empty");
    }

    private Task maybeMaintainArmory(Belfegor mod) {
        if (_phase == Phase.HOME || _phase == Phase.SURVIVE) return null;
        if (!isCoreComplete() || !hasCompleteExpansion(BuildBaseExpansionTask.RoomType.ARMORY)) return null;
        boolean kitMissing = !mod.getItemStorage().hasItem(Items.BOW)
                || mod.getItemStorage().getItemCountInventoryOnly(Items.ARROW) < 16
                || (!mod.getItemStorage().hasItem(Items.SHIELD)
                && !mod.getItemStorage().hasItemInOffhand(Items.SHIELD));
        if (!kitMissing && !_armoryTimer.elapsed()) return null;
        _armoryTimer.reset();
        return cacheTask("camp-armory", new CampArmoryTask());
    }

    private boolean isProtectedHomeStructureBlock(Belfegor mod, BlockPos pos) {
        if (_homeBase == null || pos == null || mod.getWorld() == null) return false;
        String dimension = WorldHelper.getCurrentDimension().name();
        if (BaseMemory.getInstance().isProtectedFixturePosition(pos, dimension)) return true;
        net.minecraft.block.Block block = mod.getWorld().getBlockState(pos).getBlock();
        if (block != net.minecraft.block.Blocks.COBBLESTONE
                && !(block instanceof net.minecraft.block.DoorBlock)
                && !(block instanceof net.minecraft.block.BedBlock)
                && block != net.minecraft.block.Blocks.CHEST
                && block != net.minecraft.block.Blocks.CRAFTING_TABLE
                && block != net.minecraft.block.Blocks.FURNACE) {
            return false;
        }
        return BaseMemory.getInstance().baseAt(_homeBase, dimension)
                .or(() -> BaseMemory.getInstance().nearestBase(_homeBase, dimension))
                .stream()
                .flatMap(base -> base.modules.stream())
                .filter(BaseMemory.getInstance()::moduleComplete)
                .anyMatch(module -> insideModule(module, pos));
    }

    private boolean insideModule(BaseMemory.BaseModule module, BlockPos pos) {
        return pos.getX() >= module.x && pos.getX() < module.x + Math.max(1, module.width)
                && pos.getY() >= module.y && pos.getY() < module.y + Math.max(1, module.height)
                && pos.getZ() >= module.z && pos.getZ() < module.z + Math.max(1, module.depth);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
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
            return cacheTask("survival-food:cooked_beef", foodTask);
        }
        return cacheTask("survival-food:beef", TaskCatalogue.getItemTask("beef", 1));
    }

    private Task doTools(Belfegor mod) {
        // Tool progression: complete the current tier's set first, then move up
        // the chain wood -> stone -> iron -> diamond. A complete diamond set is
        // the end goal and is "good enough" for most tasks.
        ToolSetTask.Tier completed = ToolSetTask.highestCompleteSetTier(mod);
        ToolSetTask.Tier target = completed == null ? ToolSetTask.Tier.WOOD : ToolSetTask.next(completed);
        if (target == null) {
            setDebugState("Full diamond tool set complete; tools are good enough");
            _phase = Phase.EXPLORE;
            return null;
        }
        // If the bot already holds tools of a higher tier (drops, trades),
        // jump straight to completing that tier instead of building lower tiers.
        ToolSetTask.Tier highestHeld = ToolSetTask.currentTier(mod);
        if (highestHeld.ordinal() > target.ordinal()) {
            target = highestHeld;
        }
        if (ToolSetTask.hasFullSet(mod, target)) {
            _phase = Phase.EXPLORE;
            return null;
        }
        setDebugState("Working toward full " + target.name().toLowerCase() + " tool set");
        LlmAdvisor.getInstance().recordAction("player_mode:tool_progression",
                "target full " + target.name().toLowerCase() + " set");
        return cacheTask("tool-upgrade:" + target, new ToolSetTask(target));
    }

    private Task maybeUseShulkers(Belfegor mod) {
        // Building is spatially sensitive. Do not let inventory-pressure sorting
        // steal the task lane while @player is actively returning home or
        // expanding the base; otherwise the bot can spend the whole HOME window
        // cycling shulkers instead of placing rooms/walls/farms.
        if (_phase == Phase.HOME) return null;
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
        boolean userLaneBusy = mod.getUserTaskChain() != null
                && mod.getUserTaskChain().isActive()
                && !mod.getUserTaskChain().isRunningIdleTask();
        boolean inventoryOpen = StorageHelper.isPlayerInventoryOpen();
        boolean bigCraftOpen = StorageHelper.isBigCraftingOpen();
        boolean handledContainerOpen = StorageHelper.isHandledContainerOpen();

        var decision = LlmAdvisor.getInstance().pollPlayerDecision();
        if (decision.isPresent()) {
            var result = decision.get();
            if (!result.goal().isBlank()) {
                LlmAdvisor.getInstance().setGoal(result.goal());
            }
            if (!result.chat().isBlank()) {
                mod.log("AI: " + result.chat());
            }
            if (result.valid() && !result.command().isBlank()) {
                if (taskInProgress || userLaneBusy || inventoryOpen || bigCraftOpen || handledContainerOpen) {
                    LlmAdvisor.getInstance().recordAction("llm_skipped_busy",
                            "skipped command " + result.command() + " — task active: " + taskInProgress
                                    + " inventoryOpen=" + inventoryOpen
                                    + " bigCraftOpen=" + bigCraftOpen
                                    + " handledContainerOpen=" + handledContainerOpen);
                    return false;
                }
                mod.log("AI selected next command: " + result.command());
                if (mod.getCommandExecutor().executeAdvisorSuggestion(result.command())) {
                    LlmAdvisor.getInstance().recordCommandExecuted(result.command());
                    LlmAdvisor.getInstance().recordAction("llm_execute " + result.command(), result.reason());
                    return true;
                }
                LlmAdvisor.getInstance().recordAction("llm_deferred " + result.command(),
                        "advisor command was valid but the task/inventory lane was busy");
                return false;
            }
        }

        // Don't request new LLM decisions while a task is actively running
        if (taskInProgress || userLaneBusy || inventoryOpen || bigCraftOpen || handledContainerOpen) {
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

        if (mod.getItemStorage().hasItem(Items.BOW)
                && mod.getItemStorage().hasItem(Items.ARROW, Items.SPECTRAL_ARROW, Items.TIPPED_ARROW)) {
            Optional<Entity> rangedHostile = mod.getEntityTracker().getHostiles().stream()
                    .filter(Entity::isAlive)
                    .filter(entity -> entity.distanceTo(mod.getPlayer()) >= 6
                            && entity.distanceTo(mod.getPlayer()) <= 18)
                    .min(java.util.Comparator.comparingDouble(entity -> entity.squaredDistanceTo(mod.getPlayer())));
            if (rangedHostile.isPresent()) {
                LlmAdvisor.getInstance().recordAction("player_mode:ranged_defense",
                        "using bow against hostile at safer standoff distance");
                return new ShootArrowSimpleProjectileTask(rangedHostile.get());
            }
        }

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
        mod.getBehaviour().pop();
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
