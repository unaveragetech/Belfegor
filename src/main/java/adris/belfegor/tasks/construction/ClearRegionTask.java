package adris.belfegor.tasks.construction;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.tasksystem.ITaskRequiresGrounded;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class ClearRegionTask extends Task implements ITaskRequiresGrounded {

    private static final int MANUAL_CLEANUP_REMAINING_THRESHOLD = 256;
    private static final int MANUAL_NO_PROGRESS_TICKS = 120;
    private static final int BARITONE_WATCHDOG_TICKS = 360;

    private final BlockPos _from;
    private final BlockPos _to;
    private final BlockPos _min;
    private final BlockPos _max;
    private final Set<BlockPos> _explicitTargets;
    private int _lastRemaining = Integer.MAX_VALUE;
    private int _noProgressTicks;
    private Task _manualDestroyTask;

    // TODO: Progress checkers in the event of a failure.
    // Progress checker 1 for movement
    // Progress checker 2 for if block breaking isn't happening
    // Make it an "and", as in both MUST fail for a failure to count.

    public ClearRegionTask(BlockPos from, BlockPos to) {
        this(from, to, null);
    }

    public ClearRegionTask(Collection<BlockPos> explicitTargets) {
        this(min(explicitTargets), max(explicitTargets), explicitTargets);
    }

    private ClearRegionTask(BlockPos from, BlockPos to, Collection<BlockPos> explicitTargets) {
        _from = from;
        _to = to;
        _min = new BlockPos(
                Math.min(from.getX(), to.getX()),
                Math.min(from.getY(), to.getY()),
                Math.min(from.getZ(), to.getZ()));
        _max = new BlockPos(
                Math.max(from.getX(), to.getX()),
                Math.max(from.getY(), to.getY()),
                Math.max(from.getZ(), to.getZ()));
        _explicitTargets = explicitTargets == null ? null : new HashSet<>(explicitTargets);
    }

    @Override
    protected void onStart(Belfegor mod) {
        mod.getBehaviour().push();
        mod.getBehaviour().setAutoMLG(false);
        mod.getBehaviour().setAllowDiagonalAscend(false);
        mod.getBehaviour().forceUseTool((state, stack) -> stack != null && stack.isSuitableFor(state));
        _lastRemaining = Integer.MAX_VALUE;
        _noProgressTicks = 0;
        _manualDestroyTask = null;
        Debug.logInternal("Clear region start: " + _from.toShortString()
                + " to " + _to.toShortString()
                + " remaining=" + countRemaining());
    }

    @Override
    protected Task onTick(Belfegor mod) {
        int remaining = countRemaining();
        if (remaining <= 0) return null;
        trackProgress(mod, remaining);

        if (_manualDestroyTask != null && !_manualDestroyTask.stopped() && !_manualDestroyTask.isFinished(mod)) {
            setDebugState("Manually clearing stubborn region block remaining=" + remaining);
            return _manualDestroyTask;
        }
        _manualDestroyTask = null;

        if (shouldUseManualCleanup(remaining)) {
            BlockPos stubborn = closestRemaining(mod);
            if (stubborn != null) {
                Debug.logInternal("Clear region tool-aware cleanup: " + stubborn.toShortString()
                        + " block=" + MinecraftClient.getInstance().world.getBlockState(stubborn).getBlock()
                        + " remaining=" + remaining
                        + " noProgressTicks=" + _noProgressTicks);
                mod.getClientBaritone().getBuilderProcess().onLostControl();
                _manualDestroyTask = new DestroyBlockTask(stubborn);
                _noProgressTicks = 0;
                return _manualDestroyTask;
            }
        }

        if (_explicitTargets == null && !mod.getClientBaritone().getBuilderProcess().isActive()) {
            mod.getClientBaritone().getBuilderProcess().clearArea(_from, _to);
        }
        setDebugState((_explicitTargets == null ? "Baritone" : "Targeted")
                + " clearing region remaining=" + remaining);
        return null;
    }

    private void trackProgress(Belfegor mod, int remaining) {
        if (remaining < _lastRemaining) {
            Debug.logInternal("Clear region progress: " + _from.toShortString()
                    + " to " + _to.toShortString()
                    + " remaining=" + remaining);
            _lastRemaining = remaining;
            _noProgressTicks = 0;
            return;
        }
        if (remaining == _lastRemaining) {
            _noProgressTicks++;
            if (_noProgressTicks == 100 || _noProgressTicks % 120 == 0) {
                Debug.logInternal("Clear region no-progress: " + _from.toShortString()
                        + " to " + _to.toShortString()
                        + " remaining=" + remaining
                        + " ticks=" + _noProgressTicks
                        + " builderActive=" + mod.getClientBaritone().getBuilderProcess().isActive());
            }
            if (_noProgressTicks == BARITONE_WATCHDOG_TICKS) {
                Debug.logInternal("Clear region watchdog reset: " + _from.toShortString()
                        + " to " + _to.toShortString()
                        + " remaining=" + remaining);
                mod.getClientBaritone().getBuilderProcess().onLostControl();
            }
        } else {
            _lastRemaining = remaining;
            _noProgressTicks = 0;
        }
    }

    private boolean shouldUseManualCleanup(int remaining) {
        if (_explicitTargets != null) return true;
        return remaining <= MANUAL_CLEANUP_REMAINING_THRESHOLD
                || _noProgressTicks >= MANUAL_NO_PROGRESS_TICKS;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        mod.getClientBaritone().getBuilderProcess().onLostControl();
        mod.getBehaviour().pop();
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return countRemaining() == 0;
    }

    private int countRemaining() {
        int remaining = 0;
        for (BlockPos toCheck : positionsToCheck()) {
            assert MinecraftClient.getInstance().world != null;
            if (isProtectedBaseFixture(toCheck)) continue;
            if (!MinecraftClient.getInstance().world.isAir(toCheck)
                    && MinecraftClient.getInstance().world.getBlockState(toCheck).getBlock() != Blocks.WATER) {
                remaining++;
            }
        }
        return remaining;
    }

    private BlockPos closestRemaining(Belfegor mod) {
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        BlockPos player = mod.getPlayer() == null ? _min : mod.getPlayer().getBlockPos();
        for (BlockPos toCheck : positionsToCheck()) {
            assert MinecraftClient.getInstance().world != null;
            if (isProtectedBaseFixture(toCheck)) continue;
            if (MinecraftClient.getInstance().world.isAir(toCheck)
                    || MinecraftClient.getInstance().world.getBlockState(toCheck).getBlock() == Blocks.WATER) {
                continue;
            }
            double distance = toCheck.getSquaredDistance(player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = toCheck;
            }
        }
        return best;
    }

    private Iterable<BlockPos> positionsToCheck() {
        if (_explicitTargets != null) return _explicitTargets;
        return () -> new java.util.Iterator<>() {
            private int x = _min.getX();
            private int y = _min.getY();
            private int z = _min.getZ();

            @Override
            public boolean hasNext() {
                return x <= _max.getX();
            }

            @Override
            public BlockPos next() {
                BlockPos result = new BlockPos(x, y, z);
                z++;
                if (z > _max.getZ()) {
                    z = _min.getZ();
                    y++;
                    if (y > _max.getY()) {
                        y = _min.getY();
                        x++;
                    }
                }
                return result;
            }
        };
    }

    private static BlockPos min(Collection<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) return BlockPos.ORIGIN;
        int x = Integer.MAX_VALUE, y = Integer.MAX_VALUE, z = Integer.MAX_VALUE;
        for (BlockPos pos : positions) {
            x = Math.min(x, pos.getX());
            y = Math.min(y, pos.getY());
            z = Math.min(z, pos.getZ());
        }
        return new BlockPos(x, y, z);
    }

    private static BlockPos max(Collection<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) return BlockPos.ORIGIN;
        int x = Integer.MIN_VALUE, y = Integer.MIN_VALUE, z = Integer.MIN_VALUE;
        for (BlockPos pos : positions) {
            x = Math.max(x, pos.getX());
            y = Math.max(y, pos.getY());
            z = Math.max(z, pos.getZ());
        }
        return new BlockPos(x, y, z);
    }

    private boolean isProtectedBaseFixture(BlockPos pos) {
        return BaseMemory.getInstance().isProtectedFixturePosition(pos, WorldHelper.getCurrentDimension().name());
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof ClearRegionTask) {
            ClearRegionTask task = (ClearRegionTask) other;
            return (task._from.equals(_from) && task._to.equals(_to));
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Clear region from " + _from.toShortString() + " to " + _to.toShortString();
    }
}
