package adris.belfegor.tasks.construction;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.memory.LocationMemory;
import adris.belfegor.tasks.InteractWithBlockTask;
import adris.belfegor.tasks.movement.GetToBlockTask;
import adris.belfegor.tasks.resources.CollectBucketLiquidTask;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Builds a remembered room expansion off the persistent @player home base.
 *
 * Expansions are intentionally modular:
 * - choose one side of the existing base/module graph,
 * - carve a two-wide 3-5 block hall,
 * - build a named room shell,
 * - add type-specific internals such as hydrated farmland, storage, or a roofed mob room,
 * - persist the center and connection metadata so @home <room> can route there later.
 */
public class BuildBaseExpansionTask extends Task {

    public enum RoomType {
        FARMLAND,
        STORAGE,
        WORKSHOP,
        MOBFARM,
        EMPTY
    }

    private static final Block[] WALL_BLOCKS = {
            Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.DIRT, Blocks.NETHERRACK
    };
    private static final Item[] HOES = {
            Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE,
            Items.GOLDEN_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE
    };
    private static final int WALL_HEIGHT = 4;
    private static final int HALL_WIDTH = 2;

    private enum Phase {
        PLAN,
        GO_HOME,
        CLEAR,
        FLOOR,
        WALLS,
        ROOF,
        FIXTURES,
        FARM_WATER,
        FARM_TILL,
        FARM_PLANT,
        DONE
    }

    private final RoomType _type;
    private final String _requestedName;
    private Phase _phase = Phase.PLAN;
    private Task _activeTask;
    private int _index;

    private BlockPos _baseCenter;
    private BaseMemory.BaseRecord _base;
    private String _dimension;
    private String _roomName;
    private Direction _direction;
    private int _hallLength;
    private int _roomSize;
    private BlockPos _roomAnchor;
    private BlockPos _roomCenter;
    private List<BlockPos> _clearTargets = List.of();
    private List<BlockPos> _floorTargets = List.of();
    private List<BlockPos> _wallTargets = List.of();
    private List<BlockPos> _roofTargets = List.of();
    private List<BlockPos> _waterTargets = List.of();
    private List<BlockPos> _farmTargets = List.of();
    private List<BlockRegion> _clearRegions = List.of();

    public BuildBaseExpansionTask(RoomType type, String requestedName) {
        _type = type == null ? RoomType.EMPTY : type;
        _requestedName = requestedName == null || requestedName.isBlank()
                ? defaultName(_type)
                : normalize(requestedName);
    }

    @Override
    protected void onStart(Belfegor mod) {
        _phase = Phase.PLAN;
        _activeTask = null;
        _index = 0;
        mod.getBehaviour().push();
    }

    @Override
    protected Task onTick(Belfegor mod) {
        switch (_phase) {
            case PLAN -> {
                plan(mod);
                remember("planned");
                next(Phase.GO_HOME);
                return null;
            }
            case GO_HOME -> {
                if (_baseCenter != null && mod.getPlayer() != null
                        && _baseCenter.getSquaredDistance(mod.getPlayer().getBlockPos()) > 20 * 20) {
                    setDebugState("Returning to base before building " + _roomName);
                    return cache(mod, new GetToBlockTask(_baseCenter));
                }
                next(Phase.CLEAR);
                return null;
            }
            case CLEAR -> {
                Task clear = runClearRegions(mod);
                if (clear != null) return clear;
                remember("clear_complete");
                next(Phase.FLOOR);
                return null;
            }
            case FLOOR -> {
                Task floor = _type == RoomType.FARMLAND
                        ? runBuildRegion(mod, _floorTargets, "dirt farm floor", false, Blocks.DIRT)
                        : runBuildRegion(mod, _floorTargets, "floor", true, WALL_BLOCKS);
                if (floor != null) return floor;
                remember("floor_complete");
                next(Phase.WALLS);
                return null;
            }
            case WALLS -> {
                Task walls = runBuildRegion(mod, _wallTargets, "walls", true, WALL_BLOCKS);
                if (walls != null) return walls;
                remember("walls_complete");
                next(_roofTargets.isEmpty() ? Phase.FIXTURES : Phase.ROOF);
                return null;
            }
            case ROOF -> {
                Task roof = runBuildRegion(mod, _roofTargets, "roof", true, WALL_BLOCKS);
                if (roof != null) return roof;
                remember("roof_complete");
                next(Phase.FIXTURES);
                return null;
            }
            case FIXTURES -> {
                Task fixtures = runFixtures(mod);
                if (fixtures != null) return fixtures;
                next(_type == RoomType.FARMLAND ? Phase.FARM_WATER : Phase.DONE);
                return null;
            }
            case FARM_WATER -> {
                Task water = runFarmWater(mod);
                if (water != null) return water;
                remember("water_complete");
                next(Phase.FARM_TILL);
                return null;
            }
            case FARM_TILL -> {
                Task till = runFarmTill(mod);
                if (till != null) return till;
                remember("tilled");
                next(Phase.FARM_PLANT);
                return null;
            }
            case FARM_PLANT -> {
                Task plant = runFarmPlant(mod);
                if (plant != null) return plant;
                remember("complete");
                next(Phase.DONE);
                return null;
            }
            case DONE -> {
                return null;
            }
        }
        return null;
    }

