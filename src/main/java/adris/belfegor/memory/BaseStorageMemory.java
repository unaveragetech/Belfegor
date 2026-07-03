package adris.belfegor.memory;

import adris.belfegor.Belfegor;
import adris.belfegor.debug.DebugLogger;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.WorldHelper;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent memory for the base storage economy.
 *
 * This is deliberately a ledger, not a perfect live inventory mirror. It gives
 * long-running autonomy a durable idea of "what should be available at home"
 * before the bot has opened every chest this session. Counts are updated when
 * Belfegor stores/stockpiles items and later refined by fuller scan/sort tasks.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class BaseStorageMemory {

    private static BaseStorageMemory INSTANCE = new BaseStorageMemory();
    private static final String FOLDER = "belfegor";
    private static final String FILE_NAME = "belfegor_base_storage.json";

    private final Map<String, StorageNetwork> _networks = new ConcurrentHashMap<>();
    private boolean _dirty = false;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class StorageNetwork {
        public String id = "";
        public int homeX;
        public int homeY;
        public int homeZ;
        public String dimension = "";
        public List<StorageChest> chests = new ArrayList<>();
        public Map<String, Integer> knownCounts = new TreeMap<>();
        public long lastUpdated;
        public String phase = "camp";

        public BlockPos home() {
            return new BlockPos(homeX, homeY, homeZ);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class StorageChest {
        public int x;
        public int y;
        public int z;
        public String role = "overflow";
        public boolean doubleChest = false;
        public long lastSeen;
        public String note = "";

        public StorageChest() {}

        public StorageChest(BlockPos pos, String role, boolean doubleChest, String note) {
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
            this.role = role == null || role.isBlank() ? "overflow" : role;
            this.doubleChest = doubleChest;
            this.note = note == null ? "" : note;
            this.lastSeen = System.currentTimeMillis();
        }

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    public static BaseStorageMemory getInstance() {
        return INSTANCE;
    }

    public static void init(File gameDir) {
        File file = new File(gameDir, FILE_NAME);
        if (file.exists()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                INSTANCE = mapper.readValue(file, BaseStorageMemory.class);
            } catch (Exception e) {
                INSTANCE = new BaseStorageMemory();
            }
        }
    }

    public List<StorageNetwork> networks() {
        List<StorageNetwork> result = new ArrayList<>(_networks.values());
        result.sort(Comparator.comparingLong(network -> -network.lastUpdated));
        return result;
    }

    public StorageNetwork networkFor(BlockPos home, String dimension) {
        String id = networkId(home, dimension);
        StorageNetwork network = _networks.computeIfAbsent(id, ignored -> {
            StorageNetwork created = new StorageNetwork();
            created.id = id;
            created.homeX = home.getX();
            created.homeY = home.getY();
            created.homeZ = home.getZ();
            created.dimension = dimension == null ? "" : dimension;
            created.lastUpdated = System.currentTimeMillis();
            _dirty = true;
            return created;
        });
        network.lastUpdated = System.currentTimeMillis();
        return network;
    }

    public void rememberChest(BlockPos home, String dimension, BlockPos chest, String role, boolean doubleChest, String note) {
        if (home == null || chest == null) return;
        StorageNetwork network = networkFor(home, dimension);
        Optional<StorageChest> existing = network.chests.stream()
                .filter(c -> c.x == chest.getX() && c.y == chest.getY() && c.z == chest.getZ())
                .findFirst();
        if (existing.isPresent()) {
            StorageChest c = existing.get();
            c.role = role == null || role.isBlank() ? c.role : role;
            c.doubleChest = c.doubleChest || doubleChest;
            c.note = note == null || note.isBlank() ? c.note : note;
            c.lastSeen = System.currentTimeMillis();
        } else {
            network.chests.add(new StorageChest(chest, role, doubleChest, note));
        }
        network.phase = role != null && role.contains("storage") ? "storage_room" : network.phase;
        network.lastUpdated = System.currentTimeMillis();
        _dirty = true;
    }

    public Optional<BlockPos> bestChest(Belfegor mod, BlockPos home, String dimension) {
        if (home == null) return Optional.empty();
        StorageNetwork network = networkFor(home, dimension);
        return network.chests.stream()
                .map(StorageChest::pos)
                .filter(pos -> mod == null || mod.getWorld() == null || WorldHelper.isChest(mod, pos))
                .min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(home)));
    }

    public void recordStored(BlockPos home, String dimension, BlockPos chest, ItemTarget... stored) {
        if (home == null || stored == null) return;
        if (chest != null) {
            rememberChest(home, dimension, chest, "overflow", false, "stored resources");
        }
        StorageNetwork network = networkFor(home, dimension);
        for (ItemTarget target : stored) {
            if (target == null || target.getMatches().length == 0) continue;
            String key = itemKey(target.getMatches()[0]);
            if (key.isBlank()) continue;
            network.knownCounts.merge(key, Math.max(0, target.getTargetCount()), Integer::sum);
        }
        network.lastUpdated = System.currentTimeMillis();
        _dirty = true;
        DebugLogger.getInstance().log("BASE-STORAGE",
                "record-stored home=" + home.toShortString()
                        + " chest=" + (chest == null ? "unknown" : chest.toShortString())
                        + " items=" + Arrays.toString(stored));
    }

    public int knownCount(Item... items) {
        int result = 0;
        for (StorageNetwork network : _networks.values()) {
            for (Item item : items) {
                result += network.knownCounts.getOrDefault(itemKey(item), 0);
            }
        }
        return result;
    }

    public int knownCountAt(BlockPos home, String dimension, Item... items) {
        if (home == null) return 0;
        StorageNetwork network = _networks.get(networkId(home, dimension));
        if (network == null) return 0;
        int result = 0;
        for (Item item : items) {
            result += network.knownCounts.getOrDefault(itemKey(item), 0);
        }
        return result;
    }

    public int availableAtBase(Belfegor mod, BlockPos home, String dimension, Item... items) {
        int carried = mod == null ? 0 : mod.getItemStorage().getItemCountInventoryOnly(items);
        return carried + knownCountAt(home, dimension, items);
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

    private static String networkId(BlockPos home, String dimension) {
        if (home == null) return (dimension == null ? "" : dimension) + ":unknown";
        return (dimension == null ? "" : dimension) + ":" + home.getX() + "," + home.getY() + "," + home.getZ();
    }

    private static String itemKey(Item item) {
        if (item == null) return "";
        Identifier id = Registries.ITEM.getId(item);
        return id == null ? "" : id.toString();
    }
}
