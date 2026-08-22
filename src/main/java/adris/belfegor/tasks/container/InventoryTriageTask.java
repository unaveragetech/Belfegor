package adris.belfegor.tasks.container;

import adris.belfegor.Belfegor;
import adris.belfegor.memory.BaseStorageMemory;
import adris.belfegor.memory.ErrandMemory;
import adris.belfegor.tasks.slot.DropJunkToMakeSpaceTask;
import adris.belfegor.tasksystem.ITaskCanForce;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.ItemHelper;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Stores inventory the bot does not need right now into the base storage
 * network while keeping a compact "field kit" (tools, armor, food, torches,
 * a crafting table...) that the bot will need away from home.
 *
 * This is the player-like "use the chest room" behavior: surplus goes in,
 * essentials stay in hand, and the storage network grows as the bot plays.
 */
public class InventoryTriageTask extends Task implements ITaskCanForce {

    private final int _desiredFreeSlots;
    private final Set<Item> _keep;
    private final ItemTarget[] _surplusThresholds;
    private final ItemTarget[] _mustStore;
    private Task _delegate;
    private boolean _attempted;
    private boolean _dropStage;
    private ItemTarget[] _lastSurplus;
    private BlockPos _lastChest;

    public InventoryTriageTask(int desiredFreeSlots, Item[] keep,
                               ItemTarget[] surplusThresholds,
                               ItemTarget[] mustStore) {
        _desiredFreeSlots = Math.max(1, desiredFreeSlots);
        _keep = new HashSet<>();
        if (keep != null) _keep.addAll(Arrays.asList(keep));
        _surplusThresholds = surplusThresholds == null ? new ItemTarget[0] : surplusThresholds;
        _mustStore = mustStore == null ? new ItemTarget[0] : mustStore;
    }

    @Override
    protected void onStart(Belfegor mod) {
        _delegate = null;
        _attempted = false;
        _dropStage = false;
        _lastSurplus = null;
        _lastChest = null;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        if (_delegate != null && !_delegate.stopped() && !_delegate.isFinished(mod)) {
            return _delegate;
        }
        if (_delegate != null) {
            if (!_dropStage && _lastSurplus != null && _lastChest != null) {
                BlockPos home = mod.getModSettings().getHomeBasePosition();
                if (home != null) {
                    ErrandMemory.getInstance().recordStored(
                            home, _lastChest, WorldHelper.getCurrentDimension().name(),
                            "triage", _lastSurplus);
                    ErrandMemory.getInstance().save();
                }
            }
            _delegate = null;
        }
        if (!StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            return null;
        }
        ItemTarget[] surplus = findSurplus(mod);
        if (surplus.length == 0 && OverflowInventoryTask.freeSlots(mod) >= _desiredFreeSlots) {
            return null;
        }
        if (surplus.length == 0 && _dropStage) return null;
        if (surplus.length > 0 && !_attempted) {
            setDebugState("Storing unused inventory " + Arrays.toString(surplus));
            BlockPos home = mod.getModSettings().getHomeBasePosition();
            String dimension = WorldHelper.getCurrentDimension().name();
            Optional<BlockPos> chest = home == null ? Optional.empty()
                    : BaseStorageMemory.getInstance()
                    .preferredChestFor(mod, home, dimension, surplus)
                    .filter(pos -> pos.isWithinDistance(mod.getPlayer().getPos(), 24));
            _lastSurplus = surplus;
            _lastChest = chest.orElse(null);
            _delegate = chest
                    .<Task>map(pos -> new StoreInContainerTask(pos, false, surplus))
                    .orElseGet(() -> new StoreInAnyContainerTask(false, false, surplus));
            _attempted = true;
            return _delegate;
        }
        // Storage could not absorb everything (full chest or none nearby).
        // Drop junk (flowers, leaves, throwaway blocks...) to reclaim space;
        // recipe-relevant, protected, and valuable items are never dropped.
        if (!_dropStage && OverflowInventoryTask.freeSlots(mod) < _desiredFreeSlots) {
            setDebugState("Dropping junk to make inventory space");
            _dropStage = true;
            _delegate = new DropJunkToMakeSpaceTask(_desiredFreeSlots);
            return _delegate;
        }
        return null;
    }

