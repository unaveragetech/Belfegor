package adris.belfegor.util.baritone;

import baritone.api.pathing.goals.Goal;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class GoalBlockSide implements Goal {
    private final BlockPos target;
    private final Direction side;
    private final int penalty;

    public GoalBlockSide(BlockPos target, Direction side, int penalty) {
        this.target = target;
        this.side = side;
        this.penalty = penalty;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockPos goal = target.offset(side.getOpposite());
        return pos.equals(goal);
    }

    @Override
    public double heuristic(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockPos goal = target.offset(side.getOpposite());
        double dist = Math.abs(x - goal.getX()) + Math.abs(y - goal.getY()) + Math.abs(z - goal.getZ());
        return dist + penalty;
    }
}