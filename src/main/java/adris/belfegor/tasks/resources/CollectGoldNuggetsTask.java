package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.tasks.CraftInInventoryTask;
import adris.belfegor.tasks.ResourceTask;
import adris.belfegor.tasks.movement.DefaultGoToDimensionTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.*;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;

public class CollectGoldNuggetsTask extends ResourceTask {

    private final int _count;

    public CollectGoldNuggetsTask(int count) {
        super(Items.GOLD_NUGGET, count);
        _count = count;
    }

    @Override
    protected boolean shouldAvoidPickingUp(Belfegor mod) {
        return false;
    }

    @Override
    protected void onResourceStart(Belfegor mod) {

    }

    @Override
    protected Task onResourceTick(Belfegor mod) {
        switch (WorldHelper.getCurrentDimension()) {
            case OVERWORLD -> {
                setDebugState("Getting gold ingots to convert to nuggets");
                int potentialNuggies = mod.getItemStorage().getItemCount(Items.GOLD_NUGGET) + mod.getItemStorage().getItemCount(Items.GOLD_INGOT) * 9;
                if (potentialNuggies >= _count && mod.getItemStorage().hasItem(Items.GOLD_INGOT)) {
                    // Craft gold ingots to nuggets
                    return new CraftInInventoryTask(new RecipeTarget(Items.GOLD_NUGGET, _count, CraftingRecipe.newShapedRecipe("golden_nuggets", new ItemTarget[]{new ItemTarget(Items.GOLD_INGOT, 1), null, null, null}, 9)));
                }
                // Get gold ingots
                int nuggiesStillNeeded = _count - potentialNuggies;
                return TaskCatalogue.getItemTask(Items.GOLD_INGOT, (int) Math.ceil((double) nuggiesStillNeeded / 9.0));
            }
            case NETHER -> {
                setDebugState("Mining nuggies");
                return new MineAndCollectTask(Items.GOLD_NUGGET, _count, new Block[]{Blocks.NETHER_GOLD_ORE, Blocks.GILDED_BLACKSTONE}, MiningRequirement.WOOD);
            }
            case END -> {
                setDebugState("Going to overworld");
                return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
            }
        }

        setDebugState("INVALID DIMENSION??: " + WorldHelper.getCurrentDimension());
        return null;
    }

    @Override
    protected void onResourceStop(Belfegor mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectGoldNuggetsTask;
    }

    @Override
    protected String toDebugStringName() {
        return "Collecting " + _count + " nuggets";
    }
}