    private void plan(Belfegor mod) {
        _dimension = WorldHelper.getCurrentDimension().name();
        BlockPos playerPos = mod.getPlayer() == null ? BlockPos.ORIGIN : mod.getPlayer().getBlockPos();
        _base = BaseMemory.getInstance().nearestBase(playerPos, _dimension)
                .orElseGet(() -> BaseMemory.getInstance().rememberBase(
                        mod.getModSettings().getHomeBasePosition() == null
                                ? playerPos
                                : mod.getModSettings().getHomeBasePosition(),
                        _dimension, 8, WALL_HEIGHT, 5, "created_by_build_command"));
        _baseCenter = _base.center();
        mod.getModSettings().setHomeBasePosition(_baseCenter);

        _roomName = uniqueRoomName(_base, _requestedName, _type);
        _direction = chooseDirection(_base);
        _hallLength = 3 + Math.min(2, BaseMemory.getInstance().countModulesOfType(_base, _type.name().toLowerCase(Locale.ROOT)) % 3);
        _roomSize = switch (_type) {
            case FARMLAND -> 9;
            case MOBFARM -> 11;
            case STORAGE, WORKSHOP -> 7;
            case EMPTY -> 7;
        };

        int baseRadius = Math.max(8, _base.radius);
        _roomCenter = switch (_direction) {
            case NORTH -> _baseCenter.add(0, 0, -baseRadius - _hallLength - (_roomSize / 2) - 1);
            case SOUTH -> _baseCenter.add(0, 0, baseRadius + _hallLength + (_roomSize / 2) + 1);
            case WEST -> _baseCenter.add(-baseRadius - _hallLength - (_roomSize / 2) - 1, 0, 0);
            default -> _baseCenter.add(baseRadius + _hallLength + (_roomSize / 2) + 1, 0, 0);
        };
        _roomAnchor = _roomCenter.add(-_roomSize / 2, 0, -_roomSize / 2);

        _clearTargets = buildClearTargets();
        _floorTargets = buildFloorTargets();
        _wallTargets = buildWallTargets();
        _roofTargets = _type == RoomType.MOBFARM ? buildRoofTargets() : List.of();
        _waterTargets = _type == RoomType.FARMLAND ? buildWaterTargets() : List.of();
        _farmTargets = _type == RoomType.FARMLAND ? buildFarmTargets() : List.of();
        _clearRegions = buildClearRegions();
    }

    private Task runClearRegions(Belfegor mod) {
        while (_index < _clearRegions.size() && _clearRegions.get(_index).isClear(mod)) {
            _index++;
        }
        if (_index >= _clearRegions.size()) return null;
        BlockRegion region = _clearRegions.get(_index);
        setDebugState("Baritone clearing " + _roomName + " region " + (_index + 1) + "/" + _clearRegions.size());
        return cache(mod, new ClearRegionTask(region.min(), region.max()));
    }

    private Task runBuildRegion(Belfegor mod, List<BlockPos> targets, String label,
                                boolean useThrowaways, Block... desired) {
        if (targets.isEmpty()) return null;
        int needed = 0;
        for (BlockPos target : targets) {
            if (!targetDone(mod, target, useThrowaways, desired)) needed++;
        }
        if (needed > 0 && useThrowaways && StorageHelper.getBuildingMaterialCount(mod) < Math.min(needed, 160)) {
            setDebugState("Collecting materials for " + _roomName + " " + label);
            return new GetBuildingMaterialsTask(Math.min(Math.max(needed, 32), 160));
        }
        if (needed > 0 && !useThrowaways && mod.getItemStorage().getItemCount(blockItems(desired)) < Math.min(needed, 64)) {
            if (desired.length == 1 && desired[0] == Blocks.DIRT) {
                Task dirt = TaskCatalogue.getItemTask("dirt", Math.min(Math.max(needed, 32), 64));
                if (dirt != null) return dirt;
            }
        }
        if (needed <= 0) return null;
        setDebugState("Baritone building " + _roomName + " " + label + " as one schematic");
        return cache(mod, new BuildRegionSchematicTask(_roomName + " " + label, targets, useThrowaways, desired));
    }

