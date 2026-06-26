package adris.altoclef.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.resources.CollectRecipeCataloguedResourcesTask;
import adris.altoclef.tasks.slot.ReceiveCraftingOutputSlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.slots.Slot;
import adris.altoclef.memory.CraftingMemory;
import adris.altoclef.tasksystem.CraftingPathRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Crafts an item within the 2x2 inventory crafting grid.
 */
public class CraftInInventoryTask extends ResourceTask implements adris.altoclef.tasksystem.ITaskUsesCraftingGrid {
    private static final AtomicLong SCREEN_OPEN_SEQUENCE = new AtomicLong();
    private static volatile long ACTIVE_SCREEN_OPEN = -1;

    private final RecipeTarget _target;
    private final boolean _collect;
    private final boolean _ignoreUncataloguedSlots;
    private boolean _fullCheckFailed = false;
    private long _startTimeMs = 0;
    private Screen invScreen;
    private MinecraftClient client = MinecraftClient.getInstance();
    private Task _cachedCollectTask = null;
    private Task _cachedCraftTask = null;
    private int _inventoryOpenWaitTicks = 0;
    private String _lastGateState = "";

    public CraftInInventoryTask(RecipeTarget target, boolean collect, boolean ignoreUncataloguedSlots) {
        super(new ItemTarget(target.getOutputItem(), target.getTargetCount()));
        _target = target;
        _collect = collect;
        _ignoreUncataloguedSlots = ignoreUncataloguedSlots;
    }

