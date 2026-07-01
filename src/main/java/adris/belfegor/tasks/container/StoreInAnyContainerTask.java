package adris.belfegor.tasks.container;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.tasks.DoToClosestBlockTask;
import adris.belfegor.tasks.construction.PlaceBlockNearbyTask;
import adris.belfegor.tasks.construction.PlaceBlockTask;
import adris.belfegor.tasks.slot.EnsureFreeInventorySlotTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.trackers.storage.ContainerCache;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.ItemHelper;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.helpers.WorldHelper;
import adris.belfegor.util.progresscheck.MovementProgressChecker;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Dumps items in any container, placing a chest if we can't find any.
 */
public class StoreInAnyContainerTask extends Task {

    private static final Block[] OVERFLOW_SCAN = new Block[]{Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL};
    private static final Block[] TO_SCAN = Stream.concat(Arrays.stream(OVERFLOW_SCAN), Arrays.stream(ItemHelper.itemsToBlocks(ItemHelper.SHULKER_BOXES))).toArray(Block[]::new);
    private static BlockPos rememberedOverflowContainer = null;
    private final ItemTarget[] _toStore;
    private final boolean _getIfNotPresent;
    private final boolean _allowShulkers;
    private final HashSet<BlockPos> _dungeonChests = new HashSet<>();
    private final HashSet<BlockPos> _nonDungeonChests = new HashSet<>();
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
    private final ContainerStoredTracker _storedItems = new ContainerStoredTracker(slot -> true);
    private BlockPos _currentChestTry = null;
    private Task _emergencyFreeSlotTask = null;

    public StoreInAnyContainerTask(boolean getIfNotPresent, ItemTarget... toStore) {
        this(getIfNotPresent, true, toStore);
    }

    /**
     * @param allowShulkers true for general storage commands, false for
     *                      emergency overflow. Overflow should be boring,
     *                      craftable, and reusable, so it uses chests/barrels
     *                      instead of shulker sub-inventory transactions.
     */
    public StoreInAnyContainerTask(boolean getIfNotPresent, boolean allowShulkers, ItemTarget... toStore) {
        _getIfNotPresent = getIfNotPresent;
        _allowShulkers = allowShulkers;
        _toStore = toStore;
    }

    @Override
    protected void onStart(Belfegor mod) {
        mod.getBlockTracker().trackBlock(blocksToScan());
        _storedItems.startTracking();
        _dungeonChests.clear();
        _nonDungeonChests.clear();
        _emergencyFreeSlotTask = null;
    }

