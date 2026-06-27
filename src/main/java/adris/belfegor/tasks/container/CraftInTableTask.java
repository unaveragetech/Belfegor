package adris.belfegor.tasks.container;

import adris.belfegor.Belfegor;
import adris.belfegor.tasks.CraftGenericManuallyTask;
import adris.belfegor.tasks.ResourceTask;
import adris.belfegor.tasks.construction.DestroyBlockTask;
import adris.belfegor.tasks.movement.TimeoutWanderTask;
import adris.belfegor.tasks.resources.CollectRecipeCataloguedResourcesTask;
import adris.belfegor.tasks.slot.ReceiveCraftingOutputSlotTask;
import adris.belfegor.tasksystem.ITaskUsesCraftingGrid;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.RecipeTarget;
import adris.belfegor.util.helpers.ItemHelper;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.slots.Slot;
import adris.belfegor.util.time.TimerGame;
import adris.belfegor.memory.CraftingMemory;
import adris.belfegor.memory.LocationMemory;
import adris.belfegor.tasksystem.CraftingPathRegistry;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;



import java.util.*;

/**
 * Crafts an item in a crafting table, obtaining and placing the table down if none was found.
 */
public class CraftInTableTask extends ResourceTask {

    private final RecipeTarget[] _targets;

    private final DoCraftInTableTask _craftTask;

    public CraftInTableTask(RecipeTarget[] targets) {
        super(extractItemTargets(targets));
        _targets = targets;
        _craftTask = new DoCraftInTableTask(_targets);
    }

    public CraftInTableTask(RecipeTarget target, boolean collect, boolean ignoreUncataloguedSlots) {
        super(new ItemTarget(target.getOutputItem(), target.getTargetCount()));
        _targets = new RecipeTarget[]{target};
        _craftTask = new DoCraftInTableTask(_targets, collect, ignoreUncataloguedSlots);
    }

    public CraftInTableTask(RecipeTarget target) {
        this(target, true, true);
    }

    /**
     * Extracts item targets from recipe targets.
     *
     * @param recipeTargets The array of recipe targets.
     * @return The array of item targets.
     */
    private static ItemTarget[] extractItemTargets(RecipeTarget[] recipeTargets) {
        // Use Java streams to map each recipe target to a new item target
        return Arrays.stream(recipeTargets)
                .map(t -> new ItemTarget(t.getOutputItem(), t.getTargetCount()))
                .toArray(ItemTarget[]::new);
    }

    /**
     * Determines whether the player should avoid picking up items.
     *
     * @param mod The Belfegor mod instance.
     * @return true if the player should avoid picking up items, false otherwise.
     */
    @Override
    protected boolean shouldAvoidPickingUp(Belfegor mod) {
        return true;
    }

    /**
     * Called when the resource starts.
     *
     * @param mod The Belfegor mod instance.
     */
    @Override
    protected void onResourceStart(Belfegor mod) {

    }

    /**
     * This method is called on each tick of the resource manager.
     * It returns the task that should be executed on each tick.
     *
     * @param mod The instance of the Belfegor mod.
     * @return The task to be executed on each tick.
     */
    @Override
    protected Task onResourceTick(Belfegor mod) {
        return _craftTask;
    }

    /**
     * Override method called when the resource stops.
     *
     * @param mod           The Belfegor mod.
     * @param interruptTask The interrupt task.
     */
    @Override
    protected void onResourceStop(Belfegor mod, Task interruptTask) {
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (!cursorStack.isEmpty()) {
            Optional<Slot> moveToSlot = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            if (moveToSlot.isPresent()) {
                // One bounded recovery click. Never cascade through several fallback
                // clicks during task interruption; later clicks may use a new screen.
                mod.getSlotHandler().clickSlotForce(moveToSlot.get(), 0, SlotActionType.PICKUP);
                return;
            }
        }
        StorageHelper.closeScreen();
    }

    /**
     * Checks if the given ResourceTask is equal to this CraftInTableTask.
     *
     * @param other The ResourceTask to compare with.
     * @return true if the ResourceTask is a CraftInTableTask and its craftTask is equal to this task's craftTask, false otherwise.
     */
    @Override
    protected boolean isEqualResource(ResourceTask other) {
        // Check if the other task is an instance of CraftInTableTask
        if (other instanceof CraftInTableTask task) {
            // Compare the craftTask of the two tasks
            return _craftTask.isEqual(task._craftTask);
        }
        // The other task is not a CraftInTableTask, return false
        return false;
    }

