package adris.belfegor.tasks.construction;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.debug.DebugLogger;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.memory.BaseStorageMemory;
import adris.belfegor.memory.LocationMemory;
import adris.belfegor.schematic.BelfegorSchematic;
import adris.belfegor.tasks.InteractWithBlockTask;
import adris.belfegor.tasks.movement.GetToBlockTask;
import adris.belfegor.tasks.movement.RecoverToYLevelTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.ItemHelper;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds and expands the @player home base.
 *
 * This is intentionally staged instead of "place blocks wherever":
 * 1) clear the inside and a five-block exterior safety gap,
 * 2) flatten/fill the floor,
 * 3) build a four-high perimeter wall,
 * 4) build interior room dividers,
 * 5) place utility room anchors.
 *
 * Farms, storage expansions, workshops, and mob rooms are separate modules.
 * Keeping them out of the core campsite prevents later @build full phases from
 * overlapping their own blueprints and breaking freshly placed blocks.
 */
public class BuildCampsiteTask extends Task {

    private static final Block[] STRUCTURE_BLOCKS = {
            Blocks.COBBLESTONE
    };
    private static final int WALL_HEIGHT = 4;
    private static final int EXTERIOR_CLEARANCE = 5;

    private enum Phase {
        PREFLIGHT,
        CLEAR,
        FLOOR,
        WALL,
        ROOMS,
        UTILITY,
        DOORS,
        BED,
        DONE
    }

    private final BlockPos _home;
    private final int _radius;
    private List<BlockPos> _clearTargets;
    private List<BlockPos> _floorTargets;
    private List<BlockPos> _wallTargets;
    private List<BlockPos> _roomTargets;
    private Map<BlockPos, Block> _protectedBlueprintTargets;
    private Phase _phase = Phase.PREFLIGHT;
    private int _index;
    private Task _activeTask;
    private boolean _bedSpawnClicked;
    private boolean _clickingBedToSetSpawn;
    private int _doorPlacementCooldown;
    private int _bedPlacementCooldown;
    private int _bedPlacementAttempts;

    public BuildCampsiteTask(BlockPos home, int radius) {
        _home = home;
        _radius = Math.max(6, Math.min(18, radius));
    }

    public static int countCoreBlueprintMismatches(Belfegor mod, BlockPos home, int radius) {
        int normalizedRadius = Math.max(6, Math.min(18, radius));
        String dimension = WorldHelper.getCurrentDimension().name();
        return loadOrExportCoreSchematic(dimension, home, normalizedRadius).countMismatches(mod);
    }

    public static BelfegorSchematic loadOrExportCoreSchematic(String dimension, BlockPos home, int radius) {
        int normalizedRadius = Math.max(6, Math.min(18, radius));
        java.io.File file = BelfegorSchematic.baseCoreFile(dimension, home);
        return BelfegorSchematic.load(file).orElseGet(() -> exportCoreSchematic(dimension, home, normalizedRadius));
    }

    public static BelfegorSchematic exportCoreSchematic(String dimension, BlockPos home, int radius) {
        int normalizedRadius = Math.max(6, Math.min(18, radius));
        BelfegorSchematic schematic = BelfegorSchematic.fromBlocks("base_core", dimension, home,
                coreBlueprintTargets(home, normalizedRadius));
        schematic.save(BelfegorSchematic.baseCoreFile(dimension, home));
        return schematic;
    }

    public static Map<BlockPos, Block> coreBlueprintTargets(BlockPos home, int radius) {
        int normalizedRadius = Math.max(6, Math.min(18, radius));
        LinkedHashMap<BlockPos, Block> targets = new LinkedHashMap<>();
        // Do not require a decorative cobblestone floor in the generated
        // campsite blueprint. A flat solid natural floor is already useful,
        // and replacing it all makes @build full waste hundreds of breaks and
        // placements before it has even built walls or staging.
        for (BlockPos pos : coreWallTargets(home, normalizedRadius)) {
            targets.put(pos, Blocks.COBBLESTONE);
        }
        for (BlockPos pos : coreRoomTargets(home, normalizedRadius)) {
            targets.put(pos, Blocks.COBBLESTONE);
        }
        return targets;
    }

    @Override
    protected void onStart(Belfegor mod) {
        _clearTargets = buildClearTargets(mod);
        _floorTargets = buildFloorTargets();
        _wallTargets = buildWallTargets(mod);
        _roomTargets = buildRoomTargets(mod);
        _protectedBlueprintTargets = coreBlueprintTargets(_home, _radius);
        _phase = Phase.PREFLIGHT;
        _index = 0;
        _activeTask = null;
        _bedSpawnClicked = false;
        _clickingBedToSetSpawn = false;
        _bedPlacementCooldown = 0;
        _bedPlacementAttempts = 0;
        mod.getBehaviour().push();
        exportCurrentCoreSchematic();
        mod.getBehaviour().setAutoMLG(false);
        mod.getBehaviour().setAllowDiagonalAscend(false);
        mod.getBehaviour().avoidWalkingThrough(pos ->
                pos.getY() >= _home.getY()
                        && pos.getY() <= _home.getY() + WALL_HEIGHT
                        && Math.abs(pos.getX() - _home.getX()) <= _radius
                        && Math.abs(pos.getZ() - _home.getZ()) <= _radius
                        && !isDoorway(pos)
                        && (Math.abs(pos.getX() - _home.getX()) == _radius
                        || Math.abs(pos.getZ() - _home.getZ()) == _radius));
        mod.getBehaviour().avoidBlockBreaking(pos ->
                isCompletedBlueprintBlock(pos) || isProtectedCampsiteFloor(pos));
        rememberBase("started");
        rememberRooms();
        restorePhase(mod);
    }