    private Task runFarmDirtFloor(Belfegor mod) {
        int needed = 0;
        for (BlockPos target : _floorTargets) {
            Block block = mod.getWorld().getBlockState(target).getBlock();
            if (block != Blocks.DIRT && block != Blocks.GRASS_BLOCK && block != Blocks.FARMLAND && block != Blocks.WATER) {
                needed++;
            }
        }
        if (needed > 0 && mod.getItemStorage().getItemCount(Items.DIRT) < Math.min(needed, 64)) {
            Task dirt = TaskCatalogue.getItemTask("dirt", Math.min(Math.max(needed, 32), 64));
            if (dirt != null) return dirt;
        }
        while (_index < _floorTargets.size()) {
            Block block = mod.getWorld().getBlockState(_floorTargets.get(_index)).getBlock();
            if (block == Blocks.DIRT || block == Blocks.GRASS_BLOCK || block == Blocks.FARMLAND || block == Blocks.WATER) {
                _index++;
                continue;
            }
            break;
        }
        if (_index >= _floorTargets.size()) return null;
        BlockPos target = _floorTargets.get(_index);
        setDebugState("Laying dirt farm floor " + (_index + 1) + "/" + _floorTargets.size());
        if (!mod.getWorld().getBlockState(target).isAir()) {
            return cache(mod, new DestroyBlockTask(target));
        }
        return new InteractWithBlockTask(new ItemTarget(Items.DIRT, 1), Direction.UP, target.down(), true);
    }

    private Task runFixtures(Belfegor mod) {
        if (_type == RoomType.STORAGE) {
            BlockPos chest = _roomCenter;
            if (!mod.getItemStorage().hasItem(Items.CHEST)) {
                return TaskCatalogue.getItemTask("chest", 1);
            }
            if (mod.getWorld().getBlockState(chest).getBlock() != Blocks.CHEST) {
                setDebugState("Placing storage room chest");
                return placeFixture(mod, chest, Items.CHEST);
            }
        }
        if (_type == RoomType.WORKSHOP) {
            BlockPos table = _roomCenter.add(-1, 0, 0);
            if (!mod.getItemStorage().hasItem(Items.CRAFTING_TABLE)) {
                return TaskCatalogue.getItemTask("crafting_table", 1);
            }
            if (mod.getWorld().getBlockState(table).getBlock() != Blocks.CRAFTING_TABLE) {
                setDebugState("Placing workshop crafting table");
                return placeFixture(mod, table, Items.CRAFTING_TABLE);
            }
            BlockPos furnace = _roomCenter.add(1, 0, 0);
            if (!mod.getItemStorage().hasItem(Items.FURNACE)) {
                Task furnaceTask = TaskCatalogue.getItemTask("furnace", 1);
                if (furnaceTask != null) return furnaceTask;
            }
            if (mod.getItemStorage().hasItem(Items.FURNACE)
                    && mod.getWorld().getBlockState(furnace).getBlock() != Blocks.FURNACE) {
                setDebugState("Placing workshop furnace");
                return placeFixture(mod, furnace, Items.FURNACE);
            }
        }
        remember("complete");
        return null;
    }

    private Task placeFixture(Belfegor mod, BlockPos target, Item item) {
        if (!mod.getWorld().getBlockState(target).isAir()) {
            return cache(mod, new DestroyBlockTask(target));
        }
        return new InteractWithBlockTask(new ItemTarget(item, 1), Direction.UP, target.down(), true);
    }

    private Task runFarmWater(Belfegor mod) {
        for (BlockPos water : _waterTargets) {
            if (mod.getWorld().getBlockState(water).getBlock() == Blocks.WATER) continue;
            if (mod.getWorld().getBlockState(water).getBlock() != Blocks.AIR) {
                setDebugState("Digging hydration hole");
                return cache(mod, new DestroyBlockTask(water));
            }
            if (!mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                return new CollectBucketLiquidTask.CollectWaterBucketTask(1);
            }
            setDebugState("Placing farmland water source");
            return new InteractWithBlockTask(new ItemTarget(Items.WATER_BUCKET, 1),
                    Direction.UP, water.down(), true);
        }
        return null;
    }