    /**
     * Returns the debug string name of the craft task.
     * If the craft task is not null, it calls the toDebugString() method of the craft task and returns the result.
     * Otherwise, it returns null.
     *
     * @return the debug string name of the craft task, or null if the craft task is null.
     */
    @Override
    protected String toDebugStringName() {
        return (_craftTask != null) ? _craftTask.toDebugString() : null;
    }

    /**
     * Returns a copy of the recipe targets.
     *
     * @return The recipe targets.
     */
    public RecipeTarget[] getRecipeTargets() {
        return Arrays.copyOf(_targets, _targets.length);
    }
}


class DoCraftInTableTask extends DoStuffInContainerTask implements ITaskUsesCraftingGrid {

    private final float CRAFT_RESET_TIMER_BONUS_SECONDS = 10;

    private final RecipeTarget[] _targets;

    private final boolean _collect;

    private final CollectRecipeCataloguedResourcesTask _collectTask;
    private final TimerGame _craftResetTimer = new TimerGame(CRAFT_RESET_TIMER_BONUS_SECONDS);
    private int _craftCount;
    private long _startTimeMs = 0;
    // Cache the active craft task so it persists across ticks.
    // Creating a new CraftGenericManuallyTask every tick resets its InventoryManager state.
    private CraftGenericManuallyTask _cachedCraftTask = null;
    private RecipeTarget _cachedCraftTarget = null;
    // When true, the crafting table was placed by the bot and should be
    // broken and collected after crafting is complete so the bot carries
    // it instead of walking back to a fixed location every time.
    private boolean _shouldPickupTable = false;
    private DestroyBlockTask _pickupTableTask = null;

    public DoCraftInTableTask(RecipeTarget[] targets, boolean collect, boolean ignoreUncataloguedSlots) {
        super(Blocks.CRAFTING_TABLE, new ItemTarget("crafting_table"));
        _collectTask = new CollectRecipeCataloguedResourcesTask(ignoreUncataloguedSlots, targets);
        _targets = targets;
        _collect = collect;
        _startTimeMs = System.currentTimeMillis();
    }

    public DoCraftInTableTask(RecipeTarget[] targets) {
        this(targets, true, false);
    }

    /**
     * Override method called when the mod starts.
     * Refactored to handle item management and screen closing.
     * Resets the collect task.
     *
     * @param mod The Belfegor mod instance.
     */
    @Override
    protected void onStart(Belfegor mod) {
        super.onStart(mod);

        // Save the current behaviour and craft count
        mod.getBehaviour().push();
        _craftCount = 0;
        _cachedCraftTask = null;
        _cachedCraftTarget = null;

        // Protect crafting materials from being placed as blocks
        mod.getBehaviour().addPlacementProtectedItems(getMaterialsArray());

        // Check if there is an item in the cursor slot
        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();

        if (!cursorStack.isEmpty()) {
            // Move the item to a slot in the player's inventory that can fit it
            Optional<Slot> moveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            moveTo.ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP));

            // Check if the item can be thrown away
            if (ItemHelper.canThrowAwayStack(mod, cursorStack)) {
                // Throw away the item
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
            }

            // Move the item to the garbage slot
            StorageHelper.getGarbageSlot(mod).ifPresent(slot -> mod.getSlotHandler().clickSlot(slot, 0, SlotActionType.PICKUP));

