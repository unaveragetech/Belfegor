package adris.belfegor.commandsystem;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            Map.entry("status", List.of("@status", "@status history", "@status history 10")),
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
            Map.entry("drop", List.of("@drop home")),
            Map.entry("baritone", List.of("@baritone proc", "@baritone help sel", "@baritone sel clear",
                    "@baritone surface", "@baritone forcecancel")),
            Map.entry("toolset", List.of("@toolset iron", "@toolset diamond")),
            Map.entry("armor", List.of("@armor iron", "@armor diamond", "@armor netherite")),
            Map.entry("equipment", List.of("@equipment iron", "@equipment diamond", "@equipment leather")),
            Map.entry("stockpile", List.of("@stockpile", "@stockpile stone starter",
                    "@stockpile cobblestone 512", "@stockpile iron_ingot 32 iron",
                    "@stockpile wood starter", "@stockpile stone build")),
            Map.entry("store", List.of("@store diamond 3", "@store [diamond 3, gold_ingot 8]")),
            Map.entry("retrieve", List.of("@retrieve diamond 3")),
            Map.entry("shulker", List.of("@shulker list", "@shulker store diamond 3",
                    "@shulker retrieve stick 8", "@shulker auto on")),
            Map.entry("ai", List.of("@ai \"what should I do next?\"", "@ai \"why am I stuck?\"")),
            Map.entry("craftaudit", List.of("@craftaudit screens", "@craftaudit anvil",
                    "@craftaudit all 25", "@craftaudit all"))
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
            Map.entry("armor", "Crafting"),
            Map.entry("equipment", "Crafting"),
            Map.entry("stockpile", "Base"),
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
            Map.entry("drop", "Control"),
            Map.entry("baritone", "Development"),
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
            case "ui" -> "Opens the same Belfegor control panel as pressing C. Useful from chat, macros, help examples, or when a keybind is inconvenient.";
            case "get" -> "Obtains the requested catalogued item and quantity using gathering, crafting, smelting, containers, or carried shulkers.";
            case "give" -> "Gets an item and delivers it to a named player when possible.";
            case "follow" -> "Follows the named player, or the command issuer when no explicit target is provided.";
            case "equip" -> "Equips one item or a list of armor/tool items from inventory, storage, or newly crafted resources.";
            case "goto" -> "Travels to XYZ, XZ, or Y coordinates. A dimension may be appended when cross-dimensional travel is required.";
            case "coords" -> "Prints Belfegor's current position and dimension.";
            case "status" -> "Prints the current task, active task chain, and last task-chain interruption snapshot when available. Use @status history or @status history <count> to review recent chain switches and diagnose oscillation loops where one chain repeatedly steals control from another.";
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
            case "camp" -> "Builds or repairs the core expandable campsite at the locked home. If no home exists, the current position becomes home. Once home exists, @camp will not move it; run @drop home first if you deliberately want a new camp. The camp records a two-wide doorway and places/clicks a bed inside.";
            case "build" -> "Expands the locked remembered base with a connected room, runs @build full to build the complete modular base, imports external .litematic/.json schematics with @build base import \"file\", or runs @build validate/@build repair to inspect and fix incomplete remembered rooms. Imported schematics are copied into belfegor/schematics/imported, parsed into Belfegor's internal blueprint, assigned a staging chest, resource-counted, built, and remembered for later navigation/repair. Placement is footprint-aware to avoid overlapping rooms. Full mode builds core campsite, storage, workshop, hydrated crop farmland, roofed mob-farm room, repair validation, and route validation. Existing home is never overwritten by build commands; use @drop home to reset.";
            case "home" -> "Routes to the locked remembered home base, remembered doorway, or a named room/module center created by @player or @build. Examples: @home, @home farmland, @home shulker_vault.";
            case "drop" -> "Clears the locked home/base/camp memory so the next @camp or @build full here can establish a new base deliberately. Example: @drop home.";
            case "baritone" -> "Controlled bridge to native Baritone diagnostics and area/build commands. Supported examples include @baritone proc, @baritone help sel, @baritone sel clear, @baritone surface, @baritone forcecancel, @baritone build <schematic> [x y z], and @baritone litematica. Belfegor's own construction tasks use native builder/selection APIs internally, while this command is for testing, recovery, and command reference.";
            case "toolset" -> "Crafts one pickaxe, axe, shovel, and sword at the requested material tier.";
            case "armor" -> "Crafts and equips a full armor set (helmet, chestplate, leggings, boots) for the requested material: leather, chainmail, iron, gold, diamond, or netherite. Already-owned or already-equipped pieces are skipped.";
            case "equipment" -> "Prepares a full loadout for the requested material: a complete tool set plus a full armor set, equipping the armor once crafted. wood/stone craft tools only, leather/chainmail craft armor only, and iron/gold/diamond/netherite craft both.";
            case "stockpile" -> "Returns to the locked remembered camp, ensures the storage-room chest exists, prepares a wood/stone/iron/diamond toolset through the same toolset task, gathers practical base resources, and deposits them into the camp storage chest. Use @stockpile stone starter for a small reserve, @stockpile stone build before larger @build work, or point at one resource such as @stockpile cobblestone 512 / @stockpile iron_ingot 32 iron.";
            case "locate_structure" -> "Uses Minecraft structure location support to find a generated structure, then reports or navigates according to the command behavior.";
            case "coverwithsand" -> "Nether utility that covers dangerous lava using sand-style falling blocks.";
            case "coverwithblocks" -> "Nether utility that covers dangerous lava using available solid blocks.";
            case "gamma" -> "Sets client brightness/gamma for visibility during automation.";
            case "gamer" -> "Runs the legacy beat-the-game route.";
            case "marvion" -> "Runs the Marvion beat-the-game route variant.";
            case "ai" -> "Asks the local llama.cpp advisor for a chat response or high-level command suggestion using current context, command docs, inventory, shulker memory, goal, and action log. Defaults to llmLlamaModelPath=belfegor/models/Qwen3-1.7B-Q4_K_M.gguf when llmAdvisorEnabled is true.";
            case "craftaudit" -> "Developer-only audit harness. Use @craftaudit screens to create and open every supported handled-screen fixture in order, verifying inventory, crafting table, chest/barrel, shulker, furnace, smoker, blast furnace, and brewing stand handlers. Use @craftaudit <item>, @craftaudit all <limit>, or @craftaudit all to expand bundled recipe data, /give @s leaf resources, craft each target through Belfegor's real task system, store outputs, and write belfegor/craft_audit_*.log or belfegor/screen_audit_*.log. Requires cheats/op.";
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

    /**
     * Exports the command catalogue as a JSON "tool" list, MCP-style, so the
     * LLM advisor can look up command names, arguments, descriptions, and
     * examples at runtime instead of guessing.
     */
    public static String exportJson(Collection<Command> commands, String prefix) {
        String cmdPrefix = prefix == null ? "@" : prefix;
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Command command : commands) {
            if (command == null) continue;
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", cmdPrefix + command.getName());
            tool.put("category", categoryFor(command.getName()));
            tool.put("description", command.getDetailedDescription());
            List<Map<String, Object>> args = new ArrayList<>();
            for (ArgBase arg : command.getArguments()) {
                Map<String, Object> argMap = new LinkedHashMap<>();
                argMap.put("name", arg.getName());
                argMap.put("type", arg.getTypeName());
                argMap.put("required", !arg.hasDefault());
                args.add(argMap);
            }
            tool.put("arguments", args);
            tool.put("examples", command.getExamples());
            tools.add(tool);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tools", tools);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(root);
    }
}

