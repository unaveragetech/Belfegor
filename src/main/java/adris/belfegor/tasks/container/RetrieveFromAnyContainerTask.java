package adris.belfegor.tasks.container;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.tasks.DoToClosestBlockTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.trackers.storage.ContainerCache;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.ItemHelper;
import adris.belfegor.util.helpers.WorldHelper;
import adris.belfegor.util.progresscheck.MovementProgressChecker;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Retrieves items from any nearby container.
 */
public class RetrieveFromAnyContainerTask extends Task {

    private static final Block[] TO_SCAN = Stream.concat(
            Arrays.stream(new Block[]{Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.ENDER_CHEST}),
            Arrays.stream(ItemHelper.itemsToBlocks(ItemHelper.SHULKER_BOXES))
    ).toArray(Block[]::new);

    private final ItemTarget[] _targets;
    private final HashSet<BlockPos> _failedContainers = new HashSet<>();
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
    private BlockPos _currentTry = null;

    public RetrieveFromAnyContainerTask(ItemTarget... targets) {
        _targets = targets;
    }

    @Override
    protected void onStart(Belfegor mod) {
        mod.getBlockTracker().trackBlock(TO_SCAN);
    }

    @Override
    protected Task onTick(Belfegor mod) {
        // Check if we already have enough items in inventory
        boolean haveAll = Arrays.stream(_targets).allMatch(
                target -> mod.getItemStorage().getItemCountInventoryOnly(target.getMatches()) >= target.getTargetCount()
        );
        if (haveAll) return null;

        Predicate<BlockPos> validContainer = pos -> {
            if (_failedContainers.contains(pos)) return false;
            if (WorldHelper.isChest(mod, pos) && WorldHelper.isSolid(mod, pos.up()) && !WorldHelper.canBreak(mod, pos.up()))
                return false;
            Optional<ContainerCache> data = mod.getItemStorage().getContainerAtPosition(pos);
            if (data.isEmpty()) return true; // Uncached — might have items
            // Only consider containers that have at least one target item
            return Arrays.stream(_targets).anyMatch(target -> data.get().hasItem(target.getMatches()));
        };

        if (mod.getBlockTracker().anyFound(validContainer, TO_SCAN)) {
            setDebugState("Going to container and retrieving items");

            if (!_progressChecker.check(mod) && _currentTry != null) {
                Debug.logMessage("Failed to retrieve from container. Marking unreachable.");
                mod.getBlockTracker().requestBlockUnreachable(_currentTry, 2);
                _currentTry = null;
                _progressChecker.reset();
            }

            return new DoToClosestBlockTask(
                    blockPos -> {
                        if (_currentTry != blockPos) {
                            _progressChecker.reset();
                        }
                        _currentTry = blockPos;
                        return new PickupFromContainerTask(blockPos, _targets);
                    },
                    validContainer,
                    TO_SCAN);
        }

        _progressChecker.reset();
        setDebugState("No containers with target items found nearby");
        return null;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return Arrays.stream(_targets).allMatch(
                target -> mod.getItemStorage().getItemCountInventoryOnly(target.getMatches()) >= target.getTargetCount()
        );
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(TO_SCAN);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof RetrieveFromAnyContainerTask task) {
            return Arrays.equals(task._targets, _targets);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Retrieving from container: " + Arrays.toString(_targets);
    }
}
