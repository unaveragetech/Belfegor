package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.Settings;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.memory.BaseStorageMemory;
import adris.belfegor.memory.ErrandMemory;
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
        // Wipe every home-related memory so a later @player/@camp starts fresh
        // near the player instead of pathing back to the old base: nearby base
        // records, storage networks, home/door/room locations, and stash
        // errands all reference the old home.
        int removedBases = oldHome == null ? 0
                : BaseMemory.getInstance().forgetBasesNear(oldHome, dimension, 256);
        int removedStorage = oldHome == null ? 0
                : BaseStorageMemory.getInstance().forgetNetworksNear(oldHome, dimension, 256);
        int removedLocations = oldHome == null ? 0
                : LocationMemory.getInstance().forgetNear(oldHome, dimension, 256);
        int removedErrands = oldHome == null ? 0
                : ErrandMemory.getInstance().forgetHome(oldHome, dimension);
        mod.getModSettings().clearHomeBasePosition();
        mod.getModSettings().setReturnHomeOnIdle(false);
        mod.getModSettings().setDefendHomeBase(false);
        Settings.save(mod.getModSettings());

        LocationMemory.getInstance().save();
        BaseMemory.getInstance().save();
        BaseStorageMemory.getInstance().save();
        ErrandMemory.getInstance().save();
        mod.log("Dropped home lock"
                + (oldHome == null ? " (no previous home was set)" : " at " + oldHome.toShortString())
                + (removedBase ? " and removed exact base record." : "."));
        if (oldHome != null) {
            mod.log("Cleared nearby home memories: bases=" + removedBases
                    + " storage=" + removedStorage
                    + " locations=" + removedLocations
                    + " errands=" + removedErrands
                    + " (within 256 blocks of the old home)");
        }
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
