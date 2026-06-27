package adris.belfegor.tasks.construction;

import adris.belfegor.Belfegor;
import adris.belfegor.tasks.InteractWithBlockTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import baritone.api.utils.input.Input;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

/**
 * Given a block position with fire in it, extinguish the fire at that position
 */
public class PutOutFireTask extends Task {

    private final BlockPos _firePosition;

    public PutOutFireTask(BlockPos firePosition) {
        _firePosition = firePosition;
    }

    @Override
    protected void onStart(Belfegor mod) {

    }

    @Override
    protected Task onTick(Belfegor mod) {
        return new InteractWithBlockTask(ItemTarget.EMPTY, null, _firePosition, Input.CLICK_LEFT, false, false);
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {

    }

    @Override
    public boolean isFinished(Belfegor mod) {
        BlockState s = mod.getWorld().getBlockState(_firePosition);
        return (s.getBlock() != Blocks.FIRE && s.getBlock() != Blocks.SOUL_FIRE);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof PutOutFireTask task) {
            return (task._firePosition.equals(_firePosition));
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Putting out fire at " + _firePosition;
    }
}