    /**
     * Continues an interrupted campsite build from the remembered phase. The
     * saved phase is only trusted when its prerequisites are actually met in
     * the world; otherwise the task falls back to the earliest unfinished
     * phase, so a stale saved phase can never skip required construction.
     */
    private void restorePhase(Belfegor mod) {
        Optional<String> saved = BaseMemory.getInstance()
                .loadBuildPhase(_home, WorldHelper.getCurrentDimension().name(), "camp");
        if (saved.isEmpty()) return;
        Phase savedPhase;
        try {
            savedPhase = Phase.valueOf(saved.get());
        } catch (Exception ignored) {
            return;
        }
        _phase = savedPhase;
        if (savedPhase.ordinal() >= Phase.FLOOR.ordinal() && !targetsClear(mod, _clearTargets)) {
            _phase = Phase.CLEAR;
        }
        if (savedPhase.ordinal() >= Phase.WALL.ordinal() && !missingFloorTargets(mod).isEmpty()) {
            _phase = Phase.FLOOR;
        }
        if (savedPhase.ordinal() >= Phase.ROOMS.ordinal() && countMissingCobblestone(mod, _wallTargets) > 0) {
            _phase = Phase.WALL;
        }
        if (savedPhase.ordinal() >= Phase.UTILITY.ordinal() && countMissingCobblestone(mod, _roomTargets) > 0) {
            _phase = Phase.ROOMS;
        }
        if (savedPhase.ordinal() >= Phase.DOORS.ordinal() && !utilitiesReady(mod)) {
            _phase = Phase.UTILITY;
        }
        if (savedPhase.ordinal() >= Phase.BED.ordinal() && !entranceDoorsReady(mod)) {
            _phase = Phase.DOORS;
        }
        if (_phase == Phase.DONE && !campsiteReady(mod)) {
            _phase = Phase.BED;
        }
        persistPhase();
    }

    private boolean utilitiesReady(Belfegor mod) {
        return mod.getWorld().getBlockState(_home.add(2, 0, 2)).getBlock() == Blocks.CRAFTING_TABLE
                && mod.getWorld().getBlockState(_home.add(-2, 0, 2)).getBlock() == Blocks.FURNACE
                && mod.getWorld().getBlockState(_home.add(2, 0, -2)).getBlock() == Blocks.CHEST;
    }

    private boolean campsiteReady(Belfegor mod) {
        return entranceDoorsReady(mod)
                && findCampBed(mod) != null
                && countMissingCobblestone(mod, _wallTargets) == 0
                && countMissingCobblestone(mod, _roomTargets) == 0;
    }

    private boolean isCompletedBlueprintBlock(BlockPos pos) {
        if (_protectedBlueprintTargets == null || !_protectedBlueprintTargets.containsKey(pos)) {
            return false;
        }
        Block expected = _protectedBlueprintTargets.get(pos);
        if (expected == null || modWorldUnavailable()) {
            return false;
        }
        return net.minecraft.client.MinecraftClient.getInstance().world
                .getBlockState(pos).getBlock() == expected;
    }

    private boolean isProtectedCampsiteFloor(BlockPos pos) {
        if (pos == null || pos.getY() != _home.getY() - 1 || modWorldUnavailable()) return false;
        if (Math.abs(pos.getX() - _home.getX()) > _radius
                || Math.abs(pos.getZ() - _home.getZ()) > _radius) {
            return false;
        }
        Block block = MinecraftClient.getInstance().world.getBlockState(pos).getBlock();
        return block != Blocks.AIR
                && block != Blocks.CAVE_AIR
                && block != Blocks.VOID_AIR
                && block != Blocks.WATER
                && block != Blocks.LAVA;
    }

    private boolean modWorldUnavailable() {
        return net.minecraft.client.MinecraftClient.getInstance() == null
                || net.minecraft.client.MinecraftClient.getInstance().world == null;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        switch (_phase) {
            case PREFLIGHT: {
                Task preflight = runCampsitePreflight(mod);
                if (preflight != null) return preflight;
                rememberBase("preflight_complete");
                nextPhase(Phase.CLEAR);
                return null;
            }
            case CLEAR: {
                Task clear = runClearPhase(mod);
                if (clear != null) return clear;
                rememberBase("clear_complete");
                nextPhase(Phase.FLOOR);
                return null;
            }
            case FLOOR: {
                Task returnToPlatform = returnToBuildPlatformIfDrifted(mod);
                if (returnToPlatform != null) return returnToPlatform;
                Task floor = runFloorPhase(mod);
                if (floor != null) return floor;
                rememberBase("floor_complete");
                nextPhase(Phase.WALL);
                return null;
            }
            case WALL: {
                Task returnToPlatform = returnToBuildPlatformIfDrifted(mod);
                if (returnToPlatform != null) return returnToPlatform;
                Task wall = runWallPhase(mod);
                if (wall != null) return wall;
                rememberBase("wall_complete");
                nextPhase(Phase.ROOMS);
                return null;
            }
            case ROOMS: {
                Task returnToPlatform = returnToBuildPlatformIfDrifted(mod);
                if (returnToPlatform != null) return returnToPlatform;
                Task rooms = runRoomPhase(mod);
                if (rooms != null) return rooms;
                rememberBase("rooms_complete");
                nextPhase(Phase.UTILITY);
                return null;
            }
            case UTILITY: {
                Task returnToPlatform = returnToBuildPlatformIfDrifted(mod);
                if (returnToPlatform != null) return returnToPlatform;
                Task utility = placeUtilityBlocks(mod);
                if (utility != null) return utility;
                rememberBase("utility_complete");
                nextPhase(Phase.DOORS);
                return null;
            }
            case DOORS: {
                Task returnToPlatform = returnToBuildPlatformIfDrifted(mod);
                if (returnToPlatform != null) return returnToPlatform;
                Task doors = placeEntranceDoors(mod);
                if (doors != null) return doors;
                // Direct controller placement returns null while the server is
                // accepting the click. Do not interpret that as completion of
                // the entire two-door phase. Re-enter this phase until both
                // lower door blocks exist; placeEntranceDoors then records both
                // fixtures as complete before we advance to the bed.
                if (!entranceDoorsReady(mod)) {
                    setDebugState("Waiting for both protected campsite entrance doors");
                    return null;
                }
                rememberEntranceDoor(0, _home.add(_radius, 0, 0), "complete");
                rememberEntranceDoor(1, _home.add(_radius, 0, 1), "complete");
                rememberBase("doors_complete");
                nextPhase(Phase.BED);
                return null;
            }
            case BED: {
                Task returnToPlatform = returnToBuildPlatformIfDrifted(mod);
                if (returnToPlatform != null) return returnToPlatform;
                Task bed = placeBedAndSetSpawn(mod);
                if (bed != null) return bed;
                rememberBase("bed_complete");
                LocationMemory.getInstance().remember("home_campsite",
                        _home.getX(), _home.getY(), _home.getZ(),
                        WorldHelper.getCurrentDimension().name(),
                        "radius=" + _radius + ";wallHeight=" + WALL_HEIGHT
                                + ";clearance=" + EXTERIOR_CLEARANCE);
                markCoreModulesComplete();
                exportCurrentCoreSchematic();
                rememberBase("complete");
                LocationMemory.getInstance().save();
                BaseMemory.getInstance().save();
                nextPhase(Phase.DONE);
                return null;
            }
            case DONE:
                return null;
        }
        return null;
    }