    private ItemTarget[] findSurplus(Belfegor mod) {
        Map<Item, Integer> carried = new HashMap<>();
        for (ItemStack stack : mod.getPlayer().getInventory().main) {
            if (stack == null || stack.isEmpty()) continue;
            carried.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        Map<Item, Integer> surplusByItem = new HashMap<>();
        for (ItemTarget threshold : _surplusThresholds) {
            if (threshold == null) continue;
            for (Item item : threshold.getMatches()) {
                if (_keep.contains(item)) continue;
                Integer count = carried.get(item);
                if (count == null) continue;
                int excess = count - threshold.getTargetCount();
                if (excess > 0) surplusByItem.merge(item, excess, Integer::sum);
            }
        }
        for (ItemTarget target : _mustStore) {
            if (target == null) continue;
            for (Item item : target.getMatches()) {
                if (_keep.contains(item)) continue;
                Integer count = carried.get(item);
                if (count == null || count <= 0) continue;
                surplusByItem.merge(item,
                        Math.min(count, Math.max(1, target.getTargetCount())), Integer::sum);
            }
        }
        List<ItemTarget> result = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : surplusByItem.entrySet()) {
            if (entry.getValue() > 0) {
                result.add(new ItemTarget(entry.getKey(), entry.getValue()));
            }
        }
        return result.toArray(ItemTarget[]::new);
    }

    @Override
    public boolean shouldForce(Belfegor mod, Task interruptingCandidate) {
        return !StorageHelper.getItemStackInCursorSlot().isEmpty()
                || (_delegate != null && !_delegate.stopped() && !_delegate.isFinished(mod));
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _delegate = null;
        _attempted = false;
        _dropStage = false;
        _lastSurplus = null;
        _lastChest = null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof InventoryTriageTask task
                && task._desiredFreeSlots == _desiredFreeSlots
                && Arrays.equals(task._surplusThresholds, _surplusThresholds);
    }

    @Override
    protected String toDebugString() {
        return "Inventory triage freeSlots=" + _desiredFreeSlots;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        boolean delegateReleased = _delegate == null || _delegate.stopped();
        if (!delegateReleased || !StorageHelper.getItemStackInCursorSlot().isEmpty()) {
            return false;
        }
        if (_attempted || _dropStage) return true;
        return OverflowInventoryTask.freeSlots(mod) >= _desiredFreeSlots
                || findSurplus(mod).length == 0;
    }

