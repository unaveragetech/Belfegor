package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.util.helpers.WorldHelper;

public class CoordsCommand extends Command {
    public CoordsCommand() {
        super("coords", "Get the bot's current coordinates");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) {
        mod.log("CURRENT COORDINATES: " + mod.getPlayer().getBlockPos().toShortString() + " (Current dimension: " + WorldHelper.getCurrentDimension() + ")");
        finish();
    }
}
