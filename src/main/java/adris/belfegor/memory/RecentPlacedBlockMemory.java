package adris.belfegor.memory;

import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Short-lived memory for blocks Belfegor placed itself.
 *
 * This prevents resource gathering from immediately mining a throwaway/scaffold
 * block it just placed, which can otherwise create a place -> mine -> place loop
 * that burns tool durability without producing progress.
 */
public final class RecentPlacedBlockMemory {
    private static final long TTL_MS = 120_000L;
    private static final Map<BlockPos, Long> RECENT = new HashMap<>();

    private RecentPlacedBlockMemory() {}

    public static synchronized void markPlaced(BlockPos pos) {
        if (pos == null) return;
        cleanup();
        RECENT.put(pos.toImmutable(), System.currentTimeMillis());
    }

    public static synchronized boolean wasRecentlyPlaced(BlockPos pos) {
        if (pos == null) return false;
        cleanup();
        return RECENT.containsKey(pos);
    }

    public static synchronized void clear(BlockPos pos) {
        if (pos == null) return;
        RECENT.remove(pos);
    }

    private static void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<BlockPos, Long>> iterator = RECENT.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Long> entry = iterator.next();
            if (now - entry.getValue() > TTL_MS) {
                iterator.remove();
            }
        }
    }
}