    private boolean isDoorway(BlockPos pos) {
        if (pos == null) return false;
        int dx = pos.getX() - _home.getX();
        int dz = pos.getZ() - _home.getZ();
        return dx == _radius
                && (dz == 0 || dz == 1)
                && pos.getY() >= _home.getY()
                && pos.getY() <= _home.getY() + 2;
    }

    private Task runCampsitePreflight(Belfegor mod) {
        int missingStructure = countMissingCobblestone(mod, _wallTargets)
                + countMissingCobblestone(mod, _roomTargets);
        int requiredCobble = Math.max(64, missingStructure + 64);
        int cobble = mod.getItemStorage().getItemCount(Items.COBBLESTONE);
        if (missingStructure > 0 && cobble < requiredCobble) {
            setDebugState("Preparing campsite cobblestone reserve " + cobble + "/" + requiredCobble);
            return cacheActive(mod, TaskCatalogue.getItemTask("cobblestone", requiredCobble));
        }
        if (mod.getWorld().getBlockState(_home.add(2, 0, -2)).getBlock() != Blocks.CHEST
                && mod.getItemStorage().getItemCount(Items.CHEST) < 1) {
            setDebugState("Preparing campsite storage chest");
            return cacheActive(mod, TaskCatalogue.getItemTask("chest", 1));
        }
        if (mod.getWorld().getBlockState(_home.add(2, 0, 2)).getBlock() != Blocks.CRAFTING_TABLE
                && mod.getItemStorage().getItemCount(Items.CRAFTING_TABLE) < 1) {
            setDebugState("Preparing campsite crafting table");
            return cacheActive(mod, TaskCatalogue.getItemTask("crafting_table", 1));
        }
        if (mod.getWorld().getBlockState(_home.add(-2, 0, 2)).getBlock() != Blocks.FURNACE
                && mod.getItemStorage().getItemCount(Items.FURNACE) < 1) {
            setDebugState("Preparing campsite furnace");
            return cacheActive(mod, TaskCatalogue.getItemTask("furnace", 1));
        }
        if (!entranceDoorsReady(mod) && !mod.getItemStorage().hasItem(ItemHelper.WOOD_DOOR)) {
            setDebugState("Preparing campsite entrance doors");
            return cacheActive(mod, TaskCatalogue.getItemTask("wooden_door", 2));
        }
        if (!hasBedInCamp(mod) && !mod.getItemStorage().hasItem(ItemHelper.BED)) {
            setDebugState("Preparing campsite bed");
            return cacheActive(mod, TaskCatalogue.getItemTask("bed", 1));
        }
        _activeTask = null;
        return null;
    }

    private Task cacheActive(Belfegor mod, Task task) {
        if (task == null) return null;
        if (_activeTask != null && !_activeTask.stopped() && !_activeTask.isFinished(mod)) {
            return _activeTask;
        }
        _activeTask = task;
        return _activeTask;
    }

