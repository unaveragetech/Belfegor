package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.tasks.DoToClosestBlockTask;
import adris.belfegor.tasks.ResourceTask;
import adris.belfegor.tasks.construction.DestroyBlockTask;
import adris.belfegor.tasks.movement.SearchWithinBiomeTask;
import adris.belfegor.tasksystem.Task;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CocoaBlock;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.BiomeKeys;

import java.util.HashSet;
import java.util.function.Predicate;

public class CollectCocoaBeansTask extends ResourceTask {
    private final int _count;
    private final HashSet<BlockPos> _wasFullyGrown = new HashSet<>();

    public CollectCocoaBeansTask(int targetCount) {
        super(Items.COCOA_BEANS, targetCount);
        _count = targetCount;
    }

    @Override
    protected boolean shouldAvoidPickingUp(Belfegor mod) {
        return false;
    }

    @Override
    protected void onResourceStart(Belfegor mod) {
        mod.getBlockTracker().trackBlock(Blocks.COCOA);
    }

    @Override
    protected Task onResourceTick(Belfegor mod) {

        Predicate<BlockPos> validCocoa = (blockPos) -> {
            if (!mod.getChunkTracker().isChunkLoaded(blockPos)) {
                return _wasFullyGrown.contains(blockPos);
            }

            BlockState s = mod.getWorld().getBlockState(blockPos);
            boolean mature = s.get(CocoaBlock.AGE) == 2;
            if (_wasFullyGrown.contains(blockPos)) {
                if (!mature) _wasFullyGrown.remove(blockPos);
            } else {
                if (mature) _wasFullyGrown.add(blockPos);
            }
            return mature;
        };

        // Break mature cocoa blocks
        if (mod.getBlockTracker().anyFound(validCocoa, Blocks.COCOA)) {
            setDebugState("Breaking cocoa blocks");
            return new DoToClosestBlockTask(DestroyBlockTask::new, validCocoa, Blocks.COCOA);
        }

        // Dimension
        if (isInWrongDimension(mod)) {
            return getToCorrectDimensionTask(mod);
        }

        // Search for jungles
        setDebugState("Exploring around jungles");
        return new SearchWithinBiomeTask(BiomeKeys.JUNGLE);
    }

    @Override
    protected void onResourceStop(Belfegor mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.COCOA);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof CollectCocoaBeansTask;
    }

    @Override
    protected String toDebugStringName() {
        return "Collecting " + _count + " cocoa beans.";
    }
}
