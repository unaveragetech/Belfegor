package adris.belfegor.chains;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.tasksystem.ITaskCanForce;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.tasksystem.TaskChain;
import adris.belfegor.tasksystem.TaskRunner;
import adris.belfegor.util.time.Stopwatch;

public abstract class SingleTaskChain extends TaskChain {

    private final Stopwatch _taskStopwatch = new Stopwatch();
    protected Task _mainTask = null;
    private boolean _interrupted = false;
    private boolean _actuallyInterrupted = false;

    private Belfegor _mod;

    public SingleTaskChain(TaskRunner runner) {
        super(runner);
        _mod = runner.getMod();
    }

    @Override
    protected void onTick(Belfegor mod) {
        if (!isActive()) return;

        if (_interrupted) {
            _interrupted = false;
            // Only reset the task if it was actually interrupted (not forced).
            // Forced tasks survive chain-level interrupts with state intact.
            if (_actuallyInterrupted && _mainTask != null) {
                _mainTask.reset();
            }
            _actuallyInterrupted = false;
        }

        if (_mainTask != null) {
            if ((_mainTask.isFinished(mod)) || _mainTask.stopped()) {
                onTaskFinish(mod);
            } else {
                _mainTask.tick(mod, this);
            }
        }
    }

    protected void onStop(Belfegor mod) {
        if (isActive() && _mainTask != null) {
            _mainTask.stop(mod);
            _mainTask = null;
        }
    }

    public void setTask(Task task) {
        if (_mainTask == null || !_mainTask.equals(task)) {
            if (_mainTask != null) {
                _mainTask.stop(_mod, task);
            }
            _mainTask = task;
            if (task != null) task.reset();
        }
    }


    @Override
    public boolean isActive() {
        return _mainTask != null;
    }

    protected abstract void onTaskFinish(Belfegor mod);

    @Override
    public void onInterrupt(Belfegor mod, TaskChain other) {
        if (other != null) {
            Debug.logInternal("Chain Interrupted: " + this + " by " + other);
        }
        // Stop our task. When we're started up again, let our task know we need to run.
        _interrupted = true;
        _actuallyInterrupted = false;
        if (_mainTask != null && _mainTask.isActive()) {
            // If the task or any child declares shouldForce(), do NOT interrupt it.
            // Interrupting resets _first=true which causes onStart() to re-run on
            // resume, destroying phase progress (e.g. shulker PLACE→OPEN→TRANSFER).
            boolean canInterrupt = _mainTask.thisOrChildSatisfies(task -> {
                if (task instanceof ITaskCanForce canForce) {
                    return !canForce.shouldForce(mod, null);
                }
                return true;
            });
            if (canInterrupt) {
                _mainTask.interrupt(mod, null);
                _actuallyInterrupted = true;
            }
            // else: task survives interrupt with state intact; _interrupted flag
            // is set but _actuallyInterrupted is false → tick() won't call reset().
        }
    }

    protected boolean isCurrentlyRunning(Belfegor mod) {
        return !_interrupted && _mainTask != null && _mainTask.isActive() && !_mainTask.isFinished(mod);
    }

    public Task getCurrentTask() {
        return _mainTask;
    }
}
