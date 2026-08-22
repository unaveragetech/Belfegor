package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasks.movement.GetToBlockTask;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

/**
 * @forward|@back|@left|@right <blocks>
 *
 * Base movement controls: walk N blocks in a direction relative to the bot's
 * current facing. Lets the AI advisor (or a remote operator) steer the bot in
 * precise block amounts, e.g. @forward 5 or @left 3.
 */
public class MoveCommand extends Command {

    private final String _direction;

    public MoveCommand(String direction) throws CommandException {
        super(direction, "Move N blocks " + direction + ". Example: @" + direction + " 5");
        _direction = direction;
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        if (mod.getPlayer() == null) {
            throw new CommandException("No player to move.");
        }
        String[] args = parser.getArgUnits();
        int blocks = 5;
        if (args.length >= 1) {
            try {
                blocks = Math.max(1, Math.min(64, Integer.parseInt(args[0].trim())));
            } catch (NumberFormatException ignored) {
                throw new CommandException("Usage: @" + _direction + " <blocks 1-64>");
            }
        }
        float yaw = mod.getPlayer().getYaw();
        Vec3i direction = switch (_direction) {
            case "forward" -> facingVector(yaw);
            case "back", "backward" -> facingVector(yaw + 180);
            case "left" -> facingVector(yaw + 90);
            case "right" -> facingVector(yaw - 90);
            default -> facingVector(yaw);
        };
        BlockPos target = mod.getPlayer().getBlockPos()
                .add(direction.getX() * blocks, 0, direction.getZ() * blocks);
        mod.runUserTask(GetToBlockTask.baseAware(mod, target), this::finish);
    }

    /** Minecraft yaw: 0 = south (+Z), 90 = west (-X), -90 = east (+X), 180 = north (-Z). */
    private static Vec3i facingVector(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec3i(
                (int) Math.round(-Math.sin(radians)),
                0,
                (int) Math.round(Math.cos(radians)));
    }

    @Override
    public java.util.List<String> getExamples() {
        return switch (_direction) {
            case "forward" -> java.util.List.of("@forward 5", "@forward 10");
            case "back" -> java.util.List.of("@back 3", "@back 8");
            case "left" -> java.util.List.of("@left 2", "@left 6");
            default -> java.util.List.of("@right 2", "@right 10");
        };
    }

    @Override
    public String getDetailedDescription() {
        return "Walks the bot N blocks " + _direction
                + " relative to its current facing direction (max 64).";
    }
}
