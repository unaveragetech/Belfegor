package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.memory.BaseStorageMemory;
import adris.belfegor.tasks.construction.BuildBaseExpansionTask;
import adris.belfegor.tasks.container.OverflowInventoryTask;
import adris.belfegor.tasks.container.PickupFromContainerTask;
import adris.belfegor.tasks.container.StoreInContainerTask;
import adris.belfegor.tasks.misc.EquipArmorTask;
import adris.belfegor.tasks.movement.GetToBlockTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Maintains the persistent combat economy for @player.
 *
 * The carried kit and stored reserve are deliberately separate. Belfegor keeps
 * one usable set on the player, stores backup tools/armor/weapons in the armory
 * gear chest, and stores the ingredients used to replace them in the armory
 * material chest. Every phase owns one task at a time so crafting, equipping,
 * container transfers, and base expansion cannot interrupt each other.
 */
public class CampArmoryTask extends Task {

    private enum Phase {
        RESOLVE,
        GO_HOME,
        ENSURE_ROOM,
        RESOLVE_CHESTS,
        INVENTORY_SPACE,
        CARRIED_KIT,
        EQUIP_ARMOR,
        BACKUP_GEAR,
        RESERVE_MATERIALS,
        STORE_GEAR,
        STORE_MATERIALS,
        DONE
    }

    private static final Item[] USEFUL_PICKAXES = {
            Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE,
            Items.STONE_PICKAXE, Items.WOODEN_PICKAXE
    };
    private static final Item[] USEFUL_SWORDS = {
            Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD,
            Items.STONE_SWORD, Items.WOODEN_SWORD
    };
    private static final Item[] IRON_ARMOR = {
            Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS
    };
    private static final Item[] BACKUP_GEAR = {
            Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL, Items.STONE_SWORD,
            Items.BOW, Items.SHIELD,
            Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS
    };
    private static final ItemTarget[] MATERIAL_RESERVES = {
            new ItemTarget(Items.IRON_INGOT, 16),
            new ItemTarget(Items.STICK, 16),
            new ItemTarget(Items.STRING, 8),
            new ItemTarget(Items.FLINT, 8),
            new ItemTarget(Items.FEATHER, 8)
    };

    private Phase _phase = Phase.RESOLVE;
    private Task _activeTask;
    private BlockPos _home;
    private BlockPos _gearChest;
    private BlockPos _materialChest;
    private String _dimension;

    @Override
    protected void onStart(Belfegor mod) {
        _phase = Phase.RESOLVE;
        _activeTask = null;
        _home = null;
        _gearChest = null;
        _materialChest = null;
        _dimension = WorldHelper.getCurrentDimension().name();
    }

    @Override
    protected Task onTick(Belfegor mod) {
        return switch (_phase) {
            case RESOLVE -> {
                _home = mod.getModSettings().getHomeBasePosition();
                if (_home == null && mod.getPlayer() != null) {
                    _home = BaseMemory.getInstance().nearestBase(mod.getPlayer().getBlockPos(), _dimension)
                            .map(BaseMemory.BaseRecord::center).orElse(null);
                }
                if (_home == null) {
                    setDebugState("No locked home exists for armory maintenance");
                    _phase = Phase.DONE;
                    yield null;
                }
                next(Phase.GO_HOME);
                yield null;
            }
            case GO_HOME -> {
                if (mod.getPlayer() != null && _home.getSquaredDistance(mod.getPlayer().getBlockPos()) > 24 * 24) {
                    setDebugState("Returning home before armory maintenance");
                    yield cache(mod, new GetToBlockTask(_home));
                }
                next(Phase.ENSURE_ROOM);
                yield null;
            }
            case ENSURE_ROOM -> {
                if (!hasCompleteArmory()) {
                    setDebugState("Building the missing connected armory room");
                    yield cache(mod, new BuildBaseExpansionTask(BuildBaseExpansionTask.RoomType.ARMORY, "armory"));
                }
                next(Phase.RESOLVE_CHESTS);
                yield null;
            }
            case RESOLVE_CHESTS -> {
                resolveChests(mod);
                if (_gearChest == null || _materialChest == null) {
                    // A completed room from an older build may not have fixture
                    // metadata. Rerun the named room idempotently to install it.
                    setDebugState("Repairing missing armory chest fixtures");
                    yield cache(mod, new BuildBaseExpansionTask(BuildBaseExpansionTask.RoomType.ARMORY, "armory"));
                }
                next(Phase.INVENTORY_SPACE);
                yield null;
            }
            case INVENTORY_SPACE -> {
                if (OverflowInventoryTask.freeSlots(mod) < 12) {
                    setDebugState("Freeing inventory before crafting armory reserves");
                    yield cache(mod, new OverflowInventoryTask(12, protectedCarriedKit()));
                }
                next(Phase.CARRIED_KIT);
                yield null;
            }
            case CARRIED_KIT -> {
                Task kit = ensureCarriedKit(mod);
                if (kit != null) yield kit;
                next(Phase.EQUIP_ARMOR);
                yield null;
            }
            case EQUIP_ARMOR -> {
                if (!StorageHelper.isArmorEquippedAll(mod, IRON_ARMOR)) {
                    setDebugState("Crafting and equipping baseline iron survival armor");
                    yield cache(mod, new EquipArmorTask(IRON_ARMOR));
                }
                next(Phase.BACKUP_GEAR);
                yield null;
            }
            case BACKUP_GEAR -> {
                Task backup = ensureBackupGear(mod);
                if (backup != null) yield backup;
                next(Phase.RESERVE_MATERIALS);
                yield null;
            }
            case RESERVE_MATERIALS -> {
                Task reserve = ensureMaterialReserves(mod);
                if (reserve != null) yield reserve;
                next(Phase.STORE_GEAR);
                yield null;
            }
            case STORE_GEAR -> {
                ItemTarget[] store = carriedBackupGear(mod);
                if (store.length > 0) {
                    setDebugState("Storing backup tools, weapons and armor in the armory gear chest");
                    yield cache(mod, new StoreInContainerTask(_gearChest, false, store));
                }
                next(Phase.STORE_MATERIALS);
                yield null;
            }
            case STORE_MATERIALS -> {
                ItemTarget[] store = carriedReserveMaterials(mod);
                if (store.length > 0) {
                    setDebugState("Storing replacement-equipment materials in the armory material chest");
                    yield cache(mod, new StoreInContainerTask(_materialChest, false, store));
                }
                BaseMemory.getInstance().rememberInspection(_home, _dimension,
                        "armory", "survival_reserve", BACKUP_GEAR.length + MATERIAL_RESERVES.length,
                        0, 0, 1, "ready",
                        "carried combat kit equipped; backup gear and replacement materials stored");
                BaseMemory.getInstance().save();
                next(Phase.DONE);
                yield null;
            }
            case DONE -> null;
        };
    }

