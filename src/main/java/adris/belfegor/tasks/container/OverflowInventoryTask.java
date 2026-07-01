package adris.belfegor.tasks.container;

import adris.belfegor.Belfegor;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.tasksystem.ITaskCanForce;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.ItemHelper;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.helpers.WorldHelper;
import adris.belfegor.util.slots.Slot;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Moves surplus inventory into a remembered/nearby overflow chest.
 *
 * This is intentionally conservative: current recipe targets, shulkers, tools
 * in equipment slots, and empty stacks are excluded so overflow management does
 * not sabotage the task that requested it.
 */
public class OverflowInventoryTask extends Task implements ITaskCanForce {

    private final int _desiredFreeSlots;
    private final ItemTarget[] _protect;
    private Task _delegate;

    public OverflowInventoryTask(int desiredFreeSlots, ItemTarget... protect) {
        _desiredFreeSlots = Math.max(1, desiredFreeSlots);
        _protect = protect == null ? new ItemTarget[0] : protect;
    }

    @Override
    protected void onStart(Belfegor mod) {
        _delegate = null;
    }

    @Override
    public boolean shouldForce(Belfegor mod, Task interruptingCandidate) {
        return !StorageHelper.getItemStackInCursorSlot().isEmpty()
                || (_delegate != null && !_delegate.stopped() && !_delegate.isFinished(mod));
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _delegate = null;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        if (_delegate != null && !_delegate.stopped() && !_delegate.isFinished(mod)) {
            return _delegate;
        }
        if (freeSlots(mod) >= _desiredFreeSlots
                && StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            return null;
        }
        ItemTarget[] surplus = findSurplus(mod);
        if (surplus.length == 0) return null;
        setDebugState("Storing overflow inventory in chest " + Arrays.toString(surplus));
        Optional<BlockPos> staging = findReadyConstructionStaging(mod);
        _delegate = staging
                .<Task>map(pos -> new StoreInContainerTask(pos, false, surplus))
                .orElseGet(() -> new StoreInAnyContainerTask(false, false, surplus));
        return _delegate;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return StorageHelper.getItemStackInCursorSlot().isEmpty()
                && (freeSlots(mod) >= _desiredFreeSlots || findSurplus(mod).length == 0);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof OverflowInventoryTask task
                && task._desiredFreeSlots == _desiredFreeSlots
                && Arrays.equals(task._protect, _protect);
    }

    @Override
    protected String toDebugString() {
        return "Store overflow inventory until " + _desiredFreeSlots + " slots free";
    }

    public static int freeSlots(Belfegor mod) {
        int free = 0;
        for (int i = 0; i < 36; i++) {
            Slot slot = Slot.getFromCurrentScreenInventory(i);
            if (slot != null && StorageHelper.getItemStackInSlot(slot).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    private ItemTarget[] findSurplus(Belfegor mod) {
        Set<Item> protectedItems = new HashSet<>();
        for (ItemTarget target : _protect) {
            if (target != null) protectedItems.addAll(Arrays.asList(target.getMatches()));
        }
        protectedItems.addAll(Arrays.asList(ItemHelper.SHULKER_BOXES));
        // Keep the basic wood crafting chain in hand. If these are staged as
        // "surplus" while a tool/table/stick recipe is active, the resource
        // task immediately withdraws them again and can ping-pong forever.
        protectedItems.addAll(Arrays.asList(ItemHelper.LOG));
        protectedItems.addAll(Arrays.asList(ItemHelper.PLANKS));
        protectedItems.addAll(Arrays.asList(
                Items.STICK,
                Items.CHEST,
                Items.TRAPPED_CHEST,
                Items.BARREL,
                Items.BUCKET,
                Items.WATER_BUCKET,
                Items.WHEAT_SEEDS,
                Items.WHEAT,
                Items.WOODEN_HOE,
                Items.STONE_HOE,
                Items.IRON_HOE,
                Items.GOLDEN_HOE,
                Items.DIAMOND_HOE,
                Items.NETHERITE_HOE,
                Items.CRAFTING_TABLE,
                Items.FURNACE,
                Items.BLAST_FURNACE,
                Items.SMOKER,
                Items.SMITHING_TABLE,
                Items.ANVIL,
                Items.CHIPPED_ANVIL,
                Items.DAMAGED_ANVIL,
                Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE,
                Items.NETHERITE_INGOT,
                Items.NETHERITE_SCRAP,
                Items.DIAMOND,
                Items.DIAMOND_BLOCK,
                Items.EMERALD,
                Items.EMERALD_BLOCK
        ));

        Set<Item> seen = new HashSet<>();
        List<ItemTarget> result = new ArrayList<>();
        collectSurplusPass(mod, protectedItems, seen, result, true);
        collectSurplusPass(mod, protectedItems, seen, result, false);
        return result.toArray(ItemTarget[]::new);
    }

    private Optional<BlockPos> findReadyConstructionStaging(Belfegor mod) {
        if (mod.getPlayer() == null) return Optional.empty();
        String dimension = WorldHelper.getCurrentDimension().name();
        Optional<BlockPos> remembered = BaseMemory.getInstance()
                .findNearestModule(mod.getPlayer().getBlockPos(), dimension, "construction_staging_chest")
                .or(() -> BaseMemory.getInstance()
                        .findNearestModule(mod.getPlayer().getBlockPos(), dimension, "construction_staging"))
                .map(module -> new BlockPos(module.x, module.y, module.z))
                .filter(pos -> mod.getWorld().getBlockState(pos).getBlock() == Blocks.CHEST);
        if (remembered.isPresent()) return remembered;

        return BaseMemory.getInstance()
                .nearestBase(mod.getPlayer().getBlockPos(), dimension)
                .map(base -> base.center().add(2, 0, -2))
                .filter(pos -> mod.getWorld().getBlockState(pos).getBlock() == Blocks.CHEST);
    }

    private void collectSurplusPass(Belfegor mod,
                                    Set<Item> protectedItems,
                                    Set<Item> seen,
                                    List<ItemTarget> result,
                                    boolean throwawayOnly) {
        if (result.size() >= 12) return;
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(false)) {
            if (result.size() >= 12) break;
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (protectedItems.contains(item)) continue;
            if (stack.isDamageable() || stack.getMaxCount() <= 1) continue;
            if (seen.contains(item)) continue;
            boolean throwaway = ItemHelper.canThrowAwayStack(mod, stack);
            if (throwawayOnly != throwaway) continue;
            seen.add(item);
            result.add(new ItemTarget(item, Math.max(1, stack.getCount())));
        }
    }
}
