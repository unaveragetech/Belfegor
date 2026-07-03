package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.debug.DebugLogger;
import adris.belfegor.memory.RecentPlacedBlockMemory;
import adris.belfegor.tasks.construction.DestroyBlockTask;
import adris.belfegor.tasks.movement.TimeoutWanderTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

/**
 * Gets early cobblestone by strip-clearing shallow surface dirt/grass to expose
 * stone, then mining that stone. This avoids the ugly failure mode where the
 * bot digs a 1x1 shaft to a cached underground target and starts scaffold loops.
 */
public class SurfaceCobblestoneTask extends Task {
    private static final int RADIUS = 12;
    private static final int MAX_DEPTH_BELOW_FEET = 5;
    private static final int MAX_COVER_ABOVE_FEET = 2;
    private static final net.minecraft.item.Item[] SHOVELS = {
            Items.WOODEN_SHOVEL,
            Items.STONE_SHOVEL,
            Items.IRON_SHOVEL,
            Items.DIAMOND_SHOVEL,
            Items.NETHERITE_SHOVEL
    };
    private static final Block[] CLEARABLE_COVER = {
            Blocks.GRASS_BLOCK,
            Blocks.DIRT,
            Blocks.COARSE_DIRT,
            Blocks.ROOTED_DIRT,
            Blocks.PODZOL,
            Blocks.MYCELIUM,
            Blocks.GRAVEL
    };

    private final int _startCobble;
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(true);
    private BlockPos _activeTarget;
    private int _activeTargetTicks;
    private int _failedScans;

    public SurfaceCobblestoneTask(Belfegor mod) {
        _startCobble = mod.getItemStorage().getItemCount(Items.COBBLESTONE);
    }

    @Override
    protected void onStart(Belfegor mod) {
        _activeTarget = null;
        _activeTargetTicks = 0;
        _failedScans = 0;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        if (!mod.getItemStorage().hasItem(Items.WOODEN_PICKAXE)) {
            setDebugState("Crafting wooden pickaxe before surface stone mining.");
            return TaskCatalogue.getItemTask("wooden_pickaxe", 1);
        }
        if (!hasShovel(mod)) {
            setDebugState("Crafting shovel before surface dirt excavation.");
            return TaskCatalogue.getItemTask("wooden_shovel", 1);
        }

        BlockPos target = getLockedTarget(mod);
        if (target == null) {
            target = findSurfaceStoneOrCover(mod);
        }
        if (target != null) {
            if (!_activeTargetEquals(target)) {
                _activeTarget = target;
                _activeTargetTicks = 0;
                DebugLogger.getInstance().log("SURFACE-COBBLE",
                        "target=" + target.toShortString()
                                + " block=" + mod.getWorld().getBlockState(target).getBlock()
                                + " player=" + mod.getPlayer().getBlockPos().toShortString());
            }
            _activeTargetTicks++;
            return new DestroyBlockTask(target);
        }

        _failedScans++;
        setDebugState("No shallow surface stone nearby; wandering for exposed stone.");
        return _wanderTask;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _activeTarget = null;
        _activeTargetTicks = 0;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return mod.getItemStorage().getItemCount(Items.COBBLESTONE) > _startCobble;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof SurfaceCobblestoneTask;
    }

    @Override
    protected String toDebugString() {
        return "Surface strip mine for cobblestone";
    }

    private boolean _activeTargetEquals(BlockPos target) {
        return _activeTarget != null && _activeTarget.equals(target);
    }

    private BlockPos getLockedTarget(Belfegor mod) {
        if (_activeTarget == null || _activeTargetTicks > 80) {
            return null;
        }
        if (mod.getWorld() == null || RecentPlacedBlockMemory.wasRecentlyPlaced(_activeTarget)) {
            return null;
        }
        Block block = mod.getWorld().getBlockState(_activeTarget).getBlock();
        if (block == Blocks.STONE || isClearableCover(block)) {
            return _activeTarget;
        }
        return null;
    }

    private BlockPos findSurfaceStoneOrCover(Belfegor mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return null;
        BlockPos player = mod.getPlayer().getBlockPos();
        BlockPos bestStone = null;
        BlockPos bestCover = null;
        double bestStoneScore = Double.POSITIVE_INFINITY;
        double bestCoverScore = Double.POSITIVE_INFINITY;

        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (dx * dx + dz * dz > RADIUS * RADIUS) continue;
                for (int dy = 1; dy >= -MAX_DEPTH_BELOW_FEET; dy--) {
                    BlockPos stone = player.add(dx, dy, dz);
                    if (RecentPlacedBlockMemory.wasRecentlyPlaced(stone)) continue;
                    if (mod.getWorld().getBlockState(stone).getBlock() != Blocks.STONE) continue;
                    if (!mod.getChunkTracker().isChunkLoaded(stone)) continue;
                    if (!WorldHelper.canBreak(mod, stone)) continue;

                    double score = stone.getSquaredDistance(player);
                    BlockPos cover = findTopCoverToClear(mod, stone, player);
                    if (cover == null) {
                        if (score < bestStoneScore) {
                            bestStoneScore = score;
                            bestStone = stone;
                        }
                    } else {
                        if (score < bestCoverScore) {
                            bestCoverScore = score;
                            bestCover = cover;
                        }
                    }
                    break;
                }
            }
        }
        return bestStone != null ? bestStone : bestCover;
    }

    private BlockPos findTopCoverToClear(Belfegor mod, BlockPos stone, BlockPos player) {
        int topY = Math.max(stone.getY() + 1, player.getY() + MAX_COVER_ABOVE_FEET);
        BlockPos best = null;
        for (int y = stone.getY() + 1; y <= topY; y++) {
            BlockPos pos = new BlockPos(stone.getX(), y, stone.getZ());
            Block block = mod.getWorld().getBlockState(pos).getBlock();
            if (mod.getWorld().getBlockState(pos).isAir()) {
                continue;
            }
            if (!isClearableCover(block) || !WorldHelper.canBreak(mod, pos)) {
                return null;
            }
            if (!RecentPlacedBlockMemory.wasRecentlyPlaced(pos)) {
                best = pos;
            }
        }
        return best;
    }

    private boolean hasShovel(Belfegor mod) {
        return mod.getItemStorage().getItemCount(SHOVELS) > 0;
    }

    private boolean isClearableCover(Block block) {
        for (Block clearable : CLEARABLE_COVER) {
            if (block == clearable) return true;
        }
        return false;
    }
}