    private Task runFarmTill(Belfegor mod) {
        if (!mod.getItemStorage().hasItem(HOES)) {
            Task hoe = TaskCatalogue.getItemTask("wooden_hoe", 1);
            if (hoe != null) return hoe;
        }
        while (_index < _farmTargets.size()
                && mod.getWorld().getBlockState(_farmTargets.get(_index)).getBlock() == Blocks.FARMLAND) {
            _index++;
        }
        if (_index >= _farmTargets.size()) return null;
        BlockPos soil = _farmTargets.get(_index);
        if (mod.getWorld().getBlockState(soil).getBlock() == Blocks.WATER) {
            _index++;
            return null;
        }
        setDebugState("Tilling hydrated farmland " + (_index + 1) + "/" + _farmTargets.size());
        return new InteractWithBlockTask(new ItemTarget(HOES, 1), Direction.UP, soil, true);
    }

    private Task runFarmPlant(Belfegor mod) {
        if (!mod.getItemStorage().hasItem(Items.WHEAT_SEEDS)) {
            Task seeds = TaskCatalogue.getItemTask("wheat_seeds", 16);
            if (seeds != null) return seeds;
            return null;
        }
        while (_index < _farmTargets.size()
                && !mod.getWorld().getBlockState(_farmTargets.get(_index).up()).isAir()) {
            _index++;
        }
        if (_index >= _farmTargets.size()) return null;
        BlockPos soil = _farmTargets.get(_index);
        if (mod.getWorld().getBlockState(soil).getBlock() != Blocks.FARMLAND) {
            _index++;
            return null;
        }
        setDebugState("Planting farmland " + (_index + 1) + "/" + _farmTargets.size());
        return new InteractWithBlockTask(new ItemTarget(Items.WHEAT_SEEDS, 1), Direction.UP, soil, true);
    }

    private List<BlockPos> buildClearTargets() {
        ArrayList<BlockPos> result = new ArrayList<>();
        addBoxAir(result, _roomAnchor.add(-1, 0, -1), _roomSize + 2, _roomSize + 2, WALL_HEIGHT + 2);
        addHallAir(result);
        return result;
    }

    private List<BlockRegion> buildClearRegions() {
        ArrayList<BlockRegion> result = new ArrayList<>();
        result.add(new BlockRegion(_roomAnchor.add(-1, 0, -1),
                _roomAnchor.add(_roomSize, WALL_HEIGHT + 1, _roomSize)));
        List<BlockPos> hall = hallFloorPositions();
        if (!hall.isEmpty()) {
            result.add(BlockRegion.fromPositions(hall.stream()
                    .flatMap(floor -> List.of(floor.add(0, 1, 0),
                            floor.add(0, WALL_HEIGHT + 1, 0),
                            floor.offset(_direction.rotateYCounterclockwise()).add(0, 1, 0),
                            floor.offset(_direction.rotateYClockwise()).add(0, WALL_HEIGHT + 1, 0)).stream())
                    .toList()));
        }
        return result;
    }

    private List<BlockPos> buildFloorTargets() {
        ArrayList<BlockPos> result = new ArrayList<>();
        for (int dx = 0; dx < _roomSize; dx++) {
            for (int dz = 0; dz < _roomSize; dz++) {
                result.add(_roomAnchor.add(dx, -1, dz));
            }
        }
        addHallFloor(result);
        return result;
    }

    private List<BlockPos> buildWallTargets() {
        ArrayList<BlockPos> result = new ArrayList<>();
        for (int dx = 0; dx < _roomSize; dx++) {
            for (int dz = 0; dz < _roomSize; dz++) {
                boolean perimeter = dx == 0 || dz == 0 || dx == _roomSize - 1 || dz == _roomSize - 1;
                if (!perimeter || isDoorway(dx, dz)) continue;
                for (int h = 0; h < WALL_HEIGHT; h++) {
                    result.add(_roomAnchor.add(dx, h, dz));
                }
            }
        }
        addHallWalls(result);
        return result;
    }

    private List<BlockPos> buildRoofTargets() {
        ArrayList<BlockPos> result = new ArrayList<>();
        for (int dx = 0; dx < _roomSize; dx++) {
            for (int dz = 0; dz < _roomSize; dz++) {
                result.add(_roomAnchor.add(dx, WALL_HEIGHT, dz));
            }
        }
        return result;
    }

