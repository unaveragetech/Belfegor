package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.Arg;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasks.misc.EquipArmorTask;
import adris.belfegor.util.helpers.ItemHelper;
import net.minecraft.item.Item;

/**
 * @armor <material> - Craft and equip a full armor set for the material.
 *
 * Materials: leather, chainmail, iron, gold, diamond, netherite
 *
 * Already-owned or already-equipped pieces are skipped, so the command is
 * safe to re-run after losing a piece.
 */
public class ArmorSetCommand extends Command {
    public ArmorSetCommand() throws CommandException {
        super("armor", "Craft and equip a full armor set (leather/chainmail/iron/gold/diamond/netherite)",
                new Arg<>(String.class, "material"));
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        Item[] armors = armorForMaterial(parser.get(String.class).toLowerCase());
        mod.runUserTask(new EquipArmorTask(armors), this::finish);
    }

    public static Item[] armorForMaterial(String material) throws CommandException {
        return switch (material) {
            case "leather" -> ItemHelper.LEATHER_ARMORS;
            case "chainmail", "chain" -> ItemHelper.CHAINMAIL_ARMORS;
            case "iron" -> ItemHelper.IRON_ARMORS;
            case "gold", "golden" -> ItemHelper.GOLDEN_ARMORS;
            case "diamond" -> ItemHelper.DIAMOND_ARMORS;
            case "netherite" -> ItemHelper.NETHERITE_ARMORS;
            default -> throw new CommandException("Invalid armor material: " + material
                    + ". Use leather, chainmail, iron, gold, diamond, or netherite.");
        };
    }
}
