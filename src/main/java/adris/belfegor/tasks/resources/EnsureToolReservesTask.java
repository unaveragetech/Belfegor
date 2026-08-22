package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.memory.BaseStorageMemory;
import adris.belfegor.memory.ErrandMemory;
import adris.belfegor.tasks.container.StoreInContainerTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Ensures the bot always has a complete carried tool set AND a backup set of
 * the same tools stored at the base storage network, like a player keeping a
 * spare pickaxe/axe/shovel/sword/hoe in the chest before leaving home.
 */
public class EnsureToolReservesTask extends Task {

    private enum Phase {
        RESOLVE,
        CARRIED_SET,
        ENSURE_SPARES,
        STORE_SPARES,
        DONE
    }

    private final BlockPos _home;
    private Phase _phase = Phase.RESOLVE;
    private Task _activeTask;
    private ToolSetTask.Tier _tier;
    private final List<Item> _missingBackups = new ArrayList<>();
    private int _backupIndex;
    private Item _currentTool;
    private boolean _pendingStore;
    private BlockPos _storageChest;

    public EnsureToolReservesTask(BlockPos home) {
        _home = home;
    }

    @Override
    protected void onStart(Belfegor mod) {
        _phase = Phase.RESOLVE;
        _activeTask = null;
        _missingBackups.clear();
        _backupIndex = 0;
        _currentTool = null;
        _pendingStore = false;
        _storageChest = null;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        return switch (_phase) {
            case RESOLVE -> {
                _tier = ToolSetTask.currentTier(mod);
                _phase = Phase.CARRIED_SET;
                yield null;
            }
            case CARRIED_SET -> {
                if (!ToolSetTask.hasFullSet(mod, _tier)) {
                    if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
                        _activeTask = new ToolSetTask(_tier);
                    }
                    if (!_activeTask.isFinished(mod)) {
                        setDebugState("Ensuring carried " + _tier.name().toLowerCase() + " tool set");
                        yield _activeTask;
                    }
                    _activeTask = null;
                }
                _phase = Phase.ENSURE_SPARES;
                yield null;
            }
            case ENSURE_SPARES -> {
                _missingBackups.clear();
                String dimension = WorldHelper.getCurrentDimension().name();
                for (Item tool : ToolSetTask.tierTools(_tier)) {
                    if (tool == null) continue;
                    int carried = mod.getItemStorage().getItemCountInventoryOnly(tool);
                    int stored = BaseStorageMemory.getInstance().knownCountAt(_home, dimension, tool);
                    if (carried + stored < 2) _missingBackups.add(tool);
                }
                if (_missingBackups.isEmpty()) {
                    _phase = Phase.DONE;
                    yield null;
                }
                _backupIndex = 0;
                _phase = Phase.STORE_SPARES;
                yield null;
            }
            case STORE_SPARES -> storeSpares(mod);
            case DONE -> null;
        };
    }

    private Task storeSpares(Belfegor mod) {
        if (_activeTask != null) {
            if (!_activeTask.stopped() && !_activeTask.isFinished(mod)) {
                return _activeTask;
            }
            if (_pendingStore && _storageChest != null && _currentTool != null) {
                ErrandMemory.getInstance().recordStored(_home, _storageChest,
                        WorldHelper.getCurrentDimension().name(), "tool_backup",
                        new ItemTarget(_currentTool, 1));
                ErrandMemory.getInstance().save();
            }
            _activeTask = null;
            _pendingStore = false;
            _storageChest = null;
            _currentTool = null;
            _backupIndex++;
            return null;
        }

        String dimension = WorldHelper.getCurrentDimension().name();
        while (_backupIndex < _missingBackups.size()) {
            Item tool = _missingBackups.get(_backupIndex);
            int carried = mod.getItemStorage().getItemCountInventoryOnly(tool);
            int stored = BaseStorageMemory.getInstance().knownCountAt(_home, dimension, tool);
            if (stored >= 1 && carried + stored >= 2) {
                _backupIndex++;
                continue;
            }
            String toolName = net.minecraft.registry.Registries.ITEM.getId(tool).getPath();
            if (carried >= 2) {
                BlockPos chest = resolveStorageChest(mod, tool);
                if (chest != null) {
                    _currentTool = tool;
                    _pendingStore = true;
                    _storageChest = chest;
                    setDebugState("Storing backup " + toolName + " at " + chest.toShortString());
                    _activeTask = new StoreInContainerTask(chest, false, new ItemTarget(tool, 1));
                    return _activeTask;
                }
                // No storage reachable: keep the spare in hand.
                _backupIndex++;
                continue;
            }
            _currentTool = tool;
            _pendingStore = false;
            setDebugState("Crafting backup " + toolName);
            _activeTask = TaskCatalogue.getItemTask(toolName, 1);
            return _activeTask;
        }
        _phase = Phase.DONE;
        return null;
    }

    private BlockPos resolveStorageChest(Belfegor mod, Item tool) {
        String dimension = WorldHelper.getCurrentDimension().name();
        return BaseStorageMemory.getInstance()
                .preferredChestForRole(mod, _home, dimension, "armory", new ItemTarget(tool, 1))
                .or(() -> BaseStorageMemory.getInstance()
                        .preferredChestFor(mod, _home, dimension, new ItemTarget(tool, 1)))
                .filter(pos -> pos.isWithinDistance(mod.getPlayer().getPos(), 64))
                .orElse(null);
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _activeTask = null;
        _currentTool = null;
        _pendingStore = false;
        _storageChest = null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof EnsureToolReservesTask task
                && task._home.equals(_home);
    }

    @Override
    protected String toDebugString() {
        return "Ensure tool reserves tier=" + _tier
                + " phase=" + _phase
                + " backups=" + _missingBackups.size();
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return _phase == Phase.DONE;
    }
}