    /** Items the bot should always keep in hand: a working field kit. */
    public static Item[] fieldKit() {
        List<Item> keep = new ArrayList<>();
        keep.addAll(Arrays.asList(ItemHelper.WOODEN_TOOLS));
        keep.addAll(Arrays.asList(ItemHelper.STONE_TOOLS));
        keep.addAll(Arrays.asList(ItemHelper.IRON_TOOLS));
        keep.addAll(Arrays.asList(ItemHelper.GOLDEN_TOOLS));
        keep.addAll(Arrays.asList(ItemHelper.DIAMOND_TOOLS));
        keep.addAll(Arrays.asList(ItemHelper.NETHERITE_TOOLS));
        keep.addAll(Arrays.asList(ItemHelper.LEATHER_ARMORS));
        keep.addAll(Arrays.asList(ItemHelper.GOLDEN_ARMORS));
        keep.addAll(Arrays.asList(ItemHelper.IRON_ARMORS));
        keep.addAll(Arrays.asList(ItemHelper.DIAMOND_ARMORS));
        keep.addAll(Arrays.asList(ItemHelper.NETHERITE_ARMORS));
        keep.addAll(Arrays.asList(ItemHelper.RAW_FOODS));
        keep.addAll(Arrays.asList(ItemHelper.BED));
        keep.addAll(Arrays.asList(ItemHelper.SHULKER_BOXES));
        keep.addAll(Arrays.asList(
                Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN,
                Items.COOKED_MUTTON, Items.COOKED_COD, Items.COOKED_SALMON,
                Items.COOKED_RABBIT, Items.BREAD, Items.APPLE, Items.GOLDEN_APPLE,
                Items.ENCHANTED_GOLDEN_APPLE, Items.COOKIE, Items.CAKE,
                Items.PUMPKIN_PIE, Items.BAKED_POTATO, Items.DRIED_KELP,
                Items.MELON_SLICE, Items.SWEET_BERRIES, Items.GLOW_BERRIES,
                Items.MUSHROOM_STEW, Items.RABBIT_STEW, Items.BEETROOT_SOUP,
                Items.HONEY_BOTTLE,
                Items.CRAFTING_TABLE, Items.FURNACE, Items.CHEST,
                Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.MILK_BUCKET,
                Items.SHIELD, Items.BOW, Items.CROSSBOW, Items.ARROW,
                Items.SPECTRAL_ARROW, Items.TIPPED_ARROW,
                Items.FLINT_AND_STEEL, Items.SHEARS,
                Items.OAK_BOAT, Items.SPRUCE_BOAT, Items.BIRCH_BOAT,
                Items.JUNGLE_BOAT, Items.ACACIA_BOAT, Items.DARK_OAK_BOAT,
                Items.MANGROVE_BOAT, Items.CHERRY_BOAT, Items.BAMBOO_RAFT,
                Items.PALE_OAK_BOAT, Items.TOTEM_OF_UNDYING,
                Items.ENDER_PEARL, Items.ENDER_EYE, Items.BLAZE_ROD,
                Items.DIAMOND, Items.EMERALD, Items.NETHERITE_INGOT,
                Items.NETHERITE_SCRAP
        ));
        return keep.toArray(Item[]::new);
    }

    /** Common bulk items a player stockpiles; anything carried above the
     *  threshold is surplus and gets stored at home. */
    public static ItemTarget[] standardSurplusTargets() {
        return new ItemTarget[]{
                new ItemTarget(Items.COBBLESTONE, 64),
                new ItemTarget(Items.STONE, 64),
                new ItemTarget(Items.COBBLED_DEEPSLATE, 64),
                new ItemTarget(Items.DEEPSLATE, 64),
                new ItemTarget(Items.DIRT, 64),
                new ItemTarget(ItemHelper.LOG, 32),
                new ItemTarget(ItemHelper.PLANKS, 32),
                new ItemTarget(Items.STICK, 16),
                new ItemTarget(Items.COAL, 16),
                new ItemTarget(Items.RAW_IRON, 8),
                new ItemTarget(Items.IRON_INGOT, 16),
                new ItemTarget(Items.GOLD_INGOT, 8),
                new ItemTarget(Items.WHEAT_SEEDS, 8),
                new ItemTarget(Items.WHEAT, 16),
                new ItemTarget(Items.SAND, 64),
                new ItemTarget(Items.GRAVEL, 32),
                new ItemTarget(Items.GRANITE, 32),
                new ItemTarget(Items.DIORITE, 32),
                new ItemTarget(Items.ANDESITE, 32),
                new ItemTarget(Items.TORCH, 32),
                new ItemTarget(Items.LADDER, 16),
                new ItemTarget(Items.BONE, 16),
                new ItemTarget(Items.GUNPOWDER, 16),
                new ItemTarget(Items.STRING, 16),
                new ItemTarget(Items.FEATHER, 16),
                new ItemTarget(Items.ROTTEN_FLESH, 16),
                new ItemTarget(Items.LEATHER, 8),
                new ItemTarget(Items.REDSTONE, 16),
                new ItemTarget(Items.SUGAR_CANE, 16),
                new ItemTarget(Items.PAPER, 16),
                new ItemTarget(Items.BOOK, 8)
        };
    }
}
