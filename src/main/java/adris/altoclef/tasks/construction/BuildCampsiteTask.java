package adris.altoclef.tasks.construction;

import adris.altoclef.AltoClef;
import adris.altoclef.memory.LocationMemory;
import adris.altoclef.tasks.resources.GetBuildingMaterialsTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a simple reusable campsite around a home base.
 *
 * The first pass creates a low wall around a central area and places basic
 * utility blocks when available. Later passes can use a larger radius, allowing
 * @player mode to expand the base over time without needing a complex planner.
 */
public class BuildCampsiteTask extends Task {

    private static final Block[] WALL_BLOCKS = {
            Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.DIRT, Blocks.NETHERRACK
    };

    private final BlockPos _home;
    private final int _radius;
    private final int _wallHeight;
    private List<BlockPos> _wallTargets;
    private int _index;
    private Task _activeTask;

    public BuildCampsiteTask(BlockPos home, int radius) {
        _home = home;
        _radius = Math.max(3, Math.min(8, radius));
        _wallHeight = 2;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _wallTargets = buildWallTargets(mod);
        _index = 0;
        _activeTask = null;
        mod.getBehaviour().push();
        mod.getBehaviour().avoidWalkingThrough(pos ->
                pos.getY() >= _home.getY()
                        && pos.getY() <= _home.getY() + _wallHeight
                        && Math.abs(pos.getX() - _home.getX()) <= _radius
                        && Math.abs(pos.getZ() - _home.getZ()) <= _radius
                        && (Math.abs(pos.getX() - _home.getX()) == _radius
                        || Math.abs(pos.getZ() - _home.getZ()) == _radius));
    }

    @Override
    protected Task onTick(AltoClef mod) {
        int neededBlocks = Math.max(24, _wallTargets.size() - countFinishedWalls(mod));
        if (StorageHelper.getBuildingMaterialCount(mod) < Math.min(neededBlocks, 96)) {
            setDebugState("Collecting campsite wall materials");
            return new GetBuildingMaterialsTask(Math.min(neededBlocks, 96));
        }

        while (_index < _wallTargets.size() && wallBlockDone(mod, _wallTargets.get(_index))) {
            _index++;
        }
        if (_index < _wallTargets.size()) {
            BlockPos target = _wallTargets.get(_index);
            if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
                _activeTask = new PlaceBlockTask(target, WALL_BLOCKS, true, true);
            }
            setDebugState("Building campsite wall " + (_index + 1) + "/" + _wallTargets.size());
            return _activeTask;
        }

        Task utility = placeUtilityBlocks(mod);
        if (utility != null) return utility;

        LocationMemory.getInstance().remember("home_campsite",
                _home.getX(), _home.getY(), _home.getZ(),
                WorldHelper.getCurrentDimension().name(),
                "radius=" + _radius);
        LocationMemory.getInstance().save();
        return null;
    }

    private List<BlockPos> buildWallTargets(AltoClef mod) {
        ArrayList<BlockPos> result = new ArrayList<>();
        int y = _home.getY();
        for (int dx = -_radius; dx <= _radius; dx++) {
            for (int dz = -_radius; dz <= _radius; dz++) {
                boolean perimeter = Math.abs(dx) == _radius || Math.abs(dz) == _radius;
                if (!perimeter) continue;
                // Leave a simple two-wide doorway on the east side.
                if (dx == _radius && (dz == 0 || dz == 1)) continue;
                for (int h = 0; h < _wallHeight; h++) {
                    BlockPos pos = new BlockPos(_home.getX() + dx, y + h, _home.getZ() + dz);
                    if (WorldHelper.isInsidePlayer(mod, pos)) continue;
                    result.add(pos);
                }
            }
        }
        return result;
    }

    private Task placeUtilityBlocks(AltoClef mod) {
        BlockPos table = _home.add(1, 0, 1);
        if (mod.getItemStorage().hasItem(Items.CRAFTING_TABLE)
                && mod.getWorld().getBlockState(table).getBlock() != Blocks.CRAFTING_TABLE) {
            setDebugState("Placing campsite crafting table");
            return new PlaceBlockTask(table, Blocks.CRAFTING_TABLE);
        }

        BlockPos furnace = _home.add(-1, 0, 1);
        if (mod.getItemStorage().hasItem(Items.FURNACE)
                && mod.getWorld().getBlockState(furnace).getBlock() != Blocks.FURNACE) {
            setDebugState("Placing campsite furnace");
            return new PlaceBlockTask(furnace, Blocks.FURNACE);
        }

        BlockPos chest = _home.add(0, 0, -2);
        if (mod.getItemStorage().hasItem(Items.CHEST)
                && mod.getWorld().getBlockState(chest).getBlock() != Blocks.CHEST) {
            setDebugState("Placing campsite chest");
            return new PlaceBlockTask(chest, Blocks.CHEST);
        }
        return null;
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
        return "Build campsite at " + _home.toShortString() + " r=" + _radius;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return _wallTargets != null
                && _wallTargets.stream().allMatch(pos -> wallBlockDone(mod, pos))
                && (mod.getWorld().getBlockState(_home.add(1, 0, 1)).getBlock() == Blocks.CRAFTING_TABLE
                || !mod.getItemStorage().hasItem(Items.CRAFTING_TABLE));
    }
}
