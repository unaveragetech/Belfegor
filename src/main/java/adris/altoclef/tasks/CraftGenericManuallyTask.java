package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.debug.DebugLogger;
import adris.altoclef.tasksystem.ITaskCanForce;
import adris.altoclef.tasksystem.ITaskUsesCraftingGrid;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.InventoryManager;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * Crafts a recipe using the inventory manager.
 * Handles the full workflow: validate grid size, find items, place in grid, collect output.
 * Uses InventoryManager for human-like slot navigation.
 */
public class CraftGenericManuallyTask extends Task implements ITaskCanForce, ITaskUsesCraftingGrid {

    private final RecipeTarget _target;
    private int _craftAttempts = 0;
    private static final int MAX_CRAFT_ATTEMPTS = 50;
    private int _noProgressTicks = 0;
    private static final int MAX_NO_PROGRESS_TICKS = 80;
    private InventoryManager _inv;
    private String _lastInventoryFingerprint = "";
    private boolean _wrongGridHandled = false;
    private String _lastDetailedState = "";
    private int _snapshotTicks = 0;

    public CraftGenericManuallyTask(RecipeTarget target) {
        _target = target;
    }

    @Override
    public boolean shouldForce(AltoClef mod, Task interruptingCandidate) {
        // Keep ownership only while a craft transaction is genuinely in flight.
        // Once the output target exists, the parent must be allowed to retire this
        // child and advance to the next recipe.
        if (mod.getItemStorage().getItemCount(_target.getOutputItem()) >= _target.getTargetCount()) {
            return false;
        }
        if (_inv == null || !_inv.isScreenOpen()) {
            return false;
        }
        if (!_inv.isCursorEmpty()) {
            return true;
        }
        for (Slot slot : _inv.getCurrentCraftSlots()) {
            if (!_inv.getItemInSlot(slot).isEmpty()) {
                return true;
            }
        }
        Slot output = _inv.getOutputSlot();
        return output != null && !_inv.getItemInSlot(output).isEmpty();
    }

    @Override
    protected void onStart(AltoClef mod) {
        _craftAttempts = 0;
        _noProgressTicks = 0;
        _lastInventoryFingerprint = "";
        _wrongGridHandled = false;
        _lastDetailedState = "";
        _snapshotTicks = 0;
        _inv = new InventoryManager(mod);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_inv == null) _inv = new InventoryManager(mod);

        // No screen open — cannot craft
        if (!_inv.isScreenOpen()) {
            setDebugState("No screen open");
            return null;
        }

        // CRITICAL: Validate that the current grid can hold this recipe.
        // A 3x3 recipe (9 slots) MUST NOT be attempted in a 2x2 player inventory grid (4 slots).
        // This prevents diamonds from getting stuck in the player crafting grid.
        Slot[] craftSlots = _inv.getCurrentCraftSlots();
        logDetailedState(mod, craftSlots, "tick");
        if (craftSlots.length == 0) {
            setDebugState("No crafting grid available");
            return null;
        }
        if (_target.getRecipe().getSlotCount() > craftSlots.length) {
            if (!_wrongGridHandled) {
                DebugLogger.getInstance().log("CraftGenericManuallyTask", "REJECTED: Recipe " + _target.getOutputItem().getName().getString()
                        + " needs " + _target.getRecipe().getSlotCount() + " slots but grid only has " + craftSlots.length);
                _wrongGridHandled = true;
                // Force the table owner to reopen its 3x3 handler instead of allowing
                // this cached task to spin forever against the player 2x2 grid.
                StorageHelper.closeScreen();
            }
            setDebugState("Wrong grid: recipe needs " + _target.getRecipe().getSlotCount() + " but grid has " + craftSlots.length);
            return null;
        }
        _wrongGridHandled = false;