            // Clear the cursor slot
            mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
        }
        // NOTE: Do NOT close screen here - parent task manages screen lifecycle.
        // Closing here causes the crafting table to close every time the task restarts.

        // Reset the collect task
        _collectTask.reset();
    }

    /**
     * This method is called when the task is interrupted or stopped.
     * It performs the necessary actions to handle the interruption or stopping of the task.
     *
     * @param mod           The instance of the Belfegor mod.
     * @param interruptTask The task that caused the interruption, or null if the task was stopped manually.
     */
    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        // Record crafting attempt in memory
        if (_startTimeMs > 0) {
            double elapsedSeconds = (System.currentTimeMillis() - _startTimeMs) / 1000.0;
            for (RecipeTarget target : _targets) {
                String itemName = target.getOutputItem().getName().getString();
                if (mod.getItemStorage().getItemCount(target.getOutputItem()) >= target.getTargetCount()) {
                    CraftingMemory.getInstance().recordSuccess(itemName, elapsedSeconds);
                    CraftingMemory.getInstance().recordStep(itemName,
                            new CraftingMemory.Step("craft_table", itemName, target.getTargetCount()));
                    CraftingPathRegistry.getInstance().recordSuccess(itemName, "craft_table");
                } else {
                    CraftingMemory.getInstance().recordFailure(itemName);
                    CraftingPathRegistry.getInstance().recordFailure(itemName, "craft_table");
                }
            }
        }

        ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
        if (!cursorStack.isEmpty()) {
            Optional<Slot> moveToSlot = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursorStack, false);
            moveToSlot.ifPresent(slot -> mod.getSlotHandler().clickSlotForce(slot, 0, SlotActionType.PICKUP));
        }
        StorageHelper.closeScreen();

        // Call the onStop method of the super class
        super.onStop(mod, interruptTask);

        // Record crafting table location in LocationMemory if we used one
        if (_cachedContainerPosition != null) {
            LocationMemory.getInstance().forget("crafting_table",
                    _cachedContainerPosition.getX(), _cachedContainerPosition.getY(), _cachedContainerPosition.getZ());
        }

        // Pop the behaviour from the stack
        mod.getBehaviour().pop();
    }

    /**
     * This method is called periodically to perform crafting-related tasks.
     *
     * @param mod The Belfegor mod instance.
     * @return The next task to execute.
     */
    @Override
    protected Task onTick(Belfegor mod) {
        // Add protected items to the behaviour
        Item[] materials = getMaterialsArray();
        mod.getBehaviour().addProtectedItems(materials);
        // Also prevent these from being placed as blocks (reserved for crafting)
        mod.getBehaviour().addPlacementProtectedItems(materials);

        // Avoid breaking crafting tables while we're using them,
        // UNLESS we're done and want to pick up the table
        if (_shouldPickupTable) {
            // Don't protect the table — we want to break it
        } else if (mod.getBlockTracker().isTracking(Blocks.CRAFTING_TABLE)) {
            Optional<BlockPos> craftingTable = mod.getBlockTracker().getNearestTracking(Blocks.CRAFTING_TABLE);
            craftingTable.ifPresent(blockPos -> mod.getBehaviour().avoidBlockBreaking(blockPos));
        }

        // Track whether the bot placed the table (so we can pick it up later)
        if (_placeTask != null && _placeTask.getPlaced() != null) {
            _shouldPickupTable = true;
        }

        // Check if we need to collect items and the collect task is not finished.
        // This handles: gathering materials, crafting intermediate items (planks, sticks),
        // and even crafting the crafting table itself if none exists.
        if (_collect && !_collectTask.isFinished(mod) && !StorageHelper.hasRecipeMaterialsOrTarget(mod, _targets)) {
            return _collectTask;
        }

        // Reset the craft reset timer if the container is not open
        if (!isContainerOpen(mod)) {
            _craftResetTimer.reset();
            _cachedCraftTask = null;
            _cachedCraftTarget = null;
        }

        // Call the parent method — this handles walking to / placing / opening the crafting table
        return super.onTick(mod);
    }

    /**
     * Checks if the given DoStuffInContainerTask is equal to this task.
     *
     * @param other The other DoStuffInContainerTask to compare.
     * @return True if the tasks are equal, False otherwise.
     */
    @Override
    protected boolean isSubTaskEqual(DoStuffInContainerTask other) {
        // Check if the other task is an instance of DoCraftInTableTask
        if (other instanceof DoCraftInTableTask task) {
            // Compare the targets arrays of the two tasks
            return Arrays.equals(task._targets, _targets);
        }
        // The other task is not an instance of DoCraftInTableTask, so they are not equal
        return false;
    }

    /**
     * Checks if the container is open.
     *
     * @param mod The Belfegor mod instance.
     * @return True if the container is open, false otherwise.
     */
    @Override
    protected boolean isContainerOpen(Belfegor mod) {
        return mod.getPlayer().currentScreenHandler instanceof CraftingScreenHandler;
    }

    /**
     * Executes the container subtask.
     *
     * @param mod The Belfegor mod instance.
     * @return The subtask to be executed.
     */

    @Override
    protected Task containerSubTask(Belfegor mod) {
        // Calculate the interval based on the container item move delay and a bonus duration
        float interval = mod.getModSettings().getContainerItemMoveDelay() * 10 + CRAFT_RESET_TIMER_BONUS_SECONDS;
        _craftResetTimer.setInterval(interval);

        // Only use the table-open timeout before a manual craft has begun.
        // CraftGenericManuallyTask has its own inventory-progress watchdog; timing
        // out here during a valid multi-material craft closes the 3x3 screen and
        // leaves its cached child running against the player 2x2 grid.
        if (_cachedCraftTask == null && _craftResetTimer.elapsed()) {
            _cachedCraftTask = null;
            _cachedCraftTarget = null;
            return new TimeoutWanderTask(5);
        }

        // Iterate through each target recipe
        for (RecipeTarget target : _targets) {
            // Check if the output item count meets the target count
            if (mod.getItemStorage().getItemCount(target.getOutputItem()) >= target.getTargetCount()) {
                // This target is done, clear cache if it was for this target
                if (_cachedCraftTarget == target) {
                    _cachedCraftTask = null;
                    _cachedCraftTarget = null;
                }
                continue;
            }

            // Reuse cached task if it's for the same target and still active
            if (_cachedCraftTask != null && _cachedCraftTarget == target) {
                _craftResetTimer.reset();
                return _cachedCraftTask;
            }

            // Create and cache a new craft task
            _cachedCraftTarget = target;
            _cachedCraftTask = new CraftGenericManuallyTask(target);
            _craftResetTimer.reset();
            return _cachedCraftTask;
        }

        // All targets are crafted — close the screen, then break and collect the table
        _cachedCraftTask = null;
        _cachedCraftTarget = null;
        if (isContainerOpen(mod)) {
            StorageHelper.closeScreen();
        }
        // Always break the crafting table when done so the bot carries it.
        // This prevents the bot from walking back to a fixed table location
        // every time it needs to craft, greatly reducing travel time.
        BlockPos tablePos = _cachedContainerPosition;
        if (tablePos == null && _placeTask != null && _placeTask.getPlaced() != null) {
            tablePos = _placeTask.getPlaced();
        }
        if (tablePos != null && mod.getWorld().getBlockState(tablePos).getBlock() == Blocks.CRAFTING_TABLE) {
            if (_pickupTableTask == null || _pickupTableTask.stopped()) {
                _pickupTableTask = new DestroyBlockTask(tablePos);
            }
            _shouldPickupTable = true;
            return _pickupTableTask;
        }
        return null;
    }

    /**
     * Checks if the specified mod is finished.
     *
     * @param mod The mod to check.
     * @return True if the mod is finished, false otherwise.
     */
    @Override
    public boolean isFinished(Belfegor mod) {
        return Arrays.stream(_targets).allMatch(target ->
                mod.getItemStorage().getItemCountInventoryOnly(target.getOutputItem())
                        >= target.getTargetCount())
                && StorageHelper.getItemStackInCursorSlot().isEmpty();
    }

    /**
     * Returns the cost to make a new Belfegor mod.
     *
     * @param mod The Belfegor mod instance.
     * @return The cost to make a new Belfegor mod.
     */
    @Override
    protected double getCostToMakeNew(Belfegor mod) {
        // Get the nearest crafting table.
        Optional<BlockPos> closestCraftingTable = mod.getBlockTracker().getNearestTracking(Blocks.CRAFTING_TABLE);

        // If a crafting table is within 40 blocks of the player, return positive infinity.
        if (closestCraftingTable.isPresent() && closestCraftingTable.get().isWithinDistance(mod.getPlayer().getPos(), 40)) {
            return Double.POSITIVE_INFINITY;
        }

        // If the mod has logs or enough planks, return a cost of 10.
        if (mod.getItemStorage().hasItem(ItemHelper.LOG) || mod.getItemStorage().getItemCount(ItemHelper.PLANKS) >= 4) {
            return 10;
        }

        // Otherwise, return a cost of 100.
        return 100;
    }

    /**
     * Returns an array of materials.
     *
     * @return the array of materials
     */
    private Item[] getMaterialsArray() {
        List<Item> result = new ArrayList<>();

        // Iterate over each target
        for (RecipeTarget target : _targets) {
            // Iterate over each slot in the recipe
            for (int i = 0; i < target.getRecipe().getSlotCount(); ++i) {
                ItemTarget materialTarget = target.getRecipe().getSlot(i);
                // Check if the material target is not null and has matches
                if (materialTarget != null && materialTarget.getMatches() != null) {
                    // Add all the matches to the result list
                    Collections.addAll(result, materialTarget.getMatches());
                }
            }
        }

        // Convert the result list to an array and return it
        return result.toArray(new Item[0]);
    }

}
