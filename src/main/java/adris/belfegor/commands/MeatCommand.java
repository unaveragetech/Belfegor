package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.Arg;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasks.resources.CollectMeatTask;

public class MeatCommand extends Command {
    public MeatCommand() throws CommandException {
        super("meat", "Collects a certain amount of meat", new Arg<>(Integer.class, "count"));
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        mod.runUserTask(new CollectMeatTask(parser.get(Integer.class)), this::finish);
    }
}