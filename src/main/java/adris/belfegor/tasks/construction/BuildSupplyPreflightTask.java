package adris.belfegor.tasks.construction;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.memory.LocationMemory;
import adris.belfegor.tasks.container.OverflowInventoryTask;
import adris.belfegor.tasks.container.StoreInContainerTask;
import adris.belfegor.tasks.resources.CollectBucketLiquidTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

/**
 * Prepares the boring-but-critical construction state before @build full starts.
 *
 * The base builder was previously allowed to discover missing water, broken hoes,
 * full inventory, or missing overflow storage in the middle of room construction.
 * That made long builds wander away, break partial work, or fight their own
 * inventory. This task serializes the setup:
 * - place/remember a central construction staging chest once the campsite exists,
 * - ensure core construction/farm supplies exist,
 * - create free inventory space while protecting supplies used by later phases.
 */
public class BuildSupplyPreflightTask extends Task {

    private static final int MIN_FREE_SLOTS = 8;
    private static final int MIN_STARTER_BUILDING_BLOCKS = 512;
    private static final int MIN_BUILDING_BLOCKS = 1536;
    private static final int MIN_STAGED_BUILDING_BLOCKS = 768;
    private static final int MIN_DIRT = 96;
    private static final int MAX_DIRT_IN_INVENTORY_AFTER_STAGING = 128;
    private static final int MIN_WATER_BUCKETS = 2;
    private static final int MIN_HOES = 3;
    private static final int MIN_SEEDS = 48;
    private static final int MIN_CHESTS = 1;
    private static final int MIN_CRAFTING_TABLES = 1;
    private static final int MIN_FURNACES = 1;
    private static final int MIN_PICKAXES = 1;
    private static final int MIN_SHOVELS = 1;
    private static final int MIN_AXES = 1;

    private static final Item[] HOES = {
            Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE,
            Items.GOLDEN_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE
    };
    private static final Item[] PICKAXES = {
            Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE,
            Items.GOLDEN_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE
    };
    private static final Item[] SHOVELS = {
            Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL,
            Items.GOLDEN_SHOVEL, Items.DIAMOND_SHOVEL, Items.NETHERITE_SHOVEL
    };
    private static final Item[] AXES = {
            Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE,
            Items.GOLDEN_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE
    };

    private enum Phase {
        STORAGE,
        CLEAR_TOOLS,
        MATERIALS,
        FARM_SUPPLIES,
        FIXTURES,
        INVENTORY_SPACE,
        DONE
    }

    private final BlockPos _home;
    private final int _radius;
    private final boolean _placeStagingChest;
    private final boolean _freeInventorySpace;
    private Phase _phase = Phase.STORAGE;
    private Task _activeTask;
    private String _dimension;
    private BlockPos _stagingChest;

    public BuildSupplyPreflightTask(BlockPos home, int radius) {
        this(home, radius, true, true);
    }

    public BuildSupplyPreflightTask(BlockPos home, int radius,
                                    boolean placeStagingChest,
                                    boolean freeInventorySpace) {
        _home = home == null ? BlockPos.ORIGIN : home;
        _radius = Math.max(8, radius);
        _placeStagingChest = placeStagingChest;
        _freeInventorySpace = freeInventorySpace;
        _stagingChest = _home.add(2, 0, -2);
    }

    @Override
    protected void onStart(Belfegor mod) {
        _phase = Phase.STORAGE;
        _activeTask = null;
        _dimension = WorldHelper.getCurrentDimension().name();
        mod.getBehaviour().push();
        mod.getBehaviour().avoidBlockBreaking(this::isCompletedCoreBuildBlock);
        rememberStaging("planned");
    }

    @Override
    protected Task onTick(Belfegor mod) {
        return switch (_phase) {
            case STORAGE -> {
                if (!_placeStagingChest) {
                    rememberStaging("planned_after_campsite");
                    next(Phase.CLEAR_TOOLS);
                    yield null;
                }
                Task task = ensureStagingChest(mod);
                if (task != null) yield task;
                rememberStaging("ready");
                next(Phase.CLEAR_TOOLS);
                yield null;
            }
            case CLEAR_TOOLS -> {
                Task task = ensureClearTools(mod);
                if (task != null) yield task;
                next(Phase.MATERIALS);
                yield null;
            }
            case MATERIALS -> {
                Task task = ensureMaterials(mod);
                if (task != null) yield task;
                next(Phase.FARM_SUPPLIES);
                yield null;
            }
            case FARM_SUPPLIES -> {
                if (!_placeStagingChest) {
                    setDebugState("Deferring farm supplies until construction staging chest exists");
                    next(Phase.FIXTURES);
                    yield null;
                }
                Task task = ensureFarmSupplies(mod);
                if (task != null) yield task;
                next(Phase.FIXTURES);
                yield null;
            }
            case FIXTURES -> {
                Task task = ensureFixtures(mod);
                if (task != null) yield task;
                next(Phase.INVENTORY_SPACE);
                yield null;
            }
            case INVENTORY_SPACE -> {
                if (!_freeInventorySpace) {
                    rememberStaging("inventory_space_deferred");
                    next(Phase.DONE);
                    yield null;
                }
                Task surplus = storePhaseSurplus(mod);
                if (surplus != null) yield surplus;
                if (OverflowInventoryTask.freeSlots(mod) < MIN_FREE_SLOTS) {
                    setDebugState("Freeing build inventory space into staging/overflow chest");
                    yield cache(mod, new OverflowInventoryTask(MIN_FREE_SLOTS, protectedConstructionTargets()));
                }
                rememberStaging("ready_free_slots=" + OverflowInventoryTask.freeSlots(mod));
                next(Phase.DONE);
                yield null;
            }
            case DONE -> null;
        };
    }

