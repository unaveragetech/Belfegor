package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.Arg;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.tasksystem.TaskRunner;

import java.util.List;

public class StatusCommand extends Command {
    public StatusCommand() throws CommandException {
        super("status", "Get status of currently executing command",
                new Arg<>(String.class, "mode", "", 0, false),
                new Arg<>(Integer.class, "count", 5, 1));
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        String mode = parser.get(String.class).trim().toLowerCase();
        Integer count = parser.get(Integer.class);
        if (mode.equals("history") || mode.equals("interrupts")) {
            printInterruptHistory(mod, count == null ? 5 : count);
            finish();
            return;
        }

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

    private void printInterruptHistory(Belfegor mod, int count) {
        List<TaskRunner.InterruptSnapshot> history = mod.getTaskRunner().getInterruptHistory();
        if (history.isEmpty()) {
            mod.log("No task interruptions recorded this session.");
            return;
        }
        int max = Math.max(1, Math.min(count, history.size()));
        mod.log("Recent task interruptions, newest first (" + max + "/" + history.size() + "):");
        for (int i = history.size() - 1; i >= history.size() - max; i--) {
            TaskRunner.InterruptSnapshot interrupt = history.get(i);
            mod.log(" - " + interrupt.fromChain()
                    + " -> " + interrupt.toChain()
                    + " outcome=" + interrupt.outcome()
                    + " root=" + (interrupt.interruptedRoot().isBlank() ? "unknown" : interrupt.interruptedRoot())
                    + " age=" + interrupt.ageMs() + "ms");
        }
    }
}