    private Task ensureCarriedKit(Belfegor mod) {
        if (!mod.getItemStorage().hasItem(USEFUL_PICKAXES)) {
            setDebugState("Preparing a carried stone toolset");
            return cache(mod, new ToolSetTask(ToolSetTask.Tier.STONE));
        }
        if (!mod.getItemStorage().hasItem(USEFUL_SWORDS)) {
            return cache(mod, TaskCatalogue.getItemTask("stone_sword", 1));
        }
        if (!mod.getItemStorage().hasItem(Items.SHIELD)
                && !mod.getItemStorage().hasItemInOffhand(Items.SHIELD)) {
            Task retrieve = retrieveKnown(mod, _gearChest, Items.SHIELD, 1);
            if (retrieve != null) return retrieve;
            return cache(mod, new EquipArmorTask(Items.SHIELD));
        }
        if (!StorageHelper.isArmorEquipped(mod, Items.SHIELD)) {
            return cache(mod, new EquipArmorTask(Items.SHIELD));
        }
        if (!mod.getItemStorage().hasItem(Items.BOW)) {
            Task retrieve = retrieveKnown(mod, _gearChest, Items.BOW, 1);
            if (retrieve != null) return retrieve;
            return cache(mod, TaskCatalogue.getItemTask("bow", 1));
        }
        int arrows = mod.getItemStorage().getItemCountInventoryOnly(Items.ARROW);
        if (arrows < 32) {
            Task retrieve = retrieveKnown(mod, _gearChest, Items.ARROW, 32);
            if (retrieve != null) return retrieve;
            return cache(mod, TaskCatalogue.getItemTask("arrow", 32));
        }
        return null;
    }

    private Task ensureBackupGear(Belfegor mod) {
        for (Item item : BACKUP_GEAR) {
            int stored = BaseStorageMemory.getInstance().knownCountAt(_home, _dimension, item);
            if (stored >= 1) continue;
            int carried = mod.getItemStorage().getItemCountInventoryOnly(item);
            int target = carried + 1;
            setDebugState("Crafting armory backup " + item.getName().getString());
            return cache(mod, TaskCatalogue.getItemTask(item, target));
        }
        int storedArrows = BaseStorageMemory.getInstance().knownCountAt(_home, _dimension, Items.ARROW);
        if (storedArrows < 64) {
            int carried = mod.getItemStorage().getItemCountInventoryOnly(Items.ARROW);
            return cache(mod, TaskCatalogue.getItemTask("arrow", carried + (64 - storedArrows)));
        }
        return null;
    }