    private List<BlockPos> buildWaterTargets() {
        return List.of(_roomCenter.add(0, -1, 0));
    }

    private List<BlockPos> buildFarmTargets() {
        ArrayList<BlockPos> result = new ArrayList<>();
        for (int dx = 1; dx < _roomSize - 1; dx++) {
            for (int dz = 1; dz < _roomSize - 1; dz++) {
                BlockPos soil = _roomAnchor.add(dx, -1, dz);
                if (!_waterTargets.contains(soil)) result.add(soil);
            }
        }
        result.sort(Comparator.comparingInt(this::distanceToRoomCenter));
        return result;
    }

    private void addBoxAir(List<BlockPos> result, BlockPos anchor, int width, int depth, int height) {
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                for (int h = 0; h < height; h++) {
                    result.add(anchor.add(dx, h, dz));
                }
            }
        }
    }

    private void addHallAir(List<BlockPos> result) {
        for (BlockPos floor : hallFloorPositions()) {
            for (int h = 0; h <= WALL_HEIGHT; h++) {
                result.add(floor.add(0, h + 1, 0));
            }
        }
    }

    private void addHallFloor(List<BlockPos> result) {
        result.addAll(hallFloorPositions());
    }

    private void addHallWalls(List<BlockPos> result) {
        for (BlockPos floor : hallFloorPositions()) {
            BlockPos left = floor.offset(_direction.rotateYCounterclockwise());
            BlockPos right = floor.offset(_direction.rotateYClockwise());
            for (int h = 0; h < 3; h++) {
                result.add(left.add(0, h + 1, 0));
                result.add(right.add(0, h + 1, 0));
            }
        }
    }

    private List<BlockPos> hallFloorPositions() {
        ArrayList<BlockPos> result = new ArrayList<>();
        int baseRadius = Math.max(8, _base == null ? 8 : _base.radius);
        BlockPos start = switch (_direction) {
            case NORTH -> _baseCenter.add(0, -1, -baseRadius - 1);
            case SOUTH -> _baseCenter.add(0, -1, baseRadius + 1);
            case WEST -> _baseCenter.add(-baseRadius - 1, -1, 0);
            default -> _baseCenter.add(baseRadius + 1, -1, 0);
        };
        for (int i = 0; i < _hallLength + 2; i++) {
            BlockPos center = start.offset(_direction, i);
            result.add(center);
            result.add(center.offset(_direction.rotateYClockwise()));
        }
        return result;
    }

    private boolean isDoorway(int dx, int dz) {
        int mid = _roomSize / 2;
        return switch (_direction) {
            case NORTH -> dz == _roomSize - 1 && (dx == mid || dx == mid + 1);
            case SOUTH -> dz == 0 && (dx == mid || dx == mid + 1);
            case WEST -> dx == _roomSize - 1 && (dz == mid || dz == mid + 1);
            default -> dx == 0 && (dz == mid || dz == mid + 1);
        };
    }

    private Direction chooseDirection(BaseMemory.BaseRecord base) {
        Direction[] order = {Direction.EAST, Direction.NORTH, Direction.SOUTH, Direction.WEST};
        int bestScore = Integer.MAX_VALUE;
        Direction best = Direction.EAST;
        for (Direction direction : order) {
            int score = 0;
            for (BaseMemory.BaseModule module : base.modules) {
                if (direction.asString().equalsIgnoreCase(module.direction)) score++;
            }
            if (score < bestScore) {
                bestScore = score;
                best = direction;
            }
        }
        return best;
    }

    private String uniqueRoomName(BaseMemory.BaseRecord base, String requested, RoomType type) {
        String baseName = requested == null || requested.isBlank() ? defaultName(type) : normalize(requested);
        boolean exists = base.modules.stream().anyMatch(module -> normalize(module.name).equals(baseName));
        if (!exists) return baseName;
        int index = 2;
        while (true) {
            String candidate = baseName + "_" + index;
            int finalIndex = index;
            if (base.modules.stream().noneMatch(module -> normalize(module.name).equals(baseName + "_" + finalIndex))) {
                return candidate;
            }
            index++;
        }
    }

    private void remember(String status) {
        BaseMemory memory = BaseMemory.getInstance();
        memory.rememberModule(_baseCenter, _dimension, _roomName, _type.name().toLowerCase(Locale.ROOT),
                _roomAnchor, _roomSize, _roomSize, _type == RoomType.MOBFARM ? WALL_HEIGHT + 1 : WALL_HEIGHT,
                status, "expanded room; hall=" + HALL_WIDTH + "x" + _hallLength,
                "core", _direction.asString(), _hallLength, HALL_WIDTH);
        memory.rememberModule(_baseCenter, _dimension, _roomName + "_hall", "hall",
                hallFloorPositions().get(0), HALL_WIDTH, _hallLength + 2, 3,
                status, "two-wide connector hall to " + _roomName,
                "core", _direction.asString(), _hallLength, HALL_WIDTH);
        memory.rememberInspection(_baseCenter, _dimension, _roomName, "construction",
                _clearTargets.size() + _floorTargets.size() + _wallTargets.size() + _roofTargets.size(),
                0, 0, _index, status, "type=" + _type + ";center=" + _roomCenter.toShortString());
        LocationMemory.getInstance().remember("home_room_" + _roomName,
                _roomCenter.getX(), _roomCenter.getY(), _roomCenter.getZ(),
                _dimension, "type=" + _type + ";direction=" + _direction.asString()
                        + ";hallLength=" + _hallLength);
        LocationMemory.getInstance().remember("home_room_" + _type.name().toLowerCase(Locale.ROOT),
                _roomCenter.getX(), _roomCenter.getY(), _roomCenter.getZ(),
                _dimension, "latest " + _type + " room;name=" + _roomName);
        LocationMemory.getInstance().save();
        memory.save();
    }

    private Task cache(Belfegor mod, Task task) {
        if (_activeTask != null && !_activeTask.stopped() && !_activeTask.isFinished(mod)) {
            return _activeTask;
        }
        _activeTask = task;
        return _activeTask;
    }

    private int distanceToRoomCenter(BlockPos pos) {
        return Math.abs(pos.getX() - _roomCenter.getX())
                + Math.abs(pos.getY() - _roomCenter.getY())
                + Math.abs(pos.getZ() - _roomCenter.getZ());
    }

    private void next(Phase next) {
        _phase = next;
        _index = 0;
        _activeTask = null;
    }

    private boolean targetDone(Belfegor mod, BlockPos target, boolean useThrowaways, Block[] desired) {
        if (useThrowaways) return WorldHelper.isSolid(mod, target);
        Block block = mod.getWorld().getBlockState(target).getBlock();
        for (Block allowed : desired) {
            if (block == allowed) return true;
        }
        return false;
    }

    private Item[] blockItems(Block[] blocks) {
        Item[] items = new Item[blocks.length];
        for (int i = 0; i < blocks.length; i++) {
            items[i] = blocks[i].asItem();
        }
        return items;
    }

    public static RoomType parseType(String value) {
        String normalized = normalize(value);
        return switch (normalized) {
            case "farm", "farmland", "crop", "crops" -> RoomType.FARMLAND;
            case "storage", "shulker", "warehouse" -> RoomType.STORAGE;
            case "workshop", "crafting", "craft" -> RoomType.WORKSHOP;
            case "mob", "mobfarm", "mob_farm", "spawner" -> RoomType.MOBFARM;
            default -> RoomType.EMPTY;
        };
    }

    private static String defaultName(RoomType type) {
        return switch (type) {
            case FARMLAND -> "farmland";
            case STORAGE -> "storage";
            case WORKSHOP -> "workshop";
            case MOBFARM -> "mob_farm";
            case EMPTY -> "room";
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static class BlockRegion {
        private final BlockPos min;
        private final BlockPos max;

        BlockRegion(BlockPos a, BlockPos b) {
            min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
            max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        }

        BlockPos min() {
            return min;
        }

        BlockPos max() {
            return max;
        }

        boolean isClear(Belfegor mod) {
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

        static BlockRegion fromPositions(List<BlockPos> positions) {
            BlockPos first = positions.isEmpty() ? BlockPos.ORIGIN : positions.get(0);
            BlockRegion region = new BlockRegion(first, first);
            for (BlockPos pos : positions) {
                region = new BlockRegion(region.min, pos);
                region = new BlockRegion(region.min, new BlockPos(
                        Math.max(region.max.getX(), pos.getX()),
                        Math.max(region.max.getY(), pos.getY()),
                        Math.max(region.max.getZ(), pos.getZ())));
            }
            return region;
        }
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BuildBaseExpansionTask task
                && task._type == _type
                && task._requestedName.equals(_requestedName);
    }

    @Override
    protected String toDebugString() {
        return "Build base expansion " + _type + " " + _requestedName + " phase=" + _phase;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return _phase == Phase.DONE;
    }
}
