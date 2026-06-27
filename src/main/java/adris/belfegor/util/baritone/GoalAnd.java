package adris.belfegor.util.baritone;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalComposite;
import net.minecraft.util.math.BlockPos;

public class GoalAnd implements Goal {
    private final Goal[] goals;

    public GoalAnd(Goal... goals) {
        this.goals = goals;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        for (Goal goal : goals) {
            if (!goal.isInGoal(x, y, z)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double max = 0;
        for (Goal goal : goals) {
            double h = goal.heuristic(x, y, z);
            if (h > max) {
                max = h;
            }
        }
        return max;
    }
}