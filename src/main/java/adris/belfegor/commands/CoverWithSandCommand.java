package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasks.construction.CoverWithSandTask;

public class CoverWithSandCommand extends Command {
    public CoverWithSandCommand() {
        super("coverwithsand", "Cover nether lava with sand");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        mod.runUserTask(new CoverWithSandTask(), this::finish);
    }
}
