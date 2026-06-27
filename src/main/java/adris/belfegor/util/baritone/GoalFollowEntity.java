package adris.belfegor.util.baritone;

import baritone.api.pathing.goals.Goal;
import net.minecraft.entity.Entity;

public class GoalFollowEntity implements Goal {
    private final Entity entity;
    private final double distance;

    public GoalFollowEntity(Entity entity, double distance) {
        this.entity = entity;
        this.distance = distance;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return entity.squaredDistanceTo(x + 0.5, y + 0.5, z + 0.5) <= distance * distance;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return entity.squaredDistanceTo(x + 0.5, y + 0.5, z + 0.5);
    }
}