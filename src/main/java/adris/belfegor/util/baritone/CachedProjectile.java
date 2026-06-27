package adris.belfegor.util.baritone;

import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.util.math.Vec3d;

public class CachedProjectile {
    public Vec3d position;
    public Vec3d velocity;
    public double gravity;
    public Class<? extends ProjectileEntity> projectileType;
}