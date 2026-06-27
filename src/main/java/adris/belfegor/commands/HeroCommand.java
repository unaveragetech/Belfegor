package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasks.entity.HeroTask;

public class HeroCommand extends Command {
    public HeroCommand() {
        super("hero", "Kill all hostile mobs");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        mod.runUserTask(new HeroTask(), this::finish);
    }
}
