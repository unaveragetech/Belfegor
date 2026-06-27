package adris.belfegor.memory;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Remembers the contents of shulker boxes by position.
 * Built by scanning shulker NBT data or opened shulker screens.
 * Persists to JSON across sessions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ShulkerMemory {

    private static ShulkerMemory INSTANCE = new ShulkerMemory();
    private static final String FOLDER = "belfegor";
    private static final String FILE_NAME = "belfegor_shulker_memory.json";

    private final Map<String, ShulkerEntry> _shulkers = new ConcurrentHashMap<>();
    private boolean _dirty = false;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class ShulkerEntry {
        public int x;
        public int y;
        public int z;
        public List<ShulkerItem> contents = new ArrayList<>();
        public long lastUpdated;
        public String location = "world";
        public int inventorySlot = -1;
        public String shulkerItem = "";
        public String lastPlacementReason = "";

        public ShulkerEntry() {}

        public ShulkerEntry(BlockPos pos) {
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
            this.lastUpdated = System.currentTimeMillis();
        }

        public BlockPos getPos() {
            return new BlockPos(x, y, z);
        }

        public int getItemCount(String itemName) {
            for (ShulkerItem si : contents) {
                if (si.itemName.equals(itemName)) return si.count;
            }
            return 0;
        }

        public boolean hasItem(String itemName) {
            return getItemCount(itemName) > 0;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class ShulkerItem {
        public String itemName;
        public int count;

        public ShulkerItem() {}

        public ShulkerItem(String itemName, int count) {
            this.itemName = itemName;
            this.count = count;
        }
    }

    public static ShulkerMemory getInstance() {
        return INSTANCE;
    }

    public static void init(File gameDir) {
        File file = new File(new File(gameDir, FOLDER), FILE_NAME);
        if (!file.exists()) {
            file = new File(gameDir, FILE_NAME);
        }
        if (file.exists()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                INSTANCE = mapper.readValue(file, ShulkerMemory.class);
            } catch (Exception e) {
                INSTANCE = new ShulkerMemory();
            }
        }
    }

    /**
     * Remember or update the contents of a shulker at a position.
     */
    public void rememberContents(BlockPos pos, Map<String, Integer> contents) {
        String key = posKey(pos);
        ShulkerEntry entry = _shulkers.getOrDefault(key, new ShulkerEntry(pos));
        entry.location = "world";
        entry.contents = contents.entrySet().stream()
                .map(e -> new ShulkerItem(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
        entry.lastUpdated = System.currentTimeMillis();
        _shulkers.put(key, entry);
        _dirty = true;
    }

    /**
     * Remember that a carried shulker was placed at a world position even
     * before its contents are scanned. This gives diagnostics and future tasks
     * a stable "managed shulker block" position instead of rediscovering it by
     * nearest-block guesses every tick.
     */
    public void rememberPlacement(BlockPos pos, String shulkerItem, String reason) {
        String key = posKey(pos);
        ShulkerEntry entry = _shulkers.getOrDefault(key, new ShulkerEntry(pos));
        entry.location = "world";
        entry.shulkerItem = shulkerItem == null ? "" : shulkerItem;
        entry.lastPlacementReason = reason == null ? "" : reason;
        entry.lastUpdated = System.currentTimeMillis();
        _shulkers.put(key, entry);
        _dirty = true;
    }

    public void rememberInventoryContents(int inventorySlot, String shulkerItem,
                                          Map<String, Integer> contents) {
        ShulkerEntry entry = new ShulkerEntry();
        entry.location = "inventory";
        entry.inventorySlot = inventorySlot;
        entry.shulkerItem = shulkerItem;
        entry.lastUpdated = System.currentTimeMillis();
        entry.contents = contents.entrySet().stream()
                .map(e -> new ShulkerItem(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
        _shulkers.put("inventory:" + inventorySlot, entry);
        _dirty = true;
    }

    public void clearInventoryEntries() {
        if (_shulkers.entrySet().removeIf(entry ->
                "inventory".equals(entry.getValue().location))) {
            _dirty = true;
        }
    }

    /**
     * Forget a shulker's contents (e.g., when destroyed or emptied).
     */
    public void forgetContents(BlockPos pos) {
        String key = posKey(pos);
        if (_shulkers.remove(key) != null) {
            _dirty = true;
        }
    }

    /**
     * Get the remembered contents of a shulker at a position.
     */
    public Optional<ShulkerEntry> getContents(BlockPos pos) {
        return Optional.ofNullable(_shulkers.get(posKey(pos)));
    }

    /**
     * Find all positions where a shulker contains the given item.
     */
    public List<ShulkerEntry> findItem(String itemName) {
        List<ShulkerEntry> results = new ArrayList<>();
        for (ShulkerEntry entry : _shulkers.values()) {
            if (entry.hasItem(itemName)) {
                results.add(entry);
            }
        }
        return results;
    }

    /**
     * Find all positions where a shulker contains the given item.
     */
    public List<ShulkerEntry> findItem(Item item) {
        String name = adris.belfegor.util.helpers.ItemHelper.stripItemName(item);
        return findItem(name);
    }

    /**
     * Get all remembered shulker entries.
     */
    public List<ShulkerEntry> getAllShulkers() {
        return new ArrayList<>(_shulkers.values());
    }

    /**
     * Get the total count of an item across all remembered shulkers.
     */
    public int getTotalItemCount(String itemName) {
        int total = 0;
        for (ShulkerEntry entry : _shulkers.values()) {
            total += entry.getItemCount(itemName);
        }
        return total;
    }

    /**
     * Get the total count of an item across all remembered shulkers.
     */
    public int getTotalItemCount(Item item) {
        return getTotalItemCount(adris.belfegor.util.helpers.ItemHelper.stripItemName(item));
    }

    /**
     * Check if any remembered shulker has the given item.
     */
    public boolean hasItem(String itemName) {
        return _shulkers.values().stream().anyMatch(e -> e.hasItem(itemName));
    }

    /**
     * Check if any remembered shulker has the given item.
     */
    public boolean hasItem(Item item) {
        return hasItem(adris.belfegor.util.helpers.ItemHelper.stripItemName(item));
    }

    /**
     * Check if we have any remembered shulkers at all.
     */
    public boolean isEmpty() {
        return _shulkers.isEmpty();
    }

    /**
     * Get the number of remembered shulkers.
     */
    public int size() {
        return _shulkers.size();
    }

    /**
     * Save to JSON if dirty.
     */
    public void save() {
        if (!_dirty) return;
        try {
            File dir = new File(FOLDER);
            dir.mkdirs();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(dir, FILE_NAME), this);
            _dirty = false;
        } catch (Exception e) {
            // Silently fail
        }
    }

    private static String posKey(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
