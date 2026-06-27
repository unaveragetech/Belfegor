package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.memory.LocationMemory;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.resources.GetBuildingMaterialsTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
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
 * 3) build a three-high perimeter wall,
 * 4) place utility room anchors,
 * 5) prepare a starter farm plot.
 */
public class BuildCampsiteTask extends Task {

    private static final Block[] WALL_BLOCKS = {
            Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.DIRT, Blocks.NETHERRACK
    };
    private static final Item[] HOES = {
            Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE,
            Items.GOLDEN_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE
    };
    private static final int WALL_HEIGHT = 3;
    private static final int EXTERIOR_CLEARANCE = 5;

    private enum Phase {
        CLEAR,
        FLOOR,
        WALL,
        UTILITY,
        FARM,
        DONE
    }

    private final BlockPos _home;
    private final int _radius;
    private List<BlockPos> _clearTargets;
    private List<BlockPos> _floorTargets;
    private List<BlockPos> _wallTargets;
    private List<BlockPos> _farmTargets;
    private Phase _phase = Phase.CLEAR;
    private int _index;
    private Task _activeTask;

    public BuildCampsiteTask(BlockPos home, int radius) {
        _home = home;
        _radius = Math.max(4, Math.min(10, radius));
    }

    @Override
    protected void onStart(AltoClef mod) {
        _clearTargets = buildClearTargets(mod);
        _floorTargets = buildFloorTargets();
        _wallTargets = buildWallTargets(mod);
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
        rememberRooms();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        switch (_phase) {
            case CLEAR: {
                Task clear = runClearPhase(mod);
                if (clear != null) return clear;
                nextPhase(Phase.FLOOR);
                return null;
            }
            case FLOOR: {
                Task floor = runFloorPhase(mod);
                if (floor != null) return floor;
                nextPhase(Phase.WALL);
                return null;
            }
            case WALL: {
                Task wall = runWallPhase(mod);
                if (wall != null) return wall;
                nextPhase(Phase.UTILITY);
                return null;
            }
            case UTILITY: {
                Task utility = placeUtilityBlocks(mod);
                if (utility != null) return utility;
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
                LocationMemory.getInstance().save();
                nextPhase(Phase.DONE);
                return null;
            }
            case DONE:
                return null;
        }
        return null;
    }

