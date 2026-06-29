package adris.belfegor.memory;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
    private static final String BACKUP_FILE_NAME = FILE_NAME + ".bak";

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
        public String sourceKey = "";
        public String fingerprint = "";
        public String lastVerifiedSource = "";
        public int slotCount = 27;
        public int freeSlots = 27;
        public int totalItems = 0;
        public List<ShulkerSlotItem> slots = new ArrayList<>();

        public ShulkerEntry() {}

        public ShulkerEntry(BlockPos pos) {
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
            this.lastUpdated = System.currentTimeMillis();
        }

        @JsonIgnore
        public BlockPos getPos() {
            return new BlockPos(x, y, z);
        }

        public int getItemCount(String itemName) {
            int count = 0;
            for (ShulkerSlotItem slot : slots) {
                if (slot.itemName.equals(itemName)) count += slot.count;
            }
            if (count > 0) return count;
            for (ShulkerItem si : contents) {
                if (si.itemName.equals(itemName)) return si.count;
            }
            return 0;
        }

        public boolean hasItem(String itemName) {
            return getItemCount(itemName) > 0;
        }

        public List<Integer> getSlotsWithItem(String itemName) {
            return slots.stream()
                    .filter(slot -> slot.itemName.equals(itemName))
                    .map(slot -> slot.slot)
                    .collect(Collectors.toList());
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class ShulkerSlotItem {
        public int slot;
        public String itemName = "";
        public String itemKey = "";
        public int count;

        public ShulkerSlotItem() {}

        public ShulkerSlotItem(int slot, String itemName, int count) {
            this(slot, itemName, itemName, count);
        }

        public ShulkerSlotItem(int slot, String itemName, String itemKey, int count) {
            this.slot = slot;
            this.itemName = itemName == null ? "" : itemName;
            this.itemKey = itemKey == null || itemKey.isBlank() ? this.itemName : itemKey;
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
                return;
            } catch (Exception e) {
                System.err.println("[Belfegor] Failed to load shulker memory from "
                        + file.getAbsolutePath() + ": " + e.getMessage());
                quarantineCorruptFile(file);
            }
        }

        File backup = new File(file.getParentFile() == null ? new File(".") : file.getParentFile(),
                BACKUP_FILE_NAME);
        if (backup.exists()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                INSTANCE = mapper.readValue(backup, ShulkerMemory.class);
                System.err.println("[Belfegor] Recovered shulker memory from backup "
                        + backup.getAbsolutePath());
                return;
            } catch (Exception e) {
                System.err.println("[Belfegor] Failed to load backup shulker memory from "
                        + backup.getAbsolutePath() + ": " + e.getMessage());
            }
        }
        INSTANCE = new ShulkerMemory();
    }

    private static void quarantineCorruptFile(File file) {
        if (file == null || !file.exists()) return;
        try {
            File corrupt = new File(file.getParentFile() == null ? new File(".") : file.getParentFile(),
                    FILE_NAME + ".corrupt." + System.currentTimeMillis());
            Files.move(file.toPath(), corrupt.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.err.println("[Belfegor] Moved corrupt shulker memory to "
                    + corrupt.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[Belfegor] Failed to quarantine corrupt shulker memory: "
                    + e.getMessage());
        }
    }

    /**
     * Remember or update the contents of a shulker at a position.
     */
    public void rememberContents(BlockPos pos, Map<String, Integer> contents) {
        String key = posKey(pos);
        ShulkerEntry entry = _shulkers.getOrDefault(key, new ShulkerEntry(pos));
        entry.location = "world";
        applyTotals(entry, contents);
        entry.lastUpdated = System.currentTimeMillis();
        _shulkers.put(key, entry);
        _dirty = true;
    }

    public void rememberContentsDetailed(BlockPos pos, String shulkerItem,
                                         Map<Integer, ShulkerSlotItem> slotContents,
                                         String source) {
        String key = posKey(pos);
        ShulkerEntry entry = _shulkers.getOrDefault(key, new ShulkerEntry(pos));
        entry.location = "world";
        entry.inventorySlot = -1;
        entry.shulkerItem = shulkerItem == null ? entry.shulkerItem : shulkerItem;
        entry.sourceKey = key;
        entry.lastVerifiedSource = source == null ? "open-screen" : source;
        applySlotContents(entry, slotContents);
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
        entry.sourceKey = key;
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
        entry.sourceKey = "inventory:" + inventorySlot;
        entry.lastVerifiedSource = "item-component";
        applyTotals(entry, contents);
        _shulkers.put("inventory:" + inventorySlot, entry);
        _dirty = true;
    }

    public void rememberInventoryContentsDetailed(int inventorySlot, String shulkerItem,
                                                  Map<Integer, ShulkerSlotItem> slotContents) {
        ShulkerEntry entry = new ShulkerEntry();
        entry.location = "inventory";
        entry.inventorySlot = inventorySlot;
        entry.shulkerItem = shulkerItem == null ? "" : shulkerItem;
        entry.sourceKey = "inventory:" + inventorySlot;
        entry.lastVerifiedSource = "item-component";
        applySlotContents(entry, slotContents);
        entry.lastUpdated = System.currentTimeMillis();
        _shulkers.put(entry.sourceKey, entry);
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
        results.sort((a, b) -> {
            int byCount = Integer.compare(b.getItemCount(itemName), a.getItemCount(itemName));
            if (byCount != 0) return byCount;
            return Long.compare(b.lastUpdated, a.lastUpdated);
        });
        return results;
    }

    public List<ShulkerEntry> findInventoryItem(String itemName) {
        return findItem(itemName).stream()
                .filter(entry -> "inventory".equals(entry.location))
                .collect(Collectors.toList());
    }

    public Optional<ShulkerEntry> bestInventoryShulkerFor(String itemName) {
        return findInventoryItem(itemName).stream().findFirst();
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
            File target = new File(dir, FILE_NAME);
            File backup = new File(dir, BACKUP_FILE_NAME);
            File tmp = new File(dir, FILE_NAME + ".tmp");
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp, this);
            if (target.exists()) {
                Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicMoveFailed) {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            _dirty = false;
        } catch (Exception e) {
            System.err.println("[Belfegor] Failed to save shulker memory: " + e.getMessage());
        }
    }

    private static String posKey(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static void applyTotals(ShulkerEntry entry, Map<String, Integer> contents) {
        Map<String, Integer> safe = contents == null ? Collections.emptyMap() : contents;
        entry.contents = safe.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .map(e -> new ShulkerItem(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
        if (entry.slots == null) entry.slots = new ArrayList<>();
        entry.totalItems = safe.values().stream()
                .filter(count -> count != null && count > 0)
                .mapToInt(Integer::intValue)
                .sum();
        entry.slotCount = Math.max(entry.slotCount, 27);
        entry.freeSlots = entry.slots.isEmpty()
                ? Math.max(0, entry.slotCount - safe.size())
                : Math.max(0, entry.slotCount - entry.slots.size());
        entry.fingerprint = fingerprint(entry.slots, entry.contents);
    }

    private static void applySlotContents(ShulkerEntry entry, Map<Integer, ShulkerSlotItem> slotContents) {
        Map<Integer, ShulkerSlotItem> safe = slotContents == null ? Collections.emptyMap() : slotContents;
        entry.slotCount = 27;
        entry.slots = safe.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getValue() != null && e.getValue().count > 0)
                .map(e -> new ShulkerSlotItem(e.getKey(),
                        e.getValue().itemName,
                        e.getValue().itemKey,
                        e.getValue().count))
                .sorted((a, b) -> Integer.compare(a.slot, b.slot))
                .collect(Collectors.toList());
        Map<String, Integer> totals = new HashMap<>();
        for (ShulkerSlotItem slot : entry.slots) {
            totals.merge(slot.itemName, slot.count, Integer::sum);
        }
        entry.contents = totals.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new ShulkerItem(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
        entry.totalItems = entry.slots.stream().mapToInt(slot -> slot.count).sum();
        entry.freeSlots = Math.max(0, entry.slotCount - entry.slots.size());
        entry.fingerprint = fingerprint(entry.slots, entry.contents);
    }

    private static String fingerprint(List<ShulkerSlotItem> slots, List<ShulkerItem> contents) {
        if (slots != null && !slots.isEmpty()) {
            return slots.stream()
                    .sorted((a, b) -> Integer.compare(a.slot, b.slot))
                    .map(slot -> slot.slot + ":" + slot.itemName + "x" + slot.count)
                    .collect(Collectors.joining("|"));
        }
        if (contents == null || contents.isEmpty()) return "empty";
        return contents.stream()
                .sorted((a, b) -> a.itemName.compareTo(b.itemName))
                .map(item -> item.itemName + "x" + item.count)
                .collect(Collectors.joining("|"));
    }
}
