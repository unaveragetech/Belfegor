package adris.altoclef.tasks.development;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.container.StoreInAnyContainerTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeRegistry;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Developer-only in-game craft audit.
 *
 * For each offline recipe target:
 * - recursively computes leaf resources from bundled recipe data,
 * - uses /give @s for those resources,
 * - asks Belfegor's normal crafting system to craft the item,
 * - stores the result in chest/barrel/shulker storage,
 * - writes PASS/FAIL lines to belfegor/craft_audit_*.log.
 */
public class CraftAuditTask extends Task {

    private static final int GIVE_COOLDOWN_TICKS = 4;
    private static final int ITEM_TIMEOUT_TICKS = 20 * 120;

    private enum Phase {
        INIT,
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
    private int _giveIndex = 0;
    private int _cooldownTicks = 0;
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
    protected void onStart(AltoClef mod) {
        _phase = Phase.INIT;
        _index = 0;
        _passed = 0;
        _failed = 0;
        _activeTask = null;
        _itemTicks = 0;
        _cooldownTicks = 0;
        _registry.load();
        _logFile = createLogFile();
        writeLog("START target=" + _target + " limit=" + _limit + " at=" + Instant.now());
        mod.log("Craft audit started. Requires cheats/op because it uses /give @s.");
    }

    @Override
    protected Task onTick(AltoClef mod) {
        switch (_phase) {
            case INIT -> {
                _items = selectItems();
                if (_items.isEmpty()) {
                    writeLog("DONE no craftable items matched target=" + _target);
                    _phase = Phase.DONE;
                    return null;
                }
                writeLog("ITEMS count=" + _items.size());
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
                _plan = _registry.buildLeafResourcePlan(item, 1);
                _giveQueue = new ArrayList<>(normalizeGiveResources(_plan).entrySet());
                _giveIndex = 0;
                _itemTicks = 0;
                _activeTask = null;
                writeLog("");
                writeLog("PLAN " + (_index + 1) + "/" + _items.size()
                        + " item=" + RecipeRegistry.itemId(item)
                        + " resources=" + describeResources(_giveQueue)
                        + " failures=" + _plan.failures);
                if (!_plan.failures.isEmpty()) {
                    failCurrent("plan failures " + _plan.failures);
                    return null;
                }
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
                    _phase = Phase.STORE;
                    _activeTask = null;
                    return null;
                }
                if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
                    _activeTask = TaskCatalogue.getItemTask(item, 1);
                    if (_activeTask == null) {
                        failCurrent("TaskCatalogue could not create task");
                        return null;
                    }
                }
                setDebugState("Craft audit crafting " + RecipeRegistry.itemId(item)
                        + " " + (_index + 1) + "/" + _items.size());
                _itemTicks++;
                return _activeTask;
            }
            case STORE -> {
                if (timedOut()) {
                    failCurrent("store timeout");
                    return null;
                }
                Item item = _items.get(_index);
                int count = mod.getItemStorage().getItemCountInventoryOnly(item);
                if (count <= 0) {
                    passCurrent("crafted and no longer in inventory; assumed stored or consumed");
                    return null;
                }
                if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
                    _activeTask = new StoreInAnyContainerTask(false, new ItemTarget(item, count));
                }
                setDebugState("Craft audit storing " + count + "x " + RecipeRegistry.itemId(item));
                _itemTicks++;
                Task store = _activeTask;
                if (store.isFinished(mod)) {
                    passCurrent("crafted and stored count=" + count);
                    return null;
                }
                return store;
            }
            case NEXT -> {
                _index++;
                _phase = Phase.PLAN;
                return null;
            }
            case DONE -> {
                return null;
            }
        }
        return null;
    }

    private List<Item> selectItems() {
        List<Item> all = _registry.getSortedCraftableItems();
        List<Item> selected = new ArrayList<>();
        if ("all".equalsIgnoreCase(_target)) {
            selected.addAll(all);
        } else {
            Item item = RecipeRegistry.getItemByName(normalizeName(_target));
            if (item != null && _registry.isCraftable(item)) {
                selected.add(item);
            }
        }
        if (_limit > 0 && selected.size() > _limit) {
            return new ArrayList<>(selected.subList(0, _limit));
        }
        return selected;
    }

    private Map<Item, Integer> normalizeGiveResources(RecipeRegistry.CraftPlan plan) {
        Map<Item, Integer> result = new LinkedHashMap<>(plan.leafResources);
        Item current = plan.targetItem;
        var recipe = _registry.getRecipe(current);
        if (recipe != null && recipe.recipe.isBig()) {
            result.merge(net.minecraft.item.Items.CRAFTING_TABLE, 1, Integer::sum);
        }
        result.merge(net.minecraft.item.Items.CHEST, 1, Integer::sum);
        return result;
    }

    private boolean hasGivenResources(AltoClef mod) {
        for (Map.Entry<Item, Integer> entry : _giveQueue) {
            if (mod.getItemStorage().getItemCountInventoryOnly(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private void sendGiveCommand(AltoClef mod, Item item, int count) {
        if (mod.getPlayer() == null || mod.getPlayer().networkHandler == null) return;
        String id = Registries.ITEM.getId(item).toString();
        int amount = Math.max(1, count);
        writeLog("GIVE /give @s " + id + " " + amount);
        mod.getPlayer().networkHandler.sendChatCommand("give @s " + id + " " + amount);
    }

    private boolean timedOut() {
        return _itemTicks > ITEM_TIMEOUT_TICKS;
    }

    private void passCurrent(String reason) {
        Item item = _items.get(_index);
        _passed++;
        writeLog("PASS item=" + RecipeRegistry.itemId(item) + " reason=" + reason);
        _phase = Phase.NEXT;
        _activeTask = null;
    }

    private void failCurrent(String reason) {
        Item item = _items.get(_index);
        _failed++;
        writeLog("FAIL item=" + RecipeRegistry.itemId(item) + " reason=" + reason
                + " resources=" + describeResources(_giveQueue)
                + " steps=" + (_plan == null ? "[]" : _plan.steps));
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
    protected void onStop(AltoClef mod, Task interruptTask) {
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
    public boolean isFinished(AltoClef mod) {
        return _phase == Phase.DONE;
    }
}
