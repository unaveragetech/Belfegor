package adris.belfegor.commands;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.commandsystem.*;
import adris.belfegor.memory.ShulkerMemory;
import adris.belfegor.tasks.container.ShulkerInteractionTask;
import adris.belfegor.ui.MessagePriority;
import adris.belfegor.util.ItemTarget;
import net.minecraft.item.Item;

import java.util.Arrays;
import java.util.List;

public class ShulkerCommand extends Command {

    public ShulkerCommand() throws CommandException {
        super("shulker", "Use carried shulker boxes as managed sub-inventories",
                new Arg<>(String.class, "action and values").asArray());
    }

    @Override
    protected void call(Belfegor mod, ArgParser parser) throws CommandException {
        String action = parser.get(String.class);
        if (action == null) {
            mod.log("Usage: @shulker list|find <item>|store <items>|retrieve <items>");
            finish();
            return;
        }
        String[] remaining = parser.getArgUnits();
        // Skip the first arg (the subcommand name)
        int offset = 1;
        if (remaining.length > 0 && remaining[0].equals(action)) {
            offset = 1;
        }

        switch (action.toLowerCase()) {
            case "list":
                handleList(mod);
                break;
            case "find":
                handleFind(mod, remaining, offset);
                break;
            case "store":
                handleStore(mod, remaining, offset);
                break;
            case "retrieve":
                handleRetrieve(mod, remaining, offset);
                break;
            case "auto":
                handleAuto(mod, remaining, offset);
                break;
            default:
                mod.log("Unknown shulker action: " + action
                        + ". Use list, find, store, retrieve, or auto.");
                finish();
        }
    }

    private void handleAuto(Belfegor mod, String[] args, int offset) {
        String value = offset < args.length ? args[offset].toLowerCase() : "status";
        switch (value) {
            case "on", "enable", "enabled" -> {
                mod.getModSettings().setAutoShulkerMode(true);
                adris.belfegor.Settings.save(mod.getModSettings());
                mod.log("Auto shulker mode enabled.");
            }
            case "off", "disable", "disabled" -> {
                mod.getModSettings().setAutoShulkerMode(false);
                adris.belfegor.Settings.save(mod.getModSettings());
                mod.log("Auto shulker mode disabled.");
            }
            case "run", "now" -> {
                ItemTarget[] targets = ShulkerInteractionTask.getAutoStoreTargets(mod);
                if (targets.length == 0) {
                    mod.log("No non-tool inventory items are available to sort.");
                    finish();
                    return;
                }
                mod.runUserTask(new ShulkerInteractionTask(
                        ShulkerInteractionTask.Mode.STORE, targets), this::finish);
                return;
            }
            case "status" -> mod.log("Auto shulker mode is "
                    + (mod.getModSettings().shouldAutoShulkerMode() ? "enabled" : "disabled")
                    + ". Sort mode=" + mod.getModSettings().getAutoShulkerSortMode()
                    + ", timer=" + mod.getModSettings().getAutoShulkerTimerSeconds() + "s"
                    + ", detection threshold=" + mod.getModSettings().getAutoShulkerInventoryThreshold() + "%.");
            case "timer" -> {
                mod.getModSettings().setAutoShulkerSortMode("timer");
                adris.belfegor.Settings.save(mod.getModSettings());
                mod.log("Auto shulker sort mode set to timer.");
            }
            case "detection", "detect" -> {
                mod.getModSettings().setAutoShulkerSortMode("detection");
                adris.belfegor.Settings.save(mod.getModSettings());
                mod.log("Auto shulker sort mode set to detection.");
            }
            default -> mod.log("Usage: @shulker auto on|off|status|run|timer|detection");
        }
        finish();
    }

    @Override
    public java.util.List<String> getExamples() {
        return java.util.List.of(
                "@shulker list",
                "@shulker find diamond",
                "@shulker store diamond 3",
                "@shulker store [diamond 3, stick 16]",
                "@shulker retrieve stick 8",
                "@shulker auto on",
                "@shulker auto detection",
                "@shulker auto timer",
                "@shulker auto run"
        );
    }

