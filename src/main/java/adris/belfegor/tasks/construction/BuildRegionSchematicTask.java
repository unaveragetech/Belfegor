package adris.belfegor.tasks.construction;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.debug.DebugLogger;
import adris.belfegor.eventbus.EventBus;
import adris.belfegor.eventbus.Subscription;
import adris.belfegor.eventbus.events.BlockBreakingEvent;
import adris.belfegor.eventbus.events.BlockBrokenEvent;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.memory.ConstructionBreakGuard;
import adris.belfegor.tasks.container.PickupFromContainerTask;
import adris.belfegor.tasks.movement.GetToBlockTask;
import adris.belfegor.tasks.resources.GetBuildingMaterialsTask;
import adris.belfegor.tasksystem.ITaskRequiresGrounded;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.ExternalAutomationGuard;
import adris.belfegor.util.helpers.NativeBaritoneHelper;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.helpers.WorldHelper;
import baritone.api.schematic.AbstractSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.utils.input.Input;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds a whole blueprint region with Baritone's native builder process.
 *
 * This exists because feeding construction one block at a time creates two bad
 * goals: "walk to one placement" and "build the structure". For rooms, halls,
 * floors, walls, and roofs we want one coherent Baritone build goal with a
 * schematic containing every desired block.
 */
public class BuildRegionSchematicTask extends Task implements ITaskRequiresGrounded {

    private static final int BLUEPRINT_SCAN_INTERVAL_TICKS = 10;
    private static final int BUILDER_RELAUNCH_COOLDOWN_TICKS = 40;
    private static final int CONTROLLED_FINISH_THRESHOLD = 8;
    private static final long SUPPLY_LOG_INTERVAL_MS = 5000L;
    private static final long MISSING_DETAIL_LOG_INTERVAL_MS = 2000L;

    private final String _name;
    private final LinkedHashMap<BlockPos, Block[]> _targets;
    private final boolean _allowAnyThrowaway;
    private int _minimumStandY = Integer.MIN_VALUE;
    private BlockPos _protectedFloorCenter;
    private int _protectedFloorRadius = -1;
    private int _protectedFloorY = Integer.MIN_VALUE;
    private BlockPos _origin;
    private BlockPos _max;
    private Task _materialTask;
    private int _lastMissing = Integer.MAX_VALUE;
    private int _noProgressTicks;
    private Task _manualFallbackTask;
    private Task _supplyTask;
    private boolean _builderLaunched;
    private boolean _manualFallbackLatched;
    private boolean _waitingForSupply;
    private boolean _completed;
    private int _cachedMissing;
    private ItemTarget _cachedNeeded;
    private int _lastBlueprintScanTick;
    private int _nextBuilderLaunchTick;
    private long _lastSupplyLogMs;
    private String _lastSupplyLogKey;
    private long _lastMissingDetailLogMs;
    private String _lastMissingDetailSignature;
    private Subscription<BlockBreakingEvent> _protectedFloorBreakingSubscription;
    private Subscription<BlockBrokenEvent> _protectedFloorBrokenSubscription;
    private long _lastFloorGuardLogMs;
    private long _constructionBreakGuardToken;
    private boolean _firstTickTracePending;
    private ExternalAutomationGuard.Lease _externalPrinterLease;

    public BuildRegionSchematicTask(String name, List<BlockPos> targets, Block... blocks) {
        this(name, toMap(targets, blocks), false);
    }

    public BuildRegionSchematicTask(String name, List<BlockPos> targets, boolean allowAnyThrowaway, Block... blocks) {
        this(name, toMap(targets, blocks), allowAnyThrowaway);
    }

    public BuildRegionSchematicTask(String name, Map<BlockPos, Block[]> targets, boolean allowAnyThrowaway) {
        _name = name == null || name.isBlank() ? "region" : name;
        _targets = new LinkedHashMap<>(targets);
        _allowAnyThrowaway = allowAnyThrowaway;
    }

    public BuildRegionSchematicTask withMinimumStandY(int minimumStandY) {
        _minimumStandY = minimumStandY;
        return this;
    }

