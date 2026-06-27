package adris.belfegor.util.baritone;

import adris.belfegor.Belfegor;
import baritone.api.pathing.goals.Goal;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Optional;

public abstract class GoalRunAwayFromEntities implements Goal {
    protected final Belfegor mod;
    protected final double distance;
    protected final boolean xzOnly;
    protected final double penalty;

    public GoalRunAwayFromEntities(Belfegor mod, double distance, boolean xzOnly, double penalty) {
        this.mod = mod;
        this.distance = distance;
        this.xzOnly = xzOnly;
        this.penalty = penalty;
    }

    protected abstract Optional<Entity> getEntities(Belfegor mod);

    protected double getCostOfEntity(Entity entity, int x, int y, int z) {
        return entity.squaredDistanceTo(x + 0.5, y + 0.5, z + 0.5);
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        Optional<Entity> entities = getEntities(mod);
        if (entities.isEmpty()) return true;
        Entity entity = entities.get();
        double dist = entity.squaredDistanceTo(x + 0.5, y + 0.5, z + 0.5);
        return dist > distance * distance;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        Optional<Entity> entities = getEntities(mod);
        if (entities.isEmpty()) return 0;
        Entity entity = entities.get();
        double dist = entity.squaredDistanceTo(x + 0.5, y + 0.5, z + 0.5);
        double safe = distance * distance;
        if (dist > safe) return 0;
        return (safe - dist) * penalty;
    }
}