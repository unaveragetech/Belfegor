package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;

import java.util.Locale;

/**
 * @turn <left|right|around|degrees>
 *
 * Rotates the bot by 90 degrees left/right, 180 degrees around, or an exact
 * number of degrees, keeping the current pitch.
 */
public class TurnCommand extends Command {

    public TurnCommand() throws CommandException {
        super("turn", "Turn the bot. Examples: @turn left, @turn right, @turn 180, @turn 45");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        if (mod.getPlayer() == null) {
            throw new CommandException("No player to turn.");
        }
        String[] args = parser.getArgUnits();
        String amount = args.length == 0 ? "90" : args[0].trim().toLowerCase(Locale.ROOT);
        float yaw = mod.getPlayer().getYaw();
        switch (amount) {
            case "left" -> yaw += 90;
            case "right" -> yaw -= 90;
            case "around", "back", "180" -> yaw += 180;
            default -> {
                try {
                    yaw += Float.parseFloat(amount);
                } catch (NumberFormatException e) {
                    throw new CommandException("Usage: @turn <left|right|around|degrees>");
                }
            }
        }
        mod.getInputControls().forceLook(yaw, mod.getPlayer().getPitch());
        finish();
    }

    @Override
    public java.util.List<String> getExamples() {
        return java.util.List.of("@turn left", "@turn right", "@turn around", "@turn 45");
    }

    @Override
    public String getDetailedDescription() {
        return "Rotates the bot left or right by 90 degrees, 180 degrees around, "
                + "or an exact number of degrees.";
    }
}
