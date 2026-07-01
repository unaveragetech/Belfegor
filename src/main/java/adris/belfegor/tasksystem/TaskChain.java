package adris.belfegor.tasksystem;

import adris.belfegor.Belfegor;

import java.util.ArrayList;
import java.util.List;

public abstract class TaskChain {

    public static final int MAX_TASK_DEPTH_PER_TICK = 96;

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

    boolean isOverDepthLimit() {
        return _cachedTaskChain.size() > MAX_TASK_DEPTH_PER_TICK;
    }

    String describeTaskChain() {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < _cachedTaskChain.size(); i++) {
            if (i > 0) result.append(" -> ");
            result.append(_cachedTaskChain.get(i));
        }
        return result.toString();
    }

    public String toString() {
        return getName();
    }

}
