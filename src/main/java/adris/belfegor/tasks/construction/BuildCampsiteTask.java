package adris.belfegor.tasks.construction;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.memory.LocationMemory;
import adris.belfegor.schematic.BelfegorSchematic;
import adris.belfegor.tasks.InteractWithBlockTask;
import adris.belfegor.tasks.movement.GetToBlockTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        CLEAR,
        FLOOR,
        WALL,
        ROOMS,
        UTILITY,
        DONE
    }

    private final BlockPos _home;
    private final int _radius;
    private List<BlockPos> _clearTargets;
    private List<BlockPos> _floorTargets;
    private List<BlockPos> _wallTargets;
    private List<BlockPos> _roomTargets;
    private Map<BlockPos, Block> _protectedBlueprintTargets;
    private Phase _phase = Phase.CLEAR;
    private int _index;
    private Task _activeTask;

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
        _phase = Phase.CLEAR;
        _index = 0;
        _activeTask = null;
        mod.getBehaviour().push();
        exportCurrentCoreSchematic();
        mod.getBehaviour().setAutoMLG(false);
        mod.getBehaviour().setAllowDiagonalAscend(false);
        mod.getBehaviour().avoidWalkingThrough(pos ->
                pos.getY() >= _home.getY()
                        && pos.getY() <= _home.getY() + WALL_HEIGHT
                        && Math.abs(pos.getX() - _home.getX()) <= _radius
                        && Math.abs(pos.getZ() - _home.getZ()) <= _radius
                        && (Math.abs(pos.getX() - _home.getX()) == _radius
                        || Math.abs(pos.getZ() - _home.getZ()) == _radius));
        mod.getBehaviour().avoidBlockBreaking(this::isCompletedBlueprintBlock);
        rememberBase("started");
        rememberRooms();
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

    private boolean modWorldUnavailable() {
        return net.minecraft.client.MinecraftClient.getInstance() == null
                || net.minecraft.client.MinecraftClient.getInstance().world == null;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        switch (_phase) {
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
                Task utility = placeUtilityBlocks(mod);
                if (utility != null) return utility;
                rememberBase("utility_complete");
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
        int floorBatch = Math.min(neededBlocks, 32);
        if (carriedCobble < Math.min(neededBlocks, 8)) {
            setDebugState("Collecting small cobblestone patch batch for unsafe campsite floor cells carried="
                    + carriedCobble + " needed=" + neededBlocks);
            return TaskCatalogue.getItemTask("cobblestone", floorBatch);
        }
        if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
            _activeTask = new BuildRegionSchematicTask("campsite floor patches",
                    toTargetMap(missingFloorTargets), false);
        }
        setDebugState("Patching unsafe campsite floor cells missing=" + missingFloorTargets.size());
        return _activeTask;
    }

    private Task runWallPhase(Belfegor mod) {
        int neededBlocks = Math.max(24, _wallTargets.size() - countFinishedWalls(mod));
        int carriedCobble = mod.getItemStorage().getItemCountInventoryOnly(Items.COBBLESTONE);
        int wallBatch = Math.min(neededBlocks, 128);
        if (carriedCobble < Math.min(neededBlocks, 32)) {
            setDebugState("Collecting carried cobblestone for four-high wall carried=" + carriedCobble
                    + " needed=" + neededBlocks);
            return TaskCatalogue.getItemTask("cobblestone", wallBatch);
        }

        int totalMissing = countMissingCobblestone(mod, _wallTargets);
        if (totalMissing == 0) return null;
        while (_index < WALL_HEIGHT && countMissingCobblestone(mod, wallLayerTargets(_index)) == 0) {
            _index++;
            _activeTask = null;
        }
        if (_index >= WALL_HEIGHT) return null;
        List<BlockPos> layerTargets = wallLayerTargets(_index);
        int missing = countMissingCobblestone(mod, layerTargets);
        if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
            _activeTask = new BuildRegionSchematicTask("campsite perimeter wall layer " + (_index + 1),
                    toTargetMap(layerTargets), false);
        }
        rememberProgress("perimeter_wall", _wallTargets.size() - totalMissing, _wallTargets.size(), "building",
                "placing four-high perimeter wall blocks layer=" + (_index + 1) + "/" + WALL_HEIGHT);
        setDebugState("Baritone building wall layer " + (_index + 1) + "/" + WALL_HEIGHT
                + " missing=" + missing + " totalMissing=" + totalMissing);
        return _activeTask;
    }

    private Task runRoomPhase(Belfegor mod) {
        int neededBlocks = Math.max(16, _roomTargets.size() - countFinishedRoomWalls(mod));
        int carriedCobble = mod.getItemStorage().getItemCountInventoryOnly(Items.COBBLESTONE);
        int roomBatch = Math.min(neededBlocks, 96);
        if (carriedCobble < Math.min(neededBlocks, 24)) {
            setDebugState("Collecting carried cobblestone for interior room walls carried=" + carriedCobble
                    + " needed=" + neededBlocks);
            return TaskCatalogue.getItemTask("cobblestone", roomBatch);
        }

        int missing = countMissingCobblestone(mod, _roomTargets);
        if (missing == 0) return null;
        if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
            _activeTask = new BuildRegionSchematicTask("campsite interior rooms", toTargetMap(_roomTargets), false);
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
                    } else if (isInteriorClearObstacle(block, h)) {
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
            return placeFixture(mod, table, Items.CRAFTING_TABLE);
        }

        BlockPos furnace = _home.add(-2, 0, 2);
        if (mod.getItemStorage().hasItem(Items.FURNACE)
                && mod.getWorld().getBlockState(furnace).getBlock() != Blocks.FURNACE) {
            setDebugState("Placing smelting room furnace");
            return placeFixture(mod, furnace, Items.FURNACE);
        }

        BlockPos chest = _home.add(2, 0, -2);
        if (mod.getItemStorage().hasItem(Items.CHEST)
                && mod.getWorld().getBlockState(chest).getBlock() != Blocks.CHEST) {
            setDebugState("Placing storage room chest");
            return placeFixture(mod, chest, Items.CHEST);
        }
        return null;
    }

    private Task placeFixture(Belfegor mod, BlockPos target, Item item) {
        BlockPos stand = fixtureStandPosition(mod, target);
        if (stand != null && mod.getPlayer() != null
                && stand.getSquaredDistance(mod.getPlayer().getBlockPos()) > 4) {
            return new GetToBlockTask(stand);
        }
        if (!mod.getWorld().getBlockState(target).isAir()) {
            return new DestroyBlockTask(target);
        }
        return new InteractWithBlockTask(new ItemTarget(item, 1), Direction.UP, target.down(), true);
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
        inspectBaseFootprint();
        LocationMemory.getInstance().save();
        BaseMemory.getInstance().save();
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
    }

    private Task returnToBuildPlatformIfDrifted(Belfegor mod) {
        if (mod.getPlayer() == null) return null;
        BlockPos player = mod.getPlayer().getBlockPos();
        int margin = 2;
        boolean outsideCore = Math.abs(player.getX() - _home.getX()) > _radius + margin
                || Math.abs(player.getZ() - _home.getZ()) > _radius + margin;
        if (!outsideCore) return null;
        if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)
                || !(_activeTask instanceof GetToBlockTask)) {
            _activeTask = new GetToBlockTask(_home);
        }
        setDebugState("Returning to campsite platform before continuing build player="
                + player.toShortString() + " home=" + _home.toShortString());
        return _activeTask;
    }

    private boolean isInteriorClearObstacle(Block block, int heightAboveHome) {
        if (heightAboveHome > 0) {
            return block != Blocks.WATER;
        }
        return block != Blocks.GRASS_BLOCK
                && block != Blocks.DIRT
                && block != Blocks.COARSE_DIRT
                && block != Blocks.PODZOL
                && block != Blocks.FARMLAND;
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

    private int missingFloors(Belfegor mod) {
        int missing = 0;
        for (BlockPos pos : _floorTargets) {
            if (!floorDone(mod, pos)) missing++;
        }
        return missing;
    }

    private int countFinishedWalls(Belfegor mod) {
        int count = 0;
        for (BlockPos pos : _wallTargets) {
            if (wallBlockDone(mod, pos)) count++;
        }
        return count;
    }

    private int countFinishedRoomWalls(Belfegor mod) {
        return countFinishedTargets(mod, _roomTargets);
    }

    private int countFinishedTargets(Belfegor mod, List<BlockPos> targets) {
        int count = 0;
        for (BlockPos pos : targets) {
            if (wallBlockDone(mod, pos)) count++;
        }
        return count;
    }

    private int countMissingSolid(Belfegor mod, List<BlockPos> targets) {
        int missing = 0;
        for (BlockPos pos : targets) {
            if (!WorldHelper.isSolid(mod, pos)) missing++;
        }
        return missing;
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

    private BlockPos min(List<BlockPos> positions) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (BlockPos pos : positions) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
        }
        return new BlockPos(minX, minY, minZ);
    }

    private BlockPos max(List<BlockPos> positions) {
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : positions) {
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return new BlockPos(maxX, maxY, maxZ);
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
