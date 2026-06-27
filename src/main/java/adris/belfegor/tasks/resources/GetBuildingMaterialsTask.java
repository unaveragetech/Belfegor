package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.MiningRequirement;
import adris.belfegor.util.helpers.StorageHelper;
import net.minecraft.item.Item;

public class GetBuildingMaterialsTask extends Task {
    private final int _count;

    public GetBuildingMaterialsTask(int count) {
        _count = count;
    }

    @Override
    protected void onStart(Belfegor mod) {

    }

    @Override
    protected Task onTick(Belfegor mod) {
        Item[] throwaways = mod.getModSettings().getThrowawayItems(mod, true);
        return new MineAndCollectTask(new ItemTarget[]{new ItemTarget(throwaways, _count)}, MiningRequirement.WOOD);
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetBuildingMaterialsTask task) {
            return task._count == _count;
        }
        return false;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return StorageHelper.getBuildingMaterialCount(mod) >= _count;
    }

    @Override
    protected String toDebugString() {
        return "Collecting " + _count + " building materials.";
    }
}
