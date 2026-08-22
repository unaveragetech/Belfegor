package adris.belfegor.tasks.container;

import adris.belfegor.Belfegor;
import adris.belfegor.debug.DebugLogger;
import adris.belfegor.tasks.InteractWithBlockTask;
import adris.belfegor.tasks.construction.DestroyBlockTask;
import adris.belfegor.tasks.movement.TimeoutWanderTask;
import adris.belfegor.tasksystem.ITaskUsesContainer;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.trackers.storage.ContainerCache;
import adris.belfegor.trackers.storage.ContainerType;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

/**
 * Opens a STORAGE container and does whatever you want inside of it
 */
public abstract class AbstractDoToStorageContainerTask extends Task implements ITaskUsesContainer {

    private ContainerType _currentContainerType = null;
    private int _openTargetMismatchTicks;

    @Override
    protected void onStart(Belfegor mod) {
        _currentContainerType = null;
        _openTargetMismatchTicks = 0;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        Optional<BlockPos> containerTarget = getContainerTarget();

        // No container found
        if (containerTarget.isEmpty()) {
            setDebugState("Wandering");
            _currentContainerType = null;
            return onSearchWander(mod);
        }

        BlockPos targetPos = containerTarget.get();

        boolean handledScreenOpen = MinecraftClient.getInstance().currentScreen instanceof HandledScreen;
        Optional<BlockPos> lastInteracted = mod.getItemStorage().getLastBlockPosInteraction();
        boolean screenBelongsToTarget = lastInteracted.filter(targetPos::equals).isPresent();

        if (handledScreenOpen
                && _currentContainerType != null
                && ContainerType.screenHandlerMatches(_currentContainerType)
                && !screenBelongsToTarget) {
            _openTargetMismatchTicks++;
            if (_openTargetMismatchTicks <= 5) {
                setDebugState("Waiting for container ownership tracking at " + targetPos.toShortString());
                return null;
            }
            DebugLogger.getInstance().log("CONTAINER",
                    "closing-wrong-open-screen target=" + targetPos.toShortString()
                            + " lastInteracted=" + lastInteracted.map(BlockPos::toShortString).orElse("none")
                            + " type=" + _currentContainerType
                            + " task=" + toString());
            StorageHelper.closeScreen();
            _currentContainerType = null;
            _openTargetMismatchTicks = 0;
            return null;
        }

        if (screenBelongsToTarget) {
            _openTargetMismatchTicks = 0;
        }

        if (_currentContainerType == null && mod.getChunkTracker().isChunkLoaded(targetPos)) {
            Block type = mod.getWorld().getBlockState(targetPos).getBlock();
            ContainerType liveType = ContainerType.getFromBlock(type);
            if (liveType != ContainerType.EMPTY
                    && handledScreenOpen
                    && screenBelongsToTarget
                    && ContainerType.screenHandlerMatches(liveType)) {
                _currentContainerType = liveType;
                DebugLogger.getInstance().log("CONTAINER",
                        "adopted-already-open-screen target=" + targetPos.toShortString()
                                + " type=" + _currentContainerType
                                + " task=" + toString()
                                + " note=avoiding reopen spam");
            }
        }

        // We're open
        if (_currentContainerType != null && ContainerType.screenHandlerMatches(_currentContainerType)) {

            // Optional<BlockPos> lastInteracted = mod.getItemStorage().getLastBlockPosInteraction();
            //if (lastInteracted.isPresent() && lastInteracted.get().equals(targetPos)) {
            Optional<ContainerCache> cache = mod.getItemStorage().getContainerAtPosition(targetPos);
            if (cache.isEmpty()) {
                DebugLogger.getInstance().log("CONTAINER",
                        "screen-open-cache-missing target=" + targetPos.toShortString()
                                + " type=" + _currentContainerType
                                + " task=" + toString()
                                + " note=continuing open-screen subtask instead of re-opening container");
            }
            return onContainerOpenSubtask(mod, cache.orElse(null));
            //}
        }

        // Get to the container
        if (_currentContainerType == null && mod.getChunkTracker().isChunkLoaded(targetPos)) {
            Block type = mod.getWorld().getBlockState(targetPos).getBlock();
            _currentContainerType = ContainerType.getFromBlock(type);
        }
        if (WorldHelper.isChest(mod, targetPos) && WorldHelper.isSolid(mod, targetPos.up()) && WorldHelper.canBreak(mod, targetPos.up())) {
            setDebugState("Clearing block above chest");
            return new DestroyBlockTask(targetPos.up());
        }
        setDebugState("Opening container: " + targetPos.toShortString());
        return new InteractWithBlockTask(targetPos);
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        Optional<BlockPos> target = getContainerTarget();
        Optional<BlockPos> lastInteracted = mod.getItemStorage().getLastBlockPosInteraction();
        boolean ownsOpenScreen = _currentContainerType != null
                && ContainerType.screenHandlerMatches(_currentContainerType)
                && target.isPresent()
                && lastInteracted.filter(target.get()::equals).isPresent();
        if (ownsOpenScreen) {
            if (StorageHelper.getItemStackInCursorSlot().isEmpty()) {
                DebugLogger.getInstance().log("CONTAINER",
                        "closing-owned-screen-on-stop target=" + target.get().toShortString()
                                + " type=" + _currentContainerType
                                + " task=" + toString()
                                + " interrupt=" + (interruptTask == null ? "clean" : interruptTask));
                StorageHelper.closeScreen();
            } else {
                DebugLogger.getInstance().logImmediate("CONTAINER",
                        "owned-screen-stop-deferred-cursor-held target=" + target.get().toShortString()
                                + " type=" + _currentContainerType
                                + " task=" + toString()
                                + " cursor=" + StorageHelper.getItemStackInCursorSlot());
            }
        }
        _currentContainerType = null;
        _openTargetMismatchTicks = 0;
    }

    protected abstract Optional<BlockPos> getContainerTarget();

    protected abstract Task onContainerOpenSubtask(Belfegor mod, ContainerCache containerCache);

    // Virtual
    // TODO: Interface this
    protected Task onSearchWander(Belfegor mod) {
        return new TimeoutWanderTask();
    }
}