    private Task runClearPhase(AltoClef mod) {
        while (_index < _clearTargets.size() && clearDone(mod, _clearTargets.get(_index))) {
            _index++;
        }
        if (_index >= _clearTargets.size()) return null;
        BlockPos target = _clearTargets.get(_index);
        if (!WorldHelper.canBreak(mod, target)) {
            _index++;
            return null;
        }
        if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
            _activeTask = new DestroyBlockTask(target);
        }
        setDebugState("Clearing/flattening base area " + (_index + 1) + "/" + _clearTargets.size());
        return _activeTask;
    }

    private Task runFloorPhase(AltoClef mod) {
        int neededBlocks = Math.max(24, missingFloors(mod));
        if (StorageHelper.getBuildingMaterialCount(mod) < Math.min(neededBlocks, 128)) {
            setDebugState("Collecting floor fill materials");
            return new GetBuildingMaterialsTask(Math.min(neededBlocks, 128));
        }
        while (_index < _floorTargets.size() && floorDone(mod, _floorTargets.get(_index))) {
            _index++;
        }
        if (_index >= _floorTargets.size()) return null;
        BlockPos target = _floorTargets.get(_index);
        if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
            _activeTask = new PlaceBlockTask(target, WALL_BLOCKS, true, true);
        }
        setDebugState("Flattening base floor " + (_index + 1) + "/" + _floorTargets.size());
        return _activeTask;
    }

    private Task runWallPhase(AltoClef mod) {
        int neededBlocks = Math.max(24, _wallTargets.size() - countFinishedWalls(mod));
        if (StorageHelper.getBuildingMaterialCount(mod) < Math.min(neededBlocks, 160)) {
            setDebugState("Collecting three-high wall materials");
            return new GetBuildingMaterialsTask(Math.min(neededBlocks, 160));
        }

        while (_index < _wallTargets.size() && wallBlockDone(mod, _wallTargets.get(_index))) {
            _index++;
        }
        if (_index >= _wallTargets.size()) return null;
        BlockPos target = _wallTargets.get(_index);
        if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
            _activeTask = new PlaceBlockTask(target, WALL_BLOCKS, true, true);
        }
        setDebugState("Building three-high wall " + (_index + 1) + "/" + _wallTargets.size());
        return _activeTask;
    }

    private Task runFarmPhase(AltoClef mod) {
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

    private List<BlockPos> buildClearTargets(AltoClef mod) {
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

    private List<BlockPos> buildWallTargets(AltoClef mod) {
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

    private List<BlockPos> buildFarmTargets() {
        ArrayList<BlockPos> result = new ArrayList<>();
        BlockPos origin = _home.add(-_radius + 2, -1, _radius - 5);
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                result.add(origin.add(dx, 0, dz));
            }
        }
        return result;
    }

    private Task placeUtilityBlocks(AltoClef mod) {
        BlockPos table = _home.add(1, 0, 1);
        if (mod.getItemStorage().hasItem(Items.CRAFTING_TABLE)
                && mod.getWorld().getBlockState(table).getBlock() != Blocks.CRAFTING_TABLE) {
            setDebugState("Placing crafting room table");
            return new PlaceBlockTask(table, Blocks.CRAFTING_TABLE);
        }

        BlockPos furnace = _home.add(-1, 0, 1);
        if (mod.getItemStorage().hasItem(Items.FURNACE)
                && mod.getWorld().getBlockState(furnace).getBlock() != Blocks.FURNACE) {
            setDebugState("Placing smelting room furnace");
            return new PlaceBlockTask(furnace, Blocks.FURNACE);
        }

        BlockPos chest = _home.add(0, 0, -2);
        if (mod.getItemStorage().hasItem(Items.CHEST)
                && mod.getWorld().getBlockState(chest).getBlock() != Blocks.CHEST) {
            setDebugState("Placing storage room chest");
            return new PlaceBlockTask(chest, Blocks.CHEST);
        }
        return null;
    }

    private void rememberRooms() {
        String dim = WorldHelper.getCurrentDimension().name();
        LocationMemory.getInstance().remember("home_room_core",
                _home.getX(), _home.getY(), _home.getZ(), dim, "center");
        LocationMemory.getInstance().remember("home_room_crafting",
                _home.getX() + 1, _home.getY(), _home.getZ() + 1, dim, "crafting_table_anchor");
        LocationMemory.getInstance().remember("home_room_smelting",
                _home.getX() - 1, _home.getY(), _home.getZ() + 1, dim, "furnace_anchor");
        LocationMemory.getInstance().remember("home_room_storage",
                _home.getX(), _home.getY(), _home.getZ() - 2, dim, "chest_anchor");
        LocationMemory.getInstance().remember("home_room_farm",
                _home.getX() - _radius + 3, _home.getY() - 1, _home.getZ() + _radius - 4,
                dim, "starter_crop_plot");
        LocationMemory.getInstance().save();
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

    private boolean clearDone(AltoClef mod, BlockPos pos) {
        return mod.getWorld().getBlockState(pos).isAir();
    }

    private boolean floorDone(AltoClef mod, BlockPos pos) {
        return WorldHelper.isSolid(mod, pos);
    }

    private int missingFloors(AltoClef mod) {
        int missing = 0;
        for (BlockPos pos : _floorTargets) {
            if (!floorDone(mod, pos)) missing++;
        }
        return missing;
    }

    private int countFinishedWalls(AltoClef mod) {
        int count = 0;
        for (BlockPos pos : _wallTargets) {
            if (wallBlockDone(mod, pos)) count++;
        }
        return count;
    }

    private boolean wallBlockDone(AltoClef mod, BlockPos pos) {
        return WorldHelper.isSolid(mod, pos);
    }

    private boolean farmDone(AltoClef mod, BlockPos soil) {
        return mod.getWorld().getBlockState(soil).getBlock() == Blocks.FARMLAND
                && mod.getWorld().getBlockState(soil.up()).getBlock() == Blocks.WHEAT;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
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
    public boolean isFinished(AltoClef mod) {
        return _phase == Phase.DONE;
    }
}
