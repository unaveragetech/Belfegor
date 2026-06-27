package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.tasks.ResourceTask;
import adris.belfegor.tasks.entity.DoToClosestEntityTask;
import adris.belfegor.tasks.movement.DefaultGoToDimensionTask;
import adris.belfegor.tasks.movement.GetToEntityTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.Dimension;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.item.Items;

public class CollectEggsTask extends ResourceTask {

    private final int _count;

    private final DoToClosestEntityTask _waitNearChickens;

    private Belfegor _mod;

    public CollectEggsTask(int targetCount) {
        super(Items.EGG, targetCount);
        _count = targetCount;
        _waitNearChickens = new DoToClosestEntityTask(chicken -> new GetToEntityTask(chicken, 5), ChickenEntity.class);
    }

    @Override
    protected boolean shouldAvoidPickingUp(Belfegor mod) {
        return false;
    }

    @Override
    protected void onResourceStart(Belfegor mod) {
        _mod = mod;
    }

    @Override
    protected Task onResourceTick(Belfegor mod) {
        // Wrong dimension check.
        if (_waitNearChickens.wasWandering() && WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            setDebugState("Going to right dimension.");
            return new DefaultGoToDimensionTask(Dimension.OVERWORLD);
        }
        // Just wait around chickens.
        setDebugState("Waiting around chickens. Yes.");
        return _waitNearChickens;
    }

    @Override
    protected void onResourceStop(Belfegor mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectEggsTask;
    }

    @Override
    protected String toDebugStringName() {
        return "Collecting " + _count + " eggs.";
    }
}
