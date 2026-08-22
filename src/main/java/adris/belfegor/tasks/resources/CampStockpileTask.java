package adris.belfegor.tasks.resources;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.memory.BaseStorageMemory;
import adris.belfegor.memory.ErrandMemory;
import adris.belfegor.memory.LocationMemory;
import adris.belfegor.tasks.construction.DestroyBlockTask;
import adris.belfegor.tasks.construction.PlaceBlockTask;
import adris.belfegor.tasks.container.RetrieveFromStashTask;
import adris.belfegor.tasks.container.StoreInContainerTask;
import adris.belfegor.tasks.movement.GetToBlockTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Maintains useful supplies inside the remembered campsite storage room.
 *
 * This intentionally targets the camp storage chest instead of "any known
 * container" so @player can build a real persistent base economy:
 *
 *   ensure tools -> gather resources -> return home -> deposit in storage room.
 */
public class CampStockpileTask extends Task {

    public enum Profile {
        STARTER,
        BUILD
    }

    private enum Phase {
        RESOLVE,
        GO_HOME,
        ENSURE_STORAGE,
        TOOLS,
        RETRIEVE_STASH,
        COLLECT,
        RETURN_HOME,
        STORE,
        DONE
    }

    private static final int HOME_RANGE_SQ = 18 * 18;
    private static final int STORAGE_RANGE_SQ = 12 * 12;

    private final ToolSetTask.Tier _toolTier;
    private final Profile _profile;
    private final ItemTarget[] _targets;
    private Phase _phase = Phase.RESOLVE;
    private Task _activeTask;
    private BlockPos _home;
    private BlockPos _storageChest;
    private BlockPos _lastStoreChest;
    private BlockPos _lastAttemptChest;
    private String _dimension;
    private int _targetIndex;
    private int _lastAttemptCarryTotal;
    private int _sameChestAttempts;
    private final Set<BlockPos> _saturatedChests = new HashSet<>();
    private ItemTarget[] _lastStoreTargets;

    public CampStockpileTask(ToolSetTask.Tier toolTier, Profile profile) {
        _toolTier = toolTier == null ? ToolSetTask.Tier.STONE : toolTier;
        _profile = profile == null ? Profile.STARTER : profile;
        _targets = buildTargets(_profile);
    }

    public CampStockpileTask(ToolSetTask.Tier toolTier, ItemTarget... targets) {
        _toolTier = toolTier == null ? ToolSetTask.Tier.STONE : toolTier;
        _profile = null;
        _targets = targets == null || targets.length == 0
                ? buildTargets(Profile.STARTER)
                : targets;
    }

    @Override
    protected void onStart(Belfegor mod) {
        _phase = Phase.RESOLVE;
        _activeTask = null;
        _targetIndex = 0;
        _saturatedChests.clear();
        _lastStoreChest = null;
        _lastAttemptChest = null;
        _lastAttemptCarryTotal = 0;
        _sameChestAttempts = 0;
        _lastStoreTargets = null;
        _dimension = WorldHelper.getCurrentDimension().name();
    }

