package adris.belfegor.tasks.resources.wood;

import adris.belfegor.TaskCatalogue;
import adris.belfegor.tasks.resources.CraftWithMatchingPlanksTask;
import adris.belfegor.util.CraftingRecipe;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.ItemHelper;
import net.minecraft.item.Item;

public class CollectWoodenTrapDoorTask extends CraftWithMatchingPlanksTask {

    public CollectWoodenTrapDoorTask(Item[] targets, ItemTarget planks, int count) {
        super(targets, woodItems -> woodItems.trapdoor, createRecipe(planks), new boolean[]{true, true, true, true, true, true, false, false, false}, count);
    }

    public CollectWoodenTrapDoorTask(Item target, String plankCatalogueName, int count) {
        this(new Item[]{target}, new ItemTarget(plankCatalogueName, 1), count);
    }

    public CollectWoodenTrapDoorTask(int count) {
        this(ItemHelper.WOOD_TRAPDOOR, TaskCatalogue.getItemTarget("planks", 1), count);
    }

    private static CraftingRecipe createRecipe(ItemTarget planks) {
        ItemTarget p = planks;
        ItemTarget o = null;
        return CraftingRecipe.newShapedRecipe(new ItemTarget[]{p, p, p, p, p, p, o, o, o}, 2);
    }
}
