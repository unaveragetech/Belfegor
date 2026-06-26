package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.container.ShulkerInteractionTask;
import adris.altoclef.tasksystem.ITaskUsesCraftingGrid;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.item.Item;
import org.apache.commons.lang3.ArrayUtils;

import java.util.*;
import adris.altoclef.tasksystem.CraftingPathRegistry;

// Collects everything that's catalogued for a recipe.
public class CollectRecipeCataloguedResourcesTask extends Task implements ITaskUsesCraftingGrid {

    private final RecipeTarget[] _targets;
    private final boolean _ignoreUncataloguedSlots;
    private boolean _finished = false;
    private Task _activeSubTask = null;
    private String _activeSubTaskKey = null;
    private ShulkerInteractionTask _shulkerRetrieveTask = null;
    private String _shulkerRetrieveKey = null;

    public CollectRecipeCataloguedResourcesTask(boolean ignoreUncataloguedSlots, RecipeTarget... targets) {
        _targets = targets;
        _ignoreUncataloguedSlots = ignoreUncataloguedSlots;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _finished = false;
        _activeSubTask = null;
        _activeSubTaskKey = null;
        _shulkerRetrieveTask = null;
        _shulkerRetrieveKey = null;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // TODO: Cache this once instead of doing it every frame.

        // If our cached sub-task finished (returned null) or is finished (met target count),
        // clear it so we don't return a stale finished task instance that keeps getting ticked.
        if (_activeSubTask != null && (!_activeSubTask.isActive() || _activeSubTask.isFinished(mod))) {
            _activeSubTask = null;
            _activeSubTaskKey = null;
        }
        if (_shulkerRetrieveTask != null
                && !_shulkerRetrieveTask.isFinished(mod)
                && !_shulkerRetrieveTask.stopped()) {
            setDebugState("Continuing active shulker ingredient retrieval");
            return _shulkerRetrieveTask;
        }
        if (_shulkerRetrieveTask != null
                && (_shulkerRetrieveTask.isFinished(mod) || _shulkerRetrieveTask.stopped())) {
            _shulkerRetrieveTask = null;
            _shulkerRetrieveKey = null;
        }

        // Stuff to get, both catalogued + individual items.
        HashMap<String, Integer> catalogueCount = new HashMap<>();
        HashMap<Item, Integer> itemCount = new HashMap<>();

        for (RecipeTarget target : _targets) {
            // Ignore this recipe if we have its item.
            //if (mod.getItemStorage().targetMet(target.getItem())) continue;

            // null = empty which is always met.
            if (target == null) continue;

            int weNeed = target.getTargetCount() - mod.getItemStorage().getItemCount(target.getOutputItem());

            if (weNeed > 0) {
                CraftingRecipe recipe = target.getRecipe();
                // Default, just go through the recipe slots and collect the first one.
                for (int i = 0; i < recipe.getSlotCount(); ++i) {
                    ItemTarget slot = recipe.getSlot(i);
                    if (slot == null || slot.isEmpty()) continue;
                    int numberOfRepeats = (int) Math.floor(-0.1 + (double) weNeed / target.getRecipe().outputCount()) + 1;
                    if (!slot.isCatalogueItem()) {
                        if (slot.getMatches().length != 1) {
                            if (!_ignoreUncataloguedSlots) {
                                Debug.logWarning("Recipe collection for recipe " + recipe + " slot " + i
                                        + " is not catalogued. Please define an explicit"
                                        + " collectRecipeSubTask() function for this item target:" + slot
                                );
                            }
                        } else {
                            Item item = slot.getMatches()[0];
                            itemCount.put(item, itemCount.getOrDefault(item, 0) + numberOfRepeats);
                        }
                    } else {
                        String targetName = slot.getCatalogueName();
                        // How many "repeats" of a recipe we will need.
                        catalogueCount.put(targetName, catalogueCount.getOrDefault(targetName, 0) + numberOfRepeats);
                    }
                }
            }
        }


        // (Cache this with the above stuff!!)
        // Grab materials — sort by CraftingPathRegistry difficulty (easiest first)
        CraftingPathRegistry pathRegistry = CraftingPathRegistry.getInstance();
        List<String> sortedMaterials = new ArrayList<>(catalogueCount.keySet());
        sortedMaterials.sort((a, b) -> {
            double diffA = pathRegistry.getEasiestDifficulty(a);
            double diffB = pathRegistry.getEasiestDifficulty(b);
            // If no path registered, use fallback priority
            if (diffA < 0) diffA = getFallbackPriority(a);
            if (diffB < 0) diffB = getFallbackPriority(b);
            return Double.compare(diffA, diffB);
        });
        for (String catalogueMaterialName : sortedMaterials) {
            int count = catalogueCount.get(catalogueMaterialName);
            if (count > 0) {
                ItemTarget itemTarget = new ItemTarget(catalogueMaterialName, count);
                if (!StorageHelper.itemTargetsMet(mod, itemTarget)) {
                    String key = "cat:" + catalogueMaterialName + ":" + count;
                    if (ShulkerInteractionTask.carriedShulkerContains(mod, itemTarget)) {
                        if (_shulkerRetrieveTask != null
                                && key.equals(_shulkerRetrieveKey)
                                && !_shulkerRetrieveTask.isFinished(mod)
                                && !_shulkerRetrieveTask.stopped()) {
                            return _shulkerRetrieveTask;
                        }
                        _shulkerRetrieveKey = key;
                        _shulkerRetrieveTask = new ShulkerInteractionTask(
                                ShulkerInteractionTask.Mode.RETRIEVE, itemTarget);
                        setDebugState("Retrieving " + itemTarget + " from carried shulker");
                        return _shulkerRetrieveTask;
                    }
                    if (_activeSubTask != null && key.equals(_activeSubTaskKey)) {
                        return _activeSubTask;
                    }
                    _activeSubTaskKey = key;
                    _activeSubTask = TaskCatalogue.getItemTask(catalogueMaterialName, count);
                    setDebugState("Getting " + itemTarget);
                    return _activeSubTask;
                }
            }
        }
        for (Item item : itemCount.keySet()) {
            int count = itemCount.get(item);
            if (count > 0) {
                if (mod.getItemStorage().getItemCount(item) < count) {
                    ItemTarget itemTarget = new ItemTarget(item, count);
                    String key = "item:" + item.getTranslationKey() + ":" + count;
                    if (ShulkerInteractionTask.carriedShulkerContains(mod, itemTarget)) {
                        if (_shulkerRetrieveTask != null
                                && key.equals(_shulkerRetrieveKey)
                                && !_shulkerRetrieveTask.isFinished(mod)
                                && !_shulkerRetrieveTask.stopped()) {
                            return _shulkerRetrieveTask;
                        }
                        _shulkerRetrieveKey = key;
                        _shulkerRetrieveTask = new ShulkerInteractionTask(
                                ShulkerInteractionTask.Mode.RETRIEVE, itemTarget);
                        setDebugState("Retrieving " + item.getTranslationKey() + " from carried shulker");
                        return _shulkerRetrieveTask;
                    }
                    if (_activeSubTask != null && key.equals(_activeSubTaskKey)) {
                        return _activeSubTask;
                    }
                    _activeSubTaskKey = key;
                    _activeSubTask = TaskCatalogue.getItemTask(item, count);
                    setDebugState("Getting " + item.getTranslationKey());
                    return _activeSubTask;
                }
            }
        }
        _finished = true;
        _activeSubTask = null;
        _activeSubTaskKey = null;
        _shulkerRetrieveTask = null;
        _shulkerRetrieveKey = null;

        return null;
    }


    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof CollectRecipeCataloguedResourcesTask task) {
            return Arrays.equals(task._targets, _targets);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Collect Recipe Resources: " + ArrayUtils.toString(_targets);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        if (_finished) {
            if (!StorageHelper.hasRecipeMaterialsOrTarget(mod, this._targets)) {
                _finished = false;
                Debug.logMessage("Invalid collect recipe \"finished\" state, resetting.");
            }
        }
        return _finished;
    }

    /**
     * Fallback priority for materials not in CraftingPathRegistry.
     * Lower number = easier to obtain = should be collected first.
     */
    private static double getFallbackPriority(String name) {
        // Mob drops (meat, leather, etc.) — easiest, just kill animals
        if (name.startsWith("raw_")) return 0;
        // Cooked foods — already have these usually
        if (name.startsWith("cooked_")) return 1;
        // Sticks — craft from planks (2-step)
        if (name.equals("sticks")) return 2;
        // Common mined blocks — stone, iron, gold, diamond, etc.
        if (name.equals("cobblestone") || name.equals("iron_ore") || name.equals("gold_ore")
                || name.equals("diamond_ore") || name.equals("redstone_ore") || name.equals("lapis_ore")
                || name.equals("coal") || name.equals("iron_ingot") || name.equals("gold_ingot")
                || name.equals("string") || name.equals("feather") || name.equals("flint")
                || name.equals("leather") || name.equals("paper") || name.equals("sugar_cane")
                || name.equals("wheat") || name.equals("carrot") || name.equals("potato")
                || name.equals("copper_ingot") || name.equals("raw_copper") || name.equals("raw_iron")
                || name.equals("raw_gold") || name.equals("netherite_scrap") || name.equals("netherite_ingot")
                || name.equals("obsidian") || name.equals("ender_pearl") || name.equals("blaze_rod")
                || name.equals("ghast_tear") || name.equals("magma_cream") || name.equals("nether_wart")
                || name.equals("glowstone_dust") || name.equals("redstone") || name.equals("lapus_lazuli")) {
            return 3;
        }
        // Planks — craft from logs (1-step, very easy)
        if (name.endsWith("_planks") || name.equals("oak_planks") || name.equals("spruce_planks")
                || name.equals("birch_planks") || name.equals("jungle_planks")
                || name.equals("acacia_planks") || name.equals("dark_oak_planks")
                || name.equals("mangrove_planks") || name.equals("cherry_planks")
                || name.equals("bamboo_planks") || name.equals("crimson_planks")
                || name.equals("warped_planks")) {
            return 4;
        }
        // Logs — mine trees (easy but requires finding trees)
        if (name.endsWith("_log") || name.equals("oak_log") || name.equals("spruce_log")
                || name.equals("birch_log") || name.equals("jungle_log")
                || name.equals("acacia_log") || name.equals("dark_oak_log")
                || name.equals("mangrove_log") || name.equals("cherry_log")
                || name.equals("bamboo_block") || name.equals("crimson_stem")
                || name.equals("warped_stem")) {
            return 5;
        }
        // Rare/low-drop-rate items — apples from leaves (0.5% drop rate), etc.
        if (name.equals("apple") || name.equals("golden_apple")) return 10;
        // Default — medium difficulty
        return 6;
    }
}