    /** Protects an established build floor while Baritone approaches targets. */
    public BuildRegionSchematicTask withProtectedFloor(BlockPos center, int radius, int floorY) {
        _protectedFloorCenter = center == null ? null : center.toImmutable();
        _protectedFloorRadius = Math.max(0, radius);
        _protectedFloorY = floorY;
        return this;
    }

    @Override
    protected void onStart(Belfegor mod) {
        calculateBounds();
        _lastMissing = Integer.MAX_VALUE;
        _noProgressTicks = 0;
        _manualFallbackTask = null;
        _supplyTask = null;
        _builderLaunched = false;
        _manualFallbackLatched = false;
        _waitingForSupply = false;
        _completed = _targets.isEmpty();
        _cachedMissing = _targets.size();
        _cachedNeeded = null;
        _lastBlueprintScanTick = Integer.MIN_VALUE;
        _nextBuilderLaunchTick = Integer.MIN_VALUE;
        _lastSupplyLogMs = 0L;
        _lastSupplyLogKey = "";
        _lastMissingDetailLogMs = 0L;
        _lastMissingDetailSignature = "";
        _lastFloorGuardLogMs = 0L;
        _constructionBreakGuardToken = 0L;
        _firstTickTracePending = true;
        _externalPrinterLease = ExternalAutomationGuard.suspendLitematicaPrinter(
                "build-region-" + _name);
        mod.getBehaviour().push();
        mod.getBehaviour().setAutoMLG(false);
        mod.getBehaviour().setAllowDiagonalAscend(false);
        mod.getBehaviour().forceUseTool((state, stack) -> stack != null && stack.isSuitableFor(state));
        // A region builder may path through its own finished wall to reach the
        // last target. Once a blueprint position is correct it is immutable for
        // this task; wrong blocks remain breakable so they can still be repaired.
        mod.getBehaviour().avoidBlockBreaking(pos -> {
            Block[] desired = _targets.get(pos);
            return (desired != null && isTargetDone(mod, pos, desired))
                    || isProtectedFloorBlock(mod, pos);
        });
        subscribeProtectedFloorGuard(mod);
        // The behaviour predicate above guides newly calculated Baritone
        // paths. This low-level guard also covers stale paths or another mod's
        // queued attack: established floor and every already-correct blueprint
        // cell are immutable until this region task releases ownership.
        _constructionBreakGuardToken = ConstructionBreakGuard.register(
                "build-region-" + _name,
                pos -> isInsideProtectedFloor(pos) || isCompletedTargetBlock(mod, pos));
        mod.getClientBaritone().getInputOverrideHandler()
                .setInputForceState(Input.CLICK_LEFT, false);
        if (mod.getController() != null) {
            mod.getController().cancelBlockBreaking();
        }
        mod.getClientBaritone().getBuilderProcess().onLostControl();
        NativeBaritoneHelper.selectBox(mod, _origin, _max, "build-region-" + _name);
        NativeBaritoneHelper.logProcessState(mod, "build-region-start-" + _name);
        refreshBlueprintScan(mod, true);
        DebugLogger.getInstance().log("BUILD-REGION", "start name=" + _name
                + " targets=" + _targets.size()
                + " allowThrowaway=" + _allowAnyThrowaway);
    }

