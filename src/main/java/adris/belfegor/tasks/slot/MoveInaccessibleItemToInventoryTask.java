package adris.belfegor.tasks.slot;

import adris.belfegor.Belfegor;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.ItemHelper;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.slots.CursorSlot;
import adris.belfegor.util.slots.PlayerSlot;
import adris.belfegor.util.slots.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Objects;
import java.util.Optional;

public class MoveInaccessibleItemToInventoryTask extends Task {

    private final ItemTarget _target;
    private boolean _clearedGrid = false;

    public MoveInaccessibleItemToInventoryTask(ItemTarget target) {
        _target = target;
    }

    @Override
    protected void onStart(Belfegor mod) {
        _clearedGrid = false;
    }

    @Override
    protected Task onTick(Belfegor mod) {

        // Ensure inventory is closed.
        if (!StorageHelper.isPlayerInventoryOpen()) {
            ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
            if (!cursorStack.isEmpty()) {
                Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
                if (moveTo.isPresent()) {
                    mod.getSlotHandler().clickSlotForce(moveTo.get(), 0, SlotActionType.PICKUP);
                    return null;
                }
                if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                    mod.getSlotHandler().clickSlotForce(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                    return null;
                }
                Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
                if (garbage.isPresent()) {
                    mod.getSlotHandler().clickSlotForce(garbage.get(), 0, SlotActionType.PICKUP);
                    return null;
                }
                mod.getSlotHandler().clickSlotForce(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
            } else {
                StorageHelper.closeScreen();
            }
            setDebugState("Closing screen first (hope this doesn't get spammed a million times)");
            return null;
        }

        Optional<Slot> slotToMove = StorageHelper.getFilledInventorySlotInaccessibleToContainer(mod, _target);
        if (slotToMove.isPresent()) {
            // Force cursor slot if we have one.
            if (_target.matches(StorageHelper.getItemStackInCursorSlot().getItem())) {
                slotToMove = Optional.of(CursorSlot.SLOT);
            }
            // issue is a full cursor slot when trying to clear out bad items.
            // solution: ensure cursor is empty first
            if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) {
                return new EnsureFreeCursorSlotTask();
            }

            Slot toMove = slotToMove.get();
            ItemStack stack = StorageHelper.getItemStackInSlot(toMove);
            Optional<Slot> toMoveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(stack, false);
            if (toMoveTo.isPresent()) {
                setDebugState("Moving slot " + toMove + " to inventory");
                // Use clickSlotForce for rapid clearing — this task should complete
                // in 1-2 ticks, not 20 seconds. The original used regular clickSlot
                // which is timer-gated and extremely slow.
                if (Slot.isCursor(toMove)) {
                    mod.getSlotHandler().clickSlotForce(toMoveTo.get(), 0, SlotActionType.PICKUP);
                } else {
                    mod.getSlotHandler().clickSlotForce(toMove, 0, SlotActionType.PICKUP);
                }
                return null;
            } else if (!Slot.isCursor(toMove) && isInCraftingGrid(toMove)) {
                // Crafting grid slot with no room in inventory — try shift-click
                // to move directly (this handles partial stacks etc.)
                mod.getSlotHandler().clickSlotForce(toMove, 0, SlotActionType.QUICK_MOVE);
                return null;
            } else {
                setDebugState("Free up inventory first.");
                // Make it free first.
                return new EnsureFreeInventorySlotTask();
            }
        }
        setDebugState("NONE FOUND");
        return null;
    }

    private boolean isInCraftingGrid(Slot slot) {
        for (Slot craftSlot : PlayerSlot.CRAFT_INPUT_SLOTS) {
            if (craftSlot.equals(slot)) return true;
        }
        return false;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof MoveInaccessibleItemToInventoryTask task) {
            return Objects.equals(task._target, _target);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Making item accessible: " + _target;
    }
}