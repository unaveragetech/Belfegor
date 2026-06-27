package adris.belfegor.tasksystem;

import adris.belfegor.Belfegor;

import java.util.ArrayList;
import java.util.List;

public abstract class TaskChain {

    private final List<Task> _cachedTaskChain = new ArrayList<>();

    public TaskChain(TaskRunner runner) {
        runner.addTaskChain(this);
    }

    public void tick(Belfegor mod) {
        _cachedTaskChain.clear();
        onTick(mod);
    }

    public void stop(Belfegor mod) {
        _cachedTaskChain.clear();
        onStop(mod);
    }

    protected abstract void onStop(Belfegor mod);

    public abstract void onInterrupt(Belfegor mod, TaskChain other);

    protected abstract void onTick(Belfegor mod);

    public abstract float getPriority(Belfegor mod);

    public abstract boolean isActive();

    public abstract String getName();

    public List<Task> getTasks() {
        return _cachedTaskChain;
    }

    void addTaskToChain(Task task) {
        _cachedTaskChain.add(task);
    }

    public String toString() {
        return getName();
    }

}
