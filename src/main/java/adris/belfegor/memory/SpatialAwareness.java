package adris.belfegor.memory;

import adris.belfegor.Belfegor;
import adris.belfegor.util.helpers.WorldHelper;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Compact "what is around me right now" model for autonomous player mode.
 * This intentionally stores summary counts rather than a giant voxel dump.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class SpatialAwareness {

    private static SpatialAwareness INSTANCE = new SpatialAwareness();
    private static final String FOLDER = "belfegor";
    private static final String FILE_NAME = "belfegor_spatial_awareness.json";

    public SpatialSnapshot lastSnapshot = new SpatialSnapshot();
    private boolean _dirty = false;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class SpatialSnapshot {
        public long timestamp;
        public String dimension = "";
        public int x;
        public int y;
        public int z;
        public int radius;
        public int airBlocks;
        public int solidBlocks;
        public int liquidBlocks;
        public int lavaBlocks;
        public int waterBlocks;
        public int openHeadroomColumns;
        public int flatFloorColumns;
        public int hostileEntities;
        public int passiveEntities;
        public int droppedItems;
        public boolean standingInLiquid;
        public boolean nearLava;
        public boolean hasEmergencyHeadroom;
        public Map<String, Integer> notableBlocks = new HashMap<>();
        public String summary = "";
    }

    public static SpatialAwareness getInstance() {
        return INSTANCE;
    }

    public static void init(File gameDir) {
        File file = new File(new File(gameDir, FOLDER), FILE_NAME);
        if (!file.exists()) file = new File(gameDir, FILE_NAME);
        if (!file.exists()) {
            INSTANCE = new SpatialAwareness();
            return;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            INSTANCE = mapper.readValue(file, SpatialAwareness.class);
        } catch (Exception e) {
            INSTANCE = new SpatialAwareness();
        }
    }

    public SpatialSnapshot scan(Belfegor mod, int radius) {
        SpatialSnapshot snapshot = new SpatialSnapshot();
        if (mod == null || mod.getPlayer() == null || mod.getWorld() == null) {
            lastSnapshot = snapshot;
            return snapshot;
        }
        radius = Math.max(2, Math.min(12, radius));
        BlockPos origin = mod.getPlayer().getBlockPos();
        snapshot.timestamp = System.currentTimeMillis();
        snapshot.dimension = WorldHelper.getCurrentDimension().name();
        snapshot.x = origin.getX();
        snapshot.y = origin.getY();
        snapshot.z = origin.getZ();
        snapshot.radius = radius;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos feet = origin.add(dx, 0, dz);
                BlockPos floor = feet.down();
                if (WorldHelper.isAir(mod, feet) && WorldHelper.isAir(mod, feet.up())) {
                    snapshot.openHeadroomColumns++;
                }
                if (WorldHelper.isSolid(mod, floor)
                        && WorldHelper.isAir(mod, feet)
                        && WorldHelper.isAir(mod, feet.up())) {
                    snapshot.flatFloorColumns++;
                }
                for (int dy = -2; dy <= 4; dy++) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    Block block = mod.getWorld().getBlockState(pos).getBlock();
                    if (block == Blocks.AIR) {
                        snapshot.airBlocks++;
                    } else if (block == Blocks.WATER) {
                        snapshot.waterBlocks++;
                        snapshot.liquidBlocks++;
                    } else if (block == Blocks.LAVA) {
                        snapshot.lavaBlocks++;
                        snapshot.liquidBlocks++;
                    } else {
                        snapshot.solidBlocks++;
                    }
                    if (isNotable(block)) {
                        snapshot.notableBlocks.merge(block.getName().getString(), 1, Integer::sum);
                    }
                }
            }
        }

        snapshot.standingInLiquid = mod.getWorld().getBlockState(origin).getBlock() == Blocks.WATER
                || mod.getWorld().getBlockState(origin).getBlock() == Blocks.LAVA;
        snapshot.nearLava = snapshot.lavaBlocks > 0;
        snapshot.hasEmergencyHeadroom = WorldHelper.isAir(mod, origin.up())
                && WorldHelper.isAir(mod, origin.up(2));

        for (Entity entity : mod.getWorld().getEntities()) {
            if (entity == mod.getPlayer()) continue;
            if (entity.squaredDistanceTo(mod.getPlayer()) > radius * radius) continue;
            if (entity instanceof HostileEntity) snapshot.hostileEntities++;
            else if (entity instanceof net.minecraft.entity.ItemEntity) snapshot.droppedItems++;
            else snapshot.passiveEntities++;
        }
        snapshot.summary = "r=" + radius
                + " floor=" + snapshot.flatFloorColumns
                + " headroom=" + snapshot.openHeadroomColumns
                + " hostiles=" + snapshot.hostileEntities
                + " items=" + snapshot.droppedItems
                + " lava=" + snapshot.lavaBlocks
                + " water=" + snapshot.waterBlocks;
        lastSnapshot = snapshot;
        _dirty = true;
        return snapshot;
    }

    public void save() {
        if (!_dirty) return;
        try {
            File dir = new File(FOLDER);
            dir.mkdirs();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(dir, FILE_NAME), this);
            _dirty = false;
        } catch (Exception ignored) {
        }
    }

    private boolean isNotable(Block block) {
        return block == Blocks.CRAFTING_TABLE
                || block == Blocks.FURNACE
                || block == Blocks.CHEST
                || block == Blocks.FARMLAND
                || block == Blocks.WHEAT
                || block == Blocks.WATER
                || block == Blocks.LAVA
                || block == Blocks.COAL_ORE
                || block == Blocks.IRON_ORE
                || block == Blocks.DIAMOND_ORE
                || block == Blocks.DEEPSLATE_DIAMOND_ORE
                || block == Blocks.DEEPSLATE_IRON_ORE;
    }
}
