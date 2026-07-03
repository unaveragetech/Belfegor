package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.Settings;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.tasks.construction.BuildCampsiteTask;
import adris.belfegor.util.helpers.WorldHelper;
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
        String dimension = WorldHelper.getCurrentDimension().name();
        BlockPos configured = mod.getModSettings().getHomeBasePosition();
        BlockPos home = configured != null
                ? configured
                : mod.getPlayer().getBlockPos();
        if (configured == null) {
            mod.getModSettings().setHomeBasePosition(home);
            Settings.save(mod.getModSettings());
        } else {
            BaseMemory.getInstance().rememberInspection(home, dimension, "core", "home_lock",
                    1, 0, 0, 1, "locked",
                    "@camp reused existing home; run @drop home before choosing a new camp");
            BaseMemory.getInstance().save();
        }
        mod.runUserTask(new BuildCampsiteTask(home, radius), this::finish);
    }

    @Override
    public java.util.List<String> getExamples() {
        return java.util.List.of("@camp", "@camp 10");
    }

    @Override
    public String getDetailedDescription() {
        return "Builds or repairs Belfegor's persistent home campsite. If no home is set, "
                + "the current position becomes home. If a home already exists, @camp reuses it "
                + "and will not move the base; run @drop home first if you intentionally want a new camp.";
    }
}