        String fingerprint = getInventoryFingerprint(craftSlots);
        if (fingerprint.equals(_lastInventoryFingerprint)) {
            _noProgressTicks++;
        } else {
            _lastInventoryFingerprint = fingerprint;
            _noProgressTicks = 0;
        }
        if (_noProgressTicks > MAX_NO_PROGRESS_TICKS) {
            setDebugState("Craft stalled; recovering cursor/grid");
            if (!_inv.isCursorEmpty()) {
                _inv.returnCursorToInventory();
            } else {
                for (Slot craftSlot : craftSlots) {
                    if (!_inv.getItemInSlot(craftSlot).isEmpty()) {
                        _inv.clearSlot(craftSlot);
                        break;
                    }
                }
            }
            _noProgressTicks = 0;
            return null;
        }

        // Safety: give up after too many attempts
        if (_craftAttempts > MAX_CRAFT_ATTEMPTS) {
            setDebugState("Too many craft attempts, giving up");
            return null;
        }

        // Reaching the output count does not mean the inventory transaction is
        // complete. A source stack may still be on the cursor after placing the
        // final ingredient. Return it before allowing this task to finish.
        if (mod.getItemStorage().getItemCount(_target.getOutputItem()) >= _target.getTargetCount()) {
            if (!_inv.isCursorEmpty()) {
                setDebugState("Target met; returning cursor before completion");
                DebugLogger.getInstance().logImmediate("CRAFT-CURSOR",
                        "target-met recovery target=" + _target
                                + " cursor=" + stackText(_inv.getCursorItem()));
                _inv.returnCursorToInventory();
                return null;
            }
            setDebugState("Done! Have " + mod.getItemStorage().getItemCount(_target.getOutputItem()));
            return null;
        }

        // Step 1: Check output slot — take it if ready
        Slot outputSlot = _inv.getOutputSlot();
        if (outputSlot != null && _inv.outputHasItem(_target.getOutputItem())) {
            _inv.shiftClick(outputSlot);
            int have = mod.getItemStorage().getItemCount(_target.getOutputItem());
            if (have >= _target.getTargetCount()) {
                if (!_inv.isCursorEmpty()) {
                    setDebugState("Output collected; returning cursor");
                    DebugLogger.getInstance().logImmediate("CRAFT-CURSOR",
                            "post-output recovery target=" + _target
                                    + " cursor=" + stackText(_inv.getCursorItem()));
                    _inv.returnCursorToInventory();
                    return null;
                }
                setDebugState("Done! " + have + "/" + _target.getTargetCount());
                return null;
            }
            _craftAttempts++;
            return null;
        }

        // Step 2: If cursor has an item, handle it
        if (!_inv.isCursorEmpty()) {
            Item cursorItem = _inv.getCursorItem().getItem();
            for (int i = 0; i < _target.getRecipe().getSlotCount() && i < craftSlots.length; i++) {
                ItemTarget needed = _target.getRecipe().getSlot(i);
                Slot gridSlot = craftSlots[i];
                if (needed != null && !needed.isEmpty() && needed.matches(cursorItem)) {
                    ItemStack present = _inv.getItemInSlot(gridSlot);
                    if (present.isEmpty()) {
                        _inv.placeOneInSlot(gridSlot);
                        return null;
                    }
                }
            }
            _inv.returnCursorToInventory();
            return null;
        }

        // Step 3: Fill crafting grid slots that need items
        for (int i = 0; i < _target.getRecipe().getSlotCount() && i < craftSlots.length; i++) {
            ItemTarget needed = _target.getRecipe().getSlot(i);
            Slot gridSlot = craftSlots[i];
            ItemStack present = _inv.getItemInSlot(gridSlot);

            if (needed == null || needed.isEmpty()) {
                if (!present.isEmpty()) {
                    _inv.clearSlot(gridSlot);
                }
                continue;
            }

            if (!present.isEmpty() && needed.matches(present.getItem())) {
                continue;
            }

            if (!present.isEmpty() && !needed.matches(present.getItem())) {
                _inv.clearSlot(gridSlot);
                return null;
            }

            Item itemToPlace = null;
            for (Item match : needed.getMatches()) {
                int inInv = _inv.countInInventory(match);
                // Existing grid items satisfy their own slots, but cannot supply a
                // different empty slot. Only choose a variant that is actually in
                // inventory. This permits recipes such as composters to use six
                // birch slabs plus one oak slab.
                if (inInv > 0) {
                    itemToPlace = match;
                    break;
                }
            }

            if (itemToPlace == null) {
                setDebugState("Missing: " + needed);
                return null;
            }

            if (!_inv.placeOneInCraftSlot(itemToPlace, gridSlot)) {
                setDebugState("Can't place " + itemToPlace);
                return null;
            }
            return null;
        }

