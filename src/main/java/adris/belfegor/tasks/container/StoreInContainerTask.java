package adris.belfegor.tasks.container;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.memory.BaseStorageMemory;
import adris.belfegor.tasks.slot.MoveItemToSlotFromInventoryTask;
import adris.belfegor.tasksystem.ITaskCanForce;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.trackers.storage.ContainerCache;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.slots.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Moves items from your inventory to a storage container.
 */
public class StoreInContainerTask extends AbstractDoToStorageContainerTask implements ITaskCanForce {

    private final BlockPos _targetContainer;
    private final boolean _getIfNotPresent;
    private final ItemTarget[] _toStore;
    private static final Map<String, Integer> NO_PROGRESS_ATTEMPTS = new HashMap<>();

    private ContainerStoredTracker _storedItems;
    private boolean _recordedStorageMemory;
    private boolean _containerFull;
    private int _openNoProgressTicks;
    private int _lastStoredRequestedCount;
    private int _requestedTransferCount;

    public StoreInContainerTask(BlockPos targetContainer, boolean getIfNotPresent, ItemTarget... toStore) {
        _targetContainer = targetContainer;
        _getIfNotPresent = getIfNotPresent;
        _toStore = toStore;
    }

    @Override
    protected Optional<BlockPos> getContainerTarget() {
        return Optional.of(_targetContainer);
    }

    @Override
    protected void onStart(Belfegor mod) {
        super.onStart(mod);
        if (_storedItems == null) {
            // Only consider transfers to the container we wish
            _storedItems = new ContainerStoredTracker(slot -> {
                Optional<BlockPos> openContainer = mod.getItemStorage().getLastBlockPosInteraction();
                return openContainer.isPresent() && openContainer.get().equals(_targetContainer);
            });
        }
        _storedItems.startTracking();
        _recordedStorageMemory = false;
        _containerFull = false;
        _openNoProgressTicks = 0;
        _lastStoredRequestedCount = 0;
        _requestedTransferCount = requestedTransferCount();
    }

    @Override
    protected Task onTick(Belfegor mod) {
        if (_toStore.length == 0 && StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            StorageHelper.closeScreen();
            setDebugState("Nothing to store; releasing container.");
            return null;
        }
        // Get more if we don't have & "get if not present" is true.
        if (_getIfNotPresent) {
            for (ItemTarget target : _toStore) {
                int inventoryNeed = target.getTargetCount() - _storedItems.getStoredCount(target.getMatches());
                if (inventoryNeed > mod.getItemStorage().getItemCount(target)) {
                    return TaskCatalogue.getItemTask(new ItemTarget(target, inventoryNeed));
                }
            }
        }
        return super.onTick(mod);
    }

    @Override
    public boolean shouldForce(Belfegor mod, Task interruptingCandidate) {
        return !StorageHelper.getItemStackInCursorSlot().isEmpty()
                || (_storedItems != null
                && _storedItems.getUnstoredItemTargetsYouCanStore(mod, _toStore).length > 0
                && MinecraftClient.getInstance().currentScreen instanceof HandledScreen);
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        super.onStop(mod, interruptTask);
        if (isFinished(mod)) {
            recordStorageMemory(mod);
        }
        if (_requestedTransferCount > 0
                && stillCarryingRequestedItems(mod)
                && storedNothingRequested()
                && !_recordedStorageMemory) {
            NO_PROGRESS_ATTEMPTS.merge(noProgressKey(_targetContainer, _toStore), 1, Integer::sum);
        } else {
            NO_PROGRESS_ATTEMPTS.remove(noProgressKey(_targetContainer, _toStore));
        }
        if (_storedItems != null) {
            _storedItems.stopTracking();
        }
    }

