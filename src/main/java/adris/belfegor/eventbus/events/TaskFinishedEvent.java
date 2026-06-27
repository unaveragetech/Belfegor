package adris.belfegor.eventbus.events;

import adris.belfegor.tasksystem.Task;

public class TaskFinishedEvent {
    public double durationSeconds;
    public Task lastTaskRan;

    public TaskFinishedEvent(double durationSeconds, Task lastTaskRan) {
        this.durationSeconds = durationSeconds;
        this.lastTaskRan = lastTaskRan;
    }
}
