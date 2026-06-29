package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasks.construction.BuildCampsiteTask;
import net.minecraft.util.math.BlockPos;

/**
 * @camp [radius]
 *
 * Establishes or rebuilds the remembered home campsite at the player's current
 * position. Room expansion commands use this remembered center as their anchor.
 */
public class CampCommand extends Command {

    public CampCommand() {
        super("camp", "Set home here and build the core expandable campsite");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        if (mod.getPlayer() == null) {
            throw new CommandException("Cannot create a camp without a player.");
        }
        String[] args = parser.getArgUnits();
        int radius = 8;
        if (args.length > 0) {
            try {
                radius = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                throw new CommandException("Usage: @camp [radius], for example @camp or @camp 10");
            }
        }
        BlockPos home = mod.getPlayer().getBlockPos();
        mod.getModSettings().setHomeBasePosition(home);
        mod.runUserTask(new BuildCampsiteTask(home, radius), this::finish);
    }

    @Override
    public java.util.List<String> getExamples() {
        return java.util.List.of("@camp", "@camp 10");
    }

    @Override
    public String getDetailedDescription() {
        return "Sets the current player position as Belfegor's home base, records it in settings, "
                + "then clears, flattens, and builds the core campsite. Run this before @build "
                + "when you want expansions to connect to a deliberate base instead of whatever "
                + "nearest remembered base exists.";
    }
}