    private Task ensureStagingChest(Belfegor mod) {
        if (mod.getWorld().getBlockState(_stagingChest).getBlock() == Blocks.CHEST) {
            setDebugState("Construction staging chest ready at " + _stagingChest.toShortString());
            return null;
        }
        if (!WorldHelper.isSolid(mod, _stagingChest.down())) {
            setDebugState("Building support under construction staging chest");
            return cache(mod, new PlaceBlockTask(_stagingChest.down(),
                    new net.minecraft.block.Block[]{Blocks.COBBLESTONE}, false, true));
        }
        if (!mod.getWorld().getBlockState(_stagingChest).isAir()) {
            setDebugState("Clearing construction staging chest position");
            return cache(mod, new DestroyBlockTask(_stagingChest));
        }
        if (!mod.getItemStorage().hasItem(Items.CHEST)) {
            setDebugState("Crafting construction staging chest");
            return TaskCatalogue.getItemTask("chest", MIN_CHESTS);
        }
        setDebugState("Placing construction staging chest");
        return cache(mod, new PlaceBlockTask(_stagingChest, Blocks.CHEST));
    }

    private Task ensureMaterials(Belfegor mod) {
        int targetCobblestone = targetBuildingBlocks();
        int cobblestone = mod.getItemStorage().getItemCount(Items.COBBLESTONE);
        if (cobblestone < targetCobblestone) {
            setDebugState("Preflight collecting exact cobblestone for full base "
                    + cobblestone + "/" + targetCobblestone);
            Task task = TaskCatalogue.getItemTask("cobblestone", targetCobblestone);
            if (task != null) return task;
        }
        int dirt = mod.getItemStorage().getItemCount(Items.DIRT);
        if (dirt < MIN_DIRT) {
            setDebugState("Preflight collecting farm dirt " + dirt + "/" + MIN_DIRT);
            Task task = TaskCatalogue.getItemTask("dirt", MIN_DIRT);
            if (task != null) return task;
        }
        return null;
    }

    private Task ensureClearTools(Belfegor mod) {
        int pickaxes = mod.getItemStorage().getItemCount(PICKAXES);
        if (pickaxes < MIN_PICKAXES) {
            setDebugState("Preflight crafting clear-phase pickaxe " + pickaxes + "/" + MIN_PICKAXES);
            Task task = TaskCatalogue.getItemTask("wooden_pickaxe", MIN_PICKAXES);
            if (task != null) return task;
        }
        int shovels = mod.getItemStorage().getItemCount(SHOVELS);
        if (shovels < MIN_SHOVELS) {
            setDebugState("Preflight crafting clear-phase shovel " + shovels + "/" + MIN_SHOVELS);
            Task task = TaskCatalogue.getItemTask("wooden_shovel", MIN_SHOVELS);
            if (task != null) return task;
        }
        int axes = mod.getItemStorage().getItemCount(AXES);
        if (axes < MIN_AXES) {
            setDebugState("Preflight crafting clear-phase axe " + axes + "/" + MIN_AXES);
            Task task = TaskCatalogue.getItemTask("wooden_axe", MIN_AXES);
            if (task != null) return task;
        }
        return null;
    }

    private Task storePhaseSurplus(Belfegor mod) {
        if (!_placeStagingChest) return null;
        if (mod.getWorld().getBlockState(_stagingChest).getBlock() != Blocks.CHEST) return null;
        int dirt = mod.getItemStorage().getItemCount(Items.DIRT);
        int surplusDirt = dirt - MAX_DIRT_IN_INVENTORY_AFTER_STAGING;
        if (surplusDirt > 0) {
            setDebugState("Staging surplus dirt in construction chest "
                    + surplusDirt + " excess blocks");
            return cache(mod, new StoreInContainerTask(_stagingChest, false,
                    new ItemTarget(Items.DIRT, surplusDirt)));
        }
        return null;
    }

