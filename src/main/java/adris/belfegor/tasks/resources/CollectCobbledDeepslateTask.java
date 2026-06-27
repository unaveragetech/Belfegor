package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.tasks.ResourceTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.MiningRequirement;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;

public class CollectCobbledDeepslateTask extends ResourceTask {

    private final int _count;

    public CollectCobbledDeepslateTask(int targetCount) {
        super(Items.COBBLED_DEEPSLATE, targetCount);
        _count = targetCount;
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
        return new MineAndCollectTask(Items.COBBLED_DEEPSLATE, 1, new Block[]{Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE}, MiningRequirement.WOOD);
    }

    @Override
    protected void onResourceStop(Belfegor mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CollectCobbledDeepslateTask task) {
            return task._count == _count;
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect Cobbled Deepslate";
    }
}
