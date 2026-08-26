package adris.belfegor.tasks.container;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.tasks.DoToClosestBlockTask;
import adris.belfegor.tasks.InteractWithBlockTask;
import adris.belfegor.tasks.construction.PlaceBlockNearbyTask;
import adris.belfegor.tasks.slot.EnsureFreeInventorySlotTask;
import adris.belfegor.tasksystem.ITaskUsesContainer;
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
public abstract class DoStuffInContainerTask extends Task implements ITaskUsesContainer {

    private final ItemTarget _containerTarget;
    private final Block[] _containerBlocks;

    protected final PlaceBlockNearbyTask _placeTask;
    // If we decided on placing, force place for at least 10 seconds
    private final TimerGame _placeForceTimer = new TimerGame(10);
    // If we just placed something, stop placing and try going to the nearest container.
    private final TimerGame _justPlacedTimer = new TimerGame(3);
    // Throttle for the direct world scan used to find containers the block
    // tracker missed (freshly placed, tracker cleared, base fixtures).
    private final TimerGame _nearbyScanTimer = new TimerGame(0.5);
    private Optional<BlockPos> _cachedNearbyWorld = Optional.empty();
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

        // Scan the loaded world directly: the block tracker may not know about
        // a container that is right next to us (freshly placed, tracker cleared,
        // or a base fixture it never scanned). Crafting/placing a new crafting
        // table while one already sits next to the bot caused endless 2x2 craft
        // restarts and placement cycles.
        if (_nearbyScanTimer.elapsed()) {
            _nearbyScanTimer.reset();
            _cachedNearbyWorld = findNearbyContainerInWorld(mod, 12);
        }
        Optional<BlockPos> nearbyWorld = _cachedNearbyWorld;

        // Once placement starts it owns this phase until the world confirms the
        // block. Baritone may move/consume the item one tick before the block
        // tracker observes the placement. Rechecking inventory during that gap
        // used to interrupt placement with a fresh container craft, producing a
        // rapid 2x2/3x3 screen storm and never allowing the block update to land.
        // Exception: when a usable container is already right next to us (for
        // example the area is already full of placed crafting tables), stop
        // forcing placement and use the nearby one instead.
        if (_placeTask.isActive() && !_placeTask.isFinished(mod)) {
            if (nearbyWorld.isPresent()
                    && nearbyWorld.get().isWithinDistance(mod.getPlayer().getPos(), 6)) {
                setDebugState("Using nearby container instead of placing a new one");
                _placeForceTimer.forceElapse();
                _placeTask.stop(mod);
            } else {
                setDebugState("Finishing active container placement");
                return _placeTask;
            }
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
            nearest = mod.getBlockTracker().getNearestTracking(currentPos,
                    blockPos -> WorldHelper.canReach(mod, blockPos)
                            && isContainerBlockAt(mod, blockPos),
                    _containerBlocks);
        }
        if (nearest.isEmpty()) {
            // If all else fails, trust our placed task directly.
            // We just placed this block, so we KNOW it's there.
            // blockIsValid often returns false because the block tracker
            // hasn't scanned the newly placed block yet.
            nearest = Optional.ofNullable(_placeTask.getPlaced());
        }
        // A container found by the direct world scan beats a far tracked one
        // (and is the only option when the tracker has nothing at all).
        if (override == null && nearbyWorld.isPresent()
                && (nearest.isEmpty()
                || nearbyWorld.get().getSquaredDistance(currentPos)
                < nearest.get().getSquaredDistance(currentPos))) {
            nearest = nearbyWorld;
        }
        if (nearest.isPresent()) {
            costToWalk = BaritoneHelper.calculateGenericHeuristic(currentPos, WorldHelper.toVec3d(nearest.get()));
        }
        // A container within six blocks is always worth walking to — never
        // craft or place a new one while a table is right there.
        if (nearest.isPresent() && nearest.get().isWithinDistance(currentPos, 6)) {
            _placeForceTimer.forceElapse();
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

    /** True when the world still has one of our target container blocks at the
     *  given position. Guards against walking to a stale tracker entry whose
     *  block was broken or moved since the last scan. */
    /** Scans the loaded world for one of our target container blocks near the
     *  player, so a container the block tracker missed is still used instead of
     *  crafting/placing a new one. */
    private Optional<BlockPos> findNearbyContainerInWorld(Belfegor mod, int radius) {
        if (mod.getWorld() == null || mod.getPlayer() == null) return Optional.empty();
        BlockPos center = mod.getPlayer().getBlockPos();
        BlockPos best = null;
        double bestSq = Double.POSITIVE_INFINITY;
        for (int dy = -4; dy <= 4; ++dy) {
            for (int dx = -radius; dx <= radius; ++dx) {
                for (int dz = -radius; dz <= radius; ++dz) {
                    if (dx * dx + dz * dz > radius * radius) continue;
                    BlockPos candidate = center.add(dx, dy, dz);
                    if (!mod.getChunkTracker().isChunkLoaded(candidate)) continue;
                    if (!isContainerBlockAt(mod, candidate)) continue;
                    if (!WorldHelper.canReach(mod, candidate)) continue;
                    double sq = candidate.getSquaredDistance(center);
                    if (sq < bestSq) {
                        bestSq = sq;
                        best = candidate;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private boolean isContainerBlockAt(Belfegor mod, BlockPos pos) {
        if (pos == null || mod.getWorld() == null) return false;
        net.minecraft.block.Block block = mod.getWorld().getBlockState(pos).getBlock();
        for (net.minecraft.block.Block container : _containerBlocks) {
            if (block == container) return true;
        }
        return false;
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