    @Override
    protected Task onTick(Belfegor mod) {
        boolean traceFirstTick = _firstTickTracePending;
        if (traceFirstTick) {
            DebugLogger.getInstance().logImmediate("BUILD-REGION",
                    "first-tick-enter name=" + _name
                            + " cachedMissing=" + _cachedMissing
                            + " cachedNeeded=" + _cachedNeeded
                            + " builderActive=" + mod.getClientBaritone().getBuilderProcess().isActive());
        }
        refreshBlueprintScan(mod, false);
        int missing = _cachedMissing;
        if (missing <= 0) {
            _firstTickTracePending = false;
            _completed = true;
            return null;
        }

        Task supply = ensureWorkingSupplies(mod);
        if (traceFirstTick) {
            DebugLogger.getInstance().logImmediate("BUILD-REGION",
                    "first-tick-supply name=" + _name
                            + " missing=" + missing
                            + " cachedNeeded=" + _cachedNeeded
                            + " waiting=" + _waitingForSupply
                            + " supplyTask=" + (supply == null ? "null" : supply.toString()));
            _firstTickTracePending = false;
        }
        if (supply != null || _waitingForSupply) {
            if (mod.getClientBaritone().getBuilderProcess().isActive()) {
                mod.getClientBaritone().getBuilderProcess().onLostControl();
                _builderLaunched = false;
            }
            if (supply != null) return supply;
            return null;
        }
        trackProgress(mod, missing);

        if (_manualFallbackTask != null && !_manualFallbackTask.stopped() && !_manualFallbackTask.isFinished(mod)) {
            setDebugState("Manual fallback for " + _name + " missing=" + missing + "/" + _targets.size());
            return _manualFallbackTask;
        }
        if (_manualFallbackTask != null) {
            // A direct placement may complete between the ten-tick blueprint
            // scans. Refresh before choosing another target or relaunching the
            // native builder, otherwise one stale missing count can briefly
            // restart the process that the controlled finalizer just stopped.
            refreshBlueprintScan(mod, true);
            missing = _cachedMissing;
            if (missing <= 0) {
                _completed = true;
                _manualFallbackTask = null;
                return null;
            }
        }
        _manualFallbackTask = null;

        boolean builderActive = mod.getClientBaritone().getBuilderProcess().isActive();
        if (!_manualFallbackLatched && _builderLaunched && _noProgressTicks >= 100) {
            _manualFallbackLatched = true;
            if (builderActive) {
                mod.getClientBaritone().getBuilderProcess().onLostControl();
                builderActive = false;
            }
            _builderLaunched = false;
            DebugLogger.getInstance().log("BUILD-REGION",
                    "manual-mode-latched name=" + _name
                            + " missing=" + missing + "/" + _targets.size()
                            + " noProgressTicks=" + _noProgressTicks);
        }
        if (missing <= CONTROLLED_FINISH_THRESHOLD || _manualFallbackLatched) {
            if (builderActive) {
                mod.getClientBaritone().getBuilderProcess().onLostControl();
                _builderLaunched = false;
            }
            Task fallback = createManualFallback(mod, missing);
            if (fallback != null) {
                _manualFallbackTask = fallback;
                _noProgressTicks = 0;
                setDebugState((_manualFallbackLatched ? "Controlled manual build for " : "Controlled final repair for ") + _name
                        + " missing=" + missing + "/" + _targets.size());
                return _manualFallbackTask;
            }
        }
        if ((_builderLaunched && !builderActive && missing <= 8) || _noProgressTicks >= 900) {
            Task fallback = createManualFallback(mod, missing);
            if (fallback != null) {
                _manualFallbackTask = fallback;
                _noProgressTicks = 0;
                return _manualFallbackTask;
            }
        }

        if (_allowAnyThrowaway && StorageHelper.getBuildingMaterialCount(mod) < Math.min(missing, 160)) {
            if (_materialTask != null && _materialTask.isActive() && !_materialTask.isFinished(mod)) {
                return _materialTask;
            }
            _materialTask = new GetBuildingMaterialsTask(Math.min(Math.max(missing, 32), 160));
            setDebugState("Collecting structure materials for " + _name);
            return _materialTask;
        }

        if (!builderActive) {
            int nowTick = currentTick(mod);
            if (_builderLaunched && nowTick < _nextBuilderLaunchTick) {
                setDebugState("Waiting before retrying Baritone builder for " + _name
                        + " missing=" + missing + "/" + _targets.size());
                return null;
            }
            Debug.logInternal("Run region schematic build: " + _name + " targets=" + _targets.size());
            DebugLogger.getInstance().log("BUILD-REGION", "launch-builder name=" + _name
                    + " origin=" + _origin.toShortString()
                    + " max=" + _max.toShortString()
                    + " missing=" + missing + "/" + _targets.size());
            NativeBaritoneHelper.logProcessState(mod, "build-region-before-native-builder-" + _name);
            ISchematic schematic = new RegionSchematic(mod);
            mod.getClientBaritone().getBuilderProcess().build(_name, schematic, _origin);
            _builderLaunched = true;
            _nextBuilderLaunchTick = nowTick + BUILDER_RELAUNCH_COOLDOWN_TICKS;
        }
        setDebugState("Baritone building " + _name + " missing=" + missing + "/" + _targets.size());
        return null;
    }

