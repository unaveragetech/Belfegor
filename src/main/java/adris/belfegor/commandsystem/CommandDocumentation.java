package adris.belfegor.commandsystem;

import java.util.List;
import java.util.Map;
import java.util.Collection;

public final class CommandDocumentation {
    private static final Map<String, List<String>> EXAMPLES = Map.ofEntries(
            Map.entry("help", List.of("@help", "@help shulker")),
            Map.entry("get", List.of("@get diamond 3", "@get crafting_table")),
            Map.entry("follow", List.of("@follow Steve")),
            Map.entry("give", List.of("@give Steve diamond 3")),
            Map.entry("equip", List.of("@equip diamond_helmet", "@equip [diamond_helmet, diamond_chestplate]")),
            Map.entry("deposit", List.of("@deposit", "@deposit cobblestone 64")),
            Map.entry("stash", List.of("@stash", "@stash diamond 3")),
            Map.entry("goto", List.of("@goto 100 64 -200", "@goto 100 -200 overworld")),
            Map.entry("idle", List.of("@idle")),
            Map.entry("coords", List.of("@coords")),
            Map.entry("status", List.of("@status")),
            Map.entry("inventory", List.of("@inventory", "@inventory diamond")),
            Map.entry("locate_structure", List.of("@locate_structure stronghold")),
            Map.entry("stop", List.of("@stop")),
            Map.entry("test", List.of("@test stacked")),
            Map.entry("food", List.of("@food 32")),
            Map.entry("meat", List.of("@meat 32")),
            Map.entry("reload_settings", List.of("@reload_settings")),
            Map.entry("gamer", List.of("@gamer")),
            Map.entry("marvion", List.of("@marvion")),
            Map.entry("punk", List.of("@punk Steve")),
            Map.entry("hero", List.of("@hero")),
            Map.entry("gamma", List.of("@gamma 10")),
            Map.entry("list", List.of("@list")),
            Map.entry("coverwithsand", List.of("@coverwithsand")),
            Map.entry("coverwithblocks", List.of("@coverwithblocks")),
            Map.entry("selfcare", List.of("@selfcare")),
            Map.entry("pvp", List.of("@pvp Steve")),
            Map.entry("stacked", List.of("@stacked")),
            Map.entry("player", List.of("@player")),
            Map.entry("camp", List.of("@camp", "@camp 10")),
            Map.entry("build", List.of("@build farmland", "@build storage shulker_vault",
                    "@build workshop", "@build mobfarm")),
            Map.entry("home", List.of("@home", "@home farmland", "@home shulker_vault")),
            Map.entry("toolset", List.of("@toolset iron", "@toolset diamond")),
            Map.entry("store", List.of("@store diamond 3", "@store [diamond 3, gold_ingot 8]")),
            Map.entry("retrieve", List.of("@retrieve diamond 3")),
            Map.entry("shulker", List.of("@shulker list", "@shulker store diamond 3",
                    "@shulker retrieve stick 8", "@shulker auto on")),
            Map.entry("ai", List.of("@ai \"what should I do next?\"", "@ai \"why am I stuck?\"")),
            Map.entry("craftaudit", List.of("@craftaudit anvil", "@craftaudit all 25"))
    );

    private CommandDocumentation() {}

    public static List<String> examplesFor(String name) {
        return EXAMPLES.getOrDefault(name, List.of("@" + name));
    }

    public static String detailsFor(String name, String fallback) {
        return switch (name) {
            case "get" -> "Obtains the requested catalogued item and quantity using gathering, crafting, smelting, containers, or carried shulkers.";
            case "goto" -> "Travels to XYZ, XZ, or Y coordinates. A dimension may be appended when cross-dimensional travel is required.";
            case "deposit", "store", "stash" -> "Moves requested inventory items into storage. Item lists use item count or [item count, item count].";
            case "retrieve" -> "Retrieves requested item quantities from known nearby storage.";
            case "stacked" -> "Sequentially gathers the complete configured PvP equipment and supply loadout.";
            case "player" -> "Starts autonomous player mode. Belfegor establishes a remembered base, builds a core camp, expands rooms over time, manages shulkers, gathers resources, practices crafts, upgrades tools, and can consult the local llama.cpp advisor without interrupting active tasks.";
            case "camp" -> "Sets the current position as the remembered home base and builds the core expandable campsite. Run this before @build when you want room expansions connected to a deliberate camp.";
            case "build" -> "Expands the remembered base with a connected room. Supported types include farmland, storage, workshop, mobfarm, and empty. Belfegor picks a base side, builds a two-wide 3-5 block hall, creates a named room, records its center, and adds room-specific internals such as hydrated tilled farmland.";
            case "home" -> "Routes to the remembered home base or to a named room/module center created by @player or @build. Examples: @home, @home farmland, @home shulker_vault.";
            case "toolset" -> "Crafts one pickaxe, axe, shovel, sword, and hoe at the requested material tier.";
            case "ai" -> "Asks the local llama.cpp advisor for a chat response or high-level command suggestion using current context, command docs, inventory, shulker memory, goal, and action log. Defaults to llmLlamaModelPath=belfegor/models/Qwen3-1.7B-Q4_K_M.gguf when llmAdvisorEnabled is true.";
            case "craftaudit" -> "Developer-only offline recipe audit. Uses bundled recipe data, /give @s leaf resources, crafts each target through Belfegor, stores outputs, and writes belfegor/craft_audit_*.log. Requires cheats/op.";
            default -> fallback;
        };
    }

    public static String exportMarkdown(Collection<Command> commands, String prefix) {
        StringBuilder result = new StringBuilder();
        result.append("# Belfegor command catalogue\n\n");
        result.append("Use only these commands when returning a command decision. ");
        result.append("The prefix is `").append(prefix).append("`.\n\n");
        for (Command command : commands) {
            result.append("## ").append(prefix).append(command.getHelpRepresentation()).append("\n\n");
            result.append(command.getDetailedDescription()).append("\n\n");
            result.append("Arguments:\n\n");
            ArgBase[] args = command.getArguments();
            if (args.length == 0) {
                result.append("- none\n\n");
            } else {
                for (ArgBase arg : args) {
                    result.append("- `").append(arg.getName()).append("`: ")
                            .append(arg.getTypeName()).append("; ")
                            .append(arg.hasDefault() ? "optional" : "required")
                            .append("; expected ").append(arg.getExpectedValues()).append("\n");
                }
                result.append("\n");
            }
            result.append("Runnable examples:\n\n");
            for (String example : command.getExamples()) {
                result.append("- `").append(example).append("`\n");
            }
            result.append("\n");
        }
        return result.toString();
    }
}

