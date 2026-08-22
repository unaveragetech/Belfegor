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
    private static final int MAX_NETWORK_HORIZONTAL_DISTANCE = 160;
    private static final int MAX_NETWORK_VERTICAL_DISTANCE = 48;

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
        public Map<String, Integer> itemRowOwners = new TreeMap<>();
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
        public boolean full = false;
        public int rowIndex = 0;
        public long lastSeen;
        public String note = "";
        public Map<String, Integer> knownCounts = new TreeMap<>();

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
                INSTANCE.sanitizeAllNetworks();
            } catch (Exception e) {
                INSTANCE = new BaseStorageMemory();
            }
        }
    }

    public List<StorageNetwork> networks() {
        sanitizeAllNetworks();
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
        sanitizeNetwork(network);
        network.lastUpdated = System.currentTimeMillis();
        return network;
    }

    public void rememberChest(BlockPos home, String dimension, BlockPos chest, String role, boolean doubleChest, String note) {
        if (home == null || chest == null) return;
        if (!isWithinNetworkRange(home, chest)) {
            DebugLogger.getInstance().log("BASE-STORAGE",
                    "rejected-remote-chest home=" + home.toShortString()
                            + " chest=" + chest.toShortString()
                            + " role=" + role
                            + " note=remote overflow must not become persistent camp storage");
            return;
        }
        StorageNetwork network = networkFor(home, dimension);
        Optional<StorageChest> existing = network.chests.stream()
                .filter(c -> c.x == chest.getX() && c.y == chest.getY() && c.z == chest.getZ())
                .findFirst();
        if (existing.isPresent()) {
            StorageChest c = existing.get();
            if (role != null && !role.isBlank() && rolePriority(role) >= rolePriority(c.role)) {
                c.role = role;
            }
            c.doubleChest = c.doubleChest || doubleChest;
            c.note = note == null || note.isBlank() ? c.note : note;
            c.lastSeen = System.currentTimeMillis();
        } else {
            StorageChest created = new StorageChest(chest, role, doubleChest, note);
            created.rowIndex = network.chests.size() / 5;
            network.chests.add(created);
        }
        normalizeRows(network);
        network.phase = role != null && role.contains("storage") ? "storage_room" : network.phase;
        network.lastUpdated = System.currentTimeMillis();
        _dirty = true;
    }

    public Optional<BlockPos> bestChest(Belfegor mod, BlockPos home, String dimension) {
        if (home == null) return Optional.empty();
        StorageNetwork network = networkFor(home, dimension);
        return network.chests.stream()
                .map(StorageChest::pos)
                .filter(pos -> isWithinNetworkRange(home, pos))
                .filter(pos -> mod == null || mod.getWorld() == null || WorldHelper.isChest(mod, pos))
                .min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(home)));
    }

    public List<StorageChest> chestsFor(BlockPos home, String dimension) {
        if (home == null) return List.of();
        StorageNetwork network = networkFor(home, dimension);
        sanitizeNetwork(network);
        normalizeRows(network);
        return network.chests.stream()
                .sorted(Comparator.comparingInt((StorageChest chest) -> chest.rowIndex)
                        .thenComparingInt(chest -> chest.x)
                        .thenComparingInt(chest -> chest.z))
                .toList();
    }

    public Optional<BlockPos> preferredChestFor(Belfegor mod, BlockPos home, String dimension, ItemTarget... items) {
        if (home == null) return Optional.empty();
        StorageNetwork network = networkFor(home, dimension);
        normalizeRows(network);
        for (ItemTarget target : items == null ? new ItemTarget[0] : items) {
            if (target == null || target.getMatches().length == 0) continue;
            Integer row = network.itemRowOwners.get(itemKey(target.getMatches()[0]));
            if (row == null) continue;
            Optional<BlockPos> rowChest = network.chests.stream()
                    .filter(chest -> chest.rowIndex == row && !chest.full)
                    .map(StorageChest::pos)
                    .filter(pos -> isWithinNetworkRange(home, pos))
                    .filter(pos -> mod == null || mod.getWorld() == null || WorldHelper.isChest(mod, pos))
                    .min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(home)));
            if (rowChest.isPresent()) return rowChest;
        }
        boolean armoryRequest = containsArmoryItem(items);
        return network.chests.stream()
                .filter(chest -> !chest.full)
                .filter(chest -> armoryRequest || !isArmoryRole(chest.role))
                .map(StorageChest::pos)
                .filter(pos -> isWithinNetworkRange(home, pos))
                .filter(pos -> mod == null || mod.getWorld() == null || WorldHelper.isChest(mod, pos))
                .min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(home)));
    }

    public Optional<BlockPos> preferredChestForRole(Belfegor mod, BlockPos home, String dimension,
                                                     String role, ItemTarget... items) {
        if (home == null || role == null || role.isBlank()) return Optional.empty();
        StorageNetwork network = networkFor(home, dimension);
        normalizeRows(network);
        String query = normalizeRole(role);
        return network.chests.stream()
                .filter(chest -> !chest.full)
                .filter(chest -> normalizeRole(chest.role).equals(query)
                        || normalizeRole(chest.role).contains(query)
                        || query.contains(normalizeRole(chest.role)))
                .map(StorageChest::pos)
                .filter(pos -> isWithinNetworkRange(home, pos))
                .filter(pos -> mod == null || mod.getWorld() == null || WorldHelper.isChest(mod, pos))
                .min(Comparator.comparingDouble(pos -> mod != null && mod.getPlayer() != null
                        ? pos.getSquaredDistance(mod.getPlayer().getBlockPos())
                        : pos.getSquaredDistance(home)));
    }

    public void rememberPreferredRow(BlockPos home, String dimension, int rowIndex, ItemTarget... items) {
        if (home == null || items == null) return;
        StorageNetwork network = networkFor(home, dimension);
        for (ItemTarget target : items) {
            if (target == null || target.getMatches().length == 0) continue;
            String key = itemKey(target.getMatches()[0]);
            if (!key.isBlank()) network.itemRowOwners.putIfAbsent(key, Math.max(0, rowIndex));
        }
        network.lastUpdated = System.currentTimeMillis();
        _dirty = true;
    }

    public int rowIndexForChest(BlockPos home, String dimension, BlockPos chestPos) {
        if (home == null || chestPos == null) return 0;
        StorageNetwork network = networkFor(home, dimension);
        normalizeRows(network);
        return network.chests.stream()
                .filter(chest -> chest.x == chestPos.getX()
                        && chest.y == chestPos.getY()
                        && chest.z == chestPos.getZ())
                .map(chest -> chest.rowIndex)
                .findFirst()
                .orElse(0);
    }

    public int chestCount(BlockPos home, String dimension) {
        if (home == null) return 0;
        return networkFor(home, dimension).chests.size();
    }

    public void markChestFull(BlockPos home, String dimension, BlockPos chestPos) {
        if (home == null || chestPos == null) return;
        StorageNetwork network = networkFor(home, dimension);
        for (StorageChest chest : network.chests) {
            if (chest.x == chestPos.getX() && chest.y == chestPos.getY() && chest.z == chestPos.getZ()) {
                chest.full = true;
                chest.lastSeen = System.currentTimeMillis();
                network.lastUpdated = System.currentTimeMillis();
                _dirty = true;
                return;
            }
        }
    }

    public void markChestAvailable(BlockPos home, String dimension, BlockPos chestPos) {
        if (home == null || chestPos == null || !isWithinNetworkRange(home, chestPos)) return;
        StorageNetwork network = networkFor(home, dimension);
        for (StorageChest chest : network.chests) {
            if (chest.x == chestPos.getX() && chest.y == chestPos.getY() && chest.z == chestPos.getZ()) {
                chest.full = false;
                chest.lastSeen = System.currentTimeMillis();
                network.lastUpdated = System.currentTimeMillis();
                _dirty = true;
                return;
            }
        }
    }

    /** Removes every storage network whose home is within a radius. */
    public int forgetNetworksNear(BlockPos home, String dimension, double radius) {
        if (home == null) return 0;
        double radiusSq = Math.max(0, radius) * Math.max(0, radius);
        int before = _networks.size();
        _networks.entrySet().removeIf(entry -> {
            StorageNetwork network = entry.getValue();
            if (network == null) return true;
            if (dimension != null && !dimension.isBlank() && !dimension.equals(network.dimension)) return false;
            long dx = (long) network.homeX - home.getX();
            long dz = (long) network.homeZ - home.getZ();
            return dx * dx + dz * dz <= radiusSq;
        });
        int removed = before - _networks.size();
        if (removed > 0) _dirty = true;
        return removed;
    }

    public Optional<BlockPos> nextChestPosition(BlockPos home, String dimension) {
        if (home == null) return Optional.empty();
        StorageNetwork network = networkFor(home, dimension);
        sanitizeNetwork(network);
        BlockPos anchor = network.chests.stream()
                .filter(chest -> chest.role != null && chest.role.contains("storage"))
                .map(StorageChest::pos)
                .findFirst()
                .orElse(home.add(2, 0, -2));
        int index = network.chests.size();
        int row = index / 5;
        int col = index % 5;
        return Optional.of(anchor.add(col - 2, 0, -row));
    }

    public void recordStored(BlockPos home, String dimension, BlockPos chest, ItemTarget... stored) {
        if (home == null || stored == null) return;
        if (chest != null && !isWithinNetworkRange(home, chest)) {
            DebugLogger.getInstance().log("BASE-STORAGE",
                    "ignored-remote-store home=" + home.toShortString()
                            + " chest=" + chest.toShortString()
                            + " items=" + Arrays.toString(stored));
            return;
        }
        if (chest != null) {
            rememberChest(home, dimension, chest, "stored_resources", false, "stored resources");
            markChestAvailable(home, dimension, chest);
        }
        StorageNetwork network = networkFor(home, dimension);
        for (ItemTarget target : stored) {
            if (target == null || target.getMatches().length == 0) continue;
            String key = itemKey(target.getMatches()[0]);
            if (key.isBlank()) continue;
            network.knownCounts.merge(key, Math.max(0, target.getTargetCount()), Integer::sum);
            if (chest != null) {
                network.chests.stream()
                        .filter(c -> c.x == chest.getX() && c.y == chest.getY() && c.z == chest.getZ())
                        .findFirst()
                        .ifPresent(c -> {
                            if (c.knownCounts == null) c.knownCounts = new TreeMap<>();
                            c.knownCounts.merge(key, Math.max(0, target.getTargetCount()), Integer::sum);
                            network.itemRowOwners.putIfAbsent(key, c.rowIndex);
                        });
            }
        }
        network.lastUpdated = System.currentTimeMillis();
        _dirty = true;
        DebugLogger.getInstance().log("BASE-STORAGE",
                "record-stored home=" + home.toShortString()
                        + " chest=" + (chest == null ? "unknown" : chest.toShortString())
                        + " items=" + Arrays.toString(stored));
    }

    public void recordWithdrawn(BlockPos home, String dimension, BlockPos chest, ItemTarget... withdrawn) {
        if (home == null || withdrawn == null) return;
        StorageNetwork network = _networks.get(networkId(home, dimension));
        if (network == null) return;
        StorageChest source = chest == null ? null : network.chests.stream()
                .filter(c -> c.x == chest.getX() && c.y == chest.getY() && c.z == chest.getZ())
                .findFirst().orElse(null);
        for (ItemTarget target : withdrawn) {
            if (target == null || target.getMatches().length == 0) continue;
            String key = itemKey(target.getMatches()[0]);
            int amount = Math.max(0, target.getTargetCount());
            network.knownCounts.computeIfPresent(key, (ignored, count) -> Math.max(0, count - amount));
            if (source != null) {
                if (source.knownCounts == null) source.knownCounts = new TreeMap<>();
                source.knownCounts.computeIfPresent(key, (ignored, count) -> Math.max(0, count - amount));
            }
        }
        network.knownCounts.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() <= 0);
        if (source != null && source.knownCounts != null) {
            source.knownCounts.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() <= 0);
        }
        network.lastUpdated = System.currentTimeMillis();
        _dirty = true;
        DebugLogger.getInstance().log("BASE-STORAGE",
                "record-withdrawn home=" + home.toShortString()
                        + " chest=" + (chest == null ? "unknown" : chest.toShortString())
                        + " items=" + Arrays.toString(withdrawn));
    }

    public int knownCount(Item... items) {
        int result = 0;
        for (StorageNetwork network : _networks.values()) {
            sanitizeNetwork(network);
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
        sanitizeNetwork(network);
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

    private void sanitizeAllNetworks() {
        for (StorageNetwork network : _networks.values()) {
            sanitizeNetwork(network);
        }
    }

    private void sanitizeNetwork(StorageNetwork network) {
        if (network == null) return;
        if (network.chests == null) network.chests = new ArrayList<>();
        if (network.knownCounts == null) network.knownCounts = new TreeMap<>();
        if (network.itemRowOwners == null) network.itemRowOwners = new TreeMap<>();
        for (StorageChest chest : network.chests) {
            if (chest != null && chest.knownCounts == null) chest.knownCounts = new TreeMap<>();
        }
        BlockPos home = network.home();
        int before = network.chests.size();
        network.chests.removeIf(chest -> chest == null
                || !isWithinNetworkRange(home, chest.pos()));
        if (network.chests.size() != before) {
            network.itemRowOwners.clear();
            if (network.chests.isEmpty()) {
                network.knownCounts.clear();
            }
            network.lastUpdated = System.currentTimeMillis();
            _dirty = true;
            DebugLogger.getInstance().log("BASE-STORAGE",
                    "pruned-remote-chests home=" + home.toShortString()
                            + " removed=" + (before - network.chests.size()));
        }
        normalizeRows(network);
    }

    private static boolean isWithinNetworkRange(BlockPos home, BlockPos chest) {
        if (home == null || chest == null) return false;
        long dx = (long) chest.getX() - home.getX();
        long dz = (long) chest.getZ() - home.getZ();
        int dy = Math.abs(chest.getY() - home.getY());
        long max = MAX_NETWORK_HORIZONTAL_DISTANCE;
        return dx * dx + dz * dz <= max * max
                && dy <= MAX_NETWORK_VERTICAL_DISTANCE;
    }

    private static void normalizeRows(StorageNetwork network) {
        network.chests.sort(Comparator.comparingInt((StorageChest chest) -> chest.z)
                .thenComparingInt(chest -> chest.x)
                .thenComparingInt(chest -> chest.y));
        for (int i = 0; i < network.chests.size(); i++) {
            network.chests.get(i).rowIndex = i / 5;
        }
    }

    private static int rolePriority(String role) {
        String normalized = normalizeRole(role);
        if (normalized.startsWith("armory_")) return 50;
        if (normalized.contains("bulk_storage") || normalized.contains("storage_room")) return 40;
        if (normalized.contains("camp_storage") || normalized.contains("storage_row")) return 30;
        if (normalized.contains("stored_resources") || normalized.equals("storage")) return 20;
        if (normalized.contains("overflow")) return 10;
        return normalized.isBlank() ? 0 : 15;
    }

    private static boolean isArmoryRole(String role) {
        return normalizeRole(role).startsWith("armory_");
    }

    private static String normalizeRole(String role) {
        return role == null ? "" : role.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static boolean containsArmoryItem(ItemTarget... targets) {
        if (targets == null) return false;
        for (ItemTarget target : targets) {
            if (target == null) continue;
            for (Item item : target.getMatches()) {
                if (isArmoryItem(item)) return true;
            }
        }
        return false;
    }

    private static boolean isArmoryItem(Item item) {
        if (item == null) return false;
        String key = itemKey(item);
        return key.endsWith("_pickaxe") || key.endsWith("_axe") || key.endsWith("_shovel")
                || key.endsWith("_hoe") || key.endsWith("_sword") || key.endsWith("_helmet")
                || key.endsWith("_chestplate") || key.endsWith("_leggings") || key.endsWith("_boots")
                || key.endsWith(":bow") || key.endsWith(":crossbow") || key.endsWith(":shield")
                || key.endsWith(":arrow") || key.endsWith(":spectral_arrow") || key.endsWith(":tipped_arrow");
    }
}
