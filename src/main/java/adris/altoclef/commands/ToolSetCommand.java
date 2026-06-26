package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.tasks.resources.ToolSetTask;

/**
 * @toolset <tier> - Craft a full tool set for the given tier.
 *
 * Tiers: wood, stone, iron, diamond
 *
 * Gathers ALL materials for the tier first, then crafts the full set:
 *   pickaxe (first, unlocks mining) -> axe -> shovel -> sword
 *
 * At each tier, materials are gathered using the previous tier's tools.
 * Example: @toolset iron will:
 *   1. Ensure stone pickaxe exists
 *   2. Mine iron ore + coal
 *   3. Smelt iron ingots
 *   4. Craft iron pickaxe, axe, shovel, sword
 */
public class ToolSetCommand extends Command {
    public ToolSetCommand() throws CommandException {
        super("toolset", "Craft a full tool set (wood/stone/iron/diamond)",
                new Arg<>(String.class, "tier"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String tierStr = parser.get(String.class).toLowerCase();
        ToolSetTask.Tier tier;
        switch (tierStr) {
            case "wood", "wooden" -> tier = ToolSetTask.Tier.WOOD;
            case "stone" -> tier = ToolSetTask.Tier.STONE;
            case "iron" -> tier = ToolSetTask.Tier.IRON;
            case "diamond" -> tier = ToolSetTask.Tier.DIAMOND;
            default -> throw new CommandException("Invalid tier: " + tierStr + ". Use wood, stone, iron, or diamond.");
        }
        mod.runUserTask(new ToolSetTask(tier), this::finish);
    }
}