    @Override
    protected Task onTick(Belfegor mod) {

        // Get more if we don't have & "get if not present" is true.
        if (_getIfNotPresent) {
            for (ItemTarget target : _toStore) {
                int inventoryNeed = target.getTargetCount() - _storedItems.getStoredCount(target.getMatches());
                if (inventoryNeed > mod.getItemStorage().getItemCount(target)) {
                    return TaskCatalogue.getItemTask(new ItemTarget(target, inventoryNeed));
                }
            }
        }

        // ItemTargets we haven't stored yet
        ItemTarget[] notStored = _storedItems.getUnstoredItemTargetsYouCanStore(mod, _toStore);
        if (notStored.length == 0 && StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            StorageHelper.closeScreen();
            setDebugState("Nothing left to store; releasing overflow container.");
            return null;
        }

        Predicate<BlockPos> validContainer = containerPos -> {
            if (!_allowShulkers && mod.getPlayer() != null) {
                BlockPos playerPos = mod.getPlayer().getBlockPos();
                if (containerPos.getSquaredDistance(playerPos) > 12 * 12) {
                    return false;
                }
                if (Math.abs(containerPos.getY() - playerPos.getY()) > 4) {
                    return false;
                }
            }

            // If it's a chest and the block above can't be broken, we can't open this one.
            boolean isChest = WorldHelper.isChest(mod, containerPos);
            if (isChest && WorldHelper.isSolid(mod, containerPos.up()) && !WorldHelper.canBreak(mod, containerPos.up()))
                return false;

            //if (!_acceptableContainer.test(containerPos))
            //    return false;

            Optional<ContainerCache> data = mod.getItemStorage().getContainerAtPosition(containerPos);

            if (data.isPresent() && data.get().isFull()) return false;

            if (isChest && mod.getModSettings().shouldAvoidSearchingForDungeonChests()) {
                boolean cachedDungeon = _dungeonChests.contains(containerPos) && !_nonDungeonChests.contains(containerPos);
                if (cachedDungeon) {
                    return false;
                }
                // Spawner
                int range = 6;
                for (int dx = -range; dx <= range; ++dx) {
                    for (int dz = -range; dz <= range; ++dz) {
                        BlockPos offset = containerPos.add(dx, 0, dz);
                        if (mod.getWorld().getBlockState(offset).getBlock() == Blocks.SPAWNER) {
                            _dungeonChests.add(containerPos);
                            return false;
                        }
                    }
                }
                _nonDungeonChests.add(containerPos);
            }
            return true;
        };

        if (!_allowShulkers && rememberedOverflowContainer != null
                && validContainer.test(rememberedOverflowContainer)
                && isOverflowContainerBlock(mod, rememberedOverflowContainer)) {
            setDebugState("Using remembered overflow container " + rememberedOverflowContainer.toShortString());
            _currentChestTry = rememberedOverflowContainer;
            return new StoreInContainerTask(rememberedOverflowContainer, _getIfNotPresent, notStored);
        }

        if (mod.getBlockTracker().anyFound(validContainer, blocksToScan())) {

            setDebugState("Going to container and depositing items");

            if (!_progressChecker.check(mod) && _currentChestTry != null) {
                Debug.logMessage("Failed to open container. Suggesting it may be unreachable.");
                mod.getBlockTracker().requestBlockUnreachable(_currentChestTry, 2);
                _currentChestTry = null;
                _progressChecker.reset();
            }

            return new DoToClosestBlockTask(
                    blockPos -> {
                        if (_currentChestTry != blockPos) {
                            _progressChecker.reset();
                        }
                        _currentChestTry = blockPos;
                        if (!_allowShulkers) {
                            rememberedOverflowContainer = blockPos;
                        }
                        return new StoreInContainerTask(blockPos, _getIfNotPresent, notStored);
                    },
                    validContainer,
                    blocksToScan());
        }

        _progressChecker.reset();
        // Craft + place chest nearby
        for (Block couldPlace : blocksToScan()) {
            if (mod.getItemStorage().hasItem(couldPlace.asItem())) {
                if (!_allowShulkers) {
                    Optional<BlockPos> deterministic = findEmergencyContainerPlacement(mod, couldPlace);
                    if (deterministic.isPresent()) {
                        setDebugState("Placing overflow container at stable nearby position "
                                + deterministic.get().toShortString());
                        return new PlaceBlockTask(deterministic.get(), couldPlace);
                    }
                }
                setDebugState("Placing container nearby");
                return new PlaceBlockNearbyTask(canPlace -> {
                    if (!_allowShulkers && mod.getPlayer() != null) {
                        BlockPos playerPos = mod.getPlayer().getBlockPos();
                        if (canPlace.getSquaredDistance(playerPos) > 8 * 8) {
                            return false;
                        }
                        if (Math.abs(canPlace.getY() - playerPos.getY()) > 2) {
                            return false;
                        }
                    }
                    // For chests, above must be air OR breakable.
                    if (WorldHelper.isChest(couldPlace)) {
                        return WorldHelper.isAir(mod, canPlace.up()) || WorldHelper.canBreak(mod, canPlace.up());
                    }
                    return true;
                }, couldPlace);
            }
        }
        if (!_allowShulkers && OverflowInventoryTask.freeSlots(mod) <= 0) {
            if (_emergencyFreeSlotTask == null
                    || _emergencyFreeSlotTask.stopped()
                    || _emergencyFreeSlotTask.isFinished(mod)) {
                _emergencyFreeSlotTask = new EnsureFreeInventorySlotTask(false);
            }
            setDebugState("Emergency overflow has no container; freeing one slot without recursive overflow");
            return _emergencyFreeSlotTask;
        }
        setDebugState("Obtaining a chest item (by default)");
        return TaskCatalogue.getItemTask(Items.CHEST, 1);
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        // We've stored all items
        return _storedItems.getUnstoredItemTargetsYouCanStore(mod, _toStore).length == 0;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _storedItems.stopTracking();
        mod.getBlockTracker().stopTracking(blocksToScan());
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof StoreInAnyContainerTask task) {
            return task._getIfNotPresent == _getIfNotPresent
                    && task._allowShulkers == _allowShulkers
                    && Arrays.equals(task._toStore, _toStore);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Storing in " + (_allowShulkers ? "any container" : "overflow chest")
                + ": " + Arrays.toString(_toStore);
    }

    private Block[] blocksToScan() {
        return _allowShulkers ? TO_SCAN : OVERFLOW_SCAN;
    }

    private boolean isOverflowContainerBlock(Belfegor mod, BlockPos pos) {
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        return Arrays.asList(OVERFLOW_SCAN).contains(block);
    }

    private Optional<BlockPos> findEmergencyContainerPlacement(Belfegor mod, Block block) {
        if (mod.getPlayer() == null) return Optional.empty();
        BlockPos player = mod.getPlayer().getBlockPos();
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        int range = 5;
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -range; dx <= range; dx++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos candidate = player.add(dx, dy, dz);
                    if (WorldHelper.isInsidePlayer(mod, candidate)) continue;
                    if (candidate.getSquaredDistance(player) > range * range) continue;
                    if (!WorldHelper.isSolid(mod, candidate.down())) continue;
                    if (!mod.getWorld().getBlockState(candidate).isAir()) continue;
                    if (!mod.getWorld().getBlockState(candidate.up()).isAir()) continue;
                    if (WorldHelper.isChest(block)
                            && !(WorldHelper.isAir(mod, candidate.up()) || WorldHelper.canBreak(mod, candidate.up()))) {
                        continue;
                    }
                    if (!WorldHelper.canPlace(mod, candidate)) continue;
                    double yPenalty = Math.abs(candidate.getY() - player.getY()) * 3.0;
                    double score = candidate.getSquaredDistance(player) + yPenalty;
                    if (score < bestScore) {
                        best = candidate;
                        bestScore = score;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
