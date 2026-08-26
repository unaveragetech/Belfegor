package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.Arg;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasks.resources.EquipmentTask;

/**
 * @equipment <material> - Prepares a full loadout: complete tool set plus a
 * full armor set for the given material.
 *
 * Examples:
 *   @equipment iron      -> iron tools + iron armor (equipped)
 *   @equipment wood      -> wooden tools only (no wooden armor exists)
 *   @equipment leather   -> leather armor only (no leather tools exist)
 *   @equipment netherite -> netherite tools + netherite armor (equipped)
 */
public class EquipmentCommand extends Command {
    public EquipmentCommand() throws CommandException {
        super("equipment", "Craft a full tool set + armor set for a material (wood/stone/leather/chainmail/iron/gold/diamond/netherite)",
                new Arg<>(String.class, "material"));
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        mod.runUserTask(EquipmentTask.forMaterial(parser.get(String.class).toLowerCase()), this::finish);
    }
}
