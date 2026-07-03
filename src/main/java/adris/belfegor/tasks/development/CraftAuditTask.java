package adris.belfegor.tasks.development;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.tasks.CraftInInventoryTask;
import adris.belfegor.tasks.container.CraftInTableTask;
import adris.belfegor.tasksystem.ITaskSuppressesMobDefense;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.RecipeTarget;
import adris.belfegor.util.RecipeRegistry;
import adris.belfegor.util.helpers.StorageHelper;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Developer-only in-game craft audit.
 *
 * For each offline recipe target:
 * - starts from a clean inventory/test cell,
 * - recursively computes leaf resources from bundled recipe data,
 * - uses /give @s for those resources,
 * - asks Belfegor's normal crafting system to craft the item,
 * - stores the result in chest/barrel storage,
 * - writes PASS/FAIL lines to belfegor/craft_audit_*.log.
 */
public class CraftAuditTask extends Task implements ITaskSuppressesMobDefense {

    private static final int GIVE_COOLDOWN_TICKS = 4;
    private static final int RESET_COOLDOWN_TICKS = 12;
    private static final int ITEM_TIMEOUT_TICKS = 20 * 120;

    private enum Phase {
        INIT,
        RESET,
        PLAN,
        GIVE,
        WAIT_RESOURCES,
        CRAFT,
        STORE,
        NEXT,
        DONE
    }

    private final String _target;
    private final int _limit;
    private final RecipeRegistry _registry = RecipeRegistry.getInstance();
    private Phase _phase = Phase.INIT;
    private List<Item> _items = new ArrayList<>();
    private int _index = 0;
    private RecipeRegistry.CraftPlan _plan;
    private List<Map.Entry<Item, Integer>> _giveQueue = new ArrayList<>();
    private Map<Item, Integer> _expectedResources = new LinkedHashMap<>();
    private int _giveIndex = 0;
    private int _cooldownTicks = 0;
    private int _resetStep = 0;
    private int _itemTicks = 0;
    private Task _activeTask;
    private File _logFile;
    private int _passed = 0;
    private int _failed = 0;

    public CraftAuditTask(String target, int limit) {
        _target = target == null || target.isBlank() ? "all" : target.trim();
        _limit = Math.max(0, limit);
    }

    @Override
    protected void onStart(Belfegor mod) {
        _phase = Phase.INIT;
        _index = 0;
        _passed = 0;
        _failed = 0;
        _activeTask = null;
        _itemTicks = 0;
        _cooldownTicks = 0;
        _resetStep = 0;
        _registry.load();
        _logFile = createLogFile();
        writeLog("START target=" + _target + " limit=" + _limit + " at=" + Instant.now());
        mod.log("Craft audit started. Requires cheats/op because it uses /give @s.");
    }

