package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.util.helpers.NativeBaritoneHelper;

import java.util.List;
import java.util.Locale;

/**
 * Controlled bridge to native Baritone commands for debugging and optimized
 * construction workflows.
 */
public class BaritoneCommand extends Command {

    public BaritoneCommand() {
        super("baritone", "Run safe native Baritone diagnostics or commands");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        String[] args = parser.getArgUnits();
        if (args.length == 0 || args[0].equalsIgnoreCase("proc")) {
            NativeBaritoneHelper.runProc(mod);
            mod.log("Requested native Baritone process state.");
            finish();
            return;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("cancel") || first.equals("forcecancel")) {
            NativeBaritoneHelper.execute(mod, "forcecancel");
            mod.getClientBaritone().getBuilderProcess().onLostControl();
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            mod.log("Cancelled native Baritone processes.");
            finish();
            return;
        }
        if (first.equals("sel") || first.equals("selection") || first.equals("s")) {
            NativeBaritoneHelper.execute(mod, String.join(" ", args));
            mod.log("Ran native Baritone selection command.");
            finish();
            return;
        }
        if (first.equals("help") || first.equals("surface") || first.equals("top")
                || first.equals("paused") || first.equals("pause") || first.equals("resume")
                || first.equals("version") || first.equals("modified") || first.equals("set")
                || first.equals("build") || first.equals("litematica")) {
            NativeBaritoneHelper.execute(mod, String.join(" ", args));
            mod.log("Ran native Baritone command: #" + String.join(" ", args));
            finish();
            return;
        }
        throw new CommandException("Unsupported safe Baritone bridge command. Try @baritone proc, @baritone help sel, @baritone sel clear, @baritone surface, or @baritone forcecancel.");
    }

    @Override
    public List<String> getExamples() {
        return List.of(
                "@baritone proc",
                "@baritone help",
                "@baritone help sel",
                "@baritone sel clear",
                "@baritone surface",
                "@baritone forcecancel",
                "@baritone build house 100 64 100",
                "@baritone litematica"
        );
    }

    @Override
    public String getDetailedDescription() {
        return "Runs a controlled subset of native Baritone commands through Baritone's command manager. "
                + "This is mainly for diagnostics and construction research: #proc, #help, #sel, #surface, "
                + "#build, #litematica, pause/resume, and forcecancel. Belfegor's own build tasks also use "
                + "native Baritone builder and selection APIs internally for efficient region construction.";
    }
}