    @Override
    protected Task onTick(Belfegor mod) {
        return switch (_phase) {
            case RESOLVE -> {
                resolveHomeAndStorage(mod);
                if (_home == null || _storageChest == null) {
                    setDebugState("No camp/home memory found; run @camp or @build full here first");
                    _phase = Phase.DONE;
                    yield null;
                }
                rememberStorage("planned");
                next(Phase.GO_HOME);
                yield null;
            }
            case GO_HOME -> {
                if (!near(mod, _home, HOME_RANGE_SQ)) {
                    setDebugState("Returning to camp before stockpile maintenance");
                    yield cache(mod, GetToBlockTask.baseAware(mod, _home));
                }
                next(Phase.ENSURE_STORAGE);
                yield null;
            }
            case ENSURE_STORAGE -> {
                Task task = ensureStorageChest(mod);
                if (task != null) yield task;
                rememberStorage("ready");
                next(Phase.TOOLS);
                yield null;
            }
            case TOOLS -> {
                if (!hasToolSet(mod, _toolTier)) {
                    setDebugState("Preparing " + _toolTier.name().toLowerCase() + " toolset for stockpile gathering");
                    yield cache(mod, new ToolSetTask(_toolTier));
                }
                next(Phase.RETRIEVE_STASH);
                yield null;
            }
            case RETRIEVE_STASH -> {
                if (_activeTask != null && !_activeTask.stopped() && !_activeTask.isFinished(mod)) {
                    yield _activeTask;
                }
                _activeTask = null;
                ItemTarget missing = firstMissingTarget(mod);
                if (missing == null) {
                    next(Phase.COLLECT);
                    yield null;
                }
                boolean stashed = Arrays.stream(missing.getMatches())
                        .anyMatch(item -> ErrandMemory.getInstance()
                                .hasStash(_home, _dimension, item));
                if (stashed) {
                    setDebugState("Withdrawing stockpile target from remembered stash " + missing);
                    _activeTask = new RetrieveFromStashTask(_home, missing);
                    yield _activeTask;
                }
                next(Phase.COLLECT);
                yield null;
            }
            case COLLECT -> {
                ItemTarget[] carried = carriedStockpileTargets(mod);
                if (carried.length > 0) {
                    setDebugState("Returning to camp to store gathered stockpile batch "
                            + Arrays.toString(carried));
                    next(Phase.RETURN_HOME);
                    yield null;
                }
                ItemTarget missing = firstMissingTarget(mod);
                if (missing == null) {
                    next(Phase.RETURN_HOME);
                    yield null;
                }
                setDebugState("Gathering camp stockpile target " + missing
                        + " profile=" + profileLabel());
                Task task = TaskCatalogue.getItemTask(missing);
                if (task == null) {
                    Debug.logWarning("[CampStockpile] No task for " + missing + "; skipping target");
                    _targetIndex++;
                    yield null;
                }
                yield cache(mod, task);
            }
            case RETURN_HOME -> {
                if (!near(mod, _storageChest, STORAGE_RANGE_SQ)) {
                    setDebugState("Returning to storage room to deposit gathered supplies");
                    yield cache(mod, GetToBlockTask.baseAware(mod, _storageChest));
                }
                next(Phase.STORE);
                yield null;
            }
            case STORE -> {
                ItemTarget[] carried = carriedStockpileTargets(mod);
                if (carried.length > 0) {
                    if (_activeTask instanceof StoreInContainerTask storeTask) {
                        if (!_activeTask.stopped() && !_activeTask.isFinished(mod)) {
                            yield _activeTask;
                        }
                        _activeTask = null;
                        carried = carriedStockpileTargets(mod);
                        if (carried.length == 0) {
                            if (_lastStoreTargets != null && _lastStoreChest != null) {
                                ErrandMemory.getInstance().recordStored(
                                        _home, _lastStoreChest, _dimension,
                                        "stockpile", _lastStoreTargets);
                                ErrandMemory.getInstance().save();
                            }
                            rememberStorage("stockpiled");
                            next(Phase.DONE);
                            yield null;
                        }
                        if (_lastStoreChest != null) {
                            Debug.logInternal("[CampStockpile] Storage chest accepted no more items; "
                                    + "expanding network from " + _lastStoreChest.toShortString()
                                    + " explicitFull=" + storeTask.wasContainerFull()
                                    + " remaining=" + Arrays.toString(carried));
                            BaseStorageMemory.getInstance().markChestFull(_home, _dimension, _lastStoreChest);
                            _saturatedChests.add(_lastStoreChest);
                            _storageChest = nextNetworkChest(mod, carried);
                            next(Phase.ENSURE_STORAGE);
                            yield null;
                        }
                    }
                    _storageChest = bestNetworkChest(mod, carried);
                    if (StoreInContainerTask.hadRepeatedNoProgress(_storageChest, carried)) {
                        Debug.logInternal("[CampStockpile] Storage chest has repeated no-progress store attempts; "
                                + "forcing network expansion chest=" + _storageChest.toShortString()
                                + " carried=" + Arrays.toString(carried));
                        BaseStorageMemory.getInstance().markChestFull(_home, _dimension, _storageChest);
                        _saturatedChests.add(_storageChest);
                        _storageChest = nextNetworkChest(mod, carried);
                        next(Phase.ENSURE_STORAGE);
                        yield null;
                    }
                    int carriedTotal = totalCount(carried);
                    if (_lastAttemptChest != null
                            && _lastAttemptChest.equals(_storageChest)
                            && _lastAttemptCarryTotal == carriedTotal) {
                        _sameChestAttempts++;
                    } else {
                        _lastAttemptChest = _storageChest;
                        _lastAttemptCarryTotal = carriedTotal;
                        _sameChestAttempts = 1;
                    }
                    if (_sameChestAttempts > 2) {
                        Debug.logInternal("[CampStockpile] Same storage chest made no progress; "
                                + "forcing network expansion chest=" + _storageChest.toShortString()
                                + " carried=" + Arrays.toString(carried));
                        BaseStorageMemory.getInstance().markChestFull(_home, _dimension, _storageChest);
                        _saturatedChests.add(_storageChest);
                        _storageChest = nextNetworkChest(mod, carried);
                        _lastAttemptChest = _storageChest;
                        _lastAttemptCarryTotal = carriedTotal;
                        _sameChestAttempts = 0;
                        next(Phase.ENSURE_STORAGE);
                        yield null;
                    }
                    int row = BaseStorageMemory.getInstance().rowIndexForChest(_home, _dimension, _storageChest);
                    BaseStorageMemory.getInstance().rememberPreferredRow(_home, _dimension, row, carried);
                    setDebugState("Depositing gathered supplies into camp storage network row="
                            + row + " chest=" + _storageChest.toShortString() + " "
                            + Arrays.toString(carried));
                    _lastStoreChest = _storageChest;
                    _lastStoreTargets = carried;
                    _activeTask = new StoreInContainerTask(_storageChest, false, carried);
                    yield _activeTask;
                }
                if (firstMissingTarget(mod) != null) {
                    next(Phase.COLLECT);
                    yield null;
                }
                rememberStorage("stockpiled");
                next(Phase.DONE);
                yield null;
            }
            case DONE -> null;
        };
    }

