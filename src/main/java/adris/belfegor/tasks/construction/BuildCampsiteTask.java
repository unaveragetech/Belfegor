package adris.belfegor.tasks.construction;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.memory.LocationMemory;
import adris.belfegor.tasks.InteractWithBlockTask;
import adris.belfegor.tasks.resources.GetBuildingMaterialsTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds and expands the @player home base.
 *
 * This is intentionally staged instead of "place blocks wherever":
 * 1) clear the inside and a five-block exterior safety gap,
 * 2) flatten/fill the floor,
 * 3) build a four-high perimeter wall,
 * 4) build interior room dividers,
 * 5) build a roofed mob-farm chamber,
 * 6) place utility room anchors,
 * 7) prepare a starter farm plot.
 */
public class BuildCampsiteTask extends Task {

    private static final Block[] WALL_BLOCKS = {
            Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.DIRT, Blocks.NETHERRACK
    };
    private static final Item[] HOES = {
            Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE,
            Items.GOLDEN_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE
    };
    private static final int WALL_HEIGHT = 4;
    private static final int EXTERIOR_CLEARANCE = 5;

    private enum Phase {
        CLEAR,
        FLOOR,
        WALL,
        ROOMS,
        MOB_FARM,
        UTILITY,
        FARM,
        DONE
    }

    private final BlockPos _home;
    private final int _radius;
    private List<BlockPos> _clearTargets;
    private List<BlockPos> _floorTargets;
    private List<BlockPos> _wallTargets;
    private List<BlockPos> _roomTargets;
    private List<BlockPos> _mobFarmTargets;
    private List<BlockPos> _farmTargets;
    private Phase _phase = Phase.CLEAR;
    private int _index;
    private Task _activeTask;

    public BuildCampsiteTask(BlockPos home, int radius) {
        _home = home;
        _radius = Math.max(6, Math.min(18, radius));
    }