    @Override
    public String getDetailedDescription() {
        return "Selects a carried shulker, remembers its inventory slot, places it nearby, "
                + "opens it, moves the exact requested quantities, catalogs and verifies its "
                + "contents, closes and mines it, picks it up, then restores it to its original "
                + "inventory slot when that slot is available. Auto mode sorts eligible ordinary "
                + "items while excluding shulkers, tools, weapons, armor, shields, bows, and damaged gear. "
                + "Use timer mode for periodic idle deposits or detection mode to sort as inventory fills.";
    }

    private void handleList(Belfegor mod) {
        ShulkerMemory mem = ShulkerMemory.getInstance();
        mod.log("Auto shulker mode: "
                + (mod.getModSettings().shouldAutoShulkerMode() ? "enabled" : "disabled"),
                MessagePriority.OPTIONAL);
        if (mem.isEmpty()) {
            mod.log("No shulker boxes remembered.");
            finish();
            return;
        }
        mod.log("Remembered shulker boxes: " + mem.size(), MessagePriority.OPTIONAL);
        for (ShulkerMemory.ShulkerEntry entry : mem.getAllShulkers()) {
            StringBuilder sb = new StringBuilder();
            if ("inventory".equals(entry.location)) {
                sb.append("  inventory slot ").append(entry.inventorySlot)
                        .append(" (").append(entry.shulkerItem).append("): ");
            } else {
                sb.append("  ").append(entry.x).append(", ").append(entry.y).append(", ").append(entry.z).append(": ");
            }
            if (entry.contents.isEmpty()) {
                sb.append("(empty)");
            } else {
                for (ShulkerMemory.ShulkerItem si : entry.contents) {
                    sb.append(si.itemName).append(" x").append(si.count).append(", ");
                }
            }
            mod.log(sb.toString(), MessagePriority.OPTIONAL);
        }
        finish();
    }

    private void handleFind(Belfegor mod, String[] args, int offset) {
        // Parse the item name from remaining args
        if (offset >= args.length) {
            mod.log("Usage: @shulker find <item>");
            finish();
            return;
        }
        String itemName = String.join(" ", Arrays.copyOfRange(args, offset, args.length));
        // Try to match via TaskCatalogue
        Item[] matches = TaskCatalogue.getItemMatches(itemName);
        if (matches == null || matches.length == 0) {
            mod.log("Item \"" + itemName + "\" is not recognized.");
            finish();
            return;
        }

        ShulkerMemory mem = ShulkerMemory.getInstance();
        List<ShulkerMemory.ShulkerEntry> found = mem.findItem(matches[0]);
        if (found.isEmpty()) {
            mod.log("No remembered shulker contains " + itemName + ".");
            finish();
            return;
        }
        mod.log("Found " + itemName + " in " + found.size() + " shulker(s):", MessagePriority.OPTIONAL);
        for (ShulkerMemory.ShulkerEntry entry : found) {
            int count = entry.getItemCount(adris.belfegor.util.helpers.ItemHelper.stripItemName(matches[0]));
            mod.log("  " + entry.x + ", " + entry.y + ", " + entry.z + ": " + count + "x " + itemName, MessagePriority.OPTIONAL);
        }
        finish();
    }

    private void handleStore(Belfegor mod, String[] args, int offset) {
        // Parse items from remaining args
        String remainder = String.join(" ", Arrays.copyOfRange(args, offset, args.length));
        try {
            ItemList itemList = ItemList.parseRemainder(remainder);
            if (itemList == null || itemList.items == null || itemList.items.length == 0) {
                mod.log("You must specify at least one item to store.");
                finish();
                return;
            }
            mod.runUserTask(new ShulkerInteractionTask(ShulkerInteractionTask.Mode.STORE, itemList.items), this::finish);
        } catch (CommandException e) {
            mod.log("Failed to parse items: " + e.getMessage());
            finish();
        }
    }

    private void handleRetrieve(Belfegor mod, String[] args, int offset) {
        String remainder = String.join(" ", Arrays.copyOfRange(args, offset, args.length));
        try {
            ItemList itemList = ItemList.parseRemainder(remainder);
            if (itemList == null || itemList.items == null || itemList.items.length == 0) {
                mod.log("You must specify at least one item to retrieve.");
                finish();
                return;
            }
            mod.runUserTask(new ShulkerInteractionTask(ShulkerInteractionTask.Mode.RETRIEVE, itemList.items), this::finish);
        } catch (CommandException e) {
            mod.log("Failed to parse items: " + e.getMessage());
            finish();
        }
    }
}
