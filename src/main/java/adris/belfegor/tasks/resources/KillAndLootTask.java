package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.tasks.ResourceTask;
import adris.belfegor.tasks.entity.KillEntitiesTask;
import adris.belfegor.tasks.movement.TimeoutWanderTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import net.minecraft.entity.Entity;

import java.util.function.Predicate;

public class KillAndLootTask extends ResourceTask {

    private final Class<?> _toKill;

    private final Task _killTask;

    public KillAndLootTask(Class<?> toKill, Predicate<Entity> shouldKill, ItemTarget... itemTargets) {
        super(itemTargets.clone());
        _toKill = toKill;
        _killTask = new KillEntitiesTask(shouldKill, _toKill);
    }

    public KillAndLootTask(Class<?> toKill, ItemTarget... itemTargets) {
        super(itemTargets.clone());
        _toKill = toKill;
        _killTask = new KillEntitiesTask(_toKill);
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
        if (!mod.getEntityTracker().entityFound(_toKill)) {
            if (isInWrongDimension(mod)) {
                setDebugState("Going to correct dimension.");
                return getToCorrectDimensionTask(mod);
            }
            setDebugState("Searching for mob...");
            return new TimeoutWanderTask();
        }
        // We found the mob!
        return _killTask;
    }

    @Override
    protected void onResourceStop(Belfegor mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof KillAndLootTask task) {
            return task._toKill.equals(_toKill);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect items from " + _toKill.toGenericString();
    }
}
