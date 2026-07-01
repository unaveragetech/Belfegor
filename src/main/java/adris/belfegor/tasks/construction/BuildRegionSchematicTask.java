package adris.belfegor.tasks.construction;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.debug.DebugLogger;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.tasks.container.PickupFromContainerTask;
import adris.belfegor.tasks.movement.GetToBlockTask;
import adris.belfegor.tasks.resources.GetBuildingMaterialsTask;
import adris.belfegor.tasksystem.ITaskRequiresGrounded;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.NativeBaritoneHelper;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.helpers.WorldHelper;
import baritone.api.schematic.AbstractSchematic;
import baritone.api.schematic.ISchematic;
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

    private final String _name;
    private final LinkedHashMap<BlockPos, Block[]> _targets;
    private final boolean _allowAnyThrowaway;
    private BlockPos _origin;
    private BlockPos _max;
    private Task _materialTask;
    private int _lastMissing = Integer.MAX_VALUE;
    private int _noProgressTicks;
    private Task _manualFallbackTask;
    private Task _supplyTask;
    private boolean _builderLaunched;

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

    @Override
    protected void onStart(Belfegor mod) {
        calculateBounds();
        _lastMissing = Integer.MAX_VALUE;
        _noProgressTicks = 0;
        _manualFallbackTask = null;
        _supplyTask = null;
        _builderLaunched = false;
        mod.getBehaviour().push();
        mod.getBehaviour().setAutoMLG(false);
        mod.getBehaviour().setAllowDiagonalAscend(false);
        mod.getBehaviour().forceUseTool((state, stack) -> stack != null && stack.isSuitableFor(state));
        mod.getClientBaritone().getBuilderProcess().onLostControl();
        NativeBaritoneHelper.selectBox(mod, _origin, _max, "build-region-" + _name);
        NativeBaritoneHelper.logProcessState(mod, "build-region-start-" + _name);
        DebugLogger.getInstance().log("BUILD-REGION", "start name=" + _name
                + " targets=" + _targets.size()
                + " allowThrowaway=" + _allowAnyThrowaway);
    }

    @Override
    protected Task onTick(Belfegor mod) {
        int missing = countMissing(mod);
        if (missing <= 0) return null;
        trackProgress(mod, missing);

        Task supply = ensureWorkingSupplies(mod);
        if (supply != null) return supply;

        if (_manualFallbackTask != null && !_manualFallbackTask.stopped() && !_manualFallbackTask.isFinished(mod)) {
            setDebugState("Manual fallback for " + _name + " missing=" + missing + "/" + _targets.size());
            return _manualFallbackTask;
        }
        _manualFallbackTask = null;

        boolean builderActive = mod.getClientBaritone().getBuilderProcess().isActive();
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
            Debug.logInternal("Run region schematic build: " + _name + " targets=" + _targets.size());
            DebugLogger.getInstance().log("BUILD-REGION", "launch-builder name=" + _name
                    + " origin=" + _origin.toShortString()
                    + " max=" + _max.toShortString()
                    + " missing=" + missing + "/" + _targets.size());
            NativeBaritoneHelper.logProcessState(mod, "build-region-before-native-builder-" + _name);
            ISchematic schematic = new RegionSchematic(mod);
            mod.getClientBaritone().getBuilderProcess().build(_name, schematic, _origin);
            _builderLaunched = true;
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
            return new GetToBlockTask(approach);
        }
        if (current != Blocks.AIR && current != Blocks.WATER && !isTargetDone(mod, pos, entry.getValue())) {
            return new DestroyBlockTask(pos);
        }
        return new PlaceBlockTask(pos, entry.getValue(), _allowAnyThrowaway, true);
    }

    private boolean shouldMoveToManualApproach(Belfegor mod, BlockPos approach) {
        BlockPos player = mod.getPlayer().getBlockPos();
        String normalized = _name.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("floor")) {
            return !player.equals(approach);
        }
        return approach.getSquaredDistance(player) > 9;
    }

    private Task ensureWorkingSupplies(Belfegor mod) {
        ItemTarget needed = mostNeededExactBlock(mod);
        if (needed == null) return null;

        int inventoryCount = mod.getItemStorage().getItemCountInventoryOnly(needed.getMatches());
        if (inventoryCount >= Math.min(needed.getTargetCount(), 32)) {
            _supplyTask = null;
            return null;
        }

        Optional<BlockPos> staging = findConstructionStaging(mod);
        if (staging.isPresent()) {
            int knownTotal = Math.max(
                    mod.getItemStorage().getItemCount(needed.getMatches()),
                    countOpenStagingContainerItems(mod, staging.get(), needed.getMatches()));
            if (knownTotal <= inventoryCount) {
                DebugLogger.getInstance().log("BUILD-REGION",
                        "supply-staging-lacks name=" + _name
                                + " staging=" + staging.get().toShortString()
                                + " target=" + needed
                                + " inv=" + inventoryCount
                                + " knownTotal=" + knownTotal
                                + " note=continuing with collection/fallback until staged material is available");
                _supplyTask = null;
                return null;
            }
            if (_supplyTask != null && !_supplyTask.stopped() && !_supplyTask.isFinished(mod)) {
                setDebugState("Withdrawing staged schematic supplies for " + _name + ": " + needed);
                return _supplyTask;
            }
            ItemTarget workingBatch = new ItemTarget(needed, Math.min(Math.max(needed.getTargetCount(), 32), 128));
            DebugLogger.getInstance().log("BUILD-REGION",
                    "supply-withdraw name=" + _name
                            + " staging=" + staging.get().toShortString()
                            + " target=" + workingBatch
                            + " inv=" + inventoryCount);
            _supplyTask = new PickupFromContainerTask(staging.get(), workingBatch);
            setDebugState("Withdrawing staged schematic supplies for " + _name + ": " + workingBatch);
            return _supplyTask;
        }

        DebugLogger.getInstance().log("BUILD-REGION",
                "supply-not-staged name=" + _name
                        + " target=" + needed
                        + " inv=" + inventoryCount
                        + " note=no construction staging chest found; falling back to resource task");
        return null;
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

    private ItemTarget mostNeededExactBlock(Belfegor mod) {
        LinkedHashMap<Item, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, Block[]> entry : _targets.entrySet()) {
            if (isTargetDone(mod, entry.getKey(), entry.getValue())) continue;
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
        if (best == null || bestCount <= 0) return null;
        return new ItemTarget(best, bestCount);
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
        String normalized = _name.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("floor")) {
            BlockPos player = mod.getPlayer() == null ? target : mod.getPlayer().getBlockPos();
            Direction[] directions = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
            BlockPos best = null;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (Direction direction : directions) {
                BlockPos stand = target.offset(direction).up();
                if (!WorldHelper.isSolid(mod, stand.down())) continue;
                if (!mod.getWorld().getBlockState(stand).isAir()) continue;
                if (!mod.getWorld().getBlockState(stand.up()).isAir()) continue;
                double distance = stand.getSquaredDistance(player);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = stand;
                }
            }
            if (best != null) return best;
            return target.up();
        }
        return target;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        mod.getBehaviour().pop();
        mod.getClientBaritone().getBuilderProcess().onLostControl();
        NativeBaritoneHelper.clearSelections(mod, "build-region-stop-" + _name);
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return countMissing(mod) == 0;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BuildRegionSchematicTask task
                && task._name.equals(_name)
                && task._targets.equals(_targets)
                && task._allowAnyThrowaway == _allowAnyThrowaway;
    }

    @Override
    protected String toDebugString() {
        return "Build region schematic " + _name + " targets=" + _targets.size();
    }

    private int countMissing(Belfegor mod) {
        int missing = 0;
        for (Map.Entry<BlockPos, Block[]> entry : _targets.entrySet()) {
            if (!isTargetDone(mod, entry.getKey(), entry.getValue())) {
                missing++;
            }
        }
        return missing;
    }

    private boolean isTargetDone(Belfegor mod, BlockPos pos, Block[] desired) {
        if (BaseMemory.getInstance().isProtectedFixturePosition(pos, WorldHelper.getCurrentDimension().name())) {
            return true;
        }
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        if (_allowAnyThrowaway) {
            return WorldHelper.isSolid(mod, pos);
        }
        return Arrays.asList(desired).contains(block);
    }

    private Map.Entry<BlockPos, Block[]> closestMissing(Belfegor mod) {
        Map.Entry<BlockPos, Block[]> best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        BlockPos player = mod.getPlayer() == null ? _origin : mod.getPlayer().getBlockPos();
        for (Map.Entry<BlockPos, Block[]> entry : _targets.entrySet()) {
            if (isTargetDone(mod, entry.getKey(), entry.getValue())) continue;
            double distance = entry.getKey().getSquaredDistance(player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry;
            }
        }
        return best;
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
