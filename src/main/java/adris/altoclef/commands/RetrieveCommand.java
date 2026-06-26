package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.*;
import adris.altoclef.tasks.container.RetrieveFromAnyContainerTask;
import adris.altoclef.util.ItemTarget;

public class RetrieveCommand extends Command {

    public RetrieveCommand() throws CommandException {
        super("retrieve", "Retrieve items from the nearest container",
                new Arg(ItemList.class, "items"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        ItemList itemList = parser.get(ItemList.class);
        if (itemList == null || itemList.items == null || itemList.items.length == 0) {
            mod.log("You must specify at least one item to retrieve!");
            finish();
            return;
        }
        mod.runUserTask(new RetrieveFromAnyContainerTask(itemList.items), this::finish);
    }
}
