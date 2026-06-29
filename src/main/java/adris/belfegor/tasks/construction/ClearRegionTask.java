package adris.belfegor.tasks.construction;

import adris.belfegor.Belfegor;
import adris.belfegor.tasksystem.ITaskRequiresGrounded;
import adris.belfegor.tasksystem.Task;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;


public class ClearRegionTask extends Task implements ITaskRequiresGrounded {

    private final BlockPos _from;
    private final BlockPos _to;
    private final BlockPos _min;
    private final BlockPos _max;

    // TODO: Progress checkers in the event of a failure.
    // Progress checker 1 for movement
    // Progress checker 2 for if block breaking isn't happening
    // Make it an "and", as in both MUST fail for a failure to count.

    public ClearRegionTask(BlockPos from, BlockPos to) {
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
    }

    @Override
    protected void onStart(Belfegor mod) {

    }

    @Override
    protected Task onTick(Belfegor mod) {
        if (!mod.getClientBaritone().getBuilderProcess().isActive()) {
            mod.getClientBaritone().getBuilderProcess().clearArea(_from, _to);
        }
        return null;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        mod.getClientBaritone().getBuilderProcess().onLostControl();
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        for (int x = _min.getX(); x <= _max.getX(); x++) {
            for (int y = _min.getY(); y <= _max.getY(); y++) {
                for (int z = _min.getZ(); z <= _max.getZ(); z++) {
                    BlockPos toCheck = new BlockPos(x, y, z);
                    assert MinecraftClient.getInstance().world != null;
                    if (!MinecraftClient.getInstance().world.isAir(toCheck)
                            && MinecraftClient.getInstance().world.getBlockState(toCheck).getBlock() != Blocks.WATER) {
                        return false;
                    }
                }
            }
        }
        return true;
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
