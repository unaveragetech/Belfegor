package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;

import java.util.Locale;

/**
 * @face <direction> [pitch]
 *
 * Points the bot at a cardinal direction, straight up/down, or an exact
 * yaw/pitch. Useful for the AI advisor and for aiming interactions.
 */
public class FaceCommand extends Command {

    public FaceCommand() throws CommandException {
        super("face", "Face a direction. Examples: @face north, @face east, @face up, @face 45 -30");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        if (mod.getPlayer() == null) {
            throw new CommandException("No player to face.");
        }
        String[] args = parser.getArgUnits();
        String direction = args.length == 0 ? "north" : args[0].trim().toLowerCase(Locale.ROOT);
        float yaw = mod.getPlayer().getYaw();
        float pitch = mod.getPlayer().getPitch();
        switch (direction) {
            case "north" -> yaw = 180;
            case "south" -> yaw = 0;
            case "east" -> yaw = -90;
            case "west" -> yaw = 90;
            case "up" -> pitch = -90;
            case "down" -> pitch = 90;
            default -> {
                try {
                    yaw = Float.parseFloat(direction);
                    if (args.length >= 2) {
                        pitch = Float.parseFloat(args[1].trim());
                    }
                } catch (NumberFormatException e) {
                    throw new CommandException("Unknown direction `" + direction
                            + "`. Try north, south, east, west, up, down, or a yaw (and optional pitch).");
                }
            }
        }
        mod.getInputControls().forceLook(yaw, pitch);
        finish();
    }

    @Override
    public java.util.List<String> getExamples() {
        return java.util.List.of("@face north", "@face east", "@face up", "@face down", "@face 45 -30");
    }

    @Override
    public String getDetailedDescription() {
        return "Rotates the bot to face a cardinal direction, straight up/down, "
                + "or an exact yaw (with optional pitch in degrees).";
    }
}
