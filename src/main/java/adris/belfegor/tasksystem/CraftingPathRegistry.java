package adris.belfegor.tasksystem;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import adris.belfegor.Debug;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of all known crafting paths for every item.
 * Persists to belfegor/belfegor_crafting_paths.json.
 * Picks the easiest path (lowest score) for any given item.
 * Grows over time by recording success/failure of each path.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class CraftingPathRegistry {

    private static CraftingPathRegistry INSTANCE = new CraftingPathRegistry();
    private static final String FOLDER = "belfegor";
    private static final String FILE_NAME = "belfegor_crafting_paths.json";

    private final Map<String, List<CraftingPath>> _paths = new ConcurrentHashMap<>();
    private boolean _dirty = false;

    public static CraftingPathRegistry getInstance() { return INSTANCE; }

    public static void init(File gameDir) {
        File file = new File(gameDir, FILE_NAME);
        if (file.exists()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                INSTANCE = mapper.readValue(file, CraftingPathRegistry.class);
                Debug.logInternal("Loaded " + INSTANCE._paths.size() + " crafting path categories from disk.");
            } catch (Exception e) {
                INSTANCE = new CraftingPathRegistry();
                Debug.logWarning("Failed to load crafting paths, using defaults: " + e.getMessage());
            }
        }
        INSTANCE.registerDefaults();
    }

    /**
     * Register default crafting paths for all common items.
     * These are the "easiest route" rules the bot starts with.
     * The bot learns and adjusts scores over time.
     */
    private void registerDefaults() {
        // === BASIC MATERIALS ===

        // Planks: from logs (easiest, 1 step)
        registerDefault("oak_planks", "Oak Planks from Oak Log", 1.0, List.of("oak_log"));
        registerDefault("spruce_planks", "Spruce Planks from Spruce Log", 1.0, List.of("spruce_log"));
        registerDefault("birch_planks", "Birch Planks from Birch Log", 1.0, List.of("birch_log"));
        registerDefault("jungle_planks", "Jungle Planks from Jungle Log", 1.0, List.of("jungle_log"));
        registerDefault("acacia_planks", "Acacia Planks from Acacia Log", 1.0, List.of("acacia_log"));
        registerDefault("dark_oak_planks", "Dark Oak Planks from Dark Oak Log", 1.0, List.of("dark_oak_log"));
        registerDefault("cherry_planks", "Cherry Planks from Cherry Log", 1.0, List.of("cherry_log"));
        registerDefault("mangrove_planks", "Mangrove Planks from Mangrove Log", 1.0, List.of("mangrove_log"));
        registerDefault("bamboo_planks", "Bamboo Planks from Bamboo", 1.0, List.of("bamboo"));
        registerDefault("crimson_planks", "Crimson Planks from Crimson Stem", 1.5, List.of("crimson_stem"));
        registerDefault("warped_planks", "Warped Planks from Warped Stem", 1.5, List.of("warped_stem"));

        // Sticks: from planks (easiest, 2 planks = 4 sticks)
        registerDefault("stick", "Sticks from Planks", 2.0, List.of("planks"));
        registerDefault("stick", "Sticks from Bamboo", 2.5, List.of("bamboo"));

        // Crafting table: from planks
        registerDefault("crafting_table", "Crafting Table from Planks", 1.5, List.of("planks"));

        // === TOOLS (ordered by material difficulty) ===

        // Wooden tools: planks + sticks
        registerDefault("wooden_pickaxe", "Wooden Pickaxe", 3.0, List.of("planks", "stick"));
        registerDefault("wooden_axe", "Wooden Axe", 3.0, List.of("planks", "stick"));
        registerDefault("wooden_shovel", "Wooden Shovel", 2.5, List.of("planks", "stick"));
        registerDefault("wooden_sword", "Wooden Sword", 2.5, List.of("planks", "stick"));
        registerDefault("wooden_hoe", "Wooden Hoe", 3.0, List.of("planks", "stick"));

        // Stone tools: cobblestone + sticks
        registerDefault("stone_pickaxe", "Stone Pickaxe", 3.5, List.of("cobblestone", "stick"));
        registerDefault("stone_axe", "Stone Axe", 3.5, List.of("cobblestone", "stick"));
        registerDefault("stone_shovel", "Stone Shovel", 3.0, List.of("cobblestone", "stick"));
        registerDefault("stone_sword", "Stone Sword", 3.0, List.of("cobblestone", "stick"));
        registerDefault("stone_hoe", "Stone Hoe", 3.5, List.of("cobblestone", "stick"));

        // Iron tools: iron_ingot + sticks
        registerDefault("iron_pickaxe", "Iron Pickaxe", 5.0, List.of("iron_ingot", "stick"));
        registerDefault("iron_axe", "Iron Axe", 5.0, List.of("iron_ingot", "stick"));
        registerDefault("iron_shovel", "Iron Shovel", 4.5, List.of("iron_ingot", "stick"));
        registerDefault("iron_sword", "Iron Sword", 4.5, List.of("iron_ingot", "stick"));
        registerDefault("iron_hoe", "Iron Hoe", 5.0, List.of("iron_ingot", "stick"));

        // Diamond tools: diamond + sticks
        registerDefault("diamond_pickaxe", "Diamond Pickaxe", 7.0, List.of("diamond", "stick"));
        registerDefault("diamond_axe", "Diamond Axe", 7.0, List.of("diamond", "stick"));
        registerDefault("diamond_shovel", "Diamond Shovel", 6.5, List.of("diamond", "stick"));
        registerDefault("diamond_sword", "Diamond Sword", 6.5, List.of("diamond", "stick"));
        registerDefault("diamond_hoe", "Diamond Hoe", 7.0, List.of("diamond", "stick"));

        // === ARMOR ===
        registerDefault("iron_helmet", "Iron Helmet", 5.0, List.of("iron_ingot"));
        registerDefault("iron_chestplate", "Iron Chestplate", 5.5, List.of("iron_ingot"));
        registerDefault("iron_leggings", "Iron Leggings", 5.0, List.of("iron_ingot"));
        registerDefault("iron_boots", "Iron Boots", 4.5, List.of("iron_ingot"));
        registerDefault("diamond_helmet", "Diamond Helmet", 7.0, List.of("diamond"));
        registerDefault("diamond_chestplate", "Diamond Chestplate", 7.5, List.of("diamond"));
        registerDefault("diamond_leggings", "Diamond Leggings", 7.0, List.of("diamond"));
        registerDefault("diamond_boots", "Diamond Boots", 6.5, List.of("diamond"));

        // === FOOD ===

        // Cooked food: always hunt animals → cook meat (easiest reliable food source)
        registerDefault("raw_beef", "Hunt Cows for Beef", 2.0, List.of());
        registerDefault("cooked_beef", "Cook Beef in Furnace", 3.5, List.of("raw_beef", "coal"));
        registerDefault("raw_porkchop", "Hunt Pigs for Pork", 2.0, List.of());
        registerDefault("cooked_porkchop", "Cook Pork in Furnace", 3.5, List.of("raw_porkchop", "coal"));
        registerDefault("raw_mutton", "Hunt Sheep for Mutton", 2.5, List.of());
        registerDefault("cooked_mutton", "Cook Mutton in Furnace", 4.0, List.of("raw_mutton", "coal"));
        registerDefault("raw_chicken", "Hunt Chickens for Chicken", 2.0, List.of());
        registerDefault("cooked_chicken", "Cook Chicken in Furnace", 3.5, List.of("raw_chicken", "coal"));
        registerDefault("raw_rabbit", "Hunt Rabbits for Rabbit", 3.0, List.of());
        registerDefault("cooked_rabbit", "Cook Rabbit in Furnace", 4.0, List.of("raw_rabbit", "coal"));

        // Bread: from wheat (requires farming, harder than hunting)
        registerDefault("bread", "Bread from Wheat", 5.0, List.of("wheat"));

        // Apples: from leaves (0.5% drop = very hard, should be LAST resort)
        registerDefault("apple", "Apple from Oak Leaves", 8.0, List.of("oak_leaves"));

        // Golden apple: apple + gold (very expensive)
        registerDefault("golden_apple", "Golden Apple", 9.0, List.of("apple", "gold_ingot"));

        // === SMELTING ===
        registerDefault("iron_ingot", "Smelt Iron Ore", 4.0, List.of("raw_iron"));
        registerDefault("gold_ingot", "Smelt Gold Ore", 5.0, List.of("raw_gold"));
        registerDefault("stone", "Smelt Cobblestone", 3.0, List.of("cobblestone"));
        registerDefault("glass", "Smelt Sand", 2.5, List.of("sand"));
        registerDefault("charcoal", "Smelt Logs for Charcoal", 2.0, List.of("log"));
        registerDefault("cooked_cod", "Cook Cod in Furnace", 3.5, List.of("cod"));
        registerDefault("cooked_salmon", "Cook Salmon in Furnace", 3.5, List.of("salmon"));

        // === BUILDING BLOCKS ===
        registerDefault("torch", "Torches from Coal + Sticks", 2.5, List.of("coal", "stick"));
        registerDefault("chest", "Chest from Planks", 2.0, List.of("planks"));
        registerDefault("furnace", "Furnace from Cobblestone", 2.0, List.of("cobblestone"));
        registerDefault("ladder", "Ladder from Sticks", 3.0, List.of("stick"));
        registerDefault("fence", "Fence from Planks + Sticks", 3.0, List.of("planks", "stick"));
        registerDefault("boat", "Boat from Planks", 2.5, List.of("planks"));

        // === BOW & ARROW ===
        registerDefault("bow", "Bow from String + Sticks", 4.0, List.of("string", "stick"));
        registerDefault("arrow", "Arrow from Flint + Stick + Feather", 4.5, List.of("flint", "stick", "feather"));

        // === BUCKETS ===
        registerDefault("bucket", "Bucket from Iron Ingots", 4.5, List.of("iron_ingot"));

        // === REDSTONE ===
        registerDefault("redstone_torch", "Redstone Torch", 3.5, List.of("redstone", "stick"));
        registerDefault("lever", "Lever from Cobble + Stick", 2.5, List.of("cobblestone", "stick"));
        registerDefault("dispenser", "Dispenser", 5.5, List.of("cobblestone", "bow", "redstone"));
        registerDefault("piston", "Piston", 5.0, List.of("planks", "cobblestone", "iron_ingot", "redstone"));

        // === NETHER ===
        registerDefault("blaze_powder", "Blaze Powder from Blaze Rod", 6.0, List.of("blaze_rod"));
        registerDefault("ender_pearl", "Ender Pearl from Enderman", 6.0, List.of());
        registerDefault("eyes_of_ender", "Eye of Ender", 7.5, List.of("blaze_powder", "ender_pearl"));
        registerDefault("brewing_stand", "Brewing Stand", 5.5, List.of("blaze_rod", "cobblestone"));
        registerDefault("cauldron", "Cauldron from Iron Ingots", 4.5, List.of("iron_ingot"));

        // === MISC CRAFTABLE ===
        registerDefault("shield", "Shield from Planks + Iron", 4.0, List.of("planks", "iron_ingot"));
        registerDefault("flint_and_steel", "Flint and Steel", 4.0, List.of("iron_ingot", "flint"));
        registerDefault("compass", "Compass from Iron + Redstone", 5.0, List.of("iron_ingot", "redstone"));
        registerDefault("clock", "Clock from Gold + Redstone", 6.0, List.of("gold_ingot", "redstone"));
        registerDefault("shears", "Shears from Iron Ingots", 4.0, List.of("iron_ingot"));
        registerDefault("map", "Empty Map", 3.5, List.of("paper"));
        registerDefault("book", "Book from Paper + Leather", 4.5, List.of("paper", "leather"));
        registerDefault("enchanting_table", "Enchanting Table", 8.0, List.of("diamond", "obsidian", "book"));
        registerDefault("anvil", "Anvil from Iron Blocks", 6.5, List.of("iron_ingot"));
        registerDefault("name_tag", "Name Tag (find in chest)", 7.0, List.of());

        // === PAPER & SUGAR ===
        registerDefault("paper", "Paper from Sugar Cane", 2.5, List.of("sugar_cane"));
        registerDefault("sugar", "Sugar from Sugar Cane", 2.0, List.of("sugar_cane"));
        registerDefault("bookshelf", "Bookshelf", 5.5, List.of("planks", "book"));

        // === WOOL & BED ===
        registerDefault("white_wool", "White Wool from Sheep", 2.0, List.of());
        registerDefault("bed", "Bed from Planks + Wool", 3.5, List.of("planks", "white_wool"));

        // === NETHERITE ===
        registerDefault("netherite_scrap", "Mine Ancient Debris + Smelt", 9.0, List.of("ancient_debris"));
        registerDefault("netherite_ingot", "Netherite Ingot", 9.5, List.of("netherite_scrap", "gold_ingot"));
        registerDefault("netherite_pickaxe", "Netherite Pickaxe", 10.0, List.of("netherite_ingot", "stick"));

        Debug.logInternal("CraftingPathRegistry: registered " + _paths.size() + " item categories with default paths.");
    }

    /**
     * Register a default path only if no path exists yet for this item+name combo.
     * Won't overwrite paths loaded from disk (learned paths).
     */
    private void registerDefault(String itemName, String pathName, double difficulty, List<String> materials) {
        List<CraftingPath> paths = _paths.computeIfAbsent(itemName, k -> new ArrayList<>());
        // Don't overwrite if this exact path name already exists (loaded from disk)
        for (CraftingPath existing : paths) {
            if (existing.getName().equals(pathName)) return;
        }
        CraftingPath path = new CraftingPath(pathName, pathName, difficulty);
        path.getMaterials().addAll(materials);
        paths.add(path);
        _dirty = true;
    }

    /**
     * Get the easiest crafting path for an item.
     * Returns null if no paths are registered.
     */
    public CraftingPath getBestPath(String itemName) {
        List<CraftingPath> paths = _paths.get(itemName);
        if (paths == null || paths.isEmpty()) return null;
        return paths.stream()
                .min(Comparator.comparingDouble(CraftingPath::getScore))
                .orElse(null);
    }

    /**
     * Get all registered paths for an item.
     */
    public List<CraftingPath> getAllPaths(String itemName) {
        return _paths.getOrDefault(itemName, Collections.emptyList());
    }

    /**
     * Get all registered item names.
     */
    public Set<String> getRegisteredItems() {
        return _paths.keySet();
    }

    /**
     * Record a successful use of a path by name.
     */
    public void recordSuccess(String itemName, String pathName) {
        CraftingPath path = findPath(itemName, pathName);
        if (path != null) {
            path.recordSuccess();
            _dirty = true;
        }
    }

    /**
     * Record a failure of a path by name.
     */
    public void recordFailure(String itemName, String pathName) {
        CraftingPath path = findPath(itemName, pathName);
        if (path != null) {
            path.recordFailure();
            _dirty = true;
        }
    }

    /**
     * Add a new learned path at runtime (from observation).
     * If a path with the same name exists, update its stats instead.
     */
    public void addOrLearnPath(String itemName, CraftingPath path) {
        List<CraftingPath> paths = _paths.computeIfAbsent(itemName, k -> new ArrayList<>());
        for (CraftingPath existing : paths) {
            if (existing.getName().equals(path.getName())) {
                // Merge stats
                existing.recordSuccess();
                _dirty = true;
                return;
            }
        }
        paths.add(path);
        _dirty = true;
    }

    private CraftingPath findPath(String itemName, String pathName) {
        List<CraftingPath> paths = _paths.get(itemName);
        if (paths == null) return null;
        for (CraftingPath path : paths) {
            if (path.getName().equals(pathName)) return path;
        }
        return null;
    }

    /**
     * Check if a path exists for this item.
     */
    public boolean hasPaths(String itemName) {
        return _paths.containsKey(itemName) && !_paths.get(itemName).isEmpty();
    }

    /**
     * Get the difficulty of the easiest path for an item.
     * Returns -1 if no paths exist.
     */
    public double getEasiestDifficulty(String itemName) {
        CraftingPath best = getBestPath(itemName);
        return best != null ? best.getScore() : -1;
    }

    public void save() {
        if (!_dirty) return;
        try {
            File dir = new File(FOLDER);
            dir.mkdirs();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(dir, FILE_NAME), this);
            _dirty = false;
        } catch (Exception e) {
            Debug.logWarning("Failed to save crafting paths: " + e.getMessage());
        }
    }

    public boolean isDirty() { return _dirty; }
}
