package adris.belfegor.tasks.slot;

import adris.belfegor.Belfegor;
import adris.belfegor.tasks.container.OverflowInventoryTask;
import adris.belfegor.tasksystem.ITaskCanForce;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.helpers.ItemHelper;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.slots.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Optional;

/**
 * Last-resort inventory recovery: throws away items the bot is allowed to
 * discard (flowers, leaves, throwaway blocks, anything that does not
 * contribute to the current recipe/protected set) until enough inventory
 * slots are free. Protected items, recipe materials, tools, armor, food, and
 * valuables are never dropped.
 *
 * This is the "drop things temporarily so there is room to craft/use a chest,
 * then carry on" fallback for when no shulker or chest storage is available.
 */
public class DropJunkToMakeSpaceTask extends Task implements ITaskCanForce {

    private final int _desiredFreeSlots;
    private boolean _noThrowaways;

    public DropJunkToMakeSpaceTask(int desiredFreeSlots) {
        _desiredFreeSlots = Math.max(1, desiredFreeSlots);
    }

    @Override
    protected void onStart(Belfegor mod) {
        _noThrowaways = false;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        if (OverflowInventoryTask.freeSlots(mod) >= _desiredFreeSlots) {
            return null;
        }
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (!cursorStack.isEmpty()) {
            if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                // Throw the held stack out of the world.
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                return null;
            }
            Optional<Slot> moveTo = mod.getItemStorage()
                    .getSlotThatCanFitInPlayerInventory(cursorStack, false);
            if (moveTo.isPresent()) {
                mod.getSlotHandler().clickSlot(moveTo.get(), 0, SlotActionType.PICKUP);
                return null;
            }
            // Protected item in the cursor with nowhere to go: stop gracefully.
            _noThrowaways = true;
            return null;
        }

        for (int i = 0; i < 36; i++) {
            Slot slot = Slot.getFromCurrentScreenInventory(i);
            if (slot == null) continue;
            ItemStack stack = StorageHelper.getItemStackInSlot(slot);
            if (stack.isEmpty()) continue;
            if (ItemHelper.canThrowAwayStack(mod, stack)) {
                // Grab it, then drop it on the next tick.
                mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP);
                return null;
            }
        }
        _noThrowaways = true;
        return null;
    }

    @Override
    public boolean shouldForce(Belfegor mod, Task interruptingCandidate) {
        return !StorageHelper.getItemStackInCursorSlot().isEmpty();
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _noThrowaways = false;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof DropJunkToMakeSpaceTask task
                && task._desiredFreeSlots == _desiredFreeSlots;
    }

    @Override
    protected String toDebugString() {
        return "Drop junk until " + _desiredFreeSlots + " slots free";
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return _noThrowaways || OverflowInventoryTask.freeSlots(mod) >= _desiredFreeSlots;
    }
}
