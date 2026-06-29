package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasks.construction.BuildBaseExpansionTask;

import java.util.Arrays;

/**
 * @build <roomType> [roomName]
 *
 * Expands the remembered @player base with a modular connected room.
 */
public class BuildCommand extends Command {

    public BuildCommand() {
        super("build", "Expand the remembered home base with a named connected room");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        String[] args = parser.getArgUnits();
        if (args.length == 0) {
            throw new CommandException("Usage: @build farmland [name], @build storage [name], @build workshop [name], or @build mobfarm [name]");
        }
        BuildBaseExpansionTask.RoomType type = BuildBaseExpansionTask.parseType(args[0]);
        String name = args.length >= 2
                ? String.join("_", Arrays.copyOfRange(args, 1, args.length))
                : "";
        mod.runUserTask(new BuildBaseExpansionTask(type, name), this::finish);
    }

    @Override
    public java.util.List<String> getExamples() {
        return java.util.List.of(
                "@build farmland",
                "@build farmland wheat_wing",
                "@build storage shulker_vault",
                "@build workshop",
                "@build mobfarm"
        );
    }

    @Override
    public String getDetailedDescription() {
        return "Expands the remembered home base. Belfegor chooses a base wall direction, "
                + "builds a two-wide connector hall three to five blocks long, builds a named room, "
                + "records the room center, and adds type-specific internals. Farmland rooms dig water "
                + "holes, place water, till hydrated soil, and plant seeds when available.";
    }
}
