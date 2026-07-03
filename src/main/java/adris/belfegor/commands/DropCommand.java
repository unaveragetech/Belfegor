package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.Settings;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.memory.LocationMemory;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.util.math.BlockPos;

import java.util.Locale;

/**
 * Explicitly drops persistent anchors that should never be overwritten by
 * ordinary camp/player/build tasks.
 */
public class DropCommand extends Command {

    public DropCommand() {
        super("drop", "Drop a persistent Belfegor memory anchor, such as home");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        String[] args = parser.getArgUnits();
        if (args.length == 0) {
            throw new CommandException("Usage: @drop home");
        }
        String target = String.join("_", args).trim().toLowerCase(Locale.ROOT);
        if (!target.equals("home") && !target.equals("camp") && !target.equals("base")) {
            throw new CommandException("Unknown drop target `" + target + "`. Currently supported: @drop home");
        }

        BlockPos oldHome = mod.getModSettings().getHomeBasePosition();
        String dimension = WorldHelper.getCurrentDimension().name();
        boolean removedBase = oldHome != null && BaseMemory.getInstance().forgetBase(oldHome, dimension);
        mod.getModSettings().clearHomeBasePosition();
        mod.getModSettings().setReturnHomeOnIdle(false);
        mod.getModSettings().setDefendHomeBase(false);
        Settings.save(mod.getModSettings());

        if (oldHome != null) {
            LocationMemory.getInstance().forget("home_base", oldHome.getX(), oldHome.getY(), oldHome.getZ());
            LocationMemory.getInstance().forget("home_campsite", oldHome.getX(), oldHome.getY(), oldHome.getZ());
            LocationMemory.getInstance().forget("home_door", oldHome.getX(), oldHome.getY(), oldHome.getZ());
            LocationMemory.getInstance().save();
        }
        BaseMemory.getInstance().save();
        mod.log("Dropped home lock"
                + (oldHome == null ? " (no previous home was set)" : " at " + oldHome.toShortString())
                + (removedBase ? " and removed exact base record." : "."));
        finish();
    }

    @Override
    public java.util.List<String> getExamples() {
        return java.util.List.of("@drop home");
    }

    @Override
    public String getDetailedDescription() {
        return "Clears Belfegor's locked home/camp anchor. Ordinary @camp, @player, and "
                + "@build commands will not move an existing home; use @drop home when you "
                + "intentionally want the next @camp or @build full here to establish a new base.";
    }
}
