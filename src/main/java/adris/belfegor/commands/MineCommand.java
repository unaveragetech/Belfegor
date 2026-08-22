package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasksystem.Task;

import java.util.Locale;

/**
 * @mine <resource> [count]
 *
 * Mines/collects the nearest tracked ore or resource using the normal
 * resource planner. Lets the AI advisor issue a concrete gathering goal,
 * e.g. @mine iron 8 or @mine diamond 3.
 */
public class MineCommand extends Command {

    public MineCommand() throws CommandException {
        super("mine", "Mine the nearest ore/resource. Examples: @mine iron 8, @mine coal 8, @mine diamond 3");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        String[] args = parser.getArgUnits();
        String resource = args.length == 0 ? "iron" : args[0].trim().toLowerCase(Locale.ROOT);
        int count = 8;
        if (args.length >= 2) {
            try {
                count = Math.max(1, Integer.parseInt(args[1].trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        String catalogueName = switch (resource) {
            case "coal" -> "coal";
            case "iron", "raw_iron" -> "raw_iron";
            case "gold", "raw_gold" -> "raw_gold";
            case "copper", "raw_copper" -> "raw_copper";
            case "diamond" -> "diamond";
            case "emerald" -> "emerald";
            case "redstone" -> "redstone";
            case "lapis", "lapis_lazuli" -> "lapis_lazuli";
            case "cobble", "cobblestone" -> "cobblestone";
            case "stone" -> "stone";
            case "deepslate", "cobbled_deepslate" -> "cobbled_deepslate";
            case "gravel" -> "gravel";
            case "sand" -> "sand";
            default -> null;
        };
        if (catalogueName == null) {
            throw new CommandException("Unknown resource `" + resource
                    + "`. Try coal, iron, gold, copper, diamond, emerald, redstone, lapis, cobblestone, stone, deepslate, gravel, or sand.");
        }
        Task task = TaskCatalogue.getItemTask(catalogueName, count);
        if (task == null) {
            throw new CommandException("No mining task available for `" + resource + "`.");
        }
        mod.runUserTask(task, this::finish);
    }

    @Override
    public java.util.List<String> getExamples() {
        return java.util.List.of("@mine iron", "@mine iron 8", "@mine diamond 3", "@mine coal 16");
    }

    @Override
    public String getDetailedDescription() {
        return "Uses the normal resource planner to mine/collect the nearest tracked "
                + "resource until the requested count is reached.";
    }
}