    @Override
    protected Task onContainerOpenSubtask(Belfegor mod, ContainerCache containerCache) {
        int storedRequested = requestedStoredCount();
        if (storedRequested > _lastStoredRequestedCount) {
            _lastStoredRequestedCount = storedRequested;
            _openNoProgressTicks = 0;
        } else if (_requestedTransferCount > 0
                && stillCarryingRequestedItems(mod)
                && StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            _openNoProgressTicks++;
            if (_openNoProgressTicks >= 100) {
                setDebugState("Open container made no progress for 5 seconds; releasing this chest.");
                _containerFull = true;
                StorageHelper.closeScreen();
                return null;
            }
        } else {
            _openNoProgressTicks = 0;
        }

        // Move all items that aren't in the container
        for (ItemTarget target : _storedItems.getUnstoredItemTargetsYouCanStore(mod, _toStore)) {
            setDebugState("Dumping " + target);
            // Grab the item from the current chest that most closely matches our requirements
            List<Slot> potentials = mod.getItemStorage().getSlotsWithItemPlayerInventory(false, target.getMatches());

            // Pick the best slot to grab from.
            Optional<Slot> bestPotential = PickupFromContainerTask.getBestSlotToTransfer(
                    mod,
                    target,
                    mod.getItemStorage().getItemCountContainer(target.getMatches()),
                    potentials,
                    stack -> mod.getItemStorage().getSlotThatCanFitInOpenContainer(stack, false).isPresent());
            if (bestPotential.isPresent()) {
                ItemStack stackIn = StorageHelper.getItemStackInSlot(bestPotential.get());
                Optional<Slot> toMoveTo = mod.getItemStorage().getSlotThatCanFitInOpenContainer(stackIn, false);
                if (toMoveTo.isEmpty()) {
                    setDebugState("CONTAINER FULL!");
                    _containerFull = true;
                    StorageHelper.closeScreen();
                    return null;
                }
                ItemStack destinationStack = StorageHelper.getItemStackInSlot(toMoveTo.get());
                int destinationSpace;
                if (destinationStack.isEmpty()) {
                    destinationSpace = stackIn.getMaxCount();
                } else if (target.matches(destinationStack.getItem())) {
                    destinationSpace = Math.max(0, destinationStack.getMaxCount() - destinationStack.getCount());
                } else {
                    destinationSpace = 0;
                }
                if (destinationSpace <= 0) {
                    setDebugState("CONTAINER SLOT FULL!");
                    _containerFull = true;
                    StorageHelper.closeScreen();
                    return null;
                }
                int moveCount = Math.min(target.getTargetCount(), Math.min(stackIn.getCount(), destinationSpace));
                ItemTarget moveTarget = new ItemTarget(target, moveCount);
                setDebugState("Moving to slot " + moveTarget + " space=" + destinationSpace);
                return new MoveItemToSlotFromInventoryTask(moveTarget, toMoveTo.get());
            }
            setDebugState("SHOULD NOT HAPPEN! No valid items detected.");
        }
        if (StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            recordStorageMemory(mod);
            StorageHelper.closeScreen();
        }
        setDebugState("All requested items stored; releasing container.");
        return null;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            return false;
        }
        if (_containerFull) return true;
        if (_requestedTransferCount > 0
                && storedNothingRequested()
                && stillCarryingRequestedItems(mod)) {
            return false;
        }
        return _toStore.length == 0
                || (_storedItems != null
                && _storedItems.getUnstoredItemTargetsYouCanStore(mod, _toStore).length == 0);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof StoreInContainerTask task) {
            return task._targetContainer.equals(_targetContainer) && task._getIfNotPresent == _getIfNotPresent && Arrays.equals(task._toStore, _toStore);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Storing in container[" + _targetContainer.toShortString() + "] " + Arrays.toString(_toStore);
    }

    private void recordStorageMemory(Belfegor mod) {
        if (_containerFull || _recordedStorageMemory || _toStore.length == 0 || mod == null) return;
        java.util.List<ItemTarget> actuallyStored = new java.util.ArrayList<>();
        for (ItemTarget target : _toStore) {
            if (target == null) continue;
            int stored = Math.min(target.getTargetCount(),
                    _storedItems == null ? 0 : _storedItems.getStoredCount(target.getMatches()));
            if (stored > 0) {
                actuallyStored.add(new ItemTarget(target, stored));
            }
        }
        if (actuallyStored.isEmpty()) return;
        BlockPos home = mod.getModSettings().getHomeBasePosition();
        if (home == null) return;
        String dimension = adris.belfegor.util.helpers.WorldHelper.getCurrentDimension().name();
        BaseStorageMemory.getInstance().rememberChest(home, dimension, _targetContainer,
                "storage", false, "completed StoreInContainerTask");
        BaseStorageMemory.getInstance().recordStored(home, dimension, _targetContainer,
                actuallyStored.toArray(ItemTarget[]::new));
        BaseStorageMemory.getInstance().save();
        _recordedStorageMemory = true;
    }

    public boolean wasContainerFull() {
        return _containerFull;
    }

    public static boolean hadRepeatedNoProgress(BlockPos targetContainer, ItemTarget... toStore) {
        return NO_PROGRESS_ATTEMPTS.getOrDefault(noProgressKey(targetContainer, toStore), 0) >= 3;
    }

    private boolean stillCarryingRequestedItems(Belfegor mod) {
        if (mod == null || _toStore.length == 0) return false;
        for (ItemTarget target : _toStore) {
            if (target != null && mod.getItemStorage().getItemCountInventoryOnly(target.getMatches()) > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean storedNothingRequested() {
        if (_storedItems == null) return true;
        for (ItemTarget target : _toStore) {
            if (target != null && _storedItems.getStoredCount(target.getMatches()) > 0) {
                return false;
            }
        }
        return true;
    }

    private int requestedStoredCount() {
        if (_storedItems == null) return 0;
        int result = 0;
        for (ItemTarget target : _toStore) {
            if (target != null) {
                result += Math.min(target.getTargetCount(),
                        _storedItems.getStoredCount(target.getMatches()));
            }
        }
        return result;
    }

    private int requestedTransferCount() {
        int result = 0;
        for (ItemTarget target : _toStore) {
            if (target != null) result += Math.max(0, target.getTargetCount());
        }
        return result;
    }

    private static String noProgressKey(BlockPos targetContainer, ItemTarget[] toStore) {
        return (targetContainer == null ? "unknown" : targetContainer.toShortString())
                + "|" + Arrays.toString(toStore);
    }
}