    private Task runClearPhase(Belfegor mod) {
        if (_clearTargets.isEmpty()) return null;
        if (targetsClear(mod, _clearTargets)) return null;
        if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
            ArrayList<BlockPos> batch = new ArrayList<>();
            for (BlockPos target : _clearTargets) {
                if (!clearDone(mod, target)) {
                    batch.add(target);
                    if (batch.size() >= 128) break;
                }
            }
            if (batch.isEmpty()) return null;
            _activeTask = new ClearRegionTask(batch);
        }
        setDebugState("Targeted clearing campsite obstacles remaining=" + countUnclearTargets(mod, _clearTargets));
        return _activeTask;
    }

    private Task runFloorPhase(Belfegor mod) {
        List<BlockPos> missingFloorTargets = missingFloorTargets(mod);
        if (missingFloorTargets.isEmpty()) return null;

        int neededBlocks = Math.max(1, missingFloorTargets.size());
        int carriedCobble = mod.getItemStorage().getItemCountInventoryOnly(Items.COBBLESTONE);
        int floorBatch = Math.min(Math.max(neededBlocks, 32), 128);
        if (carriedCobble < Math.min(neededBlocks, 8)) {
            setDebugState("Collecting small cobblestone patch batch for unsafe campsite floor cells carried="
                    + carriedCobble + " needed=" + neededBlocks);
            return TaskCatalogue.getItemTask("cobblestone", floorBatch);
        }
        if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
            _activeTask = new BuildRegionSchematicTask("campsite floor patches",
                    toTargetMap(missingFloorTargets), false)
                    .withMinimumStandY(_home.getY())
                    .withProtectedFloor(_home, _radius, _home.getY() - 1);
        }
        setDebugState("Patching unsafe campsite floor cells missing=" + missingFloorTargets.size());
        return _activeTask;
    }

    private Task runWallPhase(Belfegor mod) {
        int totalMissing = countMissingCobblestone(mod, _wallTargets);
        if (totalMissing == 0) return null;
        int carriedCobble = mod.getItemStorage().getItemCountInventoryOnly(Items.COBBLESTONE);
        int wallBatch = Math.min(Math.max(totalMissing, 128), 256);
        if (carriedCobble < Math.min(totalMissing, 32)) {
            setDebugState("Collecting carried cobblestone for four-high wall carried=" + carriedCobble
                    + " needed=" + totalMissing);
            return TaskCatalogue.getItemTask("cobblestone", wallBatch);
        }

        while (_index < WALL_HEIGHT && countMissingCobblestone(mod, wallLayerTargets(_index)) == 0) {
            _index++;
            _activeTask = null;
        }
        if (_index >= WALL_HEIGHT) return null;
        List<BlockPos> layerTargets = wallLayerTargets(_index);
        int missing = countMissingCobblestone(mod, layerTargets);
        if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
            _activeTask = new BuildRegionSchematicTask("campsite perimeter wall layer " + (_index + 1),
                    toTargetMap(layerTargets), false)
                    .withMinimumStandY(_home.getY())
                    .withProtectedFloor(_home, _radius, _home.getY() - 1);
        }
        rememberProgress("perimeter_wall", _wallTargets.size() - totalMissing, _wallTargets.size(), "building",
                "placing four-high perimeter wall blocks layer=" + (_index + 1) + "/" + WALL_HEIGHT);
        setDebugState("Baritone building wall layer " + (_index + 1) + "/" + WALL_HEIGHT
                + " missing=" + missing + " totalMissing=" + totalMissing);
        return _activeTask;
    }

    private Task runRoomPhase(Belfegor mod) {
        int missing = countMissingCobblestone(mod, _roomTargets);
        if (missing == 0) return null;
        int carriedCobble = mod.getItemStorage().getItemCountInventoryOnly(Items.COBBLESTONE);
        int roomBatch = Math.min(Math.max(missing, 96), 192);
        if (carriedCobble < Math.min(missing, 24)) {
            setDebugState("Collecting carried cobblestone for interior room walls carried=" + carriedCobble
                    + " needed=" + missing);
            return TaskCatalogue.getItemTask("cobblestone", roomBatch);
        }

        if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
            _activeTask = new BuildRegionSchematicTask("campsite interior rooms",
                    toTargetMap(_roomTargets), false)
                    .withMinimumStandY(_home.getY())
                    .withProtectedFloor(_home, _radius, _home.getY() - 1);
        }
        rememberProgress("interior_dividers", _roomTargets.size() - missing, _roomTargets.size(), "building",
                "placing room divider blocks");
        setDebugState("Baritone building interior rooms as one schematic missing=" + missing);
        return _activeTask;
    }

    private List<BlockPos> buildClearTargets(Belfegor mod) {
        ArrayList<BlockPos> result = new ArrayList<>();
        int clearRadius = _radius + EXTERIOR_CLEARANCE;
        for (int dx = -clearRadius; dx <= clearRadius; dx++) {
            for (int dz = -clearRadius; dz <= clearRadius; dz++) {
                boolean outsideWallGap = Math.abs(dx) > _radius || Math.abs(dz) > _radius;
                for (int h = 0; h <= WALL_HEIGHT; h++) {
                    BlockPos pos = _home.add(dx, h, dz);
                    if (WorldHelper.isInsidePlayer(mod, pos)) continue;
                    Block block = mod.getWorld().getBlockState(pos).getBlock();
                    if (block == Blocks.AIR) continue;
                    if (outsideWallGap) {
                        if (isExteriorClearanceObstacle(block)) {
                            result.add(pos);
                        }
                    } else if (isInteriorClearObstacle(block)) {
                        result.add(pos);
                    }
                }
            }
        }
        return result;
    }

    private List<BlockPos> buildFloorTargets() {
        return coreFloorTargets(_home, _radius);
    }

    private List<BlockPos> buildWallTargets(Belfegor mod) {
        return coreWallTargets(_home, _radius).stream()
                .filter(pos -> !WorldHelper.isInsidePlayer(mod, pos))
                .toList();
    }

    private List<BlockPos> wallLayerTargets(int layer) {
        ArrayList<BlockPos> result = new ArrayList<>();
        int y = _home.getY() + Math.max(0, Math.min(WALL_HEIGHT - 1, layer));
        for (BlockPos target : _wallTargets) {
            if (target.getY() == y) {
                result.add(target);
            }
        }
        return result;
    }

    private List<BlockPos> buildRoomTargets(Belfegor mod) {
        return coreRoomTargets(_home, _radius).stream()
                .filter(pos -> !WorldHelper.isInsidePlayer(mod, pos))
                .toList();
    }

    private static List<BlockPos> coreFloorTargets(BlockPos home, int radius) {
        ArrayList<BlockPos> result = new ArrayList<>();
        for (int dx = -radius + 1; dx <= radius - 1; dx++) {
            for (int dz = -radius + 1; dz <= radius - 1; dz++) {
                result.add(home.add(dx, -1, dz));
            }
        }
        return result;
    }

    private static List<BlockPos> coreWallTargets(BlockPos home, int radius) {
        ArrayList<BlockPos> result = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                boolean perimeter = Math.abs(dx) == radius || Math.abs(dz) == radius;
                if (!perimeter) continue;
                // Leave a simple two-wide doorway on the east side.
                if (dx == radius && (dz == 0 || dz == 1)) continue;
                for (int h = 0; h < WALL_HEIGHT; h++) {
                    result.add(home.add(dx, h, dz));
                }
            }
        }
        return result;
    }

    private static List<BlockPos> coreRoomTargets(BlockPos home, int radius) {
        ArrayList<BlockPos> result = new ArrayList<>();
        int inner = Math.max(3, radius - 2);
        for (int d = -inner; d <= inner; d++) {
            // Central north/south divider, with a two-wide central doorway.
            if (d != 0 && d != 1) {
                for (int h = 0; h < 3; h++) {
                    result.add(home.add(0, h, d));
                }
            }
            // Central east/west divider, also with a two-wide central doorway.
            if (d != 0 && d != 1) {
                for (int h = 0; h < 3; h++) {
                    result.add(home.add(d, h, 0));
                }
            }
        }
        return result;
    }

    private Task placeUtilityBlocks(Belfegor mod) {
        BlockPos table = _home.add(2, 0, 2);
        if (mod.getItemStorage().hasItem(Items.CRAFTING_TABLE)
                && mod.getWorld().getBlockState(table).getBlock() != Blocks.CRAFTING_TABLE) {
            setDebugState("Placing crafting room table");
            return placeFixture(mod, table, Blocks.CRAFTING_TABLE);
        }

        BlockPos furnace = _home.add(-2, 0, 2);
        if (mod.getItemStorage().hasItem(Items.FURNACE)
                && mod.getWorld().getBlockState(furnace).getBlock() != Blocks.FURNACE) {
            setDebugState("Placing smelting room furnace");
            return placeFixture(mod, furnace, Blocks.FURNACE);
        }

        BlockPos chest = _home.add(2, 0, -2);
        if (mod.getItemStorage().hasItem(Items.CHEST)
                && mod.getWorld().getBlockState(chest).getBlock() != Blocks.CHEST) {
            setDebugState("Placing storage room chest");
            return placeFixture(mod, chest, Blocks.CHEST);
        }
        if (mod.getWorld().getBlockState(chest).getBlock() == Blocks.CHEST) {
            BaseStorageMemory.getInstance().rememberChest(_home, WorldHelper.getCurrentDimension().name(),
                    chest, "camp_storage", false, "core camp storage chest");
            BaseStorageMemory.getInstance().save();
        }
        return null;
    }

    private Task placeEntranceDoors(Belfegor mod) {
        BlockPos first = _home.add(_radius, 0, 0);
        BlockPos second = _home.add(_radius, 0, 1);
        for (int index = 0; index < 2; index++) {
            BlockPos target = index == 0 ? first : second;
            Block current = mod.getWorld().getBlockState(target).getBlock();
            if (current instanceof net.minecraft.block.DoorBlock) {
                rememberEntranceDoor(index, target, "complete");
                continue;
            }
            Block head = mod.getWorld().getBlockState(target.up()).getBlock();
            if (current != Blocks.AIR) {
                setDebugState("Clearing campsite entrance door foot " + (index + 1) + "/2");
                return cacheActive(mod, new DestroyBlockTask(target));
            }
            if (head != Blocks.AIR) {
                setDebugState("Clearing campsite entrance door head " + (index + 1) + "/2");
                return cacheActive(mod, new DestroyBlockTask(target.up()));
            }
            if (!mod.getItemStorage().hasItem(ItemHelper.WOOD_DOOR)) {
                setDebugState("Crafting wooden entrance doors");
                return cacheActive(mod, TaskCatalogue.getItemTask("wooden_door", 2));
            }
            BlockPos support = target.down();
            if (!WorldHelper.isSolid(mod, support)) {
                setDebugState("Repairing campsite entrance door support " + (index + 1) + "/2");
                return cacheActive(mod, new PlaceBlockTask(support,
                        new Block[]{Blocks.COBBLESTONE}, false, true));
            }
            // The entrance is always on the east wall, so approach it from
            // inside the protected camp. Generic placement goals may consider
            // both door cells valid stands and alternate between them after a
            // rejected click. A stable inside stand gives the server one
            // reachable top-face interaction and keeps the doorway traversable.
            BlockPos stand = target.offset(Direction.WEST);
            if (mod.getPlayer() == null
                    || stand.getSquaredDistance(mod.getPlayer().getBlockPos()) > 2
                    || mod.getPlayer().getEyePos().squaredDistanceTo(Vec3d.ofCenter(target)) > 20.25) {
                setDebugState("Approaching campsite entrance door " + (index + 1) + "/2 from inside");
                return cacheActive(mod, new GetToBlockTask(stand));
            }
            setDebugState("Installing protected campsite entrance door " + (index + 1) + "/2");
            rememberEntranceDoor(index, target, "placing");
            _activeTask = null;
            if (MinecraftClient.getInstance().currentScreen != null) {
                StorageHelper.closeScreen();
                return null;
            }
            if (_doorPlacementCooldown-- > 0) return null;
            if (!mod.getSlotHandler().forceEquipItem(new ItemTarget(ItemHelper.WOOD_DOOR, 1), false)) {
                return cacheActive(mod, TaskCatalogue.getItemTask("wooden_door", 2));
            }
            Vec3d hit = Vec3d.ofCenter(support).add(0, 0.5, 0);
            BlockHitResult result = new BlockHitResult(hit, Direction.UP, support, false);
            ActionResult action = mod.getController().interactBlock(mod.getPlayer(), Hand.MAIN_HAND, result);
            mod.getPlayer().swingHand(Hand.MAIN_HAND);
            _doorPlacementCooldown = 4;
            DebugLogger.getInstance().logImmediate("BASE-DOOR",
                    "direct-place target=" + target + " stand=" + stand
                            + " support=" + support + " action=" + action);
            return null;
        }
        return null;
    }

    private boolean entranceDoorsReady(Belfegor mod) {
        return mod.getWorld().getBlockState(_home.add(_radius, 0, 0)).getBlock()
                instanceof net.minecraft.block.DoorBlock
                && mod.getWorld().getBlockState(_home.add(_radius, 0, 1)).getBlock()
                instanceof net.minecraft.block.DoorBlock;
    }

    private void rememberEntranceDoor(int index, BlockPos door, String status) {
        String dim = WorldHelper.getCurrentDimension().name();
        String name = index == 0 ? "entrance_door_a" : "entrance_door_b";
        BaseMemory.getInstance().rememberModule(_home, dim, name, "fixture",
                door, 1, 1, 2, status,
                "protected wooden door used for camp entry and exit");
        LocationMemory.getInstance().remember("home_door",
                door.getX(), door.getY(), door.getZ(), dim,
                "protected_double_entrance;door=" + (index + 1));
    }

    private Task placeFixture(Belfegor mod, BlockPos target, Block block) {
        BlockPos stand = fixtureStandPosition(mod, target);
        if (stand == null) {
            BlockPos obstruction = fixtureStandObstruction(mod, target);
            if (obstruction != null) {
                setDebugState("Clearing a safe standing position for " + block.getName().getString());
                return new DestroyBlockTask(obstruction);
            }
        }
        if (stand != null && mod.getPlayer() != null
                && (target.equals(mod.getPlayer().getBlockPos())
                || stand.getSquaredDistance(mod.getPlayer().getBlockPos()) > 4)) {
            return new GetToBlockTask(stand);
        }
        if (!mod.getWorld().getBlockState(target).isAir()) {
            return new DestroyBlockTask(target);
        }
        // The generic interaction task can repeatedly click the support face
        // while the player is standing inside the destination. PlaceBlockTask
        // owns movement, support selection, screen closure, and retry state, so
        // fixture placement cannot devolve into approach/wander spam.
        return cacheActive(mod, new PlaceBlockTask(target, new Block[]{block}, false, true));
    }

    private BlockPos fixtureStandPosition(Belfegor mod, BlockPos target) {
        Direction[] options = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (Direction option : options) {
            BlockPos stand = target.offset(option);
            if (WorldHelper.isSolid(mod, stand.down())
                    && mod.getWorld().getBlockState(stand).isAir()
                    && mod.getWorld().getBlockState(stand.up()).isAir()) {
                return stand;
            }
        }
        return null;
    }

    private BlockPos fixtureStandObstruction(Belfegor mod, BlockPos target) {
        Direction[] options = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        String dimension = WorldHelper.getCurrentDimension().name();
        for (Direction option : options) {
            BlockPos stand = target.offset(option);
            if (!WorldHelper.isSolid(mod, stand.down())) continue;
            BlockPos[] clearance = {stand.up(), stand};
            for (BlockPos blocked : clearance) {
                Block current = mod.getWorld().getBlockState(blocked).getBlock();
                if (current == Blocks.AIR || current == Blocks.WATER || current == Blocks.LAVA) continue;
                if (isCompletedBlueprintBlock(blocked)) continue;
                if (BaseMemory.getInstance().isProtectedFixturePosition(blocked, dimension)) continue;
                return blocked;
            }
        }
        return null;
    }

    private Task placeBedAndSetSpawn(Belfegor mod) {
        if (_clickingBedToSetSpawn
                && _activeTask instanceof InteractWithBlockTask interactTask
                && interactTask.getClickStatus() == InteractWithBlockTask.ClickResponse.CLICK_ATTEMPTED) {
            _bedSpawnClicked = true;
            _clickingBedToSetSpawn = false;
            BlockPos clickedBed = findCampBed(mod);
            if (clickedBed == null) clickedBed = _home.add(-2, 0, -2);
            rememberBed(clickedBed, "spawn_click_attempted");
            _activeTask = null;
        }
        if (_activeTask != null && _activeTask.isFinished(mod)) {
            if (_clickingBedToSetSpawn) {
                _bedSpawnClicked = true;
                BlockPos clickedBed = _home.add(-2, 0, -2);
                rememberBed(clickedBed, "spawn_clicked");
            }
            _clickingBedToSetSpawn = false;
            _activeTask = null;
        }
        BlockPos bed = _home.add(-2, 0, -2);
        BlockPos existingBed = findCampBed(mod);
        if (existingBed != null) {
            rememberBed(existingBed, _bedSpawnClicked ? "spawn_clicked" : "bed_ready");
            if (_bedSpawnClicked) {
                setDebugState("Campsite bed/spawn anchor ready");
                return null;
            }
            if (mod.getPlayer() != null && existingBed.getSquaredDistance(mod.getPlayer().getBlockPos()) > 9) {
                setDebugState("Walking to campsite bed");
                return cacheActive(mod, new GetToBlockTask(existingBed.offset(Direction.SOUTH)));
            }
            setDebugState("Clicking campsite bed to set spawn");
            _clickingBedToSetSpawn = true;
            return cacheActive(mod, new InteractWithBlockTask(existingBed));
        }
        if (!mod.getWorld().getBlockState(bed).isAir()) {
            setDebugState("Clearing campsite bed position");
            return cacheActive(mod, new DestroyBlockTask(bed));
        }
        BlockPos second = bed.offset(Direction.NORTH);
        if (!mod.getWorld().getBlockState(second).isAir()) {
            setDebugState("Clearing campsite bed head position");
            return cacheActive(mod, new DestroyBlockTask(second));
        }
        if (!mod.getItemStorage().hasItem(ItemHelper.BED)) {
            setDebugState("Collecting campsite bed");
            return cacheActive(mod, TaskCatalogue.getItemTask("bed", 1));
        }

        // Beds are directional two-block fixtures. The generic one-block
        // builder cannot control their facing, so it may place the head in the
        // player's standing cell, reject the click, wander, and eventually dig
        // out the support it was trying to use. Validate both footprint
        // supports and own the complete placement transaction here instead.
        BlockPos footSupport = bed.down();
        BlockPos headSupport = second.down();
        if (!WorldHelper.isSolid(mod, footSupport)) {
            setDebugState("Repairing campsite bed foot support");
            return cacheActive(mod, new PlaceBlockTask(footSupport,
                    new Block[]{Blocks.COBBLESTONE}, false, true));
        }
        if (!WorldHelper.isSolid(mod, headSupport)) {
            setDebugState("Repairing campsite bed head support");
            return cacheActive(mod, new PlaceBlockTask(headSupport,
                    new Block[]{Blocks.COBBLESTONE}, false, true));
        }
        BlockPos stand = bed.offset(Direction.SOUTH);
        if (mod.getPlayer() == null
                || !stand.equals(mod.getPlayer().getBlockPos())
                || mod.getPlayer().getEyePos().squaredDistanceTo(Vec3d.ofCenter(bed)) > 20.25) {
            setDebugState("Standing by campsite bed position");
            return cacheActive(mod, new GetToBlockTask(stand));
        }
        setDebugState("Placing campsite bed");
        rememberBed(bed, "placing");
        _clickingBedToSetSpawn = false;
        _activeTask = null;
        if (MinecraftClient.getInstance().currentScreen != null) {
            StorageHelper.closeScreen();
            return null;
        }
        if (_bedPlacementCooldown-- > 0) return null;
        if (!mod.getSlotHandler().forceEquipItem(new ItemTarget(ItemHelper.BED, 1), false)) {
            return cacheActive(mod, TaskCatalogue.getItemTask("bed", 1));
        }

        // BedItem uses the player's horizontal facing to choose its head cell.
        // Face north so this fixed campsite footprint is always foot=bed and
        // head=bed.north, away from the player standing immediately south.
        mod.getInputControls().forceLook(180.0F, 55.0F);
        Vec3d hit = Vec3d.ofCenter(footSupport).add(0, 0.5, 0);
        BlockHitResult result = new BlockHitResult(hit, Direction.UP, footSupport, false);
        ActionResult action = mod.getController().interactBlock(mod.getPlayer(), Hand.MAIN_HAND, result);
        mod.getPlayer().swingHand(Hand.MAIN_HAND);
        _bedPlacementCooldown = 5;
        _bedPlacementAttempts++;
        DebugLogger.getInstance().logImmediate("BASE-BED",
                "direct-place attempt=" + _bedPlacementAttempts
                        + " foot=" + bed + " head=" + second
                        + " stand=" + stand + " player=" + mod.getPlayer().getBlockPos()
                        + " footSupport=" + mod.getWorld().getBlockState(footSupport).getBlock()
                        + " headSupport=" + mod.getWorld().getBlockState(headSupport).getBlock()
                        + " action=" + action);
        return null;
    }

    private boolean hasBedInCamp(Belfegor mod) {
        return findCampBed(mod) != null;
    }

    private BlockPos findCampBed(Belfegor mod) {
        BlockPos bed = _home.add(-2, 0, -2);
        BlockPos[] candidates = new BlockPos[]{
                bed,
                bed.offset(Direction.NORTH),
                bed.offset(Direction.SOUTH),
                bed.offset(Direction.EAST),
                bed.offset(Direction.WEST)
        };
        for (BlockPos candidate : candidates) {
            if (mod.getWorld().getBlockState(candidate).getBlock() instanceof net.minecraft.block.BedBlock) {
                return candidate;
            }
        }
        return null;
    }

    private void rememberBed(BlockPos bed, String status) {
        String dim = WorldHelper.getCurrentDimension().name();
        LocationMemory.getInstance().remember("home_room_bed",
                bed.getX(), bed.getY(), bed.getZ(), dim, status);
        LocationMemory.getInstance().remember("home_spawn_bed",
                bed.getX(), bed.getY(), bed.getZ(), dim, status);
        BaseMemory.getInstance().rememberModule(_home, dim, "bed", "fixture",
                bed, 2, 1, 1, status, "campsite bed/spawn anchor");
    }

    private void rememberRooms() {
        String dim = WorldHelper.getCurrentDimension().name();
        LocationMemory.getInstance().remember("home_room_core",
                _home.getX(), _home.getY(), _home.getZ(), dim, "center");
        LocationMemory.getInstance().remember("home_room_crafting",
                _home.getX() + 2, _home.getY(), _home.getZ() + 2, dim, "crafting_table_anchor");
        LocationMemory.getInstance().remember("home_room_smelting",
                _home.getX() - 2, _home.getY(), _home.getZ() + 2, dim, "furnace_anchor");
        LocationMemory.getInstance().remember("home_room_storage",
                _home.getX() + 2, _home.getY(), _home.getZ() - 2, dim, "chest_anchor");
        BaseStorageMemory.getInstance().rememberChest(_home, dim, _home.add(2, 0, -2),
                "camp_storage", false, "core camp storage anchor");
        LocationMemory.getInstance().remember("home_room_entrance",
                _home.getX() + _radius, _home.getY(), _home.getZ(), dim, "two_wide_east_doorway");
        LocationMemory.getInstance().remember("home_door",
                _home.getX() + _radius, _home.getY(), _home.getZ(), dim, "two_wide_east_doorway");
        BaseMemory memory = BaseMemory.getInstance();
        memory.rememberModule(_home, dim, "core", "room",
                _home.add(-_radius + 1, 0, -_radius + 1), _radius * 2 - 1,
                _radius * 2 - 1, WALL_HEIGHT, "planned", "central living/work area");
        memory.rememberModule(_home, dim, "perimeter_wall", "defense", _home.add(-_radius, 0, -_radius),
                _radius * 2 + 1, _radius * 2 + 1, WALL_HEIGHT, "planned",
                "four-high wall with two-wide east doorway and five-block exterior clearance");
        memory.rememberModule(_home, dim, "interior_dividers", "rooms", _home.add(-_radius + 2, 0, -_radius + 2),
                _radius * 2 - 3, _radius * 2 - 3, 3, "planned",
                "cross-shaped divider walls with door gaps for four functional wings");
        memory.rememberModule(_home, dim, "crafting_workshop", "utility", _home.add(2, 0, 2),
                Math.max(3, _radius - 2), Math.max(3, _radius - 2), 2, "planned",
                "crafting and general work area");
        memory.rememberModule(_home, dim, "smelting_workshop", "utility", _home.add(-2, 0, 2),
                Math.max(3, _radius - 2), Math.max(3, _radius - 2), 2, "planned",
                "furnace and future smelter area");
        memory.rememberModule(_home, dim, "storage_wing", "utility", _home.add(2, 0, -2),
                Math.max(3, _radius - 2), Math.max(3, _radius - 2), 2, "planned",
                "chest and shulker staging area");
        memory.rememberModule(_home, dim, "entrance", "door",
                _home.add(_radius, 0, 0), 1, 2, 3, "complete",
                "remembered two-wide east doorway with protected wooden doors for safe base entry/exit");
        inspectBaseFootprint();
        LocationMemory.getInstance().save();
        BaseMemory.getInstance().save();
        BaseStorageMemory.getInstance().save();
    }

    private void rememberBase(String status) {
        String dim = WorldHelper.getCurrentDimension().name();
        BaseMemory.getInstance().rememberBase(_home, dim, _radius, WALL_HEIGHT,
                EXTERIOR_CLEARANCE, status);
    }

    private void rememberProgress(String module, int done, int total, String status, String note) {
        String dim = WorldHelper.getCurrentDimension().name();
        BaseMemory.getInstance().rememberModuleProgress(_home, dim, module, done, total, status, note);
    }

    private void inspectBaseFootprint() {
        String dim = WorldHelper.getCurrentDimension().name();
        int checked = (_radius * 2 + 1) * (_radius * 2 + 1);
        int wallBlocks = _wallTargets == null ? 0 : _wallTargets.size();
        int roomBlocks = _roomTargets == null ? 0 : _roomTargets.size();
        BaseMemory memory = BaseMemory.getInstance();
        memory.rememberInspection(_home, dim, "perimeter_wall", "blueprint",
                checked, 0, wallBlocks, 0, "planned",
                "perimeter wall targets generated with five-block exterior clearance");
        memory.rememberInspection(_home, dim, "interior_dividers", "blueprint",
                checked, 0, roomBlocks, 0, "planned",
                "room centers registered for core/crafting/smelting/storage");
    }

    private void markCoreModulesComplete() {
        String dim = WorldHelper.getCurrentDimension().name();
        BaseMemory memory = BaseMemory.getInstance();
        String[] modules = {
                "core", "perimeter_wall", "interior_dividers",
                "crafting_workshop", "smelting_workshop", "storage_wing"
        };
        for (String module : modules) {
            memory.rememberModuleProgress(_home, dim, module, 1, 1,
                    "complete", "validated by completed campsite build");
        }
    }

    private void exportCurrentCoreSchematic() {
        try {
            String dim = WorldHelper.getCurrentDimension().name();
            BelfegorSchematic schematic = exportCoreSchematic(dim, _home, _radius);
            BaseMemory.getInstance().rememberInspection(_home, dim, "base_core_schematic",
                    "schematic", schematic.blocks.size(), 0, 0, schematic.blocks.size(),
                    "exported", "saved authoritative base-core blueprint for validation");
        } catch (Exception e) {
            Debug.logWarning("Failed to export campsite schematic: " + e.getMessage());
        }
    }

    private void nextPhase(Phase next) {
        _phase = next;
        _index = 0;
        _activeTask = null;
        persistPhase();
    }

    private void persistPhase() {
        String dimension = WorldHelper.getCurrentDimension().name();
        if (_phase == Phase.DONE) {
            BaseMemory.getInstance().clearBuildPhase(_home, dimension, "camp");
        } else {
            BaseMemory.getInstance().rememberBuildPhase(_home, dimension, "camp", _phase.name());
        }
        BaseMemory.getInstance().save();
    }

    private Task returnToBuildPlatformIfDrifted(Belfegor mod) {
        if (mod.getPlayer() == null) return null;
        BlockPos player = mod.getPlayer().getBlockPos();
        int margin = 2;
        boolean outsideCore = Math.abs(player.getX() - _home.getX()) > _radius + margin
                || Math.abs(player.getZ() - _home.getZ()) > _radius + margin;
        boolean belowBuildPlane = player.getY() < _home.getY() - 1;
        if (belowBuildPlane) {
            if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)
                    || !(_activeTask instanceof RecoverToYLevelTask)) {
                _activeTask = new RecoverToYLevelTask(_home.getY(), 0);
            }
            setDebugState("Recovering to campsite Y before continuing build player="
                    + player.toShortString() + " home=" + _home.toShortString());
            return _activeTask;
        }
        if (!outsideCore) return null;
        if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)
                || !(_activeTask instanceof GetToBlockTask)) {
            _activeTask = new GetToBlockTask(_home);
        }
        setDebugState("Returning to campsite platform before continuing build player="
                + player.toShortString() + " home=" + _home.toShortString());
        return _activeTask;
    }

    private boolean isInteriorClearObstacle(Block block) {
        // _home is the player's feet/build-plane Y and the preserved natural
        // floor is _home.down(). Dirt or grass at _home is therefore raised
        // terrain inside the room, not a usable floor. Leaving it in place made
        // the bot dig a one-block fixture hole, fall into it, and retry chest
        // placement forever. Fluids are handled by site selection/fill logic;
        // repeatedly "mining" a source block here would not clear it.
        return block != Blocks.WATER && block != Blocks.LAVA;
    }

    private boolean isExteriorClearanceObstacle(Block block) {
        if (block == Blocks.GRASS_BLOCK
                || block == Blocks.DIRT
                || block == Blocks.COARSE_DIRT
                || block == Blocks.PODZOL
                || block == Blocks.FARMLAND
                || block == Blocks.TALL_GRASS
                || block == Blocks.FERN
                || block == Blocks.LARGE_FERN
                || block == Blocks.DANDELION
                || block == Blocks.POPPY) {
            return false;
        }
        String key = block.getTranslationKey();
        return key.contains("_log")
                || key.contains("_wood")
                || key.contains("mushroom")
                || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH;
    }

    private boolean clearDone(Belfegor mod, BlockPos pos) {
        if (BaseMemory.getInstance().isProtectedFixturePosition(pos, WorldHelper.getCurrentDimension().name())) {
            return true;
        }
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) return true;
        if (_wallTargets != null && _wallTargets.contains(pos) && block == Blocks.COBBLESTONE) return true;
        if (_roomTargets != null && _roomTargets.contains(pos) && block == Blocks.COBBLESTONE) return true;
        if (_floorTargets != null && _floorTargets.contains(pos) && isAcceptableFlatFloor(block)) return true;
        return false;
    }

    private boolean floorDone(Belfegor mod, BlockPos pos) {
        return isAcceptableFlatFloor(mod.getWorld().getBlockState(pos).getBlock());
    }

    private List<BlockPos> missingFloorTargets(Belfegor mod) {
        ArrayList<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : _floorTargets) {
            if (!floorDone(mod, pos)) result.add(pos);
        }
        return result;
    }

    private int countMissingCobblestone(Belfegor mod, List<BlockPos> targets) {
        int missing = 0;
        for (BlockPos pos : targets) {
            if (mod.getWorld().getBlockState(pos).getBlock() != Blocks.COBBLESTONE) missing++;
        }
        return missing;
    }

    private static boolean isAcceptableFlatFloor(Block block) {
        if (block == Blocks.COBBLESTONE
                || block == Blocks.STONE
                || block == Blocks.GRASS_BLOCK
                || block == Blocks.DIRT
                || block == Blocks.COARSE_DIRT
                || block == Blocks.PODZOL
                || block == Blocks.FARMLAND) {
            return true;
        }
        String key = block.getTranslationKey();
        return key.contains("deepslate")
                || key.contains("blackstone")
                || key.contains("terracotta")
                || key.contains("concrete")
                || key.contains("sandstone")
                || key.contains("planks")
                || key.contains("bricks");
    }

    private java.util.Map<BlockPos, Block[]> toTargetMap(List<BlockPos> targets) {
        java.util.LinkedHashMap<BlockPos, Block[]> map = new java.util.LinkedHashMap<>();
        for (BlockPos target : targets) {
            map.put(target, STRUCTURE_BLOCKS);
        }
        return map;
    }

    private boolean targetsClear(Belfegor mod, List<BlockPos> targets) {
        for (BlockPos target : targets) {
            if (!clearDone(mod, target)) return false;
        }
        return true;
    }

    private int countUnclearTargets(Belfegor mod, List<BlockPos> targets) {
        int count = 0;
        for (BlockPos target : targets) {
            if (!clearDone(mod, target)) count++;
        }
        return count;
    }

    private boolean wallBlockDone(Belfegor mod, BlockPos pos) {
        return mod.getWorld().getBlockState(pos).getBlock() == Blocks.COBBLESTONE;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BuildCampsiteTask task
                && task._home.equals(_home)
                && task._radius == _radius;
    }

    @Override
    protected String toDebugString() {
        return "Build expandable base at " + _home.toShortString()
                + " r=" + _radius
                + " phase=" + _phase;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return _phase == Phase.DONE;
    }
}
