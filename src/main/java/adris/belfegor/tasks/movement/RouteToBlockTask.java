package adris.belfegor.tasks.movement;

import adris.belfegor.Belfegor;
import adris.belfegor.tasksystem.Task;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Navigates through a remembered base by visiting room-center waypoints in
 * order before reaching the final target. Each leg is base-aware, so the bot
 * uses its doors and halls instead of mining through its own walls when both
 * it and the next waypoint are inside the base.
 */
public class RouteToBlockTask extends Task {

    private final List<BlockPos> _waypoints;
    private final BlockPos _target;
    private int _index;
    private Task _activeTask;

    public RouteToBlockTask(List<BlockPos> waypoints, BlockPos target) {
        _waypoints = waypoints == null ? new ArrayList<>() : new ArrayList<>(waypoints);
        _target = target;
    }

    @Override
    protected void onStart(Belfegor mod) {
        _index = 0;
        _activeTask = null;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        if (_activeTask != null && !_activeTask.stopped() && !_activeTask.isFinished(mod)) {
            return _activeTask;
        }
        if (_activeTask != null) {
            _activeTask = null;
            _index++;
        }
        if (_index < _waypoints.size()) {
            BlockPos waypoint = _waypoints.get(_index);
            setDebugState("Routing through base waypoint " + (_index + 1) + "/" + _waypoints.size()
                    + " " + waypoint.toShortString());
            _activeTask = GetToBlockTask.baseAware(mod, waypoint);
            return _activeTask;
        }
        if (_activeTask == null) {
            setDebugState("Routing to final target " + _target.toShortString());
            _activeTask = GetToBlockTask.baseAware(mod, _target);
        }
        return _activeTask;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _activeTask = null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof RouteToBlockTask task
                && task._target.equals(_target)
                && task._waypoints.equals(_waypoints);
    }

    @Override
    protected String toDebugString() {
        return "Route to " + _target.toShortString()
                + " waypoints=" + _waypoints.size() + " index=" + _index;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return _index >= _waypoints.size()
                && (_activeTask == null || _activeTask.isFinished(mod));
    }
}