    private void trackProgress(Belfegor mod, int missing) {
        if (missing < _lastMissing) {
            Debug.logInternal("Region schematic progress: " + _name
                    + " missing=" + missing + "/" + _targets.size());
            _lastMissing = missing;
            _noProgressTicks = 0;
            return;
        }
        if (missing == _lastMissing) {
            _noProgressTicks++;
            if (_noProgressTicks == 100 || _noProgressTicks % 300 == 0) {
                Debug.logInternal("Region schematic no-progress: " + _name
                        + " missing=" + missing + "/" + _targets.size()
                        + " ticks=" + _noProgressTicks
                        + " builderActive=" + mod.getClientBaritone().getBuilderProcess().isActive());
                DebugLogger.getInstance().log("BUILD-REGION", "no-progress name=" + _name
                        + " missing=" + missing + "/" + _targets.size()
                        + " ticks=" + _noProgressTicks
                        + " builderActive=" + mod.getClientBaritone().getBuilderProcess().isActive());
            }
            if (_noProgressTicks == 600) {
                Debug.logInternal("Region schematic watchdog reset: " + _name
                        + " missing=" + missing + "/" + _targets.size());
                DebugLogger.getInstance().log("BUILD-REGION", "watchdog-reset name=" + _name
                        + " missing=" + missing + "/" + _targets.size());
                mod.getClientBaritone().getBuilderProcess().onLostControl();
                _builderLaunched = false;
            }
        } else {
            _lastMissing = missing;
            _noProgressTicks = 0;
        }
    }

    private Task createManualFallback(Belfegor mod, int missing) {
        Map.Entry<BlockPos, Block[]> entry = closestMissing(mod);
        if (entry == null) return null;
        BlockPos pos = entry.getKey();
        Block current = mod.getWorld().getBlockState(pos).getBlock();
        DebugLogger.getInstance().log("BUILD-REGION", "manual-fallback name=" + _name
                + " pos=" + pos.toShortString()
                + " current=" + current
                + " missing=" + missing + "/" + _targets.size());
        mod.getClientBaritone().getBuilderProcess().onLostControl();
        BlockPos approach = approachPositionForManualFallback(mod, pos);
        if (mod.getPlayer() != null && shouldMoveToManualApproach(mod, approach)) {
            return new GetToBlockTask(approach).withoutBreaking();
        }
        if (current != Blocks.AIR && current != Blocks.WATER && !isTargetDone(mod, pos, entry.getValue())) {
            return new DestroyBlockTask(pos);
        }
        return new PlaceBlockTask(pos, entry.getValue(), _allowAnyThrowaway, true,
                !_allowAnyThrowaway, _minimumStandY);
    }

    private boolean shouldMoveToManualApproach(Belfegor mod, BlockPos approach) {
        return mod.getPlayer() != null && !mod.getPlayer().getBlockPos().equals(approach);
    }

