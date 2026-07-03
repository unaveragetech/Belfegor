package adris.belfegor.util;

import adris.belfegor.TaskCatalogue;
import adris.belfegor.Debug;
import adris.belfegor.util.helpers.ItemHelper;
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
     * Should be called after Minecraft items are registered (e.g. in Belfegor.onInitializeLoad).
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
                    if (entry != null && isValidCraftingRecipe(entry.recipe)) {
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
        CraftingRecipe correctedColorRecipe = getCorrectedColorRecipe(outputItem, outputCount);
        if (correctedColorRecipe != null) {
            recipe = correctedColorRecipe;
            type = "shapeless";
        } else if ("shaped".equals(type) && obj.has("pattern")) {
            recipe = parseShapedRecipe(obj.getAsJsonArray("pattern"), outputCount, outputItem);
        } else if ("shapeless".equals(type) && obj.has("ingredients")) {
            recipe = parseShapelessRecipe(obj.getAsJsonArray("ingredients"), outputCount, outputItem);
        } else {
            return null;
        }

        if (recipe == null) return null;

        return new RecipeEntry(outputItem, outputCount, recipe, type);
    }

    private CraftingRecipe getCorrectedColorRecipe(Item outputItem, int outputCount) {
        CraftingRecipe darkPrismarine = getCorrectedDarkPrismarineRecipe(outputItem, outputCount);
        if (darkPrismarine != null) return darkPrismarine;
        CraftingRecipe dyedWool = getCorrectedDyedWoolRecipe(outputItem, outputCount);
        if (dyedWool != null) return dyedWool;
        CraftingRecipe dyedCandle = getCorrectedDyedCandleRecipe(outputItem, outputCount);
        if (dyedCandle != null) return dyedCandle;
        CraftingRecipe concretePowder = getCorrectedConcretePowderRecipe(outputItem, outputCount);
        if (concretePowder != null) return concretePowder;
        CraftingRecipe stainedGlass = getCorrectedDyedBaseRecipe(outputItem, outputCount,
                "_stained_glass", net.minecraft.item.Items.GLASS, 8);
        if (stainedGlass != null) return stainedGlass;
        CraftingRecipe terracotta = getCorrectedDyedBaseRecipe(outputItem, outputCount,
                "_terracotta", net.minecraft.item.Items.TERRACOTTA, 8);
        if (terracotta != null) return terracotta;
        CraftingRecipe shulkerBox = getCorrectedDyedBaseRecipe(outputItem, outputCount,
                "_shulker_box", net.minecraft.item.Items.SHULKER_BOX, 1);
        if (shulkerBox != null) return shulkerBox;
        return null;
    }

    private CraftingRecipe getCorrectedDarkPrismarineRecipe(Item outputItem, int outputCount) {
        String outputId = itemId(outputItem);
        if (!"minecraft:dark_prismarine".equals(outputId)) {
            return null;
        }
        ItemTarget[] slots = new ItemTarget[9];
        Arrays.fill(slots, new ItemTarget(net.minecraft.item.Items.PRISMARINE_SHARD, 1));
        slots[4] = new ItemTarget(net.minecraft.item.Items.BLACK_DYE, 1);
        return CraftingRecipe.newShapedRecipe("corrected_dark_prismarine", slots, outputCount);
    }

    private CraftingRecipe getCorrectedDyedWoolRecipe(Item outputItem, int outputCount) {
        String outputId = itemId(outputItem);
        if (outputId == null || !outputId.startsWith("minecraft:") || !outputId.endsWith("_wool")) {
            return null;
        }
        String path = outputId.substring("minecraft:".length());
        if ("white_wool".equals(path)) {
            return null;
        }
        String color = path.substring(0, path.length() - "_wool".length());
        Item dye = getItemByName("minecraft:" + color + "_dye");
        if (dye == null) {
            return null;
        }
        ItemTarget[] slots = new ItemTarget[]{
                new ItemTarget(net.minecraft.item.Items.WHITE_WOOL, 1),
                new ItemTarget(dye, 1),
                ItemTarget.EMPTY,
                ItemTarget.EMPTY
        };
        return CraftingRecipe.newShapedRecipe("corrected_" + color + "_wool", slots, outputCount);
    }

    private CraftingRecipe getCorrectedDyedCandleRecipe(Item outputItem, int outputCount) {
        String outputId = itemId(outputItem);
        if (outputId == null || !outputId.startsWith("minecraft:") || !outputId.endsWith("_candle")) {
            return null;
        }
        String path = outputId.substring("minecraft:".length());
        if ("candle".equals(path)) {
            return null;
        }
        String color = path.substring(0, path.length() - "_candle".length());
        Item dye = getItemByName("minecraft:" + color + "_dye");
        if (dye == null) {
            return null;
        }
        ItemTarget[] slots = new ItemTarget[]{
                new ItemTarget(net.minecraft.item.Items.CANDLE, 1),
                new ItemTarget(dye, 1),
                ItemTarget.EMPTY,
                ItemTarget.EMPTY
        };
        return CraftingRecipe.newShapedRecipe("corrected_" + color + "_candle", slots, outputCount);
    }

    private CraftingRecipe getCorrectedConcretePowderRecipe(Item outputItem, int outputCount) {
        String outputId = itemId(outputItem);
        if (outputId == null || !outputId.startsWith("minecraft:") || !outputId.endsWith("_concrete_powder")) {
            return null;
        }
        String path = outputId.substring("minecraft:".length());
        String color = path.substring(0, path.length() - "_concrete_powder".length());
        Item dye = getItemByName("minecraft:" + color + "_dye");
        if (dye == null) {
            return null;
        }
        ItemTarget[] slots = new ItemTarget[]{
                new ItemTarget(net.minecraft.item.Items.SAND, 1),
                new ItemTarget(net.minecraft.item.Items.SAND, 1),
                new ItemTarget(net.minecraft.item.Items.SAND, 1),
                new ItemTarget(net.minecraft.item.Items.SAND, 1),
                new ItemTarget(net.minecraft.item.Items.GRAVEL, 1),
                new ItemTarget(net.minecraft.item.Items.GRAVEL, 1),
                new ItemTarget(net.minecraft.item.Items.GRAVEL, 1),
                new ItemTarget(net.minecraft.item.Items.GRAVEL, 1),
                new ItemTarget(dye, 1)
        };
        return CraftingRecipe.newShapedRecipe("corrected_" + color + "_concrete_powder", slots, outputCount);
    }

    private CraftingRecipe getCorrectedDyedBaseRecipe(Item outputItem, int outputCount, String suffix, Item base, int baseCount) {
        String outputId = itemId(outputItem);
        if (outputId == null || !outputId.startsWith("minecraft:") || !outputId.endsWith(suffix)) {
            return null;
        }
        String path = outputId.substring("minecraft:".length());
        String color = path.substring(0, path.length() - suffix.length());
        if (color.isBlank()) {
            return null;
        }
        Item dye = getItemByName("minecraft:" + color + "_dye");
        if (dye == null) {
            return null;
        }
        if (baseCount == 8) {
            ItemTarget[] slots = new ItemTarget[9];
            Arrays.fill(slots, new ItemTarget(base, 1));
            slots[4] = new ItemTarget(dye, 1);
            return CraftingRecipe.newShapedRecipe("corrected_" + color + suffix, slots, outputCount);
        }
        int slotCount = baseCount + 1 <= 4 ? 4 : 9;
        ItemTarget[] slots = new ItemTarget[slotCount];
        Arrays.fill(slots, ItemTarget.EMPTY);
        for (int i = 0; i < Math.min(baseCount, slotCount - 1); i++) {
            slots[i] = new ItemTarget(base, 1);
        }
        slots[Math.min(baseCount, slotCount - 1)] = new ItemTarget(dye, 1);
        return CraftingRecipe.newShapedRecipe("corrected_" + color + suffix, slots, outputCount);
    }

    private CraftingRecipe parseShapedRecipe(JsonArray pattern, int outputCount, Item outputItem) {
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
                    ItemTarget ingredient = getIngredientTargetByName(cell.getAsString(), outputItem);
                    if (ingredient == null) return null;
                    slots[y * 3 + x] = ingredient;
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

    private CraftingRecipe parseShapelessRecipe(JsonArray ingredients, int outputCount, Item outputItem) {
        if (ingredients.size() > 9) return null;

        ItemTarget[] slots = new ItemTarget[9];
        Arrays.fill(slots, ItemTarget.EMPTY);

        for (int i = 0; i < ingredients.size(); i++) {
            JsonElement elem = ingredients.get(i);
            if (!elem.isJsonNull()) {
                ItemTarget ingredient = getIngredientTargetByName(elem.getAsString(), outputItem);
                if (ingredient == null) return null;
                slots[i] = ingredient;
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
        List<RecipeEntry> recipes = _recipesByOutput.get(item);
        if (recipes == null || recipes.isEmpty()) return false;
        return recipes.stream().anyMatch(entry -> isValidCraftingRecipe(entry.recipe));
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
        if (depth > 0 && TaskCatalogue.getItemTask(item, Math.max(1, count)) != null) {
            addLeaf(resources, item, count);
            steps.add("supplied ingredient " + count + "x " + itemId(item));
            return;
        }
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
        String normalized = name == null ? "" : name.trim();
        if (!normalized.contains(":")) {
            normalized = "minecraft:" + normalized;
        }
        Identifier id = Identifier.tryParse(normalized);
        if (id == null) return null;
        Item item = Registries.ITEM.get(id);
        if (item == net.minecraft.item.Items.AIR && !"minecraft:air".equals(normalized)) {
            return null;
        }
        return item;
    }

    private Item[] expandSimilarIngredient(Item ingredient) {
        if (contains(ItemHelper.WOOD_SLAB, ingredient)) return ItemHelper.WOOD_SLAB;
        if (contains(ItemHelper.PLANKS, ingredient)) return ItemHelper.PLANKS;
        if (contains(ItemHelper.LOG, ingredient)) return ItemHelper.LOG;
        if (contains(ItemHelper.WOOL, ingredient)) return ItemHelper.WOOL;
        if (contains(ItemHelper.CARPET, ingredient)) return ItemHelper.CARPET;
        return new Item[]{ingredient};
    }

    private ItemTarget getIngredientTargetByName(String name, Item outputItem) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty()) return null;
        ItemTarget colorSpecific = getColorSpecificIngredient(normalized, outputItem);
        if (colorSpecific != null) return colorSpecific;
        ItemTarget woodSpecific = getWoodSpecificIngredient(normalized, outputItem);
        if (woodSpecific != null) return woodSpecific;
        return switch (normalized) {
            case "planks", "#minecraft:planks", "minecraft:planks" -> new ItemTarget(ItemHelper.PLANKS);
            case "log", "logs", "#minecraft:logs", "minecraft:logs" -> new ItemTarget(ItemHelper.LOG);
            case "stripped_logs", "#minecraft:stripped_logs", "minecraft:stripped_logs" -> new ItemTarget(ItemHelper.STRIPPED_LOGS);
            case "wood_slab", "wooden_slab", "slab", "#minecraft:wooden_slabs", "minecraft:wooden_slabs" -> new ItemTarget(ItemHelper.WOOD_SLAB);
            case "wool", "#minecraft:wool", "minecraft:wool" -> new ItemTarget(ItemHelper.WOOL);
            case "carpet", "#minecraft:wool_carpets", "minecraft:wool_carpets" -> new ItemTarget(ItemHelper.CARPET);
            default -> {
                Item ingredient = getItemByName(normalized);
                if (ingredient == null) {
                    yield null;
                }
                ItemTarget familySpecific = getWoodFamilySpecificIngredient(ingredient, outputItem);
                yield familySpecific != null ? familySpecific : new ItemTarget(expandSimilarIngredient(ingredient));
            }
        };
    }

    private ItemTarget getColorSpecificIngredient(String normalized, Item outputItem) {
        if (outputItem == null) return null;
        boolean woolIngredient = switch (normalized) {
            case "wool", "#minecraft:wool", "minecraft:wool" -> true;
            default -> false;
        };
        if (!woolIngredient) return null;
        String outputId = itemId(outputItem);
        if (outputId == null || !outputId.startsWith("minecraft:")) return null;
        String path = outputId.substring("minecraft:".length());
        String color = null;
        for (String suffix : new String[]{"_bed", "_carpet", "_banner"}) {
            if (path.endsWith(suffix) && path.length() > suffix.length()) {
                color = path.substring(0, path.length() - suffix.length());
                break;
            }
        }
        if (color == null || color.isBlank()) return null;
        Item wool = getItemByName("minecraft:" + color + "_wool");
        return wool == null ? null : new ItemTarget(wool, 1);
    }

    private ItemTarget getWoodSpecificIngredient(String normalized, Item outputItem) {
        if (outputItem == null) return null;
        ItemHelper.WoodItems wood = getWoodFamilyForOutput(outputItem);
        if (wood == null) return null;
        return switch (normalized) {
            case "planks", "#minecraft:planks", "minecraft:planks" ->
                    getFamilyPlanks(wood) == null ? null : new ItemTarget(getFamilyPlanks(wood), 1);
            case "log", "logs", "#minecraft:logs", "minecraft:logs" ->
                    wood.log == null ? null : new ItemTarget(wood.log, 1);
            case "stripped_logs", "#minecraft:stripped_logs", "minecraft:stripped_logs" ->
                    wood.strippedLog == null ? null : new ItemTarget(wood.strippedLog, 1);
            case "wood_slab", "wooden_slab", "slab", "#minecraft:wooden_slabs", "minecraft:wooden_slabs" ->
                    wood.slab == null ? null : new ItemTarget(wood.slab, 1);
            default -> null;
        };
    }

    private ItemTarget getWoodFamilySpecificIngredient(Item ingredient, Item outputItem) {
        ItemHelper.WoodItems outputWood = getWoodFamilyForOutput(outputItem);
        if (outputWood == null || ingredient == null) return null;
        for (ItemHelper.WoodItems candidate : ItemHelper.getWoodItems()) {
            if (candidate == null) continue;
            Item outputPlanks = getFamilyPlanks(outputWood);
            if (contains(ItemHelper.PLANKS, ingredient) && outputPlanks != null) return new ItemTarget(outputPlanks, 1);
            if (candidate.planks == ingredient && outputPlanks != null) return new ItemTarget(outputPlanks, 1);
            if (candidate.log == ingredient && outputWood.log != null) return new ItemTarget(outputWood.log, 1);
            if (candidate.strippedLog == ingredient && outputWood.strippedLog != null) return new ItemTarget(outputWood.strippedLog, 1);
            if (candidate.strippedWood == ingredient && outputWood.strippedWood != null) return new ItemTarget(outputWood.strippedWood, 1);
            if (candidate.wood == ingredient && outputWood.wood != null) return new ItemTarget(outputWood.wood, 1);
            if (candidate.slab == ingredient && outputWood.slab != null) return new ItemTarget(outputWood.slab, 1);
            if (candidate.stairs == ingredient && outputWood.stairs != null) return new ItemTarget(outputWood.stairs, 1);
            if (candidate.fence == ingredient && outputWood.fence != null) return new ItemTarget(outputWood.fence, 1);
            if (candidate.fenceGate == ingredient && outputWood.fenceGate != null) return new ItemTarget(outputWood.fenceGate, 1);
            if (candidate.sign == ingredient && outputWood.sign != null) return new ItemTarget(outputWood.sign, 1);
            if (candidate.hangingSign == ingredient && outputWood.hangingSign != null) return new ItemTarget(outputWood.hangingSign, 1);
            if (candidate.door == ingredient && outputWood.door != null) return new ItemTarget(outputWood.door, 1);
            if (candidate.trapdoor == ingredient && outputWood.trapdoor != null) return new ItemTarget(outputWood.trapdoor, 1);
            if (candidate.button == ingredient && outputWood.button != null) return new ItemTarget(outputWood.button, 1);
            if (candidate.pressurePlate == ingredient && outputWood.pressurePlate != null) return new ItemTarget(outputWood.pressurePlate, 1);
        }
        return null;
    }

    private Item getFamilyPlanks(ItemHelper.WoodItems wood) {
        if (wood == null) return null;
        if ("bamboo".equals(wood.prefix)) {
            return net.minecraft.item.Items.BAMBOO_PLANKS;
        }
        return wood.planks;
    }

    private ItemHelper.WoodItems getWoodFamilyForOutput(Item outputItem) {
        if (outputItem == null) return null;
        String outputId = itemId(outputItem);
        ItemHelper.WoodItems best = null;
        for (ItemHelper.WoodItems candidate : ItemHelper.getWoodItems()) {
            if (candidate == null || candidate.prefix == null) continue;
            if (isOutputInWoodFamily(outputId, candidate.prefix)) {
                if (best == null || candidate.prefix.length() > best.prefix.length()) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private boolean isOutputInWoodFamily(String outputId, String prefix) {
        if (outputId == null || prefix == null || prefix.isBlank()) return false;
        int namespaceSplit = outputId.indexOf(':');
        String path = namespaceSplit >= 0 ? outputId.substring(namespaceSplit + 1) : outputId;
        return path.startsWith(prefix + "_")
                || path.startsWith("stripped_" + prefix + "_");
    }

    private boolean isValidCraftingRecipe(CraftingRecipe recipe) {
        if (recipe == null) return false;
        if (recipe.outputCount() <= 0) return false;
        if (recipe.getSlotCount() != 4 && recipe.getSlotCount() != 9) return false;
        if (recipe.getFilledSlotCount() <= 0) return false;
        for (ItemTarget slot : recipe.getSlots()) {
            if (slot == null || slot.isEmpty()) continue;
            Item[] matches = slot.getMatches();
            if (matches == null || matches.length == 0) return false;
            boolean hasRealItem = false;
            for (Item match : matches) {
                if (match != null && match != net.minecraft.item.Items.AIR) {
                    hasRealItem = true;
                    break;
                }
            }
            if (!hasRealItem) return false;
        }
        return true;
    }

    private boolean contains(Item[] group, Item item) {
        for (Item candidate : group) {
            if (candidate == item) return true;
        }
        return false;
    }

}
