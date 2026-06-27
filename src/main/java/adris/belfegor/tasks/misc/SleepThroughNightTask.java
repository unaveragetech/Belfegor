package adris.belfegor.tasks.misc;

import adris.belfegor.Belfegor;
import adris.belfegor.tasksystem.Task;

public class SleepThroughNightTask extends Task {

    @Override
    protected void onStart(Belfegor mod) {

    }

    @Override
    protected Task onTick(Belfegor mod) {
        return new PlaceBedAndSetSpawnTask().stayInBed();
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof SleepThroughNightTask;
    }

    @Override
    protected String toDebugString() {
        return "Sleeping through the night";
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        // We're in daytime
        int time = (int) (mod.getWorld().getTimeOfDay() % 24000);
        return 0 <= time && time < 13000;
    }
}
