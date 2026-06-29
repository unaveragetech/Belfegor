package adris.belfegor.tasks.container;

import adris.belfegor.Belfegor;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.ItemHelper;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.slots.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Moves surplus inventory into a remembered/nearby overflow chest.
 *
 * This is intentionally conservative: current recipe targets, shulkers, tools
 * in equipment slots, and empty stacks are excluded so overflow management does
 * not sabotage the task that requested it.
 */
public class OverflowInventoryTask extends Task {

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
    protected void onStop(Belfegor mod, Task interruptTask) {
        _delegate = null;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        if (freeSlots(mod) >= _desiredFreeSlots) return null;
        if (_delegate != null && !_delegate.stopped() && !_delegate.isFinished(mod)) {
            return _delegate;
        }
        ItemTarget[] surplus = findSurplus(mod);
        if (surplus.length == 0) return null;
        setDebugState("Storing overflow inventory in chest " + Arrays.toString(surplus));
        _delegate = new StoreInAnyContainerTask(false, false, surplus);
        return _delegate;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return freeSlots(mod) >= _desiredFreeSlots || findSurplus(mod).length == 0;
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
        protectedItems.addAll(Arrays.asList(
                Items.CHEST,
                Items.TRAPPED_CHEST,
                Items.BARREL,
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
