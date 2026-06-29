package adris.belfegor.tasks.container;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.tasks.DoToClosestBlockTask;
import adris.belfegor.tasks.InteractWithBlockTask;
import adris.belfegor.tasks.construction.PlaceBlockNearbyTask;
import adris.belfegor.tasks.slot.EnsureFreeInventorySlotTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.BaritoneHelper;
import adris.belfegor.util.helpers.ItemHelper;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.helpers.WorldHelper;
import adris.belfegor.util.slots.Slot;
import adris.belfegor.util.time.TimerGame;
import net.minecraft.block.Block;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.Optional;


/**
 * Interacts with a container, obtaining and placing one if none were found nearby.
 */
public abstract class DoStuffInContainerTask extends Task {

    private final ItemTarget _containerTarget;
    private final Block[] _containerBlocks;

    protected final PlaceBlockNearbyTask _placeTask;
    // If we decided on placing, force place for at least 10 seconds
    private final TimerGame _placeForceTimer = new TimerGame(10);
    // If we just placed something, stop placing and try going to the nearest container.
    private final TimerGame _justPlacedTimer = new TimerGame(3);
    protected BlockPos _cachedContainerPosition = null;
    private Task _openTableTask;

    public DoStuffInContainerTask(Block[] containerBlocks, ItemTarget containerTarget) {
        _containerBlocks = containerBlocks;
        _containerTarget = containerTarget;

        _placeTask = new PlaceBlockNearbyTask(_containerBlocks);
    }

    public DoStuffInContainerTask(Block containerBlock, ItemTarget containerTarget) {
        this(new Block[]{containerBlock}, containerTarget);
    }

    @Override
    protected void onStart(Belfegor mod) {
        mod.getBehaviour().push();
        if (_openTableTask == null) {
            _openTableTask = new DoToClosestBlockTask(InteractWithBlockTask::new, _containerBlocks);
        }

        mod.getBlockTracker().trackBlock(_containerBlocks);

        // Do not protect the container item here. Baritone's builder excludes
        // protected items from its available placement palette, so protecting a
        // crafting table/furnace/chest immediately before placing it can make
        // placement fail even while the item is in inventory.
    }

    @Override
    protected Task onTick(Belfegor mod) {

        // If we're placing, keep on placing.
        if (mod.getItemStorage().hasItem(ItemHelper.blocksToItems(_containerBlocks)) && _placeTask.isActive() && !_placeTask.isFinished(mod)) {
            setDebugState("Placing container");
            return _placeTask;
        }

        if (isContainerOpen(mod)) {
            return containerSubTask(mod);
        }

        // infinity if such a container does not exist.
        double costToWalk = Double.POSITIVE_INFINITY;

        Optional<BlockPos> nearest;

        Vec3d currentPos = mod.getPlayer().getPos();
        BlockPos override = overrideContainerPosition(mod);

        if (override != null && mod.getBlockTracker().blockIsValid(override, _containerBlocks)) {
            // We have an override so go there instead.
            nearest = Optional.of(override);
        } else {
            // Track nearest container
            nearest = mod.getBlockTracker().getNearestTracking(currentPos, blockPos -> WorldHelper.canReach(mod, blockPos), _containerBlocks);
        }
        if (nearest.isEmpty()) {
            // If all else fails, trust our placed task directly.
            // We just placed this block, so we KNOW it's there.
            // blockIsValid often returns false because the block tracker
            // hasn't scanned the newly placed block yet.
            nearest = Optional.ofNullable(_placeTask.getPlaced());
        }
        if (nearest.isPresent()) {
            costToWalk = BaritoneHelper.calculateGenericHeuristic(currentPos, WorldHelper.toVec3d(nearest.get()));
        }

        // Make a new container if going to the container is a pretty bad cost.
        // Also keep on making the container if we're stuck in some
        if (costToWalk > getCostToMakeNew(mod)) {
            _placeForceTimer.reset();
        }
        if (nearest.isEmpty() || (!_placeForceTimer.elapsed() && _justPlacedTimer.elapsed())) {
            // It's cheaper to make a new one, or our only option.

            // We're no longer going to our previous container.
            _cachedContainerPosition = null;

            // Get if we don't have...
            if (!mod.getItemStorage().hasItem(_containerTarget)) {
                setDebugState("Getting container item");
                return TaskCatalogue.getItemTask(_containerTarget);
            }

            setDebugState("Placing container...");

            _justPlacedTimer.reset();
            // Now place!
            return _placeTask;
        }

        // This is insanely cursed.
        // TODO: Finish committing to optionals, this is ugly.
        _cachedContainerPosition = nearest.get();

        // Walk to it and open it

        // Wait for food
        if (mod.getFoodChain().needsToEat()) {
            setDebugState("Waiting for eating...");
            return null;
        }
        setDebugState("Walking to container... " + nearest.get().toShortString());

        if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            Optional<Slot> toMoveTo = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(StorageHelper.getItemStackInCursorSlot(), false);
            if (toMoveTo.isEmpty()) {
                return new EnsureFreeInventorySlotTask();
            }
            if (ItemHelper.canThrowAwayStack(mod, StorageHelper.getItemStackInCursorSlot())) {
                mod.getSlotHandler().clickSlot(Slot.UNDEFINED, 0, SlotActionType.PICKUP);
                return null;
            }
            mod.getSlotHandler().clickSlot(toMoveTo.get(), 0, SlotActionType.PICKUP);
            return null;
        }
        return _openTableTask;
        //return new GetToBlockTask(nearest, true);
    }

    public ItemTarget getContainerTarget() {
        return _containerTarget;
    }

    // Virtual
    protected BlockPos overrideContainerPosition(Belfegor mod) {
        return null;
    }

    protected BlockPos getTargetContainerPosition() {
        return _cachedContainerPosition;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        mod.getBehaviour().pop();
        mod.getBlockTracker().stopTracking(_containerBlocks);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DoStuffInContainerTask task) {
            if (!Arrays.equals(task._containerBlocks, _containerBlocks)) return false;
            if (!task._containerTarget.equals(_containerTarget)) return false;
            return isSubTaskEqual(task);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Doing stuff in " + _containerTarget + " container";
    }

    protected abstract boolean isSubTaskEqual(DoStuffInContainerTask other);

    protected abstract boolean isContainerOpen(Belfegor mod);

    protected abstract Task containerSubTask(Belfegor mod);

    protected abstract double getCostToMakeNew(Belfegor mod);
}
