package adris.belfegor.tasks.slot;

import adris.belfegor.Belfegor;
import adris.belfegor.tasks.container.OverflowInventoryTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.helpers.LookHelper;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.slots.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Optional;

public class EnsureFreeInventorySlotTask extends Task {

    private OverflowInventoryTask _overflowTask;

    @Override
    protected void onStart(Belfegor mod) {
        _overflowTask = null;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (cursorStack.isEmpty() && OverflowInventoryTask.freeSlots(mod) <= 0) {
            if (_overflowTask == null || _overflowTask.stopped() || _overflowTask.isFinished(mod)) {
                _overflowTask = new OverflowInventoryTask(1);
            }
            if (!_overflowTask.isFinished(mod)) {
                setDebugState("Storing overflow in chest before freeing inventory slot");
                return _overflowTask;
            }
        }
        Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
        if (cursorStack.isEmpty()) {
            if (garbage.isPresent()) {
                mod.getSlotHandler().clickSlot(garbage.get(), 0, SlotActionType.PICKUP);
                return null;
            }
        }
        if (!cursorStack.isEmpty()) {
            LookHelper.randomOrientation(mod);
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
            return null;
        }
        setDebugState("All items are protected.");
        return null;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _overflowTask = null;
    }

    @Override
    protected boolean isEqual(Task obj) {
        return obj instanceof EnsureFreeInventorySlotTask;
    }

    @Override
    protected String toDebugString() {
        return "Ensuring inventory is free";
    }
}