    private Task ensureWorkingSupplies(Belfegor mod) {
        ItemTarget needed = _cachedNeeded;
        if (needed == null) {
            _waitingForSupply = false;
            _supplyTask = null;
            return null;
        }

        int inventoryCount = mod.getItemStorage().getItemCountInventoryOnly(needed.getMatches());
        if (inventoryCount >= Math.min(needed.getTargetCount(), 32)) {
            _waitingForSupply = false;
            _supplyTask = null;
            return null;
        }

        _waitingForSupply = true;
        if (_supplyTask != null && !_supplyTask.stopped() && !_supplyTask.isFinished(mod)) {
            setDebugState("Acquiring schematic supplies for " + _name + ": " + needed);
            return _supplyTask;
        }
        _supplyTask = null;

        Optional<BlockPos> staging = findConstructionStaging(mod);
        if (staging.isPresent()) {
            int cachedStagingCount = mod.getItemStorage().getContainerAtPosition(staging.get())
                    .map(cache -> cache.getItemCount(needed.getMatches()))
                    .orElse(0);
            int openStagingCount = countOpenStagingContainerItems(mod, staging.get(), needed.getMatches());
            int stagingCount = Math.max(cachedStagingCount, openStagingCount);
            if (stagingCount > 0) {
                ItemTarget workingBatch = new ItemTarget(needed,
                        Math.min(needed.getTargetCount(), 128));
                logSupplyState("withdraw|" + staging.get().toShortString() + "|" + workingBatch,
                        "supply-withdraw name=" + _name
                                + " staging=" + staging.get().toShortString()
                                + " target=" + workingBatch
                                + " inv=" + inventoryCount
                                + " staged=" + stagingCount);
                _supplyTask = new PickupFromContainerTask(staging.get(), workingBatch);
                setDebugState("Withdrawing staged schematic supplies for " + _name + ": " + workingBatch);
                return _supplyTask;
            }
            logSupplyState("empty|" + staging.get().toShortString() + "|" + needed,
                    "supply-staging-lacks name=" + _name
                            + " staging=" + staging.get().toShortString()
                            + " target=" + needed
                            + " inv=" + inventoryCount
                            + " staged=0 note=collecting a bounded working batch before builder restart");
        }

        int workingCount = Math.max(1, Math.min(needed.getTargetCount(), 64));
        ItemTarget workingBatch = new ItemTarget(needed, workingCount);
        Task gather = TaskCatalogue.getItemTask(workingBatch);
        if (gather != null && gather != this) {
            logSupplyState("collect|" + workingBatch,
                    "supply-collect name=" + _name
                            + " target=" + workingBatch
                            + " inv=" + inventoryCount
                            + " missingBlueprint=" + _cachedMissing);
            _supplyTask = gather;
            setDebugState("Collecting bounded schematic working batch " + workingBatch);
            return _supplyTask;
        }

        logSupplyState("blocked|" + needed,
                "supply-blocked name=" + _name
                        + " target=" + needed
                        + " inv=" + inventoryCount
                        + " note=no catalogue task or staged material; builder remains paused");
        setDebugState("Paused: schematic material is not sourceable or staged: " + needed);
        return null;
    }

    private void logSupplyState(String key, String message) {
        long now = System.currentTimeMillis();
        if (!key.equals(_lastSupplyLogKey) || now - _lastSupplyLogMs >= SUPPLY_LOG_INTERVAL_MS) {
            _lastSupplyLogKey = key;
            _lastSupplyLogMs = now;
            DebugLogger.getInstance().log("BUILD-REGION", message);
        }
    }

