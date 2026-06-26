package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.commandsystem.Arg;
import adris.altoclef.commandsystem.ArgParser;
import adris.altoclef.commandsystem.Command;
import adris.altoclef.commandsystem.CommandException;
import adris.altoclef.tasks.pvp.AdvancedPvPTask;

/**
 * Advanced PvP command - gears up and hunts a player with fallback mechanics.
 * Usage: @pvp <playerName>
 */
public class PvpCommand extends Command {
    public PvpCommand() throws CommandException {
        super("pvp", "Advanced PvP - gear up, hunt, heal, repeat", new Arg(String.class, "playerName"));
    }

    @Override
    protected void call(AltoClef mod, ArgParser parser) throws CommandException {
        String playerName = parser.get(String.class);
        mod.runUserTask(new AdvancedPvPTask(playerName, true), this::finish);
    }
}
