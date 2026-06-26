package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.*;
import adris.altoclef.tasks.container.StoreInAnyContainerTask;
import adris.altoclef.util.ItemTarget;

public class StoreCommand extends Command {

    public StoreCommand() throws CommandException {
        super("store", "Store items in the nearest container (chest/barrel/shulker)",
                new Arg(ItemList.class, "items"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        ItemList itemList = parser.get(ItemList.class);
        if (itemList == null || itemList.items == null || itemList.items.length == 0) {
            mod.log("You must specify at least one item to store!");
            finish();
            return;
        }
        mod.runUserTask(new StoreInAnyContainerTask(false, itemList.items), this::finish);
    }
}
