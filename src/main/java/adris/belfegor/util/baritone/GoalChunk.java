package adris.belfegor.util.baritone;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import net.minecraft.util.math.ChunkPos;

public class GoalChunk implements Goal {
    private final GoalXZ goalXZ;

    public GoalChunk(ChunkPos pos) {
        this.goalXZ = new GoalXZ(pos.getCenterX(), pos.getCenterZ());
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return goalXZ.isInGoal(x, y, z);
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return goalXZ.heuristic(x, y, z);
    }
}