package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.Arg;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasks.pvp.AdvancedPvPTask;

/**
 * Advanced PvP command - gears up and hunts a player with fallback mechanics.
 * Usage: @pvp <playerName>
 */
public class PvpCommand extends Command {
    public PvpCommand() throws CommandException {
        super("pvp", "Advanced PvP - gear up, hunt, heal, repeat", new Arg(String.class, "playerName"));
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        String playerName = parser.get(String.class);
        mod.runUserTask(new AdvancedPvPTask(playerName, true), this::finish);
    }
}