        // Step 4: All slots filled — try to take output
        if (outputSlot != null) {
            ItemStack out = _inv.getItemInSlot(outputSlot);
            if (!out.isEmpty() && out.getItem() == _target.getOutputItem()) {
                _inv.shiftClick(outputSlot);
                _craftAttempts++;
            }
        }
        return null;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return mod.getItemStorage().getItemCount(_target.getOutputItem()) >= _target.getTargetCount()
                && StorageHelper.getItemStackInCursorSlot().isEmpty();
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // Do NOT close the screen or clear the crafting grid here.
        // This task is a child of DoCraftInTableTask which manages the crafting table lifecycle.
        // Closing the screen here breaks multi-recipe crafting: the parent creates a NEW
        // CraftGenericManuallyTask for the next recipe, but the screen is already closed,
        // so it falls back to the player inventory 2x2 grid where items get stuck.
        // The parent task (DoCraftInTableTask.onStop) handles screen closing and cursor cleanup.
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof CraftGenericManuallyTask task) {
            return task._target.equals(_target);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Crafting: " + _target;
    }

    private String getInventoryFingerprint(Slot[] craftSlots) {
        StringBuilder result = new StringBuilder();
        ItemStack cursor = _inv.getCursorItem();
        result.append(cursor.getItem()).append(':').append(cursor.getCount()).append('|');
        for (Slot slot : craftSlots) {
            ItemStack stack = _inv.getItemInSlot(slot);
            result.append(stack.getItem()).append(':').append(stack.getCount()).append(',');
        }
        Slot output = _inv.getOutputSlot();
        if (output != null) {
            ItemStack stack = _inv.getItemInSlot(output);
            result.append('|').append(stack.getItem()).append(':').append(stack.getCount());
        }
        return result.toString();
    }

    private void logDetailedState(AltoClef mod, Slot[] craftSlots, String phase) {
        _snapshotTicks++;
        StringBuilder state = new StringBuilder();
        state.append("phase=").append(phase)
                .append(" target=").append(_target)
                .append(" screen=").append(net.minecraft.client.MinecraftClient.getInstance().currentScreen == null
                        ? "null"
                        : net.minecraft.client.MinecraftClient.getInstance().currentScreen.getClass().getName())
                .append(" handler=");
        if (mod.getPlayer() == null || mod.getPlayer().currentScreenHandler == null) {
            state.append("null");
        } else {
            var handler = mod.getPlayer().currentScreenHandler;
            state.append(handler.getClass().getName())
                    .append("{sync=").append(handler.syncId)
                    .append(",revision=").append(handler.getRevision())
                    .append(",slots=").append(handler.slots.size()).append('}');
        }
        state.append(" cursor=").append(stackText(_inv.getCursorItem())).append(" grid=[");
        for (Slot slot : craftSlots) {
            state.append(slot.getWindowSlot()).append(':')
                    .append(stackText(_inv.getItemInSlot(slot))).append(',');
        }
        Slot output = _inv.getOutputSlot();
        state.append("] output=").append(output == null ? "none" : stackText(_inv.getItemInSlot(output)));
        String text = state.toString();
        if (!text.equals(_lastDetailedState) || _snapshotTicks % 20 == 0) {
            _lastDetailedState = text;
            DebugLogger.getInstance().log("CRAFT-STATE", text);
        }
    }

    private String stackText(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        return stack.getCount() + "x" + stack.getItem().getTranslationKey();
    }
}
