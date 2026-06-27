package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.*;
import adris.belfegor.tasks.container.StoreInAnyContainerTask;
import adris.belfegor.util.ItemTarget;

public class StoreCommand extends Command {

    public StoreCommand() throws CommandException {
        super("store", "Store items in the nearest container (chest/barrel/shulker)",
                new Arg(ItemList.class, "items"));
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        ItemList itemList = parser.get(ItemList.class);
        if (itemList == null || itemList.items == null || itemList.items.length == 0) {
            mod.log("You must specify at least one item to store!");
            finish();
            return;
        }
        mod.runUserTask(new StoreInAnyContainerTask(false, itemList.items), this::finish);
    }
}
