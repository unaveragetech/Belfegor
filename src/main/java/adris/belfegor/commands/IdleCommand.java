package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.tasks.movement.IdleTask;

public class IdleCommand extends Command {
    public IdleCommand() {
        super("idle", "Stand still");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) {
        mod.runUserTask(new IdleTask(), this::finish);
    }
}
