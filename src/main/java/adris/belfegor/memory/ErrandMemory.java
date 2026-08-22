package adris.belfegor.memory;

import adris.belfegor.Belfegor;
import adris.belfegor.util.ItemTarget;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Persistent "task errand" ledger: records supplies the bot gathered and
 * stashed at a remembered chest (base storage or a stash chest), so a later
 * task can come back for them instead of re-gathering from the world.
 *
 * This is the memory half of the loop:
 *   gather -> stash at chest (record errand) -> later task finds the errand
 *   -> walk to the stash chest -> withdraw -> mark errand retrieved.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ErrandMemory {

    private static ErrandMemory INSTANCE = new ErrandMemory();
    private static final String FOLDER = "belfegor";
    private static final String FILE_NAME = "belfegor_errands.json";

    private final List<Errand> errands = new ArrayList<>();
    private boolean _dirty = false;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class Errand {
        public String id = "";
        public String item = "";
        public int count;
        public int remaining;
        public String dimension = "";
        public int chestX;
        public int chestY;
        public int chestZ;
        public int homeX;
        public int homeY;
        public int homeZ;
        public String source = "";
        public String status = "stored";
        public long createdAt;
        public long updatedAt;

        public BlockPos chestPos() {
            return new BlockPos(chestX, chestY, chestZ);
        }

        public BlockPos homePos() {
            return new BlockPos(homeX, homeY, homeZ);
        }
    }

    public static ErrandMemory getInstance() {
        return INSTANCE;
    }

    public static void init(File gameDir) {
        File file = new File(new File(gameDir, FOLDER), FILE_NAME);
        if (!file.exists()) file = new File(gameDir, FILE_NAME);
        if (!file.exists()) {
            INSTANCE = new ErrandMemory();
            return;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            INSTANCE = mapper.readValue(file, ErrandMemory.class);
        } catch (Exception e) {
            INSTANCE = new ErrandMemory();
        }
    }

    /**
     * Records that supplies were stashed at a chest. Reuses an existing
     * "stored" errand for the same item/home/chest so repeated stockpile runs
     * do not spam the ledger.
     */
    public void recordStored(BlockPos home, BlockPos chest, String dimension, String source,
                             ItemTarget... stored) {
        if (home == null || chest == null || stored == null || stored.length == 0) return;
        long now = System.currentTimeMillis();
        for (ItemTarget target : stored) {
            if (target == null || target.getMatches().length == 0) continue;
            Item item = target.getMatches()[0];
            String itemId = itemId(item);
            if (itemId.isBlank()) continue;
            Optional<Errand> existing = errands.stream()
                    .filter(e -> e != null
                            && e.item.equals(itemId)
                            && e.dimension.equals(dimension)
                            && e.chestX == chest.getX()
                            && e.chestY == chest.getY()
                            && e.chestZ == chest.getZ()
                            && e.homeX == home.getX()
                            && e.homeY == home.getY()
                            && e.homeZ == home.getZ()
                            && "stored".equals(e.status))
                    .findFirst();
            if (existing.isPresent()) {
                Errand errand = existing.get();
                errand.count += Math.max(0, target.getTargetCount());
                errand.remaining += Math.max(0, target.getTargetCount());
                errand.source = source == null || source.isBlank() ? errand.source : source;
                errand.updatedAt = now;
            } else {
                Errand errand = new Errand();
                errand.id = itemId + "@" + chest.toShortString() + "@" + now;
                errand.item = itemId;
                errand.count = Math.max(0, target.getTargetCount());
                errand.remaining = errand.count;
                errand.dimension = dimension == null ? "" : dimension;
                errand.chestX = chest.getX();
                errand.chestY = chest.getY();
                errand.chestZ = chest.getZ();
                errand.homeX = home.getX();
                errand.homeY = home.getY();
                errand.homeZ = home.getZ();
                errand.source = source == null ? "" : source;
                errand.status = "stored";
                errand.createdAt = now;
                errand.updatedAt = now;
                errands.add(errand);
            }
        }
        _dirty = true;
    }

    public int storedCount(BlockPos home, String dimension, Item item) {
        if (home == null || item == null) return 0;
        String itemId = itemId(item);
        return errands.stream()
                .filter(e -> e != null
                        && e.item.equals(itemId)
                        && e.remaining > 0
                        && e.dimension.equals(dimension)
                        && e.homeX == home.getX()
                        && e.homeY == home.getY()
                        && e.homeZ == home.getZ())
                .mapToInt(e -> e.remaining)
                .sum();
    }

    /** Best stash errand (most remaining) for the item at the given home. */
    public Optional<Errand> findStash(BlockPos home, String dimension, Item item) {
        if (home == null || item == null) return Optional.empty();
        String itemId = itemId(item);
        return errands.stream()
                .filter(e -> e != null
                        && e.item.equals(itemId)
                        && e.remaining > 0
                        && e.dimension.equals(dimension)
                        && e.homeX == home.getX()
                        && e.homeY == home.getY()
                        && e.homeZ == home.getZ())
                .max(Comparator.comparingInt(e -> e.remaining));
    }

    public boolean hasStash(BlockPos home, String dimension, Item item) {
        return findStash(home, dimension, item).isPresent();
    }

    public void markRetrieved(Errand errand, int amount) {
        if (errand == null || amount <= 0) return;
        errand.remaining = Math.max(0, errand.remaining - amount);
        errand.updatedAt = System.currentTimeMillis();
        if (errand.remaining <= 0) {
            errand.status = "retrieved";
        } else {
            errand.status = "partially_retrieved";
        }
        _dirty = true;
    }

    /** Removes stash errands tied to the dropped home. */
    public int forgetHome(BlockPos home, String dimension) {
        if (home == null) return 0;
        int before = errands.size();
        errands.removeIf(errand -> errand != null
                && (dimension == null || dimension.isBlank() || dimension.equals(errand.dimension))
                && errand.homeX == home.getX()
                && errand.homeY == home.getY()
                && errand.homeZ == home.getZ());
        int removed = before - errands.size();
        if (removed > 0) _dirty = true;
        return removed;
    }

    public List<Errand> getAll() {
        return new ArrayList<>(errands);
    }

    private static String itemId(Item item) {
        if (item == null) return "";
        Identifier id = Registries.ITEM.getId(item);
        return id == null ? "" : id.toString();
    }

    public static Optional<Item> itemFromId(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(Registries.ITEM.get(Identifier.of(id)));
        } catch (Exception e) {
            return Optional.empty();
        }
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

    /** Errands are only useful when the stash chest is still a real chest
     *  within a sane distance of the player. */
    public boolean stashUsable(Belfegor mod, Errand errand, float maxDistance) {
        if (mod == null || mod.getWorld() == null || errand == null || errand.remaining <= 0) return false;
        BlockPos chest = errand.chestPos();
        if (maxDistance > 0 && !chest.isWithinDistance(mod.getPlayer().getPos(), maxDistance)) return false;
        if (!mod.getChunkTracker().isChunkLoaded(chest)) return true;
        return mod.getWorld().getBlockState(chest).getBlock()
                instanceof net.minecraft.block.BlockWithEntity;
    }
}
