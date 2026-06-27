package adris.belfegor.util.baritone;

import baritone.api.pathing.goals.Goal;
import net.minecraft.util.math.Vec3d;

public class GoalDirectionXZ implements Goal {
    private final Vec3d origin;
    private final Vec3d delta;
    private final double sidePenalty;

    public GoalDirectionXZ(Vec3d origin, Vec3d delta, double sidePenalty) {
        this.origin = origin;
        this.delta = delta;
        this.sidePenalty = sidePenalty;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return false;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double dx = x + 0.5 - origin.x;
        double dz = z + 0.5 - origin.z;
        double projected = dx * delta.x + dz * delta.z;
        double perpX = dx - projected * delta.x;
        double perpZ = dz - projected * delta.z;
        double perpDist = Math.sqrt(perpX * perpX + perpZ * perpZ);
        return -projected + perpDist * sidePenalty;
    }
}