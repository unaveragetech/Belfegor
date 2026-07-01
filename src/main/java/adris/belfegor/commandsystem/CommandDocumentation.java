package adris.belfegor.commandsystem;

import java.util.List;
import java.util.Map;
import java.util.Collection;

public final class CommandDocumentation {
    private static final Map<String, List<String>> EXAMPLES = Map.ofEntries(
            Map.entry("help", List.of("@help", "@help shulker")),
            Map.entry("ui", List.of("@ui")),
            Map.entry("get", List.of("@get diamond 3", "@get crafting_table", "@get cake")),
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
            Map.entry("build", List.of("@build full", "@build full 12 here", "@build validate",
                    "@build repair", "@build farmland", "@build storage shulker_vault",
                    "@build workshop", "@build mobfarm",
                    "@build base import \"C:\\Users\\you\\.minecraft\\schematics\\test\\camp.litematic\"",
                    "@build schematic import \"C:\\builds\\watchtower.litematic\" watchtower")),
            Map.entry("home", List.of("@home", "@home farmland", "@home shulker_vault")),
            Map.entry("toolset", List.of("@toolset iron", "@toolset diamond")),
            Map.entry("store", List.of("@store diamond 3", "@store [diamond 3, gold_ingot 8]")),
            Map.entry("retrieve", List.of("@retrieve diamond 3")),
            Map.entry("shulker", List.of("@shulker list", "@shulker store diamond 3",
                    "@shulker retrieve stick 8", "@shulker auto on")),
            Map.entry("ai", List.of("@ai \"what should I do next?\"", "@ai \"why am I stuck?\"")),
            Map.entry("craftaudit", List.of("@craftaudit anvil", "@craftaudit all 25"))
    );

    private static final Map<String, String> CATEGORIES = Map.ofEntries(
            Map.entry("help", "Reference"),
            Map.entry("ui", "Reference"),
            Map.entry("status", "Reference"),
            Map.entry("coords", "Reference"),
            Map.entry("inventory", "Reference"),
            Map.entry("list", "Reference"),
            Map.entry("reload_settings", "Reference"),
            Map.entry("stop", "Control"),
            Map.entry("idle", "Control"),
            Map.entry("get", "Resources"),
            Map.entry("give", "Resources"),
            Map.entry("food", "Resources"),
            Map.entry("meat", "Resources"),
            Map.entry("toolset", "Crafting"),
            Map.entry("craftaudit", "Development"),
            Map.entry("test", "Development"),
            Map.entry("deposit", "Storage"),
            Map.entry("stash", "Storage"),
            Map.entry("store", "Storage"),
            Map.entry("retrieve", "Storage"),
            Map.entry("shulker", "Storage"),
            Map.entry("goto", "Navigation"),
            Map.entry("follow", "Navigation"),
            Map.entry("home", "Navigation"),
            Map.entry("locate_structure", "Navigation"),
            Map.entry("camp", "Base"),
            Map.entry("build", "Base"),
            Map.entry("player", "Autonomy"),
            Map.entry("ai", "Autonomy"),
            Map.entry("stacked", "PvP"),
            Map.entry("pvp", "PvP"),
            Map.entry("punk", "PvP"),
            Map.entry("hero", "Combat"),
            Map.entry("selfcare", "Survival"),
            Map.entry("gamer", "Game completion"),
            Map.entry("marvion", "Game completion"),
            Map.entry("coverwithsand", "Nether"),
            Map.entry("coverwithblocks", "Nether"),
            Map.entry("gamma", "Client")
    );

    private CommandDocumentation() {}

    public static List<String> examplesFor(String name) {
        return EXAMPLES.getOrDefault(name, List.of("@" + name));
    }

    public static String categoryFor(String name) {
        return CATEGORIES.getOrDefault(name, "Other");
    }

