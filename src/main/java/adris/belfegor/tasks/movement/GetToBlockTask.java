package adris.belfegor.tasks.movement;

import adris.belfegor.Belfegor;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.tasksystem.ITaskRequiresGrounded;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.Dimension;
import adris.belfegor.util.helpers.WorldHelper;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.input.Input;
import net.minecraft.util.math.BlockPos;

public class GetToBlockTask extends CustomBaritoneGoalTask implements ITaskRequiresGrounded {

    private final BlockPos _position;
    private final boolean _preferStairs;
    private final Dimension _dimension;
    private boolean _avoidBreaking;

    /**
     * Makes this movement goal route only through already-open space.
     *
     * Small schematic repairs use this mode while walking to a placement
     * stand.  Letting Baritone mine its way to a stand can remove the exact
     * floor or completed wall that the parent task just repaired, producing a
     * place -> path -> break loop.
     */
    public GetToBlockTask withoutBreaking() {
        _avoidBreaking = true;
        return this;
    }

    public GetToBlockTask(BlockPos position, boolean preferStairs) {
        this(position, preferStairs, null);
    }

    public GetToBlockTask(BlockPos position, Dimension dimension) {
        this(position, false, dimension);
    }

    public GetToBlockTask(BlockPos position, boolean preferStairs, Dimension dimension) {
        _dimension = dimension;
        _position = position;
        _preferStairs = preferStairs;
    }

    public GetToBlockTask(BlockPos position) {
        this(position, false);
    }

    /**
     * Creates a movement task that routes without breaking blocks whenever the
     * player or the target is inside a remembered base. The bot should use its
     * own doorways and halls instead of mining through finished walls.
     */
    public static GetToBlockTask baseAware(Belfegor mod, BlockPos target) {
        GetToBlockTask task = new GetToBlockTask(target);
        if (mod == null || mod.getPlayer() == null || target == null) return task;
        String dimension = WorldHelper.getCurrentDimension().name();
        if (BaseMemory.getInstance().isInsideBase(mod.getPlayer().getBlockPos(), dimension, 4)
                || BaseMemory.getInstance().isInsideBase(target, dimension, 4)) {
            return task.withoutBreaking();
        }
        return task;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        if (_dimension != null && WorldHelper.getCurrentDimension() != _dimension) {
            return new DefaultGoToDimensionTask(_dimension);
        }
        return super.onTick(mod);
    }

    @Override
    protected void onStart(Belfegor mod) {
        super.onStart(mod);
        if (_preferStairs || _avoidBreaking) {
            mod.getBehaviour().push();
        }
        if (_preferStairs) {
            mod.getBehaviour().setPreferredStairs(true);
        }
        if (_avoidBreaking) {
            mod.getBehaviour().setAllowBreaking(false);
            mod.getBehaviour().avoidBlockBreaking(pos -> true);
        }
    }


    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        if (_avoidBreaking) {
            mod.getClientBaritone().getInputOverrideHandler()
                    .setInputForceState(Input.CLICK_LEFT, false);
            if (mod.getController() != null) {
                mod.getController().cancelBlockBreaking();
            }
        }
        super.onStop(mod, interruptTask);
        if (_preferStairs || _avoidBreaking) {
            mod.getBehaviour().pop();
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetToBlockTask task) {
            return task._position.equals(_position)
                    && task._preferStairs == _preferStairs
                    && task._avoidBreaking == _avoidBreaking
                    && task._dimension == _dimension;
        }
        return false;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return super.isFinished(mod) && (_dimension == null || _dimension == WorldHelper.getCurrentDimension());
    }

    @Override
    protected String toDebugString() {
        return "Getting to block " + _position
                + (_dimension != null ? " in dimension " + _dimension : "")
                + (_avoidBreaking ? " without breaking" : "");
    }


    @Override
    protected Goal newGoal(Belfegor mod) {
        return new GoalBlock(_position);
    }

    @Override
    protected void onWander(Belfegor mod) {
        super.onWander(mod);
        mod.getBlockTracker().requestBlockUnreachable(_position);
    }
}
