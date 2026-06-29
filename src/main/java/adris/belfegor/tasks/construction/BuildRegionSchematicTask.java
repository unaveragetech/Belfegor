package adris.belfegor.tasks.construction;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.tasks.resources.GetBuildingMaterialsTask;
import adris.belfegor.tasksystem.ITaskRequiresGrounded;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.helpers.WorldHelper;
import baritone.api.schematic.AbstractSchematic;
import baritone.api.schematic.ISchematic;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        mod.getBehaviour().push();
    }

    @Override
    protected Task onTick(Belfegor mod) {
        int missing = countMissing(mod);
        if (missing <= 0) return null;

        if (_allowAnyThrowaway && StorageHelper.getBuildingMaterialCount(mod) < Math.min(missing, 160)) {
            if (_materialTask != null && _materialTask.isActive() && !_materialTask.isFinished(mod)) {
                return _materialTask;
            }
            _materialTask = new GetBuildingMaterialsTask(Math.min(Math.max(missing, 32), 160));
            setDebugState("Collecting structure materials for " + _name);
            return _materialTask;
        }

        if (!mod.getClientBaritone().getBuilderProcess().isActive()) {
            Debug.logInternal("Run region schematic build: " + _name + " targets=" + _targets.size());
            ISchematic schematic = new RegionSchematic(mod);
            mod.getClientBaritone().getBuilderProcess().build(_name, schematic, _origin);
        }
        setDebugState("Baritone building " + _name + " missing=" + missing + "/" + _targets.size());
        return null;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        mod.getBehaviour().pop();
        mod.getClientBaritone().getBuilderProcess().onLostControl();
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
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        if (_allowAnyThrowaway) {
            return WorldHelper.isSolid(mod, pos);
        }
        return Arrays.asList(desired).contains(block);
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
