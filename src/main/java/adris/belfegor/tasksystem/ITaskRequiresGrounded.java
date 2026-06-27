package adris.belfegor.tasksystem;

import adris.belfegor.Belfegor;

/**
 * Some tasks may mess up royally if we interrupt them while mid air.
 * For instance, if we're doing some parkour and a baritone task is stopped,
 * the player will fall to whatever is below them, perhaps their death.
 */
public interface ITaskRequiresGrounded extends ITaskCanForce {
    @Override
    default boolean shouldForce(Belfegor mod, Task interruptingCandidate) {
        if (interruptingCandidate instanceof ITaskOverridesGrounded)
            return false;
        if (mod.getPlayer() == null) return false;
        return !(mod.getPlayer().isOnGround() || mod.getPlayer().isSwimming() || mod.getPlayer().isTouchingWater() || mod.getPlayer().isClimbing());
    }
}
