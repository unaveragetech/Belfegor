package adris.altoclef.util;

import adris.altoclef.Debug;
import adris.altoclef.util.helpers.ItemHelper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.InputStreamReader;
import java.io.Reader;
import java.util.*;

/**
 * Loads and provides access to all Minecraft crafting recipes from a bundled JSON file.
 * This gives the bot knowledge of every craftable item in 1.21.4.
 */
public class RecipeRegistry {

    private static RecipeRegistry _instance;

    private final Map<Item, List<RecipeEntry>> _recipesByOutput = new HashMap<>();
    private final Map<Item, List<RecipeEntry>> _recipesByInput = new HashMap<>();
    private boolean _loaded = false;

    public static class RecipeEntry {
        public final Item outputItem;
        public final int outputCount;
        public final CraftingRecipe recipe;
        public final String type; // "shaped" or "shapeless"

        public RecipeEntry(Item outputItem, int outputCount, CraftingRecipe recipe, String type) {
            this.outputItem = outputItem;
            this.outputCount = outputCount;
            this.recipe = recipe;
            this.type = type;
        }
    }

    public static class CraftPlan {
        public final Item targetItem;
        public final int targetCount;
        public final Map<Item, Integer> leafResources;
        public final List<String> steps;
        public final List<String> failures;

        public CraftPlan(Item targetItem, int targetCount,
                         Map<Item, Integer> leafResources,
                         List<String> steps,
                         List<String> failures) {
            this.targetItem = targetItem;
            this.targetCount = targetCount;
            this.leafResources = leafResources;
            this.steps = steps;
            this.failures = failures;
        }

        public boolean isUsable() {
            return failures.isEmpty() && !leafResources.isEmpty();
        }
    }

    private RecipeRegistry() {
    }

    public static synchronized RecipeRegistry getInstance() {
        if (_instance == null) {
            _instance = new RecipeRegistry();
        }
        return _instance;
    }

