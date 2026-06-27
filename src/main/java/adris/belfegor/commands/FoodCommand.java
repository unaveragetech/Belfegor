package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.Arg;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasks.resources.CollectFoodTask;

public class FoodCommand extends Command {
    public FoodCommand() throws CommandException {
        super("food", "Collects a certain amount of food", new Arg<>(Integer.class, "count"));
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        mod.runUserTask(new CollectFoodTask(parser.get(Integer.class)), this::finish);
    }
}