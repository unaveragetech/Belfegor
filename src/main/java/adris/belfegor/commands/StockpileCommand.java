package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.commandsystem.ArgParser;
import adris.belfegor.commandsystem.Command;
import adris.belfegor.commandsystem.CommandException;
import adris.belfegor.tasks.resources.CampStockpileTask;
import adris.belfegor.tasks.resources.ToolSetTask;
import adris.belfegor.util.ItemTarget;

import java.util.Arrays;
import java.util.Locale;

/**
 * @stockpile [tier] [starter|build]
 * @stockpile <item> [count] [tier]
 *
 * Gathers practical resources and stores them in the remembered camp storage
 * room so @player/@build can work from base supplies instead of cluttered
 * inventory or stale far-away containers.
 */
public class StockpileCommand extends Command {

    public StockpileCommand() {
        super("stockpile", "Gather resources and store them in the remembered camp storage room");
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        ToolSetTask.Tier tier = ToolSetTask.Tier.STONE;
        CampStockpileTask.Profile profile = CampStockpileTask.Profile.STARTER;
        String customItem = null;
        int customCount = 64;

        for (String raw : parser.getArgUnits()) {
            String arg = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (arg.isBlank()) continue;
            switch (arg) {
                case "wood", "wooden" -> tier = ToolSetTask.Tier.WOOD;
                case "stone" -> tier = ToolSetTask.Tier.STONE;
                case "iron" -> tier = ToolSetTask.Tier.IRON;
                case "diamond" -> tier = ToolSetTask.Tier.DIAMOND;
                case "starter", "start", "small" -> profile = CampStockpileTask.Profile.STARTER;
                case "build", "base", "full" -> profile = CampStockpileTask.Profile.BUILD;
                default -> {
                    if (arg.matches("\\d+")) {
                        customCount = Math.max(1, Integer.parseInt(arg));
                    } else if (customItem == null) {
                        customItem = arg;
                    } else {
                        throw new CommandException("Unknown stockpile option: " + arg
                                + ". Use @stockpile [wood|stone|iron|diamond] [starter|build] "
                                + "or @stockpile <item> [count] [tier].");
                    }
                }
            }
        }

        if (customItem != null) {
            ItemTarget target = TaskCatalogue.getItemTarget(customItem, customCount);
            if (target == null) {
                throw new CommandException("Unknown stockpile item: " + customItem);
            }
            mod.runUserTask(new CampStockpileTask(tier, target), this::finish);
        } else {
            mod.runUserTask(new CampStockpileTask(tier, profile), this::finish);
        }
    }

    @Override
    public java.util.List<String> getExamples() {
        return Arrays.asList(
                "@stockpile",
                "@stockpile stone starter",
                "@stockpile cobblestone 512",
                "@stockpile iron_ingot 32 iron",
                "@stockpile wood starter",
                "@stockpile stone build"
        );
    }

    @Override
    public String getDetailedDescription() {
        return "Gathers practical camp supplies, crafts the requested tool tier through the same toolset logic "
                + "as @toolset, returns to the remembered camp, and deposits gathered resources into the storage "
                + "room chest. Starter mode gathers a smaller survival reserve; build mode gathers larger base "
                + "construction reserves. You can also point it at one resource, for example "
                + "@stockpile cobblestone 512 or @stockpile iron_ingot 32 iron. Run @camp or @build full here first "
                + "so Belfegor has a remembered camp and storage room anchor.";
    }
}
