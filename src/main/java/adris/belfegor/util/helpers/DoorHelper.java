package adris.belfegor.util.helpers;

import adris.belfegor.Belfegor;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Small shared helper for everything the bot needs to do with doors.
 *
 * Belfegor builds and remembers its own entrance doors, but the bundled
 * Baritone fork has no "open doors" behavior, so the bot previously either
 * broke its own doors or treated them as random obstacles. This helper gives
 * movement tasks a way to right-click a closed door in front of them (exactly
 * what a player would do) before falling back to shimmying or breaking.
 */
public final class DoorHelper {

    private static final int OPEN_COOLDOWN_TICKS = 12;
    private static final int FAIL_COOLDOWN_TICKS = 30;
    private static final Map<String, Integer> DOOR_TRY_TICKS = new HashMap<>();

    private DoorHelper() {
    }

    public static boolean isDoor(Belfegor mod, BlockPos pos) {
        return pos != null && mod != null && mod.getWorld() != null
                && mod.getWorld().getBlockState(pos).getBlock() instanceof DoorBlock;
    }

    public static boolean isDoorOpen(Belfegor mod, BlockPos pos) {
        if (!isDoor(mod, pos)) return false;
        BlockState state = mod.getWorld().getBlockState(pos);
        try {
            return state.get(DoorBlock.OPEN);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Finds a door in the player's immediate vicinity (feet, head, or one of the four sides). */
    public static Optional<BlockPos> findDoorAtOrNear(Belfegor mod, BlockPos pos) {
        if (pos == null || mod == null || mod.getWorld() == null) return Optional.empty();
        BlockPos[] candidates = {
                pos, pos.up(), pos.down(),
                pos.north(), pos.south(), pos.east(), pos.west()
        };
        for (BlockPos candidate : candidates) {
            if (isDoor(mod, candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /**
     * Right-clicks a closed door to open it, the way a player would. Returns
     * true when the door is already open or the click was accepted. Returns
     * false when out of reach, a screen is open, or the door is missing.
     */
    public static boolean openDoor(Belfegor mod, BlockPos door) {
        if (mod == null || mod.getPlayer() == null || door == null) return false;
        if (!isDoor(mod, door)) return false;
        if (isDoorOpen(mod, door)) return true;
        if (MinecraftClient.getInstance().currentScreen != null) return false;
        if (mod.getPlayer().getBlockPos().getSquaredDistance(door) > 6 * 6) return false;

        LookHelper.lookAt(mod, door, Direction.UP);
        Vec3d hit = Vec3d.ofCenter(door);
        BlockHitResult result = new BlockHitResult(hit, Direction.UP, door, false);
        ActionResult action = mod.getController().interactBlock(mod.getPlayer(), Hand.MAIN_HAND, result);
        mod.getPlayer().swingHand(Hand.MAIN_HAND);
        return action.isAccepted();
    }

    /**
     * Called by movement tasks when they detect they are stuck against a door.
     * Cooldown-guarded so the bot does not spam right-clicks every tick. When
     * the door cannot be opened (unreachable, missing), the caller falls back
     * to its normal unstuck behavior.
     */
    public static boolean tryOpenBlockedDoor(Belfegor mod, BlockPos stuckPos) {
        if (mod == null || stuckPos == null) return false;
        Optional<BlockPos> door = findDoorAtOrNear(mod, stuckPos);
        if (door.isEmpty()) return false;
        String key = door.get().toShortString();
        int remaining = DOOR_TRY_TICKS.getOrDefault(key, 0);
        if (remaining > 0) {
            DOOR_TRY_TICKS.put(key, remaining - 1);
            return true;
        }
        boolean opened = openDoor(mod, door.get());
        DOOR_TRY_TICKS.put(key, opened ? OPEN_COOLDOWN_TICKS : FAIL_COOLDOWN_TICKS);
        return opened;
    }
}
