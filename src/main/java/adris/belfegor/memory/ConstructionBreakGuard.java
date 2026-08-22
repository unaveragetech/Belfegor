package adris.belfegor.memory;

import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Short-lived, task-owned protection for blocks that construction must not
 * mine.  Baritone's path predicate prevents new paths from choosing these
 * cells; this registry also lets the Minecraft interaction mixin reject a
 * stale attack that was already queued before a task handoff.
 */
public final class ConstructionBreakGuard {

    private static final AtomicLong NEXT_TOKEN = new AtomicLong(1L);
    private static final Map<Long, Guard> GUARDS = new LinkedHashMap<>();

    private ConstructionBreakGuard() {
    }

    public static synchronized long register(String label, Predicate<BlockPos> predicate) {
        if (predicate == null) return 0L;
        long token = NEXT_TOKEN.getAndIncrement();
        GUARDS.put(token, new Guard(label == null ? "construction" : label, predicate));
        return token;
    }

    public static synchronized void unregister(long token) {
        if (token != 0L) {
            GUARDS.remove(token);
        }
    }

    public static synchronized Optional<String> protectedBy(BlockPos pos) {
        if (pos == null) return Optional.empty();
        for (Guard guard : GUARDS.values()) {
            try {
                if (guard.predicate().test(pos)) {
                    return Optional.of(guard.label());
                }
            } catch (RuntimeException ignored) {
                // A stopped task may briefly outlive its world during unload.
            }
        }
        return Optional.empty();
    }

    private record Guard(String label, Predicate<BlockPos> predicate) {
    }
}