    private void resolveHomeAndStorage(Belfegor mod) {
        BlockPos player = mod.getPlayer() == null ? null : mod.getPlayer().getBlockPos();
        BlockPos configuredHome = mod.getModSettings().getHomeBasePosition();
        Optional<BaseMemory.BaseRecord> base = configuredHome != null
                ? BaseMemory.getInstance().baseAt(configuredHome, _dimension)
                : player == null ? Optional.empty() : BaseMemory.getInstance().nearestBase(player, _dimension);
        _home = configuredHome != null
                ? configuredHome
                : base.map(BaseMemory.BaseRecord::center).orElse(null);
        if (_home == null) {
            Debug.logWarning("[CampStockpile] No configured or remembered base found; refusing to create a transient stockpile home at player position");
            return;
        }

        Optional<LocationMemory.RememberedLocation> rememberedStorage =
                LocationMemory.getInstance().getNearest("home_room_storage",
                        _home.getX(), _home.getY(), _home.getZ(), _dimension);
        _storageChest = rememberedStorage
                .map(LocationMemory.RememberedLocation::toBlockPos)
                .orElse(_home == null ? null : _home.add(2, 0, -2));
        _storageChest = BaseStorageMemory.getInstance()
                .preferredChestFor(mod, _home, _dimension)
                .orElse(_storageChest);
        if (_home != null && _storageChest != null) {
            BaseStorageMemory.getInstance().rememberChest(_home, _dimension, _storageChest,
                    "camp_storage", false, "camp stockpile storage room");
            BaseStorageMemory.getInstance().save();
        }
    }

    private Task ensureStorageChest(Belfegor mod) {
        if (mod.getWorld().getBlockState(_storageChest).getBlock() == Blocks.CHEST) {
            setDebugState("Camp storage chest ready at " + _storageChest.toShortString());
            BaseStorageMemory.getInstance().rememberChest(_home, _dimension, _storageChest,
                    "camp_storage", false, "storage network chest");
            BaseStorageMemory.getInstance().save();
            return null;
        }
        if (!WorldHelper.isSolid(mod, _storageChest.down())) {
            setDebugState("Building support under camp storage chest");
            return cache(mod, new PlaceBlockTask(_storageChest.down(),
                    new net.minecraft.block.Block[]{Blocks.COBBLESTONE}, false, true));
        }
        if (!mod.getWorld().getBlockState(_storageChest).isAir()) {
            setDebugState("Clearing camp storage chest position");
            return cache(mod, new DestroyBlockTask(_storageChest));
        }
        if (!mod.getItemStorage().hasItem(Items.CHEST)) {
            setDebugState("Crafting camp storage chest");
            return cache(mod, TaskCatalogue.getItemTask("chest", 1));
        }
        setDebugState("Placing camp storage chest");
        return cache(mod, new PlaceBlockTask(_storageChest, Blocks.CHEST));
    }

    private BlockPos bestNetworkChest(Belfegor mod, ItemTarget[] carried) {
        Optional<BlockPos> preferred = BaseStorageMemory.getInstance()
                .preferredChestFor(mod, _home, _dimension, carried);
        if (preferred.isPresent() && !_saturatedChests.contains(preferred.get())) {
            return preferred.get();
        }
        return nextNetworkChest(mod, carried);
    }

    private BlockPos nextNetworkChest(Belfegor mod, ItemTarget[] carried) {
        Optional<BlockPos> next = BaseStorageMemory.getInstance().nextChestPosition(_home, _dimension);
        BlockPos result = next.orElse(_home.add(2, 0, -2));
        BaseStorageMemory.getInstance().rememberChest(_home, _dimension, result,
                BaseStorageMemory.getInstance().chestCount(_home, _dimension) >= 5
                        ? "storage_row_overflow"
                        : "camp_storage",
                false, "auto-expanded stockpile network chest");
        BaseStorageMemory.getInstance().rememberPreferredRow(_home, _dimension,
                BaseStorageMemory.getInstance().rowIndexForChest(_home, _dimension, result), carried);
        BaseStorageMemory.getInstance().save();
        Debug.logInternal("[CampStockpile] Selected storage network chest " + result.toShortString()
                + " count=" + BaseStorageMemory.getInstance().chestCount(_home, _dimension));
        return result;
    }

    private ItemTarget firstMissingTarget(Belfegor mod) {
        while (_targetIndex < _targets.length) {
            ItemTarget target = _targets[_targetIndex];
            int have = BaseStorageMemory.getInstance().availableAtBase(mod, _home, _dimension, target.getMatches());
            int carried = mod.getItemStorage().getItemCountInventoryOnly(target.getMatches());
            if (have > carried && carried == 0) {
                Debug.logInternal("[CampStockpile] Target already exists in known storage; "
                        + "skipping container shuttle for " + target
                        + " haveKnown=" + have);
                _targetIndex++;
                continue;
            }
            if (have < target.getTargetCount()) {
                int missing = target.getTargetCount() - have;
                int batch = Math.min(missing, Math.max(1, target.getMatches()[0].getMaxCount()));
                return new ItemTarget(target.getMatches(), batch);
            }
            _targetIndex++;
        }
        return null;
    }

    private ItemTarget[] carriedStockpileTargets(Belfegor mod) {
        java.util.ArrayList<ItemTarget> result = new java.util.ArrayList<>();
        for (ItemTarget target : _targets) {
            int carried = mod.getItemStorage().getItemCountInventoryOnly(target.getMatches());
            if (carried > 0) {
                result.add(new ItemTarget(target.getMatches(), carried));
            }
        }
        return result.toArray(ItemTarget[]::new);
    }

    private int totalCount(ItemTarget[] targets) {
        int total = 0;
        for (ItemTarget target : targets) {
            if (target != null) total += Math.max(0, target.getTargetCount());
        }
        return total;
    }

    private static ItemTarget[] buildTargets(Profile profile) {
        if (profile == Profile.BUILD) {
            return new ItemTarget[]{
                    new ItemTarget(Items.COBBLESTONE, 768),
                    new ItemTarget(Items.OAK_LOG, 128),
                    new ItemTarget(Items.DIRT, 128),
                    new ItemTarget(Items.COAL, 64),
                    new ItemTarget(Items.RAW_IRON, 32),
                    new ItemTarget(Items.WHEAT_SEEDS, 48),
                    new ItemTarget(Items.CHEST, 4),
                    new ItemTarget(Items.CRAFTING_TABLE, 2),
                    new ItemTarget(Items.FURNACE, 2)
            };
        }
        return new ItemTarget[]{
                new ItemTarget(Items.COBBLESTONE, 256),
                new ItemTarget(Items.OAK_LOG, 64),
                new ItemTarget(Items.DIRT, 64),
                new ItemTarget(Items.COAL, 32),
                new ItemTarget(Items.RAW_IRON, 16),
                new ItemTarget(Items.WHEAT_SEEDS, 24),
                new ItemTarget(Items.CHEST, 2),
                new ItemTarget(Items.CRAFTING_TABLE, 1),
                new ItemTarget(Items.FURNACE, 1)
        };
    }

