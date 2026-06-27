package adris.altoclef.commandsystem;

import java.util.List;
import java.util.Map;

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
            Map.entry("toolset", List.of("@toolset iron", "@toolset diamond")),
            Map.entry("store", List.of("@store diamond 3", "@store [diamond 3, gold_ingot 8]")),
            Map.entry("retrieve", List.of("@retrieve diamond 3")),
            Map.entry("shulker", List.of("@shulker list", "@shulker store diamond 3",
                    "@shulker retrieve stick 8", "@shulker auto on")),
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
            case "toolset" -> "Crafts one pickaxe, axe, shovel, sword, and hoe at the requested material tier.";
            case "craftaudit" -> "Developer-only offline recipe audit. Uses bundled recipe data, /give @s leaf resources, crafts each target through Belfegor, stores outputs, and writes belfegor/craft_audit_*.log. Requires cheats/op.";
            default -> fallback;
        };
    }
}