    private int countOpenStagingContainerItems(Belfegor mod, BlockPos staging, Item[] matches) {
        if (mod.getPlayer() == null) return 0;
        if (mod.getItemStorage().getLastBlockPosInteraction()
                .filter(staging::equals)
                .isEmpty()) {
            return 0;
        }
        ScreenHandler handler = MinecraftClient.getInstance().player == null
                ? null
                : MinecraftClient.getInstance().player.currentScreenHandler;
        if (handler == null || handler.slots.size() <= 36) return 0;
        int containerSlots = handler.slots.size() - 36;
        int total = 0;
        List<Item> matchList = Arrays.asList(matches);
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack == null || stack.isEmpty()) continue;
            if (matchList.contains(stack.getItem())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private void refreshBlueprintScan(Belfegor mod, boolean force) {
        int tick = currentTick(mod);
        if (!force && tick - _lastBlueprintScanTick < BLUEPRINT_SCAN_INTERVAL_TICKS) {
            return;
        }
        _lastBlueprintScanTick = tick;
        LinkedHashMap<Item, Integer> counts = new LinkedHashMap<>();
        int missing = 0;
        for (Map.Entry<BlockPos, Block[]> entry : _targets.entrySet()) {
            if (isTargetDone(mod, entry.getKey(), entry.getValue())) continue;
            missing++;
            Block[] desired = entry.getValue();
            if (desired == null || desired.length == 0 || desired[0] == null) continue;
            Item item = desired[0].asItem();
            if (item == Items.AIR) continue;
            counts.put(item, counts.getOrDefault(item, 0) + 1);
        }
        Item best = null;
        int bestCount = 0;
        for (Map.Entry<Item, Integer> entry : counts.entrySet()) {
            int inventory = mod.getItemStorage().getItemCountInventoryOnly(entry.getKey());
            int stillNeeded = Math.max(0, entry.getValue() - inventory);
            if (stillNeeded > bestCount) {
                best = entry.getKey();
                bestCount = stillNeeded;
            }
        }
        _cachedMissing = missing;
        _completed = missing <= 0;
        _cachedNeeded = best == null || bestCount <= 0 ? null : new ItemTarget(best, bestCount);
        logMissingDetails(mod, missing);
    }

    private void logMissingDetails(Belfegor mod, int missing) {
        if (missing <= 0 || missing > CONTROLLED_FINISH_THRESHOLD) return;
        StringBuilder details = new StringBuilder();
        for (Map.Entry<BlockPos, Block[]> entry : _targets.entrySet()) {
            if (isTargetDone(mod, entry.getKey(), entry.getValue())) continue;
            if (!details.isEmpty()) details.append(" | ");
            Block current = mod.getWorld().getBlockState(entry.getKey()).getBlock();
            details.append(entry.getKey().toShortString())
                    .append(" current=").append(current)
                    .append(" expected=").append(Arrays.toString(entry.getValue()));
        }
        String signature = details.toString();
        long now = System.currentTimeMillis();
        if (!signature.equals(_lastMissingDetailSignature)
                && now - _lastMissingDetailLogMs >= MISSING_DETAIL_LOG_INTERVAL_MS) {
            _lastMissingDetailSignature = signature;
            _lastMissingDetailLogMs = now;
            DebugLogger.getInstance().log("BUILD-REGION",
                    "missing-detail name=" + _name + " count=" + missing + " targets=" + signature);
        }
    }

    private int currentTick(Belfegor mod) {
        return mod.getPlayer() == null ? WorldHelper.getTicks() : mod.getPlayer().age;
    }

    private Optional<BlockPos> findConstructionStaging(Belfegor mod) {
        if (mod.getPlayer() == null) return Optional.empty();
        String dimension = WorldHelper.getCurrentDimension().name();
        Optional<BlockPos> remembered = BaseMemory.getInstance()
                .findNearestModule(mod.getPlayer().getBlockPos(), dimension, "construction_staging_chest")
                .or(() -> BaseMemory.getInstance()
                        .findNearestModule(mod.getPlayer().getBlockPos(), dimension, "construction_staging"))
                .map(module -> new BlockPos(module.x, module.y, module.z))
                .filter(pos -> mod.getWorld().getBlockState(pos).getBlock() == Blocks.CHEST);
        if (remembered.isPresent()) return remembered;

        return BaseMemory.getInstance()
                .nearestBase(mod.getPlayer().getBlockPos(), dimension)
                .map(base -> base.center().add(2, 0, -2))
                .filter(pos -> mod.getWorld().getBlockState(pos).getBlock() == Blocks.CHEST);
    }

    private BlockPos approachPositionForManualFallback(Belfegor mod, BlockPos target) {
        BlockPos player = mod.getPlayer() == null ? target : mod.getPlayer().getBlockPos();
        Direction[] directions = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Direction direction : directions) {
            for (int yOffset = 1; yOffset >= -3; yOffset--) {
                BlockPos stand = target.offset(direction).add(0, yOffset, 0);
                if (stand.getY() < _minimumStandY) continue;
                if (_targets.containsKey(stand)) continue;
                if (!WorldHelper.isSolid(mod, stand.down())) continue;
                if (!mod.getWorld().getBlockState(stand).isAir()) continue;
                if (!mod.getWorld().getBlockState(stand.up()).isAir()) continue;
                Vec3d eye = Vec3d.ofCenter(stand).add(0, 1.62, 0);
                if (eye.squaredDistanceTo(Vec3d.ofCenter(target)) > 20.25) continue;
                double distance = stand.getSquaredDistance(player);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = stand;
                }
            }
        }
        return best == null ? target.up() : best;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        if (_externalPrinterLease != null) {
            _externalPrinterLease.close();
            _externalPrinterLease = null;
        }
        ConstructionBreakGuard.unregister(_constructionBreakGuardToken);
        _constructionBreakGuardToken = 0L;
        EventBus.unsubscribe(_protectedFloorBreakingSubscription);
        EventBus.unsubscribe(_protectedFloorBrokenSubscription);
        _protectedFloorBreakingSubscription = null;
        _protectedFloorBrokenSubscription = null;
        mod.getBehaviour().pop();
        mod.getClientBaritone().getBuilderProcess().onLostControl();
        NativeBaritoneHelper.clearSelections(mod, "build-region-stop-" + _name);
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return _completed;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BuildRegionSchematicTask task
                && task._name.equals(_name)
                && task._targets.equals(_targets)
                && task._minimumStandY == _minimumStandY
                && java.util.Objects.equals(task._protectedFloorCenter, _protectedFloorCenter)
                && task._protectedFloorRadius == _protectedFloorRadius
                && task._protectedFloorY == _protectedFloorY
                && task._allowAnyThrowaway == _allowAnyThrowaway;
    }

    @Override
    protected String toDebugString() {
        return "Build region schematic " + _name + " targets=" + _targets.size();
    }

    private boolean isTargetDone(Belfegor mod, BlockPos pos, Block[] desired) {
        if (BaseMemory.getInstance().isProtectedFixturePosition(pos, WorldHelper.getCurrentDimension().name())) {
            return true;
        }
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        if (isNonFarmFloorRegion() && isAcceptableNaturalFloor(mod, pos, block)) {
            return true;
        }
        if (_allowAnyThrowaway) {
            return WorldHelper.isSolid(mod, pos);
        }
        return Arrays.asList(desired).contains(block);
    }

    private boolean isNonFarmFloorRegion() {
        String normalized = _name.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("floor") && !normalized.contains("farm");
    }

    private boolean isAcceptableNaturalFloor(Belfegor mod, BlockPos pos, Block block) {
        if (block == Blocks.AIR || block == Blocks.WATER || block == Blocks.LAVA) return false;
        if (block == Blocks.GRASS_BLOCK
                || block == Blocks.DIRT
                || block == Blocks.COARSE_DIRT
                || block == Blocks.PODZOL
                || block == Blocks.ROOTED_DIRT
                || block == Blocks.FARMLAND
                || block == Blocks.COBBLESTONE) {
            return true;
        }
        return WorldHelper.isSolid(mod, pos);
    }

    private Map.Entry<BlockPos, Block[]> closestMissing(Belfegor mod) {
        Map.Entry<BlockPos, Block[]> best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        BlockPos player = mod.getPlayer() == null ? _origin : mod.getPlayer().getBlockPos();
        for (Map.Entry<BlockPos, Block[]> entry : _targets.entrySet()) {
            if (isTargetDone(mod, entry.getKey(), entry.getValue())) continue;
            BlockPos pos = entry.getKey();
            // Finish walls bottom-up and prefer targets that already have a
            // solid click face. Choosing an unsupported upper block first made
            // the single-block placer path toward an unreachable wall cell.
            double supportPenalty = hasPlacementSupport(mod, pos) ? 0 : 1_000_000;
            double heightPenalty = Math.max(0, pos.getY() - _origin.getY()) * 10_000.0;
            double score = supportPenalty + heightPenalty + pos.getSquaredDistance(player);
            if (score < bestScore) {
                bestScore = score;
                best = entry;
            }
        }
        return best;
    }

    private boolean hasPlacementSupport(Belfegor mod, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (WorldHelper.isSolid(mod, pos.offset(direction))) return true;
        }
        return false;
    }

    private boolean isInsideProtectedFloor(BlockPos pos) {
        return pos != null
                && _protectedFloorCenter != null
                && _protectedFloorRadius >= 0
                && pos.getY() == _protectedFloorY
                && Math.abs(pos.getX() - _protectedFloorCenter.getX()) <= _protectedFloorRadius
                && Math.abs(pos.getZ() - _protectedFloorCenter.getZ()) <= _protectedFloorRadius;
    }

    private boolean isProtectedFloorBlock(Belfegor mod, BlockPos pos) {
        if (!isInsideProtectedFloor(pos) || mod.getWorld() == null) return false;
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        return block != Blocks.AIR
                && block != Blocks.CAVE_AIR
                && block != Blocks.VOID_AIR
                && block != Blocks.WATER
                && block != Blocks.LAVA
                && WorldHelper.isSolid(mod, pos);
    }

    private boolean isCompletedTargetBlock(Belfegor mod, BlockPos pos) {
        if (pos == null || mod.getWorld() == null) return false;
        Block[] desired = _targets.get(pos);
        return desired != null && isTargetDone(mod, pos, desired);
    }

    /**
     * The Baritone avoid predicate is the primary guard.  This event guard is
     * a final safety net for stale paths calculated before the predicate was
     * installed, and gives the debug log the exact block and active region if
     * anything still attempts to mine the established floor.
     */
    private void subscribeProtectedFloorGuard(Belfegor mod) {
        if (_protectedFloorCenter == null || _protectedFloorRadius < 0) return;
        _protectedFloorBreakingSubscription = EventBus.subscribe(BlockBreakingEvent.class, evt -> {
            if (evt == null || !isInsideProtectedFloor(evt.blockPos)) return;
            mod.getClientBaritone().getInputOverrideHandler()
                    .setInputForceState(Input.CLICK_LEFT, false);
            if (mod.getController() != null) {
                mod.getController().cancelBlockBreaking();
            }
            long now = System.currentTimeMillis();
            if (now - _lastFloorGuardLogMs >= 500L) {
                _lastFloorGuardLogMs = now;
                DebugLogger.getInstance().logImmediate("BUILD-FLOOR-GUARD",
                        "cancel-break name=" + _name
                                + " pos=" + evt.blockPos.toShortString()
                                + " block=" + mod.getWorld().getBlockState(evt.blockPos).getBlock()
                                + " progress=" + evt.progress
                                + " player=" + (mod.getPlayer() == null
                                ? "null" : mod.getPlayer().getBlockPos().toShortString()));
            }
        });
        _protectedFloorBrokenSubscription = EventBus.subscribe(BlockBrokenEvent.class, evt -> {
            if (evt == null || !isInsideProtectedFloor(evt.blockPos)) return;
            DebugLogger.getInstance().logImmediate("BUILD-FLOOR-GUARD",
                    "BROKEN name=" + _name
                            + " pos=" + evt.blockPos.toShortString()
                            + " old=" + evt.blockState.getBlock()
                            + " player=" + (evt.player == null
                            ? "null" : evt.player.getBlockPos().toShortString()));
        });
    }

    private void calculateBounds() {
        if (_targets.isEmpty()) {
            _origin = BlockPos.ORIGIN;
            _max = BlockPos.ORIGIN;
            return;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : _targets.keySet()) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        _origin = new BlockPos(minX, minY, minZ);
        _max = new BlockPos(maxX, maxY, maxZ);
    }

    private static LinkedHashMap<BlockPos, Block[]> toMap(List<BlockPos> targets, Block[] blocks) {
        LinkedHashMap<BlockPos, Block[]> result = new LinkedHashMap<>();
        for (BlockPos target : targets) {
            result.put(target, blocks);
        }
        return result;
    }

    private class RegionSchematic extends AbstractSchematic {

        private final Belfegor _mod;

        public RegionSchematic(Belfegor mod) {
            super(_max.getX() - _origin.getX() + 1,
                    _max.getY() - _origin.getY() + 1,
                    _max.getZ() - _origin.getZ() + 1);
            _mod = mod;
        }

        @Override
        public BlockState desiredState(int x, int y, int z, BlockState blockState, List<BlockState> available) {
            BlockPos worldPos = _origin.add(x, y, z);
            Block[] desired = _targets.get(worldPos);
            if (desired == null) {
                return blockState;
            }
            if (BaseMemory.getInstance().isProtectedFixturePosition(worldPos, WorldHelper.getCurrentDimension().name())) {
                return blockState;
            }
            if (isNonFarmFloorRegion()
                    && blockState != null
                    && isAcceptableNaturalFloor(_mod, worldPos, blockState.getBlock())) {
                return blockState;
            }
            if (_allowAnyThrowaway && blockState != null && blockState.getBlock() != Blocks.AIR
                    && WorldHelper.isSolid(_mod, worldPos)) {
                return blockState;
            }
            List<Block> desiredBlocks = Arrays.asList(desired);
            for (BlockState possible : available == null ? new ArrayList<BlockState>() : available) {
                if (possible == null) continue;
                Block block = possible.getBlock();
                if (_allowAnyThrowaway
                        && _mod.getClientBaritoneSettings().acceptableThrowawayItems.value.contains(block.asItem())
                        && block != Blocks.AIR) {
                    return possible;
                }
                if (desiredBlocks.contains(block)) {
                    return possible;
                }
            }
            return blockState;
        }
    }
}
