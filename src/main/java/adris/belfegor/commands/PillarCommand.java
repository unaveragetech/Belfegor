package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasks.construction.PillarUpTask;

/**
 * @pillar <height>
 *
 * Pillars straight up like a player: jump, place a block under yourself, land
 * on it, repeat. Useful for escaping holes, reaching ledges, and letting the
 * AI advisor give the bot a quick "go up" goal.
 */
public class PillarCommand extends Command {

    public PillarCommand() throws CommandException {
        super("pillar", "Pillar straight up. Example: @pillar 5");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        String[] args = parser.getArgUnits();
        int height = 3;
        if (args.length >= 1) {
            try {
                height = Math.max(1, Math.min(128, Integer.parseInt(args[0].trim())));
            } catch (NumberFormatException ignored) {
                throw new CommandException("Usage: @pillar <height 1-128>");
            }
        }
        int finalHeight = height;
        mod.runUserTask(new PillarUpTask(height), () -> {
            mod.log("Pillared up " + finalHeight + " blocks. Details logged to the Belfegor debug log.");
            finish();
        });
    }

    @Override
    public java.util.List<String> getExamples() {
        return java.util.List.of("@pillar", "@pillar 5", "@pillar 12");
    }

    @Override
    public String getDetailedDescription() {
        return "Builds a cobblestone pillar under the bot by jumping and placing "
                + "blocks beneath itself. Max 128 blocks per command. Useful for "
                + "escaping holes or reaching high places.";
    }
}
