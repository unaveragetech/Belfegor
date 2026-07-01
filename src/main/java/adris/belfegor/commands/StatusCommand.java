package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.tasksystem.TaskRunner;

import java.util.List;

public class StatusCommand extends Command {
    public StatusCommand() {
        super("status", "Get status of currently executing command");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) {
        List<Task> tasks = mod.getUserTaskChain().getTasks();
        if (tasks.size() == 0) {
            mod.log("No tasks currently running.");
        } else {
            mod.log("CURRENT TASK: " + tasks.get(0).toString());
            if (tasks.size() > 1) {
                mod.log("Task chain: " + tasks);
            }
        }
        TaskRunner.InterruptSnapshot interrupt = mod.getTaskRunner().getLastInterrupt();
        if (interrupt != null) {
            mod.log("Last interrupt: " + interrupt.fromChain()
                    + " -> " + interrupt.toChain()
                    + " outcome=" + interrupt.outcome()
                    + " root=" + (interrupt.interruptedRoot().isBlank() ? "unknown" : interrupt.interruptedRoot())
                    + " age=" + interrupt.ageMs() + "ms");
        }
        finish();
    }
}