    public static String detailsFor(String name, String fallback) {
        return switch (name) {
            case "help" -> "Prints either the full command reference or a focused page for one command, including inputs and runnable examples.";
            case "ui" -> "Opens the Belfegor control panel. Use this when the C key is unavailable or another client mod has captured the keybind.";
            case "get" -> "Obtains the requested catalogued item and quantity using gathering, crafting, smelting, containers, or carried shulkers.";
            case "give" -> "Gets an item and delivers it to a named player when possible.";
            case "follow" -> "Follows the named player, or the command issuer when no explicit target is provided.";
            case "equip" -> "Equips one item or a list of armor/tool items from inventory, storage, or newly crafted resources.";
            case "goto" -> "Travels to XYZ, XZ, or Y coordinates. A dimension may be appended when cross-dimensional travel is required.";
            case "coords" -> "Prints Belfegor's current position and dimension.";
            case "status" -> "Prints the current task, active chain, pathing state, and useful debug context.";
            case "inventory" -> "Prints inventory contents or the count of a specific item.";
            case "list" -> "Lists known obtainable items from the task catalogue.";
            case "stop" -> "Cancels the current task/macro/automation lane. The global + key is the emergency abort equivalent while tasks are running.";
            case "idle" -> "Clears active automation and leaves the bot standing still.";
            case "food" -> "Collects the requested amount of edible food using known gathering/crafting/smelting paths.";
            case "meat" -> "Collects the requested amount of meat, including hunting and cooking where supported.";
            case "deposit", "store", "stash" -> "Moves requested inventory items into storage. Item lists use item count or [item count, item count].";
            case "retrieve" -> "Retrieves requested item quantities from known nearby storage.";
            case "shulker" -> "Catalogs, stores to, retrieves from, and auto-sorts carried shulker boxes. Shulkers are treated as remembered sub-inventories but are never nested inside other shulkers.";
            case "stacked" -> "Sequentially gathers the complete configured PvP equipment and supply loadout.";
            case "pvp" -> "Runs the advanced PvP loop: gear up, hunt a target, heal/restock, and repeat until stopped.";
            case "punk" -> "Harasses or attacks a named player with the older PvP behavior. Prefer @pvp for the newer loop.";
            case "hero" -> "Prioritizes nearby hostile mob defense.";
            case "selfcare" -> "Experimental survival helper for food, safety, and basic recovery. Still treated as unfinished.";
            case "player" -> "Starts autonomous player mode. Belfegor establishes a remembered base, builds a core camp, expands rooms over time, manages shulkers, gathers resources, practices crafts, upgrades tools, and can consult the local llama.cpp advisor without interrupting active tasks.";
            case "camp" -> "Sets the current position as the remembered home base and builds the core expandable campsite. Run this before @build when you want room expansions connected to a deliberate camp.";
            case "build" -> "Expands the remembered base with a connected room, runs @build full to build the complete modular base, imports external .litematic/.json schematics with @build base import \"file\", or runs @build validate/@build repair to inspect and fix incomplete remembered rooms. Imported schematics are copied into belfegor/schematics/imported, parsed into Belfegor's internal blueprint, assigned a staging chest, resource-counted, built, and remembered for later navigation/repair. Placement is footprint-aware to avoid overlapping rooms. Full mode builds core campsite, storage, workshop, hydrated crop farmland, roofed mob-farm room, repair validation, and route validation.";
            case "home" -> "Routes to the remembered home base or to a named room/module center created by @player or @build. Examples: @home, @home farmland, @home shulker_vault.";
            case "toolset" -> "Crafts one pickaxe, axe, shovel, sword, and hoe at the requested material tier.";
            case "locate_structure" -> "Uses Minecraft structure location support to find a generated structure, then reports or navigates according to the command behavior.";
            case "coverwithsand" -> "Nether utility that covers dangerous lava using sand-style falling blocks.";
            case "coverwithblocks" -> "Nether utility that covers dangerous lava using available solid blocks.";
            case "gamma" -> "Sets client brightness/gamma for visibility during automation.";
            case "gamer" -> "Runs the legacy beat-the-game route.";
            case "marvion" -> "Runs the Marvion beat-the-game route variant.";
            case "ai" -> "Asks the local llama.cpp advisor for a chat response or high-level command suggestion using current context, command docs, inventory, shulker memory, goal, and action log. Defaults to llmLlamaModelPath=belfegor/models/Qwen3-1.7B-Q4_K_M.gguf when llmAdvisorEnabled is true.";
            case "craftaudit" -> "Developer-only offline recipe audit. Uses bundled recipe data, /give @s leaf resources, crafts each target through Belfegor, stores outputs, and writes belfegor/craft_audit_*.log. Requires cheats/op.";
            case "test" -> "Developer/testing command for targeted task harnesses. Use only in test worlds.";
            case "reload_settings" -> "Reloads settings, butler lists, and related runtime configuration from disk without restarting the client.";
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
            result.append("Category: ").append(categoryFor(command.getName())).append("\n\n");
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