    @Override
    protected void onStart(Belfegor mod) {
        _clearTargets = buildClearTargets(mod);
        _floorTargets = buildFloorTargets();
        _wallTargets = buildWallTargets(mod);
        _roomTargets = buildRoomTargets(mod);
        _mobFarmTargets = buildMobFarmTargets(mod);
        _farmTargets = buildFarmTargets();
        _phase = Phase.CLEAR;
        _index = 0;
        _activeTask = null;
        mod.getBehaviour().push();
        mod.getBehaviour().avoidWalkingThrough(pos ->
                pos.getY() >= _home.getY()
                        && pos.getY() <= _home.getY() + WALL_HEIGHT
                        && Math.abs(pos.getX() - _home.getX()) <= _radius
                        && Math.abs(pos.getZ() - _home.getZ()) <= _radius
                        && (Math.abs(pos.getX() - _home.getX()) == _radius
                        || Math.abs(pos.getZ() - _home.getZ()) == _radius));
        rememberBase("started");
        rememberRooms();
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
                Task floor = runFloorPhase(mod);
                if (floor != null) return floor;
                rememberBase("floor_complete");
                nextPhase(Phase.WALL);
                return null;
            }
            case WALL: {
                Task wall = runWallPhase(mod);
                if (wall != null) return wall;
                rememberBase("wall_complete");
                nextPhase(Phase.ROOMS);
                return null;
            }
            case ROOMS: {
                Task rooms = runRoomPhase(mod);
                if (rooms != null) return rooms;
                rememberBase("rooms_complete");
                nextPhase(Phase.MOB_FARM);
                return null;
            }
            case MOB_FARM: {
                Task mobFarm = runMobFarmPhase(mod);
                if (mobFarm != null) return mobFarm;
                rememberBase("mob_farm_complete");
                nextPhase(Phase.UTILITY);
                return null;
            }
            case UTILITY: {
                Task utility = placeUtilityBlocks(mod);
                if (utility != null) return utility;
                rememberBase("utility_complete");
                nextPhase(Phase.FARM);
                return null;
            }
            case FARM: {
                Task farm = runFarmPhase(mod);
                if (farm != null) return farm;
                LocationMemory.getInstance().remember("home_campsite",
                        _home.getX(), _home.getY(), _home.getZ(),
                        WorldHelper.getCurrentDimension().name(),
                        "radius=" + _radius + ";wallHeight=" + WALL_HEIGHT
                                + ";clearance=" + EXTERIOR_CLEARANCE);
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
        BlockPos min = min(_clearTargets);
        BlockPos max = max(_clearTargets);
        if (regionClear(mod, min, max)) return null;
        if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
            _activeTask = new ClearRegionTask(min, max);
        }
        setDebugState("Baritone clearing full campsite volume " + min.toShortString() + " -> " + max.toShortString());
        return _activeTask;
    }

    private Task runFloorPhase(Belfegor mod) {
        int neededBlocks = Math.max(24, missingFloors(mod));
        if (StorageHelper.getBuildingMaterialCount(mod) < Math.min(neededBlocks, 128)) {
            setDebugState("Collecting floor fill materials");
            return new GetBuildingMaterialsTask(Math.min(neededBlocks, 128));
        }
        if (countMissingSolid(mod, _floorTargets) == 0) return null;
        if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
            _activeTask = new BuildRegionSchematicTask("campsite floor", toTargetMap(_floorTargets), true);
        }
        setDebugState("Baritone building campsite floor as one schematic");
        return _activeTask;
    }

    private Task runWallPhase(Belfegor mod) {
        int neededBlocks = Math.max(24, _wallTargets.size() - countFinishedWalls(mod));
        if (StorageHelper.getBuildingMaterialCount(mod) < Math.min(neededBlocks, 160)) {
            setDebugState("Collecting four-high wall materials");
            return new GetBuildingMaterialsTask(Math.min(neededBlocks, 160));
        }

        int missing = countMissingSolid(mod, _wallTargets);
        if (missing == 0) return null;
        if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
            _activeTask = new BuildRegionSchematicTask("campsite perimeter wall", toTargetMap(_wallTargets), true);
        }
        rememberProgress("perimeter_wall", _wallTargets.size() - missing, _wallTargets.size(), "building",
                "placing four-high perimeter wall blocks");
        setDebugState("Baritone building four-high wall as one schematic missing=" + missing);
        return _activeTask;
    }

    private Task runRoomPhase(Belfegor mod) {
        int neededBlocks = Math.max(16, _roomTargets.size() - countFinishedRoomWalls(mod));
        if (StorageHelper.getBuildingMaterialCount(mod) < Math.min(neededBlocks, 160)) {
            setDebugState("Collecting interior room wall materials");
            return new GetBuildingMaterialsTask(Math.min(neededBlocks, 160));
        }

        int missing = countMissingSolid(mod, _roomTargets);
        if (missing == 0) return null;
        if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
            _activeTask = new BuildRegionSchematicTask("campsite interior rooms", toTargetMap(_roomTargets), true);
        }
        rememberProgress("interior_dividers", _roomTargets.size() - missing, _roomTargets.size(), "building",
                "placing room divider blocks");
        setDebugState("Baritone building interior rooms as one schematic missing=" + missing);
        return _activeTask;
    }

    private Task runMobFarmPhase(Belfegor mod) {
        int neededBlocks = Math.max(32, _mobFarmTargets.size() - countFinishedTargets(mod, _mobFarmTargets));
        if (StorageHelper.getBuildingMaterialCount(mod) < Math.min(neededBlocks, 256)) {
            setDebugState("Collecting cobblestone for roofed mob-farm chamber");
            return new GetBuildingMaterialsTask(Math.min(neededBlocks, 256));
        }

        int missing = countMissingSolid(mod, _mobFarmTargets);
        if (missing == 0) {
            rememberProgress("mob_farm_chamber", _mobFarmTargets.size(), _mobFarmTargets.size(),
                    "complete", "four-high roofed chamber with two-wide entrance");
            return null;
        }
        if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
            _activeTask = new BuildRegionSchematicTask("campsite mob farm chamber", toTargetMap(_mobFarmTargets), true);
        }
        rememberProgress("mob_farm_chamber", _mobFarmTargets.size() - missing, _mobFarmTargets.size(), "building",
                "placing walls and roof");
        setDebugState("Baritone building roofed mob-farm chamber as one schematic missing=" + missing);
        return _activeTask;
    }

    private Task runFarmPhase(Belfegor mod) {
        if (!mod.getItemStorage().hasItem(HOES)) {
            Task hoe = TaskCatalogue.getItemTask("wooden_hoe", 1);
            if (hoe != null) {
                setDebugState("Crafting hoe for starter farm");
                return hoe;
            }
        }
        if (!mod.getItemStorage().hasItem(Items.WHEAT_SEEDS)) {
            setDebugState("Starter farm waiting for wheat seeds");
            return null;
        }

        while (_index < _farmTargets.size() && farmDone(mod, _farmTargets.get(_index))) {
            _index++;
        }
        if (_index >= _farmTargets.size()) return null;

        BlockPos soil = _farmTargets.get(_index);
        Block block = mod.getWorld().getBlockState(soil).getBlock();
        if (block != Blocks.FARMLAND) {
            setDebugState("Tilling starter farm soil");
            return new InteractWithBlockTask(new ItemTarget(HOES, 1), Direction.UP, soil, true);
        }
        if (mod.getWorld().getBlockState(soil.up()).isAir()) {
            setDebugState("Planting starter farm seeds");
            return new InteractWithBlockTask(new ItemTarget(Items.WHEAT_SEEDS, 1), Direction.UP, soil, true);
        }
        _index++;
        return null;
    }

    private List<BlockPos> buildClearTargets(Belfegor mod) {
        ArrayList<BlockPos> result = new ArrayList<>();
        int clearRadius = _radius + EXTERIOR_CLEARANCE;
        for (int dx = -clearRadius; dx <= clearRadius; dx++) {
            for (int dz = -clearRadius; dz <= clearRadius; dz++) {
                boolean outsideWallGap = Math.abs(dx) > _radius || Math.abs(dz) > _radius;
                for (int h = 0; h <= WALL_HEIGHT + 1; h++) {
                    BlockPos pos = _home.add(dx, h, dz);
                    if (WorldHelper.isInsidePlayer(mod, pos)) continue;
                    Block block = mod.getWorld().getBlockState(pos).getBlock();
                    if (block == Blocks.AIR) continue;
                    if (outsideWallGap || isNonFloorObstacle(block)) {
                        result.add(pos);
                    }
                }
            }
        }
        return result;
    }

    private List<BlockPos> buildFloorTargets() {
        ArrayList<BlockPos> result = new ArrayList<>();
        for (int dx = -_radius + 1; dx <= _radius - 1; dx++) {
            for (int dz = -_radius + 1; dz <= _radius - 1; dz++) {
                result.add(_home.add(dx, -1, dz));
            }
        }
        return result;
    }

    private List<BlockPos> buildWallTargets(Belfegor mod) {
        ArrayList<BlockPos> result = new ArrayList<>();
        for (int dx = -_radius; dx <= _radius; dx++) {
            for (int dz = -_radius; dz <= _radius; dz++) {
                boolean perimeter = Math.abs(dx) == _radius || Math.abs(dz) == _radius;
                if (!perimeter) continue;
                // Leave a simple two-wide doorway on the east side.
                if (dx == _radius && (dz == 0 || dz == 1)) continue;
                for (int h = 0; h < WALL_HEIGHT; h++) {
                    BlockPos pos = _home.add(dx, h, dz);
                    if (WorldHelper.isInsidePlayer(mod, pos)) continue;
                    result.add(pos);
                }
            }
        }
        return result;
    }

    private List<BlockPos> buildRoomTargets(Belfegor mod) {
        ArrayList<BlockPos> result = new ArrayList<>();
        int inner = Math.max(3, _radius - 2);
        for (int d = -inner; d <= inner; d++) {
            // Central north/south divider, with a two-wide central doorway.
            if (d != 0 && d != 1) {
                for (int h = 0; h < 3; h++) {
                    BlockPos pos = _home.add(0, h, d);
                    if (!WorldHelper.isInsidePlayer(mod, pos)) result.add(pos);
                }
            }
            // Central east/west divider, also with a two-wide central doorway.
            if (d != 0 && d != 1) {
                for (int h = 0; h < 3; h++) {
                    BlockPos pos = _home.add(d, h, 0);
                    if (!WorldHelper.isInsidePlayer(mod, pos)) result.add(pos);
                }
            }
        }
        return result;
    }

    private List<BlockPos> buildMobFarmTargets(Belfegor mod) {
        ArrayList<BlockPos> result = new ArrayList<>();
        int size = getMobFarmSize();
        BlockPos origin = getMobFarmOrigin();
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                boolean wall = dx == 0 || dz == 0 || dx == size - 1 || dz == size - 1;
                boolean doorway = dz == size - 1 && (dx == size / 2 || dx == size / 2 - 1);
                if (wall && !doorway) {
                    for (int h = 0; h < WALL_HEIGHT; h++) {
                        BlockPos pos = origin.add(dx, h, dz);
                        if (!WorldHelper.isInsidePlayer(mod, pos)) result.add(pos);
                    }
                }
                BlockPos roof = origin.add(dx, WALL_HEIGHT, dz);
                if (!WorldHelper.isInsidePlayer(mod, roof)) result.add(roof);
            }
        }
        return result;
    }

    private List<BlockPos> buildFarmTargets() {
        ArrayList<BlockPos> result = new ArrayList<>();
        int farmSize = Math.max(4, Math.min(9, _radius - 2));
        BlockPos origin = _home.add(-_radius + 2, -1, _radius - farmSize - 1);
        for (int dx = 0; dx < farmSize; dx++) {
            for (int dz = 0; dz < farmSize; dz++) {
                result.add(origin.add(dx, 0, dz));
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
        if (!mod.getWorld().getBlockState(target).isAir()) {
            return new DestroyBlockTask(target);
        }
        return new InteractWithBlockTask(new ItemTarget(item, 1), Direction.UP, target.down(), true);
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
        LocationMemory.getInstance().remember("home_room_farm",
                _home.getX() - _radius + 3, _home.getY() - 1, _home.getZ() + 2,
                dim, "crop_plot");
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
        int farmSize = Math.max(4, Math.min(9, _radius - 2));
        memory.rememberModule(_home, dim, "crop_farm", "farm",
                _home.add(-_radius + 2, -1, _radius - farmSize - 1),
                farmSize, farmSize, 1, "planned", "expandable wheat crop plot");
        int mobSize = getMobFarmSize();
        BlockPos mobOrigin = getMobFarmOrigin();
        BlockPos mobCenter = mobOrigin.add(mobSize / 2, 0, mobSize / 2);
        LocationMemory.getInstance().remember("home_room_mob_farm",
                mobCenter.getX(), mobCenter.getY(), mobCenter.getZ(), dim,
                "roofed_dark_room;four_high_walls;two_wide_entrance");
        memory.rememberModule(_home, dim, "mob_farm_chamber", "mob_farm",
                mobOrigin, mobSize, mobSize, WALL_HEIGHT + 1, "planned",
                "large cobblestone roofed chamber with four-block walls and a two-wide entrance");
        memory.rememberModule(_home, dim, "mob_farm_entrance", "access",
                mobOrigin.add(mobSize / 2 - 1, 0, mobSize - 1),
                2, 1, 3, "planned", "two-wide entrance/exit into the roofed mob-farm chamber");
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
        int mobBlocks = _mobFarmTargets == null ? 0 : _mobFarmTargets.size();
        BaseMemory memory = BaseMemory.getInstance();
        memory.rememberInspection(_home, dim, "perimeter_wall", "blueprint",
                checked, 0, wallBlocks, 0, "planned",
                "perimeter wall targets generated with five-block exterior clearance");
        memory.rememberInspection(_home, dim, "interior_dividers", "blueprint",
                checked, 0, roomBlocks, 0, "planned",
                "room centers registered for core/crafting/smelting/storage/farm");
        memory.rememberInspection(_home, dim, "mob_farm_chamber", "blueprint",
                getMobFarmSize() * getMobFarmSize(), 0, mobBlocks, 0, "planned",
                "roof and four-high wall target list generated");
    }

    private void nextPhase(Phase next) {
        _phase = next;
        _index = 0;
        _activeTask = null;
    }

    private boolean isNonFloorObstacle(Block block) {
        return block != Blocks.GRASS_BLOCK
                && block != Blocks.DIRT
                && block != Blocks.COARSE_DIRT
                && block != Blocks.PODZOL
                && block != Blocks.FARMLAND;
    }

    private boolean clearDone(Belfegor mod, BlockPos pos) {
        return mod.getWorld().getBlockState(pos).isAir();
    }

    private boolean floorDone(Belfegor mod, BlockPos pos) {
        return WorldHelper.isSolid(mod, pos);
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

    private java.util.Map<BlockPos, Block[]> toTargetMap(List<BlockPos> targets) {
        java.util.LinkedHashMap<BlockPos, Block[]> map = new java.util.LinkedHashMap<>();
        for (BlockPos target : targets) {
            map.put(target, WALL_BLOCKS);
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

    private boolean regionClear(Belfegor mod, BlockPos min, BlockPos max) {
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    if (!mod.getWorld().getBlockState(new BlockPos(x, y, z)).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private int getMobFarmSize() {
        return Math.max(7, Math.min(13, _radius));
    }

    private BlockPos getMobFarmOrigin() {
        int size = getMobFarmSize();
        return _home.add(-_radius + 2, 0, -_radius + 2);
    }

    private boolean wallBlockDone(Belfegor mod, BlockPos pos) {
        return WorldHelper.isSolid(mod, pos);
    }

    private boolean farmDone(Belfegor mod, BlockPos soil) {
        return mod.getWorld().getBlockState(soil).getBlock() == Blocks.FARMLAND
                && mod.getWorld().getBlockState(soil.up()).getBlock() == Blocks.WHEAT;
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
