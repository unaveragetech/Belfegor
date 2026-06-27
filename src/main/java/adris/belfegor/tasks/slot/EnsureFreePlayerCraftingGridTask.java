package adris.belfegor.tasks.slot;

import adris.belfegor.Belfegor;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.slots.PlayerSlot;
import adris.belfegor.util.slots.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

public class EnsureFreePlayerCraftingGridTask extends Task {
    @Override
    protected void onStart(Belfegor mod) {

    }

    @Override
    protected Task onTick(Belfegor mod) {
        setDebugState("Clearing the 2x2 crafting grid");
        for (Slot slot : PlayerSlot.CRAFT_INPUT_SLOTS) {
            ItemStack items = StorageHelper.getItemStackInSlot(slot);
            ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
            if (!cursor.isEmpty()) {
                return new EnsureFreeCursorSlotTask();
            }
            if (!items.isEmpty()) {
                mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP);
                return null;
            }
        }
        return null;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof EnsureFreePlayerCraftingGridTask;
    }

    @Override
    protected String toDebugString() {
        return "Breaking the crafting grid";
    }
}