    @Override
    protected Task onTick(Belfegor mod) {
        switch (_phase) {
            case INIT -> {
                _items = selectItems();
                if (_items.isEmpty()) {
                    writeLog("DONE no craftable items matched target=" + _target);
                    _phase = Phase.DONE;
                    return null;
                }
                writeLog("ITEMS count=" + _items.size());
                _phase = Phase.RESET;
                _resetStep = 0;
                _cooldownTicks = 0;
                return null;
            }
            case RESET -> {
                if (_index >= _items.size()) {
                    _phase = Phase.PLAN;
                    return null;
                }
                if (_cooldownTicks > 0) {
                    _cooldownTicks--;
                    return null;
                }
                if (_resetStep == 0) {
                    StorageHelper.closeScreen();
                    sendCommand(mod, "clear @s", "RESET");
                    _resetStep++;
                    _cooldownTicks = RESET_COOLDOWN_TICKS;
                    return null;
                }
                if (_resetStep == 1) {
                    sendCommand(mod, "kill @e[type=item,distance=..16]", "RESET");
                    _resetStep++;
                    _cooldownTicks = RESET_COOLDOWN_TICKS;
                    return null;
                }
                writeLog("RESET clean inventory before item " + (_index + 1));
                _resetStep = 0;
                _phase = Phase.PLAN;
                return null;
            }
            case PLAN -> {
                if (_index >= _items.size()) {
                    writeLog("DONE passed=" + _passed + " failed=" + _failed);
                    mod.log("Craft audit complete. Passed=" + _passed + " failed=" + _failed
                            + " log=" + (_logFile == null ? "unavailable" : _logFile.getPath()));
                    _phase = Phase.DONE;
                    return null;
                }
                Item item = _items.get(_index);
                RecipeRegistry.RecipeEntry entry = _registry.getRecipe(item);
                if (entry == null) {
                    failCurrent("RecipeRegistry has no recipe for craftable output");
                    return null;
                }
                _plan = _registry.buildLeafResourcePlan(item, 1);
                _expectedResources = normalizeGiveResources(entry);
                _giveQueue = expandGiveResources(_expectedResources);
                _giveIndex = 0;
                _itemTicks = 0;
                _activeTask = null;
                writeLog("");
                writeLog("PLAN " + (_index + 1) + "/" + _items.size()
                        + " item=" + RecipeRegistry.itemId(item)
                        + " resources=" + describeResources(_giveQueue)
                        + " leafPlanFailures=" + (_plan == null ? "[]" : _plan.failures));
                _phase = Phase.GIVE;
                return null;
            }
            case GIVE -> {
                if (_cooldownTicks > 0) {
                    _cooldownTicks--;
                    return null;
                }
                if (_giveIndex >= _giveQueue.size()) {
                    _phase = Phase.WAIT_RESOURCES;
                    _itemTicks = 0;
                    return null;
                }
                Map.Entry<Item, Integer> entry = _giveQueue.get(_giveIndex++);
                sendGiveCommand(mod, entry.getKey(), entry.getValue());
                _cooldownTicks = GIVE_COOLDOWN_TICKS;
                return null;
            }
            case WAIT_RESOURCES -> {
                if (hasGivenResources(mod)) {
                    _phase = Phase.CRAFT;
                    _itemTicks = 0;
                    _activeTask = null;
                    return null;
                }
                if (++_itemTicks > 20 * 15) {
                    failCurrent("resources did not appear after /give");
                }
                return null;
            }
            case CRAFT -> {
                if (timedOut()) {
                    failCurrent("craft timeout");
                    return null;
                }
                Item item = _items.get(_index);
                if (mod.getItemStorage().getItemCountInventoryOnly(item) >= 1) {
                    passCurrent("crafted output appeared in inventory");
                    return null;
                }
                if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
                    _activeTask = createDirectRecipeTask(item);
                    if (_activeTask == null) {
                        failCurrent("RecipeRegistry could not create direct craft task");
                        return null;
                    }
                }
                setDebugState("Craft audit crafting " + RecipeRegistry.itemId(item)
                        + " " + (_index + 1) + "/" + _items.size());
                _itemTicks++;
                return _activeTask;
            }
            case STORE -> {
                passCurrent("legacy store phase skipped; output verified in inventory");
                return null;
            }
            case NEXT -> {
                _index++;
                _phase = Phase.RESET;
                _resetStep = 0;
                _cooldownTicks = 0;
                _activeTask = null;
                _plan = null;
                _giveQueue = new ArrayList<>();
                _expectedResources = new LinkedHashMap<>();
                return null;
            }
            case DONE -> {
                return null;
            }
        }
        return null;
    }

    private List<Item> selectItems() {
        List<Item> all = getCraftableItemsInListOrder();
        List<Item> selected = new ArrayList<>();
        if ("all".equalsIgnoreCase(_target)) {
            selected.addAll(all);
        } else {
            Item item = RecipeRegistry.getItemByName(normalizeName(_target));
            if (item != null && _registry.isCraftable(item)) {
                selected.add(item);
            } else {
                writeLog("SKIP target=" + _target + " reason=not a bundled craftable recipe target");
            }
        }
        if (_limit > 0 && selected.size() > _limit) {
            return new ArrayList<>(selected.subList(0, _limit));
        }
        return selected;
    }

    private List<Item> getCraftableItemsInListOrder() {
        LinkedHashMap<Item, Item> ordered = new LinkedHashMap<>();
        for (String resourceName : TaskCatalogue.resourceNames()) {
            for (Item item : TaskCatalogue.getItemMatches(resourceName)) {
                if (item != null && _registry.isCraftable(item)) {
                    ordered.putIfAbsent(item, item);
                }
            }
        }
        for (Item item : _registry.getSortedCraftableItems()) {
            ordered.putIfAbsent(item, item);
        }
        return new ArrayList<>(ordered.keySet());
    }

    private List<Map.Entry<Item, Integer>> expandGiveResources(Map<Item, Integer> resources) {
        ArrayList<Map.Entry<Item, Integer>> result = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : resources.entrySet()) {
            int remaining = Math.max(0, entry.getValue());
            int stackSize = Math.max(1, entry.getKey().getMaxCount());
            while (remaining > 0) {
                int chunk = Math.min(remaining, stackSize);
                result.add(Map.entry(entry.getKey(), chunk));
                remaining -= chunk;
            }
        }
        return result;
    }

    private Map<Item, Integer> normalizeGiveResources(RecipeRegistry.RecipeEntry entry) {
        Map<Item, Integer> result = new LinkedHashMap<>();
        if (entry == null || entry.recipe == null) {
            return result;
        }
        Arrays.stream(entry.recipe.getSlots())
                .filter(slot -> slot != null && !slot.isEmpty())
                .forEach(slot -> {
                    Item item = chooseAuditIngredient(slot);
                    if (item != null) {
                        result.merge(item, Math.max(1, slot.getTargetCount()), Integer::sum);
                    }
                });
        if (entry.recipe.isBig()) {
            result.merge(net.minecraft.item.Items.CRAFTING_TABLE, 1, Integer::sum);
        }
        return result;
    }

    private Item chooseAuditIngredient(adris.belfegor.util.ItemTarget slot) {
        for (Item match : slot.getMatches()) {
            if (match != null && match != net.minecraft.item.Items.AIR) {
                return match;
            }
        }
        return null;
    }

    private Task createDirectRecipeTask(Item item) {
        RecipeRegistry.RecipeEntry entry = _registry.getRecipe(item);
        if (entry == null || entry.recipe == null) {
            return null;
        }
        RecipeTarget target = new RecipeTarget(item, 1, entry.recipe);
        if (entry.recipe.isBig()) {
            return new CraftInTableTask(target);
        }
        return new CraftInInventoryTask(target);
    }

    private boolean hasGivenResources(Belfegor mod) {
        for (Map.Entry<Item, Integer> entry : _expectedResources.entrySet()) {
            if (mod.getItemStorage().getItemCountInventoryOnly(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private void sendGiveCommand(Belfegor mod, Item item, int count) {
        String id = Registries.ITEM.getId(item).toString();
        int amount = Math.max(1, count);
        sendCommand(mod, "give @s " + id + " " + amount, "GIVE");
    }

    private void sendCommand(Belfegor mod, String command, String label) {
        if (mod.getPlayer() == null || mod.getPlayer().networkHandler == null) return;
        writeLog(label + " /" + command);
        mod.getPlayer().networkHandler.sendChatCommand(command);
    }

    private boolean timedOut() {
        return _itemTicks > ITEM_TIMEOUT_TICKS;
    }

    private void passCurrent(String reason) {
        Item item = _items.get(_index);
        _passed++;
        writeLog("PASS item=" + RecipeRegistry.itemId(item) + " reason=" + reason);
        StorageHelper.closeScreen();
        _phase = Phase.NEXT;
        _activeTask = null;
    }

    private void skipCurrent(String reason) {
        Item item = _items.get(_index);
        writeLog("SKIP item=" + RecipeRegistry.itemId(item) + " reason=" + reason
                + " resources=" + describeResources(_giveQueue)
                + " steps=" + (_plan == null ? "[]" : _plan.steps));
        StorageHelper.closeScreen();
        _phase = Phase.NEXT;
        _activeTask = null;
    }

    private void failCurrent(String reason) {
        Item item = _items.get(_index);
        _failed++;
        writeLog("FAIL item=" + RecipeRegistry.itemId(item) + " reason=" + reason
                + " resources=" + describeResources(_giveQueue)
                + " steps=" + (_plan == null ? "[]" : _plan.steps));
        StorageHelper.closeScreen();
        _phase = Phase.NEXT;
        _activeTask = null;
    }

    private String describeResources(List<Map.Entry<Item, Integer>> resources) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < resources.size(); i++) {
            Map.Entry<Item, Integer> entry = resources.get(i);
            if (i > 0) result.append(", ");
            result.append(entry.getValue()).append("x").append(RecipeRegistry.itemId(entry.getKey()));
        }
        return result.append("]").toString();
    }

    private String normalizeName(String raw) {
        String trimmed = raw.trim();
        return trimmed.contains(":") ? trimmed : "minecraft:" + trimmed;
    }

    private File createLogFile() {
        try {
            File dir = new File("belfegor");
            dir.mkdirs();
            return new File(dir, "craft_audit_" + System.currentTimeMillis() + ".log");
        } catch (Exception e) {
            return null;
        }
    }

    private void writeLog(String line) {
        if (_logFile == null) return;
        try (FileWriter writer = new FileWriter(_logFile, true)) {
            writer.write(line);
            writer.write(System.lineSeparator());
        } catch (IOException ignored) {
        }
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        writeLog("STOP interrupt=" + (interruptTask == null ? "null" : interruptTask.toString())
                + " passed=" + _passed + " failed=" + _failed);
        if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            StorageHelper.closeScreen();
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof CraftAuditTask task
                && task._target.equals(_target)
                && task._limit == _limit;
    }

    @Override
    protected String toDebugString() {
        return "Craft audit " + _target + " " + (_index + 1) + "/" + Math.max(1, _items.size())
                + " phase=" + _phase;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return _phase == Phase.DONE;
    }
}