    private Task ensureFarmSupplies(Belfegor mod) {
        int waterBuckets = mod.getItemStorage().getItemCount(Items.WATER_BUCKET);
        if (waterBuckets < MIN_WATER_BUCKETS) {
            setDebugState("Preflight collecting farm water buckets "
                    + waterBuckets + "/" + MIN_WATER_BUCKETS);
            return new CollectBucketLiquidTask.CollectWaterBucketTask(MIN_WATER_BUCKETS);
        }
        int hoes = mod.getItemStorage().getItemCount(HOES);
        if (hoes < MIN_HOES) {
            setDebugState("Preflight crafting backup farm hoes " + hoes + "/" + MIN_HOES);
            Task task = TaskCatalogue.getItemTask("wooden_hoe", MIN_HOES);
            if (task != null) return task;
        }
        int seeds = mod.getItemStorage().getItemCount(Items.WHEAT_SEEDS);
        if (seeds < MIN_SEEDS) {
            setDebugState("Preflight collecting farm seeds " + seeds + "/" + MIN_SEEDS);
            Task task = TaskCatalogue.getItemTask("wheat_seeds", MIN_SEEDS);
            if (task != null) return task;
        }
        return null;
    }

    private Task ensureFixtures(Belfegor mod) {
        if (mod.getItemStorage().getItemCount(Items.CHEST) < MIN_CHESTS) {
            setDebugState("Preflight crafting spare room chest");
            return TaskCatalogue.getItemTask("chest", MIN_CHESTS);
        }
        if (mod.getItemStorage().getItemCount(Items.CRAFTING_TABLE) < MIN_CRAFTING_TABLES) {
            setDebugState("Preflight crafting workshop table");
            return TaskCatalogue.getItemTask("crafting_table", MIN_CRAFTING_TABLES);
        }
        if (mod.getItemStorage().getItemCount(Items.FURNACE) < MIN_FURNACES) {
            setDebugState("Preflight crafting workshop furnace");
            return TaskCatalogue.getItemTask("furnace", MIN_FURNACES);
        }
        return null;
    }

    private ItemTarget[] protectedConstructionTargets() {
        return new ItemTarget[]{
                new ItemTarget(Items.COBBLESTONE, MIN_BUILDING_BLOCKS),
                new ItemTarget(Items.DIRT, MIN_DIRT),
                new ItemTarget(Items.WATER_BUCKET, MIN_WATER_BUCKETS),
                new ItemTarget(HOES, MIN_HOES),
                new ItemTarget(Items.WHEAT_SEEDS, MIN_SEEDS),
                new ItemTarget(Items.CHEST, MIN_CHESTS),
                new ItemTarget(Items.CRAFTING_TABLE, MIN_CRAFTING_TABLES),
                new ItemTarget(Items.FURNACE, MIN_FURNACES),
                new ItemTarget(PICKAXES, MIN_PICKAXES),
                new ItemTarget(SHOVELS, MIN_SHOVELS),
                new ItemTarget(AXES, MIN_AXES)
        };
    }

    private int targetBuildingBlocks() {
        // Before the campsite exists there is no reliable construction
        // staging chest yet. Requiring the entire full-base cobblestone budget
        // here causes natural-terrain starts to fill the inventory and fight
        // emergency overflow before the bot has built its home core. Gather
        // enough to clear/build the campsite first; the second preflight,
        // after the core/staging chest exists, handles the larger material
        // reserve.
        return _placeStagingChest ? MIN_STAGED_BUILDING_BLOCKS : MIN_STARTER_BUILDING_BLOCKS;
    }

    private Task cache(Belfegor mod, Task task) {
        if (_activeTask != null && !_activeTask.stopped() && !_activeTask.isFinished(mod)) {
            return _activeTask;
        }
        _activeTask = task;
        return _activeTask;
    }

    private void next(Phase next) {
        _phase = next;
        _activeTask = null;
    }

    private void rememberStaging(String status) {
        BaseMemory memory = BaseMemory.getInstance();
        memory.rememberModule(_home, _dimension, "construction_staging_chest", "staging",
                _stagingChest, 1, 1, 1, status,
                "preflight overflow/staging chest for full-base construction");
        memory.rememberInspection(_home, _dimension, "construction_preflight", "supplies",
                1, 0, 0, 1, status, "stagingChest=" + _stagingChest.toShortString());
        LocationMemory.getInstance().remember("home_room_construction_staging",
                _stagingChest.getX(), _stagingChest.getY(), _stagingChest.getZ(),
                _dimension, "full-base construction staging chest");
        LocationMemory.getInstance().save();
        memory.save();
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _activeTask = null;
        mod.getBehaviour().pop();
    }

    private boolean isCompletedCoreBuildBlock(BlockPos pos) {
        try {
            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
            if (client == null || client.world == null || pos == null) return false;
            net.minecraft.block.Block expected = BuildCampsiteTask.coreBlueprintTargets(_home, _radius).get(pos);
            return expected != null && client.world.getBlockState(pos).getBlock() == expected;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BuildSupplyPreflightTask task
                && task._home.equals(_home)
                && task._radius == _radius
                && task._placeStagingChest == _placeStagingChest
                && task._freeInventorySpace == _freeInventorySpace;
    }

    @Override
    protected String toDebugString() {
        return "Build supply preflight phase=" + _phase
                + " staging=" + _stagingChest.toShortString()
                + " placeStaging=" + _placeStagingChest
                + " freeInventory=" + _freeInventorySpace;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return _phase == Phase.DONE;
    }
}
