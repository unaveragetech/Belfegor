package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasks.construction.BuildBaseExpansionTask;
import adris.belfegor.tasks.construction.BuildBaseValidationTask;
import adris.belfegor.tasks.construction.BuildFullBaseTask;
import adris.belfegor.tasks.construction.BuildImportedSchematicTask;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;

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
            throw new CommandException("Usage: @build full [radius] [here], @build farmland [name], @build storage [name], @build workshop [name], or @build mobfarm [name]");
        }
        String first = args[0].trim().toLowerCase(Locale.ROOT);
        if (first.equals("validate") || first.equals("repair") || first.equals("fix")) {
            mod.runUserTask(new BuildBaseValidationTask(), this::finish);
            return;
        }
        if ((first.equals("base") || first.equals("schematic"))
                && args.length >= 2
                && (args[1].equalsIgnoreCase("import") || args[1].equalsIgnoreCase("load"))) {
            if (args.length < 3) {
                throw new CommandException("Usage: @build base import \"C:\\path\\to\\file.litematic\" [name]");
            }
            String path = unquote(args[2]);
            String name = args.length >= 4
                    ? String.join("_", Arrays.copyOfRange(args, 3, args.length))
                    : "";
            mod.runUserTask(new BuildImportedSchematicTask(new File(path), name), this::finish);
            return;
        }
        if (first.equals("resume") || first.equals("continue")) {
            int radius = 12;
            for (int i = 1; i < args.length; i++) {
                try {
                    radius = Integer.parseInt(args[i].trim());
                } catch (NumberFormatException ignored) {
                    throw new CommandException("Usage: @build resume [radius 8-18]. Example: @build resume 12");
                }
            }
            mod.runUserTask(new BuildFullBaseTask(radius, false, true), this::finish);
            return;
        }
        if (first.equals("full") || first.equals("base") || first.equals("all")) {
            int radius = 12;
            boolean here = false;
            for (int i = 1; i < args.length; i++) {
                String arg = args[i].trim().toLowerCase(Locale.ROOT);
                if (arg.equals("here") || arg.equals("new")) {
                    here = true;
                    continue;
                }
                try {
                    radius = Integer.parseInt(arg);
                } catch (NumberFormatException ignored) {
                    throw new CommandException("Usage: @build full [radius 8-18] [here]. Example: @build full 12 here");
                }
            }
            mod.runUserTask(new BuildFullBaseTask(radius, here), this::finish);
            return;
        }
        BuildBaseExpansionTask.RoomType type = BuildBaseExpansionTask.parseType(args[0]);
        String name = args.length >= 2
                ? String.join("_", Arrays.copyOfRange(args, 1, args.length))
                : "";
        mod.runUserTask(new BuildBaseExpansionTask(type, name), this::finish);
    }

    private static String unquote(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        while (trimmed.length() >= 2
                && trimmed.startsWith("\"")
                && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    @Override
    public java.util.List<String> getExamples() {
        return java.util.List.of(
                "@build farmland",
                "@build farmland wheat_wing",
                "@build storage shulker_vault",
                "@build workshop",
                "@build mobfarm",
                "@build full",
                "@build full 12 here",
                "@build resume",
                "@build resume 12",
                "@build validate",
                "@build repair",
                "@build base import \"C:\\Users\\you\\.minecraft\\schematics\\test\\camp.litematic\"",
                "@build schematic import \"C:\\builds\\watchtower.litematic\" watchtower"
        );
    }

    @Override
    public String getDetailedDescription() {
        return "Expands the remembered home base. Use @build full to build and validate "
                + "the complete base: campsite/core, storage, workshop, hydrated crop farmland, "
                + "and roofed mob-farm room. Use @build base import \"file.litematic\" [name] to copy a user schematic "
                + "into Belfegor, parse it into the internal blueprint format, create a staging chest, collect/deposit "
                + "the required block materials, and build/remember the imported structure at the player's current origin. "
                + "Use @build resume to return to the remembered camp, validate "
                + "partial construction, and continue without choosing a new site. Use @build validate or @build repair to inspect remembered "
                + "rooms and rerun incomplete modules. For single rooms, Belfegor chooses a non-overlapping base wall direction, "
                + "builds a two-wide connector hall three to five blocks long, builds a named room, "
                + "records the room center, and adds type-specific internals. Farmland rooms dig water "
                + "holes, place water, till hydrated soil, and plant seeds when available.";
    }
}