    private boolean hasToolSet(Belfegor mod, ToolSetTask.Tier tier) {
        return switch (tier) {
            case WOOD -> hasExactToolSet(mod, ToolSetTask.Tier.WOOD)
                    || hasExactToolSet(mod, ToolSetTask.Tier.STONE)
                    || hasExactToolSet(mod, ToolSetTask.Tier.IRON)
                    || hasExactToolSet(mod, ToolSetTask.Tier.DIAMOND);
            case STONE -> hasExactToolSet(mod, ToolSetTask.Tier.STONE)
                    || hasExactToolSet(mod, ToolSetTask.Tier.IRON)
                    || hasExactToolSet(mod, ToolSetTask.Tier.DIAMOND);
            case IRON -> hasExactToolSet(mod, ToolSetTask.Tier.IRON)
                    || hasExactToolSet(mod, ToolSetTask.Tier.DIAMOND);
            case DIAMOND -> hasExactToolSet(mod, ToolSetTask.Tier.DIAMOND);
        };
    }

    private boolean hasExactToolSet(Belfegor mod, ToolSetTask.Tier tier) {
        return switch (tier) {
            case WOOD -> mod.getItemStorage().hasItem(Items.WOODEN_PICKAXE)
                    && mod.getItemStorage().hasItem(Items.WOODEN_AXE)
                    && mod.getItemStorage().hasItem(Items.WOODEN_SHOVEL)
                    && mod.getItemStorage().hasItem(Items.WOODEN_SWORD);
            case STONE -> mod.getItemStorage().hasItem(Items.STONE_PICKAXE)
                    && mod.getItemStorage().hasItem(Items.STONE_AXE)
                    && mod.getItemStorage().hasItem(Items.STONE_SHOVEL)
                    && mod.getItemStorage().hasItem(Items.STONE_SWORD);
            case IRON -> mod.getItemStorage().hasItem(Items.IRON_PICKAXE)
                    && mod.getItemStorage().hasItem(Items.IRON_AXE)
                    && mod.getItemStorage().hasItem(Items.IRON_SHOVEL)
                    && mod.getItemStorage().hasItem(Items.IRON_SWORD);
            case DIAMOND -> mod.getItemStorage().hasItem(Items.DIAMOND_PICKAXE)
                    && mod.getItemStorage().hasItem(Items.DIAMOND_AXE)
                    && mod.getItemStorage().hasItem(Items.DIAMOND_SHOVEL)
                    && mod.getItemStorage().hasItem(Items.DIAMOND_SWORD);
        };
    }

    private boolean near(Belfegor mod, BlockPos pos, int distanceSq) {
        return mod.getPlayer() != null
                && pos != null
                && pos.getSquaredDistance(mod.getPlayer().getBlockPos()) <= distanceSq;
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

    private void rememberStorage(String status) {
        if (_home == null || _storageChest == null) return;
        BaseMemory.getInstance().rememberModule(_home, _dimension,
                "camp_stockpile_chest", "fixture",
                _storageChest, 1, 1, 1, status,
                "targeted storage chest for @stockpile/@player resource maintenance");
        BaseStorageMemory.getInstance().rememberChest(_home, _dimension, _storageChest,
                status.equals("stockpiled") ? "storage_room" : "camp_storage",
                false, "camp stockpile storage chest");
        BaseMemory.getInstance().rememberInspection(_home, _dimension,
                "camp_stockpile", "resources",
                _targets.length, 0, 0, 1, status,
                "profile=" + profileLabel()
                        + " toolTier=" + _toolTier.name().toLowerCase()
                        + " storage=" + _storageChest.toShortString());
        LocationMemory.getInstance().remember("home_room_storage",
                _storageChest.getX(), _storageChest.getY(), _storageChest.getZ(),
                _dimension, "camp stockpile storage chest");
        BaseMemory.getInstance().save();
        LocationMemory.getInstance().save();
        BaseStorageMemory.getInstance().save();
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _activeTask = null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof CampStockpileTask task
                && task._toolTier == _toolTier
                && task._profile == _profile
                && Arrays.toString(task._targets).equals(Arrays.toString(_targets));
    }

    @Override
    protected String toDebugString() {
        return "Camp stockpile phase=" + _phase
                + " profile=" + profileLabel()
                + " toolTier=" + _toolTier.name().toLowerCase()
                + " home=" + (_home == null ? "?" : _home.toShortString())
                + " storage=" + (_storageChest == null ? "?" : _storageChest.toShortString());
    }

    private String profileLabel() {
        return _profile == null ? "custom" : _profile.name().toLowerCase();
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return _phase == Phase.DONE;
    }
}