    /**
     * Load recipes from the bundled JSON resource file.
     * Should be called after Minecraft items are registered (e.g. in AltoClef.onInitializeLoad).
     */
    public void load() {
        if (_loaded) return;

        try (Reader reader = new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("belfegor_recipes.json"))) {
            if (reader == null) {
                Debug.logWarning("[RecipeRegistry] belfegor_recipes.json not found in resources");
                return;
            }

            Gson gson = new Gson();
            JsonArray recipesArray = gson.fromJson(reader, JsonArray.class);

            int loaded = 0;
            for (JsonElement elem : recipesArray) {
                JsonObject recipeObj = elem.getAsJsonObject();
                try {
                    RecipeEntry entry = parseRecipe(recipeObj);
                    if (entry != null) {
                        _recipesByOutput
                                .computeIfAbsent(entry.outputItem, k -> new ArrayList<>())
                                .add(entry);

                        // Index by input items for reverse lookup
                        Set<Item> inputItems = getInputItems(entry);
                        for (Item input : inputItems) {
                            _recipesByInput
                                    .computeIfAbsent(input, k -> new ArrayList<>())
                                    .add(entry);
                        }
                        loaded++;
                    }
                } catch (Exception e) {
                    // Skip malformed recipes silently
                }
            }

            _loaded = true;
            Debug.logMessage("[RecipeRegistry] Loaded " + loaded + " recipes from belfegor_recipes.json");
        } catch (Exception e) {
            Debug.logWarning("[RecipeRegistry] Failed to load recipes: " + e.getMessage());
        }
    }

    private RecipeEntry parseRecipe(JsonObject obj) {
        String outputName = obj.get("output").getAsString();
        int outputCount = obj.has("count") ? obj.get("count").getAsInt() : 1;
        String type = obj.has("type") ? obj.get("type").getAsString() : "shaped";

        Item outputItem = getItemByName(outputName);
        if (outputItem == null) return null;

        CraftingRecipe recipe;
        if ("shaped".equals(type) && obj.has("pattern")) {
            recipe = parseShapedRecipe(obj.getAsJsonArray("pattern"), outputCount);
        } else if ("shapeless".equals(type) && obj.has("ingredients")) {
            recipe = parseShapelessRecipe(obj.getAsJsonArray("ingredients"), outputCount);
        } else {
            return null;
        }

        if (recipe == null) return null;

        return new RecipeEntry(outputItem, outputCount, recipe, type);
    }

    private CraftingRecipe parseShapedRecipe(JsonArray pattern, int outputCount) {
        int height = pattern.size();
        if (height == 0) return null;

        int width = 0;
        for (JsonElement row : pattern) {
            width = Math.max(width, row.getAsJsonArray().size());
        }

        if (width > 3 || height > 3 || width == 0 || height == 0) return null;

        // Create flat slot array (3x3 for table, 2x2 for inventory)
        ItemTarget[] slots = new ItemTarget[9]; // Always create 3x3, will be trimmed if 2x2
        Arrays.fill(slots, ItemTarget.EMPTY);

        for (int y = 0; y < height; y++) {
            JsonArray row = pattern.get(y).getAsJsonArray();
            for (int x = 0; x < row.size(); x++) {
                JsonElement cell = row.get(x);
                if (!cell.isJsonNull()) {
                    String ingredientName = cell.getAsString();
                    Item ingredient = getItemByName(ingredientName);
                    if (ingredient != null) {
                        slots[y * 3 + x] = new ItemTarget(expandSimilarIngredient(ingredient));
                    }
                }
            }
        }

        // Determine if it fits in 2x2
        boolean fits2x2 = (width <= 2 && height <= 2);
        if (fits2x2) {
            // Remap to 2x2 slots
            ItemTarget[] slots2x2 = new ItemTarget[4];
            slots2x2[0] = slots[0]; // top-left
            slots2x2[1] = slots[1]; // top-right
            slots2x2[2] = slots[3]; // mid-left
            slots2x2[3] = slots[4]; // mid-right
            return CraftingRecipe.newShapedRecipe(slots2x2, outputCount);
        }

        return CraftingRecipe.newShapedRecipe(slots, outputCount);
    }

    private CraftingRecipe parseShapelessRecipe(JsonArray ingredients, int outputCount) {
        if (ingredients.size() > 9) return null;

        ItemTarget[] slots = new ItemTarget[9];
        Arrays.fill(slots, ItemTarget.EMPTY);

        for (int i = 0; i < ingredients.size(); i++) {
            JsonElement elem = ingredients.get(i);
            if (!elem.isJsonNull()) {
                String ingredientName = elem.getAsString();
                Item ingredient = getItemByName(ingredientName);
                if (ingredient != null) {
                    slots[i] = new ItemTarget(expandSimilarIngredient(ingredient));
                }
            }
        }

        return CraftingRecipe.newShapedRecipe(slots, outputCount);
    }

    /**
     * Get a recipe for crafting the given item.
     * Returns the first matching recipe, or null if none found.
     */
    public RecipeEntry getRecipe(Item item) {
        if (!_loaded) load();
        List<RecipeEntry> recipes = _recipesByOutput.get(item);
        if (recipes == null || recipes.isEmpty()) return null;
        return recipes.get(0);
    }

    /**
     * Get all recipes that produce the given item.
     */
    public List<RecipeEntry> getRecipes(Item item) {
        if (!_loaded) load();
        return _recipesByOutput.getOrDefault(item, Collections.emptyList());
    }

    /**
     * Get all recipes that use the given item as an ingredient.
     */
    public List<RecipeEntry> getRecipesUsing(Item item) {
        if (!_loaded) load();
        return _recipesByInput.getOrDefault(item, Collections.emptyList());
    }

    /**
     * Check if an item is craftable (has at least one recipe).
     */
    public boolean isCraftable(Item item) {
        if (!_loaded) load();
        return _recipesByOutput.containsKey(item);
    }

    /**
     * Get all items that can be crafted.
     */
    public Set<Item> getCraftableItems() {
        if (!_loaded) load();
        return _recipesByOutput.keySet();
    }

    public List<Item> getSortedCraftableItems() {
        if (!_loaded) load();
        List<Item> result = new ArrayList<>(_recipesByOutput.keySet());
        result.sort(Comparator.comparing(item -> Registries.ITEM.getId(item).toString()));
        return result;
    }

    public CraftPlan buildLeafResourcePlan(Item targetItem, int targetCount) {
        if (!_loaded) load();
        Map<Item, Integer> resources = new LinkedHashMap<>();
        List<String> steps = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        expandToLeafResources(targetItem, Math.max(1, targetCount), resources, steps, failures,
                new HashSet<>(), 0);
        return new CraftPlan(targetItem, Math.max(1, targetCount), resources, steps, failures);
    }

    /**
     * Get the total number of loaded recipes.
     */
    public int getRecipeCount() {
        if (!_loaded) load();
        return _recipesByOutput.values().stream().mapToInt(List::size).sum();
    }

    private Set<Item> getInputItems(RecipeEntry entry) {
        Set<Item> items = new HashSet<>();
        ItemTarget[] slots = entry.recipe.getSlots();
        for (ItemTarget slot : slots) {
            if (slot != null && !slot.isEmpty()) {
                for (Item match : slot.getMatches()) {
                    items.add(match);
                }
            }
        }
        return items;
    }

    private void expandToLeafResources(Item item, int count,
                                       Map<Item, Integer> resources,
                                       List<String> steps,
                                       List<String> failures,
                                       Set<Item> stack,
                                       int depth) {
        if (item == null || item == net.minecraft.item.Items.AIR || count <= 0) return;
        if (depth > 32) {
            failures.add("dependency depth limit hit at " + itemId(item));
            addLeaf(resources, item, count);
            return;
        }
        if (stack.contains(item)) {
            failures.add("recipe cycle detected at " + itemId(item));
            addLeaf(resources, item, count);
            return;
        }

        RecipeEntry recipe = getRecipe(item);
        if (recipe == null) {
            addLeaf(resources, item, count);
            steps.add("leaf " + count + "x " + itemId(item));
            return;
        }

        int crafts = (int) Math.ceil(count / (double) Math.max(1, recipe.outputCount));
        steps.add("craft " + count + "x " + itemId(item)
                + " using " + crafts + " recipe pass(es)");
        stack.add(item);
        for (ItemTarget slot : recipe.recipe.getSlots()) {
            if (slot == null || slot.isEmpty()) continue;
            Item ingredient = chooseRepresentativeIngredient(slot);
            if (ingredient == null || ingredient == net.minecraft.item.Items.AIR) {
                failures.add("no representative ingredient for " + itemId(item)
                        + " slot " + slot);
                continue;
            }
            expandToLeafResources(ingredient, crafts, resources, steps, failures, stack, depth + 1);
        }
        stack.remove(item);
    }

    private Item chooseRepresentativeIngredient(ItemTarget target) {
        Item[] matches = target.getMatches();
        if (matches == null || matches.length == 0) return null;
        for (Item item : matches) {
            if (item != null && item != net.minecraft.item.Items.AIR) {
                return item;
            }
        }
        return null;
    }

    private void addLeaf(Map<Item, Integer> resources, Item item, int count) {
        resources.merge(item, count, Integer::sum);
    }

    public static String itemId(Item item) {
        return Registries.ITEM.getId(item).toString();
    }

    /**
     * Resolve a Minecraft registry name (e.g. "minecraft:iron_ingot") to an Item.
     */
    public static Item getItemByName(String name) {
        Identifier id = Identifier.tryParse(name);
        if (id == null) return null;
        return Registries.ITEM.get(id);
    }

    private Item[] expandSimilarIngredient(Item ingredient) {
        if (contains(ItemHelper.WOOD_SLAB, ingredient)) return ItemHelper.WOOD_SLAB;
        if (contains(ItemHelper.PLANKS, ingredient)) return ItemHelper.PLANKS;
        if (contains(ItemHelper.LOG, ingredient)) return ItemHelper.LOG;
        if (contains(ItemHelper.WOOL, ingredient)) return ItemHelper.WOOL;
        if (contains(ItemHelper.CARPET, ingredient)) return ItemHelper.CARPET;
        return new Item[]{ingredient};
    }

    private boolean contains(Item[] group, Item item) {
        for (Item candidate : group) {
            if (candidate == item) return true;
        }
        return false;
    }

}
