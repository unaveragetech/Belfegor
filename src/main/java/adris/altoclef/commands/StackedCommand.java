package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.ItemHelper;
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
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
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
