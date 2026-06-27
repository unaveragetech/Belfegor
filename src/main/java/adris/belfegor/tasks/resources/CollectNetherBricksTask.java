package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.tasks.CraftInInventoryTask;
import adris.belfegor.tasks.ResourceTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.CraftingRecipe;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.MiningRequirement;
import adris.belfegor.util.RecipeTarget;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;

public class CollectNetherBricksTask extends ResourceTask {

    private final int _count;

    public CollectNetherBricksTask(int count) {
        super(Items.NETHER_BRICKS, count);
        _count = count;
    }

    @Override
    protected boolean shouldAvoidPickingUp(Belfegor mod) {
        return false;
    }

    @Override
    protected void onResourceStart(Belfegor mod) {
        mod.getBlockTracker().trackBlock(Blocks.NETHER_BRICKS);
    }

    @Override
    protected Task onResourceTick(Belfegor mod) {

        /*
         * If we find nether bricks, mine them.
         *
         * Otherwise craft them from the "nether_brick" item.
         */

        if (mod.getBlockTracker().anyFound(Blocks.NETHER_BRICKS)) {
            return new MineAndCollectTask(Items.NETHER_BRICKS, _count, new Block[]{Blocks.NETHER_BRICKS}, MiningRequirement.WOOD);
        }

        ItemTarget b = new ItemTarget(Items.NETHER_BRICK, 1);
        return new CraftInInventoryTask(new RecipeTarget(Items.NETHER_BRICK, _count, CraftingRecipe.newShapedRecipe("nether_brick", new ItemTarget[]{b, b, b, b}, 1)));
    }

    @Override
    protected void onResourceStop(Belfegor mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.NETHER_BRICKS);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectNetherBricksTask;
    }

    @Override
    protected String toDebugStringName() {
        return "Collecting " + _count + " nether bricks.";
    }
}
