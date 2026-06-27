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
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

public class CollectSticksTask extends ResourceTask {

    private final int _targetCount;
    private MineAndCollectTask _activeMineTask = null;
    private CraftInInventoryTask _activeCraftTask = null;

    public CollectSticksTask(int targetCount) {
        super(Items.STICK, targetCount);
        _targetCount = targetCount;
    }

    @Override
    protected boolean shouldAvoidPickingUp(Belfegor mod) {
        return false;
    }

    @Override
    protected void onResourceStart(Belfegor mod) {
        _activeMineTask = null;
        _activeCraftTask = null;
        mod.getBehaviour().push();
        mod.getBlockTracker().trackBlock(Blocks.DEAD_BUSH);
    }

    @Override
    protected Task onResourceTick(Belfegor mod) {
        Optional<BlockPos> nearestBush = mod.getBlockTracker().getNearestTracking(Blocks.DEAD_BUSH);
        // If there's a dead bush within range, go get it
        if (nearestBush.isPresent() && nearestBush.get().isWithinDistance(mod.getPlayer().getPos(), 20)) {
            _activeCraftTask = null;
            if (_activeMineTask == null) {
                _activeMineTask = new MineAndCollectTask(Items.DEAD_BUSH, 999999, new Block[]{Blocks.DEAD_BUSH}, MiningRequirement.HAND);
            }
            return _activeMineTask;
        }
        // else craft from wood
        _activeMineTask = null;
        if (_activeCraftTask == null) {
            _activeCraftTask = new CraftInInventoryTask(new RecipeTarget(Items.STICK, _targetCount, CraftingRecipe.newShapedRecipe("sticks", new ItemTarget[]{new ItemTarget("planks"), null, new ItemTarget("planks"), null}, 4)));
        }
        return _activeCraftTask;
    }

    @Override
    protected void onResourceStop(Belfegor mod, Task interruptTask) {
        _activeMineTask = null;
        _activeCraftTask = null;
        mod.getBlockTracker().stopTracking(Blocks.DEAD_BUSH);
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectSticksTask;
    }

    @Override
    protected String toDebugStringName() {
        return "Crafting " + _targetCount + " sticks";
    }
}
