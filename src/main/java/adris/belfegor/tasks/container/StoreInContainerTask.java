package adris.belfegor.tasks.container;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
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
import java.util.List;
import java.util.Optional;

/**
 * Moves items from your inventory to a storage container.
 */
public class StoreInContainerTask extends AbstractDoToStorageContainerTask implements ITaskCanForce {

    private final BlockPos _targetContainer;
    private final boolean _getIfNotPresent;
    private final ItemTarget[] _toStore;

    private ContainerStoredTracker _storedItems;

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
        if (_storedItems != null) {
            _storedItems.stopTracking();
        }
    }

    @Override
    protected Task onContainerOpenSubtask(Belfegor mod, ContainerCache containerCache) {
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
            StorageHelper.closeScreen();
        }
        setDebugState("All requested items stored; releasing container.");
        return null;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        // We've stored all items
        return StorageHelper.getItemStackInCursorSlot().isEmpty()
                && (_toStore.length == 0
                || (_storedItems != null
                && _storedItems.getUnstoredItemTargetsYouCanStore(mod, _toStore).length == 0));
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
}