    public CraftInInventoryTask(RecipeTarget target) {
        this(target, true, false);
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        // Don't let dropped items on the ground preempt crafting.
        // When a crafting screen is open, we should finish crafting first,
        // then pick up any drops afterward.
        return true;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        _fullCheckFailed = false;
        _startTimeMs = System.currentTimeMillis();
        _cachedCollectTask = null;
        _cachedCraftTask = null;
        _inventoryOpenWaitTicks = 0;
        _lastGateState = "";
        invScreen = new InventoryScreen(mod.getPlayer());
        // Clear cursor but DON'T open inventory yet — open only when ready to craft
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (!cursorStack.isEmpty()) {
            adris.altoclef.debug.DebugLogger.getInstance().logImmediate("CRAFT-CURSOR",
                    "start recovery target=" + _target + " cursor=" + stackText(cursorStack));
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            if (moveTo.isPresent()) {
                mod.getSlotHandler().clickSlotForce(moveTo.get(), 0, SlotActionType.PICKUP);
            } else {
                Optional<Slot> garbage = StorageHelper.getGarbageSlot(mod);
                if (garbage.isPresent()) {
                    mod.getSlotHandler().clickSlotForce(garbage.get(), 0, SlotActionType.PICKUP);
                } else {
                    mod.getSlotHandler().clickSlotForce(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                }
            }
        }
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // Grab from output FIRST (inventory must be open for this)
        if (StorageHelper.isPlayerInventoryOpen()) {
            if (StorageHelper.getItemStackInCursorSlot().isEmpty()) {
                Item outputItem = StorageHelper.getItemStackInSlot(PlayerSlot.CRAFT_OUTPUT_SLOT).getItem();
                if (_itemTargets != null) {
                    for (ItemTarget target : _itemTargets) {
                        if (target.matches(outputItem)) {
                            return new ReceiveCraftingOutputSlotTask(PlayerSlot.CRAFT_OUTPUT_SLOT, target.getTargetCount());
                        }
                    }
                }
            }
        }
        if (StorageHelper.isBigCraftingOpen()) {
            StorageHelper.closeScreen();
        }
        ItemTarget toGet = _itemTargets[0];
        Item toGetItem = toGet.getMatches()[0];
        if (_collect && !StorageHelper.hasRecipeMaterialsOrTarget(mod, _target)) {
            logGateState("collecting-materials");
            // Need materials — make sure inventory is CLOSED while gathering
            if (StorageHelper.isPlayerInventoryOpen()) {
                StorageHelper.closeScreen();
            }
            // Collect recipe materials
            if (_cachedCollectTask == null || _cachedCollectTask.stopped()) {
                _cachedCollectTask = collectRecipeSubTask(mod);
            }
            setDebugState("Collecting materials");
            return _cachedCollectTask;
        }

        // Materials ready — open inventory if not already open
        if (!StorageHelper.isPlayerInventoryOpen()) {
            _inventoryOpenWaitTicks++;
            logGateState("opening-inventory screen=" + (client.currentScreen == null
                    ? "null"
                    : client.currentScreen.getClass().getSimpleName())
                    + " handler=" + describeHandler(mod)
                    + " " + inventorySnapshot(mod));
            if (client.currentScreen == null) {
                openInventoryWithDiagnostics(mod, "normal-open");
            } else if (!(client.currentScreen instanceof ChatScreen)
                    && !(client.currentScreen instanceof GameMenuScreen)
                    && !(client.currentScreen instanceof DeathScreen)) {
                // Do not wait forever behind a stale container or custom screen.
                StorageHelper.closeScreen();
            }
            if (_inventoryOpenWaitTicks > 20) {
                StorageHelper.closeScreen();
                openInventoryWithDiagnostics(mod, "retry-open");
                _inventoryOpenWaitTicks = 0;
            }
            return null; // Wait for screen to open
        }
        _inventoryOpenWaitTicks = 0;
        logGateState("inventory-ready");

        // No need to free inventory, output gets picked up.

        if (_cachedCraftTask == null || _cachedCraftTask.isFinished(mod) || _cachedCraftTask.stopped()) {
            // If we already have enough items, don't create a new craft task
            if (mod.getItemStorage().getItemCount(toGetItem) >= toGet.getTargetCount()) {
                setDebugState("Done crafting " + toGet);
                _cachedCraftTask = null;
                return null;
            }
            _cachedCraftTask = new CraftGenericManuallyTask(_target);
            logGateState("manual-craft-created");
        }
        setDebugState("Crafting in inventory... for " + toGet);
        return _cachedCraftTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        // Record crafting attempt in memory
        if (_startTimeMs > 0) {
            double elapsedSeconds = (System.currentTimeMillis() - _startTimeMs) / 1000.0;
            String itemName = _target.getOutputItem().getName().getString();
            if (mod.getItemStorage().getItemCount(_target.getOutputItem()) >= _target.getTargetCount()) {
                CraftingMemory.getInstance().recordSuccess(itemName, elapsedSeconds);
                CraftingMemory.getInstance().recordStep(itemName,
                        new CraftingMemory.Step("craft_inventory", itemName, _target.getTargetCount()));
                CraftingPathRegistry.getInstance().recordSuccess(itemName, "craft_inventory");
            } else {
                CraftingMemory.getInstance().recordFailure(itemName);
                CraftingPathRegistry.getInstance().recordFailure(itemName, "craft_inventory");
            }
        }

// Clear the 2x2 crafting grid BEFORE closing the screen.
        // If items are left in the grid when the screen closes, they get returned
        // by Minecraft, but the move operation triggers MoveInaccessibleItemToInventoryTask
        // which is extremely slow and stalls the bot for 20+ seconds.
        if (StorageHelper.isPlayerInventoryOpen()) {
            for (Slot craftSlot : PlayerSlot.CRAFT_INPUT_SLOTS) {
                ItemStack gridStack = StorageHelper.getItemStackInSlot(craftSlot);
                if (!gridStack.isEmpty()) {
                    mod.getSlotHandler().clickSlotForce(craftSlot, 0, SlotActionType.QUICK_MOVE);
                }
            }
        }

        // Recover the cursor while the current handler and its slot mapping are
        // still valid. This must bypass the normal click timer: onStop commonly
        // runs in the same tick as the last crafting click.
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (!cursorStack.isEmpty()) {
            adris.altoclef.debug.DebugLogger.getInstance().logImmediate("CRAFT-CURSOR",
                    "stop recovery begin target=" + _target
                            + " interrupt=" + (interruptTask == null ? "null" : interruptTask.toString())
                            + " cursor=" + stackText(cursorStack)
                            + " handler=" + describeHandler(mod));
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            if (moveTo.isPresent()) {
                mod.getSlotHandler().clickSlotForce(moveTo.get(), 0, SlotActionType.PICKUP);
            } else {
                Optional<Slot> garbageSlot = StorageHelper.getGarbageSlot(mod);
                if (garbageSlot.isPresent()) {
                    mod.getSlotHandler().clickSlotForce(garbageSlot.get(), 0, SlotActionType.PICKUP);
                } else {
                    mod.getSlotHandler().clickSlotForce(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                }
            }
            adris.altoclef.debug.DebugLogger.getInstance().logImmediate("CRAFT-CURSOR",
                    "stop recovery end target=" + _target
                            + " cursor=" + stackText(StorageHelper.getItemStackInCursorSlot()));
        }

// Close only after cursor recovery so a PlayerScreenHandler slot is not
        // clicked through a newly restored/default handler.
        // Do NOT close if the cursor still has an item — closing with a held item
        // effectively "locks" it to the cursor, causing the stuck-cursor bug.
        if (StorageHelper.getItemStackInCursorSlot().isEmpty()
                && client != null && client.currentScreen instanceof InventoryScreen) {
            StorageHelper.closeScreen();
        }
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return super.isFinished(mod) && StorageHelper.getItemStackInCursorSlot().isEmpty();
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CraftInInventoryTask task) {
            if (!task._target.equals(_target)) return false;
            return isCraftingEqual(task);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return toCraftingDebugStringName() + " " + _target;
    }

    // virtual. By default assumes subtasks are CATALOGUED (in TaskCatalogue.java)
    protected Task collectRecipeSubTask(AltoClef mod) {
        return new CollectRecipeCataloguedResourcesTask(_ignoreUncataloguedSlots, _target);
    }

    protected String toCraftingDebugStringName() {
        return "Craft 2x2 Task";
    }

    protected boolean isCraftingEqual(CraftInInventoryTask other) {
        return true;
    }

    public RecipeTarget getRecipeTarget() {
        return _target;
    }

    private void logGateState(String state) {
        if (state.equals(_lastGateState)) return;
        _lastGateState = state;
        adris.altoclef.debug.DebugLogger.getInstance().log("CRAFT-2X2", state + " target=" + _target);
        adris.altoclef.debug.DebugLogger.getInstance().flush();
    }

    private void openInventoryWithDiagnostics(AltoClef mod, String reason) {
        long sequence = SCREEN_OPEN_SEQUENCE.incrementAndGet();
        ACTIVE_SCREEN_OPEN = sequence;
        Thread renderThread = Thread.currentThread();
        var logger = adris.altoclef.debug.DebugLogger.getInstance();
        logger.logImmediate("SCREEN-OPEN", "begin #" + sequence + " reason=" + reason
                + " thread=" + renderThread.getName() + " " + inventorySnapshot(mod));

        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                return;
            }
            if (ACTIVE_SCREEN_OPEN != sequence) return;
            StringBuilder stack = new StringBuilder("blocked #").append(sequence)
                    .append(" renderState=").append(renderThread.getState());
            for (StackTraceElement frame : renderThread.getStackTrace()) {
                stack.append("\n    at ").append(frame);
            }
            logger.logImmediate("SCREEN-WATCHDOG", stack.toString());
        }, "Belfegor-Screen-Watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        logger.logImmediate("SCREEN-OPEN", "constructing InventoryScreen #" + sequence);
        InventoryScreen screen = new InventoryScreen(mod.getPlayer());
        logger.logImmediate("SCREEN-OPEN", "constructed InventoryScreen #" + sequence);
        client.setScreen(screen);
        logger.logImmediate("SCREEN-OPEN", "setScreen returned #" + sequence
                + " current=" + (client.currentScreen == null ? "null" : client.currentScreen.getClass().getName())
                + " handler=" + describeHandler(mod));
        ACTIVE_SCREEN_OPEN = -1;
    }

    private String inventorySnapshot(AltoClef mod) {
        if (mod.getPlayer() == null) return "player=null";
        ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
        StringBuilder result = new StringBuilder();
        result.append("cursor=").append(stackText(cursor)).append(" slots=[");
        var handler = mod.getPlayer().currentScreenHandler;
        if (handler != null) {
            for (int i = 0; i < handler.slots.size(); i++) {
                ItemStack stack = handler.getSlot(i).getStack();
                if (!stack.isEmpty()) {
                    result.append(i).append(':').append(stackText(stack)).append(',');
                }
            }
        }
        return result.append(']').toString();
    }

    private String describeHandler(AltoClef mod) {
        if (mod.getPlayer() == null || mod.getPlayer().currentScreenHandler == null) return "null";
        var handler = mod.getPlayer().currentScreenHandler;
        return handler.getClass().getName() + "{sync=" + handler.syncId
                + ",revision=" + handler.getRevision()
                + ",slots=" + handler.slots.size() + "}";
    }

    private String stackText(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        return stack.getCount() + "x" + stack.getItem().getTranslationKey();
    }
}
