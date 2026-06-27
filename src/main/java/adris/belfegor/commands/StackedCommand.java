package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.tasks.misc.EquipArmorTask;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.ItemHelper;
import net.minecraft.item.Items;

/**
 * Get stacked - collect full diamond gear, sword, shield, food, blocks, golden apples.
 * Usage: @stacked
 */
public class StackedCommand extends Command {
    public StackedCommand() throws CommandException {
        super("stacked", "Get fully stacked for PvP (armor, sword, shield, food, supplies)");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        // Run a squashed task to get everything needed for PvP
        mod.runUserTask(TaskCatalogue.getSquashedItemTask(
                new ItemTarget(Items.DIAMOND_HELMET, 1),
                new ItemTarget(Items.DIAMOND_CHESTPLATE, 1),
                new ItemTarget(Items.DIAMOND_LEGGINGS, 1),
                new ItemTarget(Items.DIAMOND_BOOTS, 1),
                new ItemTarget(Items.DIAMOND_SWORD, 1),
                new ItemTarget(Items.SHIELD, 1),
                new ItemTarget(Items.COOKED_BEEF, 32),
                new ItemTarget(Items.GOLDEN_APPLE, 4),
                new ItemTarget(Items.OAK_PLANKS, 64),
                new ItemTarget(Items.ENDER_PEARL, 4)
        ), this::finish);
    }
}
