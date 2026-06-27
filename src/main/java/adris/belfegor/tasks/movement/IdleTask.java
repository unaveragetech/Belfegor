package adris.belfegor.tasks.movement;

import adris.belfegor.Belfegor;
import adris.belfegor.Playground;
import adris.belfegor.tasksystem.Task;

/**
 * Do nothing.
 */
public class IdleTask extends Task {
    @Override
    protected void onStart(Belfegor mod) {

    }

    @Override
    protected Task onTick(Belfegor mod) {
        // Do nothing except maybe test code
        Playground.IDLE_TEST_TICK_FUNCTION(mod);
        return null;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {

    }

    @Override
    public boolean isFinished(Belfegor mod) {
        // Never finish
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof IdleTask;
    }

    @Override
    protected String toDebugString() {
        return "Idle";
    }
}
