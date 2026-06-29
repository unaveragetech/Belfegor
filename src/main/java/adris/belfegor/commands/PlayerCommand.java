package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.Arg;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasks.PlayerExplorationTask;

/**
 * @player - Start autonomous exploration mode.
 *
 * The bot plays the game on its own:
 * - Wanders to discover new areas and structures
 * - Picks up valuable items from the ground
 * - Kills mobs for food and resources
 * - Mines blocks for materials
 * - Crafts items to learn optimal paths
 * - Records observations in CraftingPathRegistry
 *
 * The bot gets better over time as it learns which paths are fastest.
 * Use @stop to end exploration.
 */
public class PlayerCommand extends Command {
    public PlayerCommand() throws CommandException {
        super("player", "Start autonomous exploration and learning mode");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        mod.runUserTask(new PlayerExplorationTask(), this::finish);
    }

    @Override
    public java.util.List<String> getExamples() {
        return java.util.List.of("@player");
    }

    @Override
    public String getDetailedDescription() {
        return "Starts autonomous player mode. The bot sets the current position as home base, "
                + "saves it to settings, builds a core campsite, remembers room centers, "
                + "and later expands the base through named connected modules, "
                + "uses carried shulkers as sub-inventories, gathers resources, practices useful "
                + "crafts, upgrades tools, hunts food, explores, and periodically returns home. "
                + "Use @build farmland/storage/workshop/mobfarm to force specific expansions and "
                + "@home <room> to navigate back to remembered base rooms.";
    }
}
