package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.tasks.ResourceTask;
import adris.belfegor.tasks.construction.DestroyBlockTask;
import adris.belfegor.tasks.movement.DefaultGoToDimensionTask;
import adris.belfegor.tasks.movement.SearchChunkForBlockTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.Dimension;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

public class GetSmithingTemplateTask extends ResourceTask {

    private final Task _searcher = new SearchChunkForBlockTask(Blocks.BLACKSTONE);
    private final int _count;
    private BlockPos _chestloc = null;

    public GetSmithingTemplateTask(int count) {
        super(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, count);
        _count = count;
    }

    @Override
    protected void onResourceStart(Belfegor mod) {
        mod.getBlockTracker().trackBlock(Blocks.CHEST);
    }

    @Override
    protected Task onResourceTick(Belfegor mod) {
        // We must go to the nether.
        if (WorldHelper.getCurrentDimension() != Dimension.NETHER) {
            setDebugState("Going to nether");
            return new DefaultGoToDimensionTask(Dimension.NETHER);
        }
        //if (_bastionloc != null && !mod.getChunkTracker().isChunkLoaded(_bastionloc)) {
        //    Debug.logMessage("Bastion at " + _bastionloc + " too far away. Re-searching.");
        //    _bastionloc = null;
        // }
        if (_chestloc == null) {
            if (mod.getBlockTracker().isTracking(Blocks.CHEST)) {
                Optional<BlockPos> chest = mod.getBlockTracker().getNearestTracking(Blocks.CHEST);
                if (chest.isPresent() && WorldHelper.isInteractableBlock(mod, chest.get())) {
                    _chestloc = chest.get();
                }
            }
        }
        if (_chestloc != null) {
            //if (!_chestloc.isWithinDistance(mod.getPlayer().getPos(), 150)) {
            setDebugState("Destroying Chest"); // TODO: Make It check the chest instead of destroying it
            if (WorldHelper.isInteractableBlock(mod, _chestloc)) {
                return new DestroyBlockTask(_chestloc);
            } else {
                _chestloc = null;
                if (mod.getBlockTracker().isTracking(Blocks.CHEST)) {
                    Optional<BlockPos> chest = mod.getBlockTracker().getNearestTracking(Blocks.CHEST);
                    if (chest.isPresent() && WorldHelper.isInteractableBlock(mod, chest.get())) {
                        _chestloc = chest.get();
                    }
                }
            }
            //}
        }
        setDebugState("Searching for/Traveling around bastion");
        return _searcher;
    }

    @Override
    protected void onResourceStop(Belfegor mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.CHEST);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof GetSmithingTemplateTask;
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + _count + " smithing templates";
    }

    @Override
    protected boolean shouldAvoidPickingUp(Belfegor mod) {
        return false;
    }
}