    private Task ensureMaterialReserves(Belfegor mod) {
        for (ItemTarget target : MATERIAL_RESERVES) {
            int known = BaseStorageMemory.getInstance().knownCountAt(_home, _dimension, target.getMatches());
            if (known >= target.getTargetCount()) continue;
            int carried = mod.getItemStorage().getItemCountInventoryOnly(target.getMatches());
            int desiredInventory = carried + (target.getTargetCount() - known);
            setDebugState("Gathering armory replacement material " + target);
            return cache(mod, TaskCatalogue.getItemTask(new ItemTarget(target, desiredInventory)));
        }
        return null;
    }

    private Task retrieveKnown(Belfegor mod, BlockPos chest, Item item, int desiredCarried) {
        if (chest == null) return null;
        if (BaseStorageMemory.getInstance().knownCountAt(_home, _dimension, item) <= 0) return null;
        return cache(mod, new PickupFromContainerTask(chest, new ItemTarget(item, desiredCarried)));
    }

    private ItemTarget[] carriedBackupGear(Belfegor mod) {
        List<ItemTarget> result = new ArrayList<>();
        for (Item item : BACKUP_GEAR) {
            int carried = mod.getItemStorage().getItemCountInventoryOnly(item);
            int keep = carriedKeepCount(mod, item);
            if (carried > keep) result.add(new ItemTarget(item, carried - keep));
        }
        int arrows = mod.getItemStorage().getItemCountInventoryOnly(Items.ARROW);
        if (arrows > 32) result.add(new ItemTarget(Items.ARROW, arrows - 32));
        return result.toArray(ItemTarget[]::new);
    }

    private int carriedKeepCount(Belfegor mod, Item item) {
        if (item == Items.BOW) return 1;
        if (item == Items.SHIELD) return StorageHelper.isArmorEquipped(mod, Items.SHIELD) ? 0 : 1;
        if (item == Items.STONE_PICKAXE) return hasBetter(mod, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE) ? 0 : 1;
        if (item == Items.STONE_AXE) return hasBetter(mod, Items.IRON_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE) ? 0 : 1;
        if (item == Items.STONE_SHOVEL) return hasBetter(mod, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL, Items.NETHERITE_SHOVEL) ? 0 : 1;
        if (item == Items.STONE_SWORD) return hasBetter(mod, Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD) ? 0 : 1;
        // Armor is equipped before backup storage; all inventory copies are reserves.
        return 0;
    }

    private boolean hasBetter(Belfegor mod, Item... items) {
        return mod.getItemStorage().hasItem(items);
    }

    private ItemTarget[] carriedReserveMaterials(Belfegor mod) {
        List<ItemTarget> result = new ArrayList<>();
        for (ItemTarget reserve : MATERIAL_RESERVES) {
            int carried = mod.getItemStorage().getItemCountInventoryOnly(reserve.getMatches());
            int keep = reserve.matches(Items.STICK) ? Math.min(4, carried) : 0;
            if (carried > keep) result.add(new ItemTarget(reserve.getMatches(), carried - keep));
        }
        return result.toArray(ItemTarget[]::new);
    }

    private ItemTarget[] protectedCarriedKit() {
        return new ItemTarget[]{
                new ItemTarget(USEFUL_PICKAXES, 1),
                new ItemTarget(USEFUL_SWORDS, 1),
                new ItemTarget(Items.SHIELD, 1),
                new ItemTarget(Items.BOW, 1),
                new ItemTarget(Items.ARROW, 32)
        };
    }

    private boolean hasCompleteArmory() {
        if (_home == null) return false;
        return BaseMemory.getInstance().baseAt(_home, _dimension)
                .or(() -> BaseMemory.getInstance().nearestBase(_home, _dimension))
                .stream()
                .flatMap(base -> base.modules.stream())
                .anyMatch(module -> "armory".equals(normalize(module.type))
                        && "core".equals(normalize(module.parent))
                        && BaseMemory.getInstance().moduleComplete(module));
    }

    private void resolveChests(Belfegor mod) {
        _gearChest = BaseStorageMemory.getInstance()
                .preferredChestForRole(mod, _home, _dimension, "armory_gear")
                .orElse(null);
        _materialChest = BaseStorageMemory.getInstance()
                .preferredChestForRole(mod, _home, _dimension, "armory_materials")
                .orElse(null);
    }

    private Task cache(Belfegor mod, Task task) {
        if (task == null) return null;
        if (_activeTask != null && !_activeTask.stopped() && !_activeTask.isFinished(mod)) {
            return _activeTask;
        }
        _activeTask = task;
        return _activeTask;
    }

    private void next(Phase phase) {
        _phase = phase;
        _activeTask = null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _activeTask = null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof CampArmoryTask;
    }

    @Override
    protected String toDebugString() {
        return "Camp armory phase=" + _phase
                + " home=" + (_home == null ? "?" : _home.toShortString())
                + " gear=" + (_gearChest == null ? "?" : _gearChest.toShortString())
                + " materials=" + (_materialChest == null ? "?" : _materialChest.toShortString());
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return _phase == Phase.DONE;
    }
}
