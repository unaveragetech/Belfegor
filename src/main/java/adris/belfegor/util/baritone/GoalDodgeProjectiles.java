package adris.belfegor.util.baritone;

import adris.belfegor.Belfegor;
import baritone.api.pathing.goals.Goal;
import net.minecraft.util.math.Vec3d;

public class GoalDodgeProjectiles implements Goal {
    private final Belfegor mod;
    private final double distanceHorizontal;
    private final double distanceVertical;

    public GoalDodgeProjectiles(Belfegor mod, double distanceHorizontal, double distanceVertical) {
        this.mod = mod;
        this.distanceHorizontal = distanceHorizontal;
        this.distanceVertical = distanceVertical;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        if (mod.getPlayer() == null) return false;
        Vec3d playerPos = mod.getPlayer().getPos();
        double dx = x + 0.5 - playerPos.x;
        double dy = y + 0.5 - playerPos.y;
        double dz = z + 0.5 - playerPos.z;
        return Math.sqrt(dx * dx + dz * dz) >= distanceHorizontal || Math.abs(dy) >= distanceVertical;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        if (mod.getPlayer() == null) return 0;
        Vec3d playerPos = mod.getPlayer().getPos();
        double dx = x + 0.5 - playerPos.x;
        double dz = z + 0.5 - playerPos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        return Math.max(0, distanceHorizontal - horizontalDist);
    }
}