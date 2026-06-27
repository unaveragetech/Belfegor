package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasks.construction.CoverWithBlocksTask;

public class CoverWithBlocksCommand extends Command {
    public CoverWithBlocksCommand() {
        super("coverwithblocks", "Cover nether lava with blocks");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        mod.runUserTask(new CoverWithBlocksTask(), this::finish);
    }
}
