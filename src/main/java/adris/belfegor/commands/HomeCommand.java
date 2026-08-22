package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.memory.LocationMemory;
import adris.belfegor.tasks.movement.GetToBlockTask;
import adris.belfegor.tasks.movement.RouteToBlockTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.Dimension;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.util.math.BlockPos;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * @home [room]
 *
 * Travels to the remembered base center, or to a named room/module center.
 */
public class HomeCommand extends Command {

    public HomeCommand() {
        super("home", "Return to the remembered home base or a named base room");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        String[] args = parser.getArgUnits();
        String room = args.length == 0 ? "" : normalize(String.join("_", Arrays.asList(args)));
        String dimension = WorldHelper.getCurrentDimension().name();
        BlockPos playerPos = mod.getPlayer() == null ? BlockPos.ORIGIN : mod.getPlayer().getBlockPos();
        BlockPos configured = mod.getModSettings().getHomeBasePosition();
        BlockPos anchor = configured != null ? configured : playerPos;

        if (!room.isBlank() && !room.equals("camp") && !room.equals("base")) {
            Optional<BaseMemory.BaseModule> module = BaseMemory.getInstance()
                    .findNearestModule(anchor, dimension, room);
            if (module.isPresent()) {
                BaseMemory.BaseModule found = module.get();
                mod.runUserTask(routeTo(mod, found.center(), parseDimension(dimension)), this::finish);
                return;
            }

            Optional<LocationMemory.RememberedLocation> location = LocationMemory.getInstance()
                    .getNearest("home_room_" + room, anchor.getX(), anchor.getY(), anchor.getZ(), dimension);
            if (location.isPresent()) {
                mod.runUserTask(routeTo(mod, location.get().toBlockPos(),
                        parseDimension(location.get().dimension)), this::finish);
                return;
            }
            throw new CommandException("No remembered base room found for `" + room + "`. Try @home, @home farmland, @home storage, or @build " + room + ".");
        }

        Optional<LocationMemory.RememberedLocation> door = LocationMemory.getInstance()
                .getNearest("home_door", anchor.getX(), anchor.getY(), anchor.getZ(), dimension)
                .or(() -> LocationMemory.getInstance()
                        .getNearest("home_room_entrance", anchor.getX(), anchor.getY(), anchor.getZ(), dimension));
        if (door.isPresent()) {
            mod.runUserTask(routeTo(mod, door.get().toBlockPos(),
                    parseDimension(door.get().dimension)), this::finish);
            return;
        }

        if (configured != null) {
            mod.runUserTask(routeTo(mod, configured, null), this::finish);
            return;
        }
        Optional<BaseMemory.BaseRecord> base = BaseMemory.getInstance().nearestBase(playerPos, dimension);
        if (base.isPresent()) {
            mod.runUserTask(routeTo(mod, base.get().center(),
                    parseDimension(base.get().dimension)), this::finish);
            return;
        }
        throw new CommandException("No home base has been established yet. Run @camp or @build full here to seed a base.");
    }

    private static Dimension parseDimension(String value) {
        if (value == null) return null;
        String normalized = value.toUpperCase(Locale.ROOT);
        try {
            return Dimension.valueOf(normalized);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    @Override
    public java.util.List<String> getExamples() {
        return java.util.List.of("@home", "@home camp", "@home farmland", "@home shulker_vault");
    }

    @Override
    public String getDetailedDescription() {
        return "Routes Belfegor to the locked remembered home base, its doorway, or to a room center. "
                + "Rooms are resolved relative to the configured home instead of the player's nearest base, "
                + "so long-running builds do not drift to another camp.";
    }

    /**
     * Routes to a home/room target without breaking blocks when the player or
     * the target is inside a remembered base, so the bot uses its doors and
     * halls instead of mining through its own walls.
     */
    private static Task routeTo(Belfegor mod, BlockPos target, Dimension dimension) {
        if (mod == null || mod.getPlayer() == null || target == null) {
            return new GetToBlockTask(target, dimension);
        }
        String currentDimension = WorldHelper.getCurrentDimension().name();
        List<BlockPos> waypoints = BaseMemory.getInstance()
                .routeWaypoints(mod.getPlayer().getBlockPos(), target, currentDimension);
        if (!waypoints.isEmpty()) return new RouteToBlockTask(waypoints, target);
        GetToBlockTask task = new GetToBlockTask(target, dimension);
        if (BaseMemory.getInstance().isInsideBase(mod.getPlayer().getBlockPos(), currentDimension, 4)
                || BaseMemory.getInstance().isInsideBase(target, currentDimension, 4)) {
            return task.withoutBreaking();
        }
        return task;
    }
}
