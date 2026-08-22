package adris.belfegor.tasks.construction;

import adris.belfegor.Belfegor;
import adris.belfegor.Settings;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.debug.DebugLogger;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.memory.BaseStorageMemory;
import adris.belfegor.memory.LocationMemory;
import adris.belfegor.tasks.InteractWithBlockTask;
import adris.belfegor.tasks.movement.GetToBlockTask;
import adris.belfegor.tasks.resources.CollectBucketLiquidTask;
import adris.belfegor.tasks.resources.GetBuildingMaterialsTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Builds a remembered room expansion off the persistent @player home base.
 *
 * Expansions are intentionally modular:
 * - choose one side of the existing base/module graph,
 * - carve a two-wide 3-5 block hall,
 * - build a named room shell,
 * - add type-specific internals such as hydrated farmland, storage, or a roofed mob room,
 * - persist the center and connection metadata so @home <room> can route there later.
 */
public class BuildBaseExpansionTask extends Task {

    public enum RoomType {
        FARMLAND,
        STORAGE,
        WORKSHOP,
        ARMORY,
        MOBFARM,
        EMPTY
    }

    private static final Block[] STRUCTURE_BLOCKS = {
            Blocks.COBBLESTONE
    };
    private static final Item[] HOES = {
            Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE,
            Items.GOLDEN_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE
    };
    private static final int WALL_HEIGHT = 4;
    private static final int HALL_WIDTH = 2;
    private static final int MIN_FARM_WATER_BUCKETS = 2;
    private static final int MIN_FARM_HOES = 1;

    private enum Phase {
        PLAN,
        GO_HOME,
        CLEAR,
        FLOOR,
        WALLS,
        ROOF,
        FIXTURES,
        FARM_WATER,
        FARM_TILL,
        FARM_PLANT,
        DONE
    }

    private final RoomType _type;
    private final String _requestedName;
    private final boolean _repairInPlace;
    private final BlockPos _rememberedRepairAnchor;
    private final BlockPos _rememberedRepairCenter;
    private final int _rememberedRepairWidth;
    private final int _rememberedRepairDepth;
    private final String _rememberedRepairParent;
    private final String _rememberedRepairDirection;
    private final int _rememberedRepairHallLength;
    private Phase _phase = Phase.PLAN;
    private Task _activeTask;
    private int _index;

    private BlockPos _baseCenter;
    private BaseMemory.BaseRecord _base;
    private String _dimension;
    private String _roomName;
    private String _parentName = "core";
    private Direction _direction;
    private int _hallLength;
    private int _roomSize;
    private BlockPos _hallStart;
    private BlockPos _roomAnchor;
    private BlockPos _roomCenter;
    private List<BlockPos> _clearTargets = List.of();
    private List<BlockPos> _floorTargets = List.of();
    private List<BlockPos> _wallTargets = List.of();
    private List<BlockPos> _roofTargets = List.of();
    private List<BlockPos> _waterTargets = List.of();
    private List<BlockPos> _farmTargets = List.of();
    private List<BlockRegion> _clearRegions = List.of();
    private int _lastLoggedFloorPatchCount = Integer.MIN_VALUE;
    private boolean _placementBlocked;
    private int _farmInteractCooldown;
    private int _lastNoFarmStandIndex = -1;
    private final Set<BlockPos> _loggedPreservedRepairFixtures = new HashSet<>();

    public BuildBaseExpansionTask(RoomType type, String requestedName) {
        this(type, requestedName, null);
    }

    /**
     * Rebuilds an established room at its remembered coordinates. The geometry
     * is copied immediately so validation can safely change the live module's
     * status without allowing a subsequent planning tick to relocate it.
     */
    public BuildBaseExpansionTask(RoomType type, String requestedName,
                                  BaseMemory.BaseModule repairModule) {
        _type = type == null ? RoomType.EMPTY : type;
        _requestedName = requestedName == null || requestedName.isBlank()
                ? defaultName(_type)
                : normalize(requestedName);
        _repairInPlace = repairModule != null;
        _rememberedRepairAnchor = repairModule == null ? null
                : new BlockPos(repairModule.x, repairModule.y, repairModule.z);
        _rememberedRepairCenter = repairModule == null ? null
                : new BlockPos(repairModule.centerX, repairModule.centerY, repairModule.centerZ);
        _rememberedRepairWidth = repairModule == null ? 0 : repairModule.width;
        _rememberedRepairDepth = repairModule == null ? 0 : repairModule.depth;
        _rememberedRepairParent = repairModule == null ? "" : repairModule.parent;
        _rememberedRepairDirection = repairModule == null ? "" : repairModule.direction;
        _rememberedRepairHallLength = repairModule == null ? 0 : repairModule.hallLength;
    }

    @Override
    protected void onStart(Belfegor mod) {
        _phase = Phase.PLAN;
        _activeTask = null;
        _index = 0;
        _farmInteractCooldown = 0;
        mod.getBehaviour().push();
        mod.getBehaviour().setAutoMLG(false);
        mod.getBehaviour().setAllowDiagonalAscend(false);
    }

    @Override
    protected Task onTick(Belfegor mod) {
        switch (_phase) {
            case PLAN -> {
                plan(mod);
                if (_placementBlocked) {
                    remember("blocked_no_supported_footprint");
                    next(Phase.DONE);
                    return null;
                }
                protectExistingBaseModules(mod);
                remember("planned");
                restorePhase(mod);
                if (_phase == Phase.PLAN) _phase = Phase.GO_HOME;
                next(_phase);
                return null;
            }
            case GO_HOME -> {
                if (_baseCenter != null && mod.getPlayer() != null
                        && _baseCenter.getSquaredDistance(mod.getPlayer().getBlockPos()) > 20 * 20) {
                    setDebugState("Returning to base before building " + _roomName);
                    return cache(mod, GetToBlockTask.baseAware(mod, _baseCenter));
                }
                next(Phase.CLEAR);
                return null;
            }
            case CLEAR -> {
                Task clear = runClearRegions(mod);
                if (clear != null) return clear;
                remember("clear_complete");
                next(Phase.FLOOR);
                return null;
            }
            case FLOOR -> {
                List<BlockPos> floorTargets = _type == RoomType.FARMLAND
                        ? farmFloorPatchTargets(mod)
                        : nonFarmFloorPatchTargets(mod);
                Task floor = _type == RoomType.FARMLAND
                        ? runBuildRegion(mod, floorTargets, "dirt farm floor", false, Blocks.DIRT)
                        : runBuildRegion(mod, floorTargets, "floor patches", false, STRUCTURE_BLOCKS);
                if (floor != null) return floor;
                remember("floor_complete");
                next(Phase.WALLS);
                return null;
            }
            case WALLS -> {
                Task walls = runBuildRegion(mod, _wallTargets, "walls", false, STRUCTURE_BLOCKS);
                if (walls != null) return walls;
                remember("walls_complete");
                next(_roofTargets.isEmpty() ? Phase.FIXTURES : Phase.ROOF);
                return null;
            }
            case ROOF -> {
                Task roof = runBuildRegion(mod, _roofTargets, "roof", false, STRUCTURE_BLOCKS);
                if (roof != null) return roof;
                remember("roof_complete");
                next(Phase.FIXTURES);
                return null;
            }
            case FIXTURES -> {
                Task fixtures = runFixtures(mod);
                if (fixtures != null) return fixtures;
                next(_type == RoomType.FARMLAND ? Phase.FARM_WATER : Phase.DONE);
                return null;
            }
            case FARM_WATER -> {
                Task water = runFarmWater(mod);
                if (water != null) return water;
                remember("water_complete");
                next(Phase.FARM_TILL);
                return null;
            }
            case FARM_TILL -> {
                Task till = runFarmTill(mod);
                if (till != null) return till;
                if (!allFarmTargetsTilled(mod)) return null;
                remember("tilled");
                next(Phase.FARM_PLANT);
                return null;
            }
            case FARM_PLANT -> {
                Task plant = runFarmPlant(mod);
                if (plant != null) return plant;
                if (!allFarmTargetsPlanted(mod)) return null;
                remember("complete");
                next(Phase.DONE);
                return null;
            }
            case DONE -> {
                return null;
            }
        }
        return null;
    }

    private void plan(Belfegor mod) {
        _dimension = WorldHelper.getCurrentDimension().name();
        BlockPos playerPos = mod.getPlayer() == null ? BlockPos.ORIGIN : mod.getPlayer().getBlockPos();
        BlockPos configuredHome = mod.getModSettings().getHomeBasePosition();
        if (configuredHome != null) {
            _base = BaseMemory.getInstance().baseAt(configuredHome, _dimension)
                    .orElseGet(() -> BaseMemory.getInstance().rememberBase(
                            configuredHome, _dimension, 8, WALL_HEIGHT, 5,
                            "created_by_build_command_locked_home"));
        } else {
            _base = BaseMemory.getInstance().nearestBase(playerPos, _dimension)
                    .orElseGet(() -> BaseMemory.getInstance().rememberBase(
                            playerPos, _dimension, 8, WALL_HEIGHT, 5,
                            "created_by_build_command_new_home"));
        }
        _baseCenter = _base.center();
        if (configuredHome == null) {
            mod.getModSettings().setHomeBasePosition(_baseCenter);
            Settings.save(mod.getModSettings());
        }

        _roomName = _repairInPlace ? _requestedName : uniqueRoomName(_base, _requestedName, _type);
        int expectedRoomSize = switch (_type) {
            case FARMLAND -> 9;
            case MOBFARM -> 11;
            case STORAGE, WORKSHOP, ARMORY -> 7;
            case EMPTY -> 7;
        };
        _roomSize = _repairInPlace
                && _rememberedRepairWidth == _rememberedRepairDepth
                && _rememberedRepairWidth >= 5
                ? _rememberedRepairWidth
                : expectedRoomSize;
        if (_repairInPlace) {
            loadRememberedRepairPlacement();
        } else {
            choosePlacement(mod);
        }

        _clearTargets = buildClearTargets();
        _floorTargets = buildFloorTargets();
        _wallTargets = buildWallTargets();
        _roofTargets = _type == RoomType.MOBFARM ? buildRoofTargets() : List.of();
        _waterTargets = _type == RoomType.FARMLAND ? buildWaterTargets() : List.of();
        _farmTargets = _type == RoomType.FARMLAND ? buildFarmTargets() : List.of();
        _clearRegions = buildClearRegions();
    }

    private void protectExistingBaseModules(Belfegor mod) {
        mod.getBehaviour().avoidBlockBreaking(pos -> {
            if (pos == null || _baseCenter == null || _dimension == null) return false;
            Optional<BaseMemory.BaseRecord> liveBase = BaseMemory.getInstance().nearestBase(_baseCenter, _dimension);
            if (liveBase.isEmpty()) return false;
            for (BaseMemory.BaseModule module : liveBase.get().modules) {
                if (module == null) continue;
                String moduleName = normalize(module.name);
                if (moduleName.equals(normalize(_roomName))
                        || moduleName.equals(normalize(_roomName + "_hall"))) {
                    continue;
                }
                if (isSupersededIncompleteModule(module)) continue;
                if (!isConstructedEnoughToProtect(module)) continue;
                if (isConnectorOpeningInParent(moduleName, pos)) continue;
                if (insideModule(module, pos, 1)) {
                    return true;
                }
            }
            return false;
        });
    }

    private boolean isConstructedEnoughToProtect(BaseMemory.BaseModule module) {
        String status = normalize(module.status);
        return status.equals("complete")
                || status.endsWith("_complete")
                || status.equals("reachable")
                || status.equals("ready")
                || status.startsWith("ready_")
                || status.equals("tilled")
                || status.equals("water_complete");
    }

    /**
     * Resumes an interrupted room build from the remembered phase, falling back
     * to the earliest phase whose world prerequisites are not actually met so
     * a stale saved phase can never skip walls, floors, or fixtures.
     */
    private void restorePhase(Belfegor mod) {
        if (_baseCenter == null || _dimension == null) return;
        Optional<String> saved = BaseMemory.getInstance()
                .loadBuildPhase(_baseCenter, _dimension, "expansion_" + _roomName);
        if (saved.isEmpty()) {
            _phase = Phase.GO_HOME;
            return;
        }
        Phase savedPhase;
        try {
            savedPhase = Phase.valueOf(saved.get());
        } catch (Exception ignored) {
            _phase = Phase.GO_HOME;
            return;
        }
        _phase = savedPhase;
        if (savedPhase.ordinal() >= Phase.FLOOR.ordinal() && !clearTargetsDone(mod)) {
            _phase = Phase.CLEAR;
        }
        if (savedPhase.ordinal() >= Phase.WALLS.ordinal() && !floorTargetsDone(mod)) {
            _phase = Phase.FLOOR;
        }
        if (savedPhase.ordinal() >= Phase.ROOF.ordinal()
                && countMissingTargets(mod, _wallTargets, STRUCTURE_BLOCKS) > 0) {
            _phase = Phase.WALLS;
        }
        if (savedPhase.ordinal() >= Phase.FIXTURES.ordinal()
                && !_roofTargets.isEmpty()
                && countMissingTargets(mod, _roofTargets, STRUCTURE_BLOCKS) > 0) {
            _phase = Phase.ROOF;
        }
        if (savedPhase.ordinal() >= Phase.FARM_WATER.ordinal()
                && _type == RoomType.FARMLAND
                && fixturesMissing(mod)) {
            _phase = Phase.FIXTURES;
        }
        if (_phase == Phase.DONE && !expansionDone(mod)) {
            _phase = Phase.FIXTURES;
        }
        persistPhase();
    }

    private boolean clearTargetsDone(Belfegor mod) {
        if (_clearTargets == null || _clearTargets.isEmpty()) return true;
        for (BlockPos target : _clearTargets) {
            if (!clearTargetDone(mod, target)) return false;
        }
        return true;
    }

    private boolean floorTargetsDone(Belfegor mod) {
        if (_floorTargets == null || _floorTargets.isEmpty()) return true;
        for (BlockPos target : _floorTargets) {
            if (_type == RoomType.FARMLAND) {
                if (mod.getWorld().getBlockState(target).getBlock() != Blocks.DIRT) return false;
            } else if (!targetDone(mod, target, false, STRUCTURE_BLOCKS)) {
                return false;
            }
        }
        return true;
    }

    private int countMissingTargets(Belfegor mod, List<BlockPos> targets, Block[] desired) {
        int missing = 0;
        if (targets == null) return 0;
        for (BlockPos target : targets) {
            boolean match = false;
            Block current = mod.getWorld().getBlockState(target).getBlock();
            for (Block block : desired) {
                if (block == current) {
                    match = true;
                    break;
                }
            }
            if (!match) missing++;
        }
        return missing;
    }

    private boolean fixturesMissing(Belfegor mod) {
        if (_roomCenter == null) return false;
        return switch (_type) {
            case STORAGE -> mod.getWorld().getBlockState(_roomCenter).getBlock() != Blocks.CHEST;
            case WORKSHOP -> mod.getWorld().getBlockState(_roomCenter.add(-1, 0, 0)).getBlock() != Blocks.CRAFTING_TABLE
                    || mod.getWorld().getBlockState(_roomCenter.add(1, 0, 0)).getBlock() != Blocks.FURNACE;
            case ARMORY -> mod.getWorld().getBlockState(_roomCenter.add(-2, 0, 0)).getBlock() != Blocks.CHEST
                    || mod.getWorld().getBlockState(_roomCenter.add(2, 0, 0)).getBlock() != Blocks.CHEST
                    || mod.getWorld().getBlockState(_roomCenter).getBlock() != Blocks.CRAFTING_TABLE;
            default -> false;
        };
    }

    private boolean expansionDone(Belfegor mod) {
        if (countMissingTargets(mod, _wallTargets, STRUCTURE_BLOCKS) > 0) return false;
        if (!_roofTargets.isEmpty() && countMissingTargets(mod, _roofTargets, STRUCTURE_BLOCKS) > 0) return false;
        if (fixturesMissing(mod)) return false;
        if (_type != RoomType.FARMLAND) return true;
        for (BlockPos water : _waterTargets) {
            if (mod.getWorld().getBlockState(water).getBlock() != Blocks.WATER) return false;
        }
        return allFarmTargetsTilled(mod) && allFarmTargetsPlanted(mod);
    }

    private boolean insideModule(BaseMemory.BaseModule module, BlockPos pos, int margin) {
        int m = Math.max(0, margin);
        int minX = module.x - m;
        int minY = module.y - m;
        int minZ = module.z - m;
        int maxX = module.x + Math.max(1, module.width) - 1 + m;
        int maxY = module.y + Math.max(1, module.height) - 1 + m;
        int maxZ = module.z + Math.max(1, module.depth) - 1 + m;
        return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    private Task runClearRegions(Belfegor mod) {
        while (_index < _clearTargets.size() && clearTargetDone(mod, _clearTargets.get(_index))) {
            _index++;
        }
        if (_index >= _clearTargets.size()) return null;
        ArrayList<BlockPos> batch = new ArrayList<>();
        for (int i = _index; i < _clearTargets.size() && batch.size() < 96; i++) {
            BlockPos target = _clearTargets.get(i);
            if (!clearTargetDone(mod, target)) {
                batch.add(target);
            }
        }
        if (batch.isEmpty()) return null;
        setDebugState("Clearing planned air for " + _roomName
                + " target " + (_index + 1) + "/" + _clearTargets.size()
                + " batch=" + batch.size());
        return cache(mod, new ClearRegionTask(batch));
    }

    private boolean clearTargetDone(Belfegor mod, BlockPos target) {
        if (target == null || mod.getWorld() == null) return true;
        if (BaseMemory.getInstance().isProtectedFixturePosition(target, WorldHelper.getCurrentDimension().name())) {
            return true;
        }
        if (preserveRepairFixture(mod, target)) return true;
        Block block = mod.getWorld().getBlockState(target).getBlock();
        if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) return true;
        if (isProtectedExistingModuleBlock(target)) return true;
        if (isCorrectCurrentBuildBlock(target, block)) return true;
        return false;
    }

    /**
     * In-place repair must never mine a data-bearing block while clearing the
     * remembered room. A lost/stale fixture-memory entry previously let the
     * clear pass break a chest and then place a new empty chest in the exact
     * same position. Preserve all block entities (chests, shulkers, barrels,
     * furnaces, and similar stateful fixtures), plus the expected stateless
     * crafting-table fixtures. The fixture phase can reuse and re-index them.
     */
    private boolean preserveRepairFixture(Belfegor mod, BlockPos target) {
        if (!_repairInPlace || mod.getWorld() == null) return false;
        Block block = mod.getWorld().getBlockState(target).getBlock();
        boolean dataBearing = mod.getWorld().getBlockEntity(target) != null;
        boolean expectedCraftingTable = block == Blocks.CRAFTING_TABLE
                && ((_type == RoomType.WORKSHOP
                && target.equals(_roomCenter.add(-1, 0, 0)))
                || (_type == RoomType.ARMORY && target.equals(_roomCenter)));
        if (!dataBearing && !expectedCraftingTable) return false;
        if (_loggedPreservedRepairFixtures.add(target.toImmutable())) {
            DebugLogger.getInstance().logImmediate("BASE-BUILD",
                    "preserve-repair-fixture room=" + _roomName
                            + " pos=" + target.toShortString()
                            + " block=" + block);
        }
        return true;
    }

    private boolean isProtectedExistingModuleBlock(BlockPos pos) {
        if (_base == null || pos == null) return false;
        for (BaseMemory.BaseModule module : _base.modules) {
            if (module == null) continue;
            String moduleName = normalize(module.name);
            if (moduleName.equals(normalize(_roomName))
                    || moduleName.equals(normalize(_roomName + "_hall"))) {
                continue;
            }
            if (isSupersededIncompleteModule(module)) continue;
            if (!isConstructedEnoughToProtect(module)) continue;
            if (isConnectorOpeningInParent(moduleName, pos)) continue;
            if (insideModule(module, pos, 1)) return true;
        }
        return false;
    }

    private boolean isConnectorOpeningInParent(String moduleName, BlockPos pos) {
        String parent = normalize(_parentName);
        boolean parentEnvelope = normalize(moduleName).equals(parent)
                || (parent.equals("core")
                && (normalize(moduleName).equals("perimeter_wall")
                || normalize(moduleName).equals("core")));
        if (!parentEnvelope || pos == null) return false;
        for (BlockPos floor : hallFloorPositions()) {
            if (floor.getX() == pos.getX()
                    && floor.getZ() == pos.getZ()
                    && pos.getY() >= floor.getY() + 1
                    && pos.getY() <= floor.getY() + WALL_HEIGHT) {
                return true;
            }
        }
        return false;
    }

    private boolean isCorrectCurrentBuildBlock(BlockPos pos, Block block) {
        if (pos == null || block == null) return false;
        if (_wallTargets.contains(pos) && block == Blocks.COBBLESTONE) return true;
        if (_roofTargets.contains(pos) && block == Blocks.COBBLESTONE) return true;
        if (_floorTargets.contains(pos)) {
            if (_type == RoomType.FARMLAND) {
                return block == Blocks.DIRT
                        || block == Blocks.GRASS_BLOCK
                        || block == Blocks.FARMLAND
                        || block == Blocks.WATER;
            }
            return block == Blocks.COBBLESTONE || block == Blocks.GRASS_BLOCK || block == Blocks.DIRT;
        }
        if (_waterTargets.contains(pos) && block == Blocks.WATER) return true;
        return false;
    }

    private Task runBuildRegion(Belfegor mod, List<BlockPos> targets, String label,
                                boolean useThrowaways, Block... desired) {
        if (targets.isEmpty()) return null;
        int needed = 0;
        for (BlockPos target : targets) {
            if (!targetDone(mod, target, useThrowaways, desired)) needed++;
        }
        if (needed > 0 && useThrowaways && StorageHelper.getBuildingMaterialCount(mod) < Math.min(needed, 160)) {
            setDebugState("Collecting materials for " + _roomName + " " + label);
            return new GetBuildingMaterialsTask(Math.min(Math.max(needed, 32), 160));
        }
        if (needed > 0 && !useThrowaways
                && mod.getItemStorage().getItemCountInventoryOnly(blockItems(desired)) < Math.min(needed, 24)) {
            Task materials = materialTaskFor(desired, Math.min(Math.max(needed, 32), 64));
            if (materials != null) {
                setDebugState("Collecting carried exact materials for " + _roomName + " " + label
                        + " carried=" + mod.getItemStorage().getItemCountInventoryOnly(blockItems(desired))
                        + " needed=" + needed);
                return materials;
            }
        }
        if (needed <= 0) return null;
        setDebugState("Baritone building " + _roomName + " " + label + " as one schematic");
        return cache(mod, new BuildRegionSchematicTask(_roomName + " " + label, targets, useThrowaways, desired));
    }

    private Task runFarmDirtFloor(Belfegor mod) {
        int needed = 0;
        for (BlockPos target : _floorTargets) {
            Block block = mod.getWorld().getBlockState(target).getBlock();
            if (block != Blocks.DIRT && block != Blocks.GRASS_BLOCK && block != Blocks.FARMLAND && block != Blocks.WATER) {
                needed++;
            }
        }
        if (needed > 0 && mod.getItemStorage().getItemCountInventoryOnly(Items.DIRT) < Math.min(needed, 24)) {
            Task dirt = TaskCatalogue.getItemTask("dirt", Math.min(Math.max(needed, 32), 64));
            if (dirt != null) return dirt;
        }
        while (_index < _floorTargets.size()) {
            Block block = mod.getWorld().getBlockState(_floorTargets.get(_index)).getBlock();
            if (block == Blocks.DIRT || block == Blocks.GRASS_BLOCK || block == Blocks.FARMLAND || block == Blocks.WATER) {
                _index++;
                continue;
            }
            break;
        }
        if (_index >= _floorTargets.size()) return null;
        BlockPos target = _floorTargets.get(_index);
        setDebugState("Laying dirt farm floor " + (_index + 1) + "/" + _floorTargets.size());
        if (!mod.getWorld().getBlockState(target).isAir()) {
            return cache(mod, new DestroyBlockTask(target));
        }
        return new InteractWithBlockTask(new ItemTarget(Items.DIRT, 1), Direction.UP, target.down(), true);
    }

    private Task runFixtures(Belfegor mod) {
        if (_type == RoomType.STORAGE) {
            BlockPos chest = _roomCenter;
            if (!mod.getItemStorage().hasItem(Items.CHEST)) {
                return TaskCatalogue.getItemTask("chest", 1);
            }
            if (mod.getWorld().getBlockState(chest).getBlock() != Blocks.CHEST) {
                setDebugState("Placing storage room chest");
                return placeFixture(mod, chest, Blocks.CHEST);
            }
            rememberStorageFixture(chest, "bulk_storage", "bulk item storage room chest");
        }
        if (_type == RoomType.WORKSHOP) {
            BlockPos table = _roomCenter.add(-1, 0, 0);
            if (!mod.getItemStorage().hasItem(Items.CRAFTING_TABLE)) {
                return TaskCatalogue.getItemTask("crafting_table", 1);
            }
            if (mod.getWorld().getBlockState(table).getBlock() != Blocks.CRAFTING_TABLE) {
                setDebugState("Placing workshop crafting table");
                return placeFixture(mod, table, Blocks.CRAFTING_TABLE);
            }
            BlockPos furnace = _roomCenter.add(1, 0, 0);
            if (!mod.getItemStorage().hasItem(Items.FURNACE)) {
                Task furnaceTask = TaskCatalogue.getItemTask("furnace", 1);
                if (furnaceTask != null) return furnaceTask;
            }
            if (mod.getItemStorage().hasItem(Items.FURNACE)
                    && mod.getWorld().getBlockState(furnace).getBlock() != Blocks.FURNACE) {
                setDebugState("Placing workshop furnace");
                return placeFixture(mod, furnace, Blocks.FURNACE);
            }
            rememberFixture("workshop_table_fixture", table, "crafting_table",
                    "workshop crafting table");
            if (mod.getWorld().getBlockState(furnace).getBlock() == Blocks.FURNACE) {
                rememberFixture("workshop_furnace_fixture", furnace, "furnace",
                        "workshop furnace");
            }
        }
        if (_type == RoomType.ARMORY) {
            BlockPos gearChest = _roomCenter.add(-2, 0, 0);
            BlockPos materialChest = _roomCenter.add(2, 0, 0);
            BlockPos table = _roomCenter;
            if (mod.getWorld().getBlockState(gearChest).getBlock() != Blocks.CHEST) {
                if (!mod.getItemStorage().hasItem(Items.CHEST)) {
                    return TaskCatalogue.getItemTask("chest", 1);
                }
                setDebugState("Placing armory backup gear chest");
                return placeFixture(mod, gearChest, Blocks.CHEST);
            }
            rememberStorageFixture(gearChest, "armory_gear", "backup tools, weapons, armor, bows and shields");
            if (mod.getWorld().getBlockState(materialChest).getBlock() != Blocks.CHEST) {
                if (!mod.getItemStorage().hasItem(Items.CHEST)) {
                    return TaskCatalogue.getItemTask("chest", 1);
                }
                setDebugState("Placing armory material chest");
                return placeFixture(mod, materialChest, Blocks.CHEST);
            }
            rememberStorageFixture(materialChest, "armory_materials", "tool, armor, bow and arrow crafting materials");
            if (mod.getWorld().getBlockState(table).getBlock() != Blocks.CRAFTING_TABLE) {
                if (!mod.getItemStorage().hasItem(Items.CRAFTING_TABLE)) {
                    return TaskCatalogue.getItemTask("crafting_table", 1);
                }
                setDebugState("Placing armory crafting table");
                return placeFixture(mod, table, Blocks.CRAFTING_TABLE);
            }
            rememberFixture("armory_table_fixture", table, "crafting_table",
                    "armory repair and equipment crafting table");
        }
        remember("complete");
        return null;
    }

    private void rememberStorageFixture(BlockPos chest, String role, String note) {
        BaseStorageMemory.getInstance().rememberChest(_baseCenter, _dimension, chest,
                role, false, note);
        BaseStorageMemory.getInstance().save();
        rememberFixture(_roomName + "_" + role + "_fixture", chest, "storage_fixture", note);
        LocationMemory.getInstance().remember("home_storage_" + role,
                chest.getX(), chest.getY(), chest.getZ(), _dimension,
                "room=" + _roomName + ";" + note);
        LocationMemory.getInstance().save();
    }

    private void rememberFixture(String name, BlockPos pos, String fixtureType, String note) {
        BaseMemory.getInstance().rememberModule(_baseCenter, _dimension, name, "fixture",
                pos, 1, 1, 1, "complete", "fixtureType=" + fixtureType + ";" + note,
                _roomName, _direction == null ? "" : _direction.asString(), 0, 0);
        BaseMemory.getInstance().save();
    }

    private Task placeFixture(Belfegor mod, BlockPos target, Block block) {
        BlockPos stand = fixtureStandPosition(mod, target);
        if (stand == null) {
            BlockPos obstruction = fixtureStandObstruction(mod, target);
            if (obstruction != null) {
                setDebugState("Clearing a safe standing position for " + block.getName().getString());
                return cache(mod, new DestroyBlockTask(obstruction));
            }
        }
        if (stand != null && mod.getPlayer() != null
                && (target.equals(mod.getPlayer().getBlockPos())
                || stand.getSquaredDistance(mod.getPlayer().getBlockPos()) > 4)) {
            return cache(mod, new GetToBlockTask(stand));
        }
        if (!mod.getWorld().getBlockState(target).isAir()) {
            return cache(mod, new DestroyBlockTask(target));
        }
        // Expansion fixtures use the same owned placement state machine as the
        // core campsite.  A raw support-face interaction can click from inside
        // the destination and alternate approach/wander tasks indefinitely.
        return cache(mod, new PlaceBlockTask(target, new Block[]{block}, false, true));
    }

    private BlockPos fixtureStandPosition(Belfegor mod, BlockPos target) {
        Direction[] options = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (Direction option : options) {
            BlockPos stand = target.offset(option);
            if (WorldHelper.isSolid(mod, stand.down())
                    && mod.getWorld().getBlockState(stand).isAir()
                    && mod.getWorld().getBlockState(stand.up()).isAir()) {
                return stand;
            }
        }
        return null;
    }

    private BlockPos fixtureStandObstruction(Belfegor mod, BlockPos target) {
        Direction[] options = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        String dimension = WorldHelper.getCurrentDimension().name();
        for (Direction option : options) {
            BlockPos stand = target.offset(option);
            if (!WorldHelper.isSolid(mod, stand.down())) continue;
            BlockPos[] clearance = {stand.up(), stand};
            for (BlockPos blocked : clearance) {
                Block current = mod.getWorld().getBlockState(blocked).getBlock();
                if (current == Blocks.AIR || current == Blocks.WATER || current == Blocks.LAVA) continue;
                if (isCorrectCurrentBuildBlock(blocked, current)) continue;
                if (BaseMemory.getInstance().isProtectedFixturePosition(blocked, dimension)) continue;
                return blocked;
            }
        }
        return null;
    }

    private Task runFarmWater(Belfegor mod) {
        for (BlockPos water : _waterTargets) {
            if (mod.getWorld().getBlockState(water).getBlock() == Blocks.WATER) continue;
            if (mod.getWorld().getBlockState(water).getBlock() != Blocks.AIR) {
                setDebugState("Digging farm water/infinite-source hole " + water.toShortString());
                return cache(mod, new DestroyBlockTask(water));
            }
            int bucketCount = mod.getItemStorage().getItemCount(Items.WATER_BUCKET);
            if (bucketCount < MIN_FARM_WATER_BUCKETS) {
                setDebugState("Preparing water buckets for farm source "
                        + bucketCount + "/" + MIN_FARM_WATER_BUCKETS);
                return new CollectBucketLiquidTask.CollectWaterBucketTask(MIN_FARM_WATER_BUCKETS);
            }
            WaterPlacementSupport support = waterPlacementSupport(mod, water);
            if (support == null) {
                setDebugState("Building solid basin floor under farm water source "
                        + (_waterTargets.indexOf(water) + 1) + "/" + _waterTargets.size());
                return cache(mod, new PlaceBlockTask(water.down(), new Block[]{Blocks.COBBLESTONE}, false, true));
            }
            setDebugState("Filling farm water/infinite-source hole "
                    + (_waterTargets.indexOf(water) + 1) + "/" + _waterTargets.size());
            return new InteractWithBlockTask(new ItemTarget(Items.WATER_BUCKET, 1),
                    support.face(), support.block(), true);
        }
        return null;
    }

    private WaterPlacementSupport waterPlacementSupport(Belfegor mod, BlockPos water) {
        if (WorldHelper.isSolid(mod, water.down())) {
            return new WaterPlacementSupport(water.down(), Direction.UP);
        }
        Direction[] sides = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (Direction side : sides) {
            BlockPos support = water.offset(side);
            if (WorldHelper.isSolid(mod, support)) {
                return new WaterPlacementSupport(support, side.getOpposite());
            }
        }
        return null;
    }

    private record WaterPlacementSupport(BlockPos block, Direction face) {}

    private Task runFarmTill(Belfegor mod) {
        int hoeCount = mod.getItemStorage().getItemCount(HOES);
        if (hoeCount < MIN_FARM_HOES) {
            Task hoe = TaskCatalogue.getItemTask("wooden_hoe", MIN_FARM_HOES);
            if (hoe != null) return hoe;
        }
        while (_index < _farmTargets.size()
                && mod.getWorld().getBlockState(_farmTargets.get(_index)).getBlock() == Blocks.FARMLAND) {
            _index++;
        }
        if (_index >= _farmTargets.size()) {
            int invalid = firstUntilledFarmTarget(mod);
            if (invalid < 0) return null;
            _index = invalid;
            DebugLogger.getInstance().logImmediate("BASE-FARM",
                    "till-pass-restart firstInvalid=" + _farmTargets.get(_index).toShortString()
                            + " index=" + (_index + 1) + "/" + _farmTargets.size());
        }
        BlockPos soil = _farmTargets.get(_index);
        Block soilBlock = mod.getWorld().getBlockState(soil).getBlock();
        if (soilBlock != Blocks.DIRT && soilBlock != Blocks.GRASS_BLOCK) {
            if (!mod.getItemStorage().hasItem(Items.DIRT)) {
                Task dirt = TaskCatalogue.getItemTask("dirt", 1);
                if (dirt != null) return dirt;
            }
            setDebugState("Repairing farm soil " + (_index + 1) + "/" + _farmTargets.size());
            DebugLogger.getInstance().logImmediate("BASE-FARM",
                    "repair-soil soil=" + soil.toShortString()
                            + " current=" + soilBlock.getName().getString()
                            + " index=" + (_index + 1) + "/" + _farmTargets.size());
            return cache(mod, new PlaceBlockTask(soil, new Block[]{Blocks.DIRT}, false, true));
        }
        Task approach = getNearFarmSoil(mod, soil);
        if (approach != null) return approach;
        setDebugState("Tilling hydrated farmland " + (_index + 1) + "/" + _farmTargets.size());
        directFarmUse(mod, soil, new ItemTarget(HOES, 1), "till");
        return null;
    }

    private Task runFarmPlant(Belfegor mod) {
        if (!mod.getItemStorage().hasItem(Items.WHEAT_SEEDS)) {
            Task seeds = TaskCatalogue.getItemTask("wheat_seeds", 16);
            if (seeds != null) return seeds;
            return null;
        }
        while (_index < _farmTargets.size()
                && mod.getWorld().getBlockState(_farmTargets.get(_index).up()).getBlock() == Blocks.WHEAT) {
            _index++;
        }
        if (_index >= _farmTargets.size()) {
            int invalid = firstUnplantedFarmTarget(mod);
            if (invalid < 0) return null;
            _index = invalid;
            DebugLogger.getInstance().logImmediate("BASE-FARM",
                    "plant-pass-restart firstInvalid=" + _farmTargets.get(_index).toShortString()
                            + " index=" + (_index + 1) + "/" + _farmTargets.size());
        }
        BlockPos soil = _farmTargets.get(_index);
        BlockPos crop = soil.up();
        if (!mod.getWorld().getBlockState(crop).isAir()
                && mod.getWorld().getBlockState(crop).getBlock() != Blocks.WHEAT) {
            return cache(mod, new DestroyBlockTask(crop));
        }
        if (mod.getWorld().getBlockState(soil).getBlock() != Blocks.FARMLAND) {
            DebugLogger.getInstance().logImmediate("BASE-FARM",
                    "plant-detected-untillled-soil soil=" + soil.toShortString()
                            + " current=" + mod.getWorld().getBlockState(soil).getBlock().getName().getString()
                            + "; returning to till pass");
            next(Phase.FARM_TILL);
            return null;
        }
        Task approach = getNearFarmSoil(mod, soil);
        if (approach != null) return approach;
        setDebugState("Planting farmland " + (_index + 1) + "/" + _farmTargets.size());
        directFarmUse(mod, soil, new ItemTarget(Items.WHEAT_SEEDS, 1), "plant");
        return null;
    }

    private int firstUntilledFarmTarget(Belfegor mod) {
        for (int i = 0; i < _farmTargets.size(); i++) {
            if (mod.getWorld().getBlockState(_farmTargets.get(i)).getBlock() != Blocks.FARMLAND) {
                return i;
            }
        }
        return -1;
    }

    private int firstUnplantedFarmTarget(Belfegor mod) {
        for (int i = 0; i < _farmTargets.size(); i++) {
            BlockPos soil = _farmTargets.get(i);
            if (mod.getWorld().getBlockState(soil).getBlock() != Blocks.FARMLAND
                    || mod.getWorld().getBlockState(soil.up()).getBlock() != Blocks.WHEAT) {
                return i;
            }
        }
        return -1;
    }

    private boolean allFarmTargetsTilled(Belfegor mod) {
        for (BlockPos soil : _farmTargets) {
            if (mod.getWorld().getBlockState(soil).getBlock() != Blocks.FARMLAND) return false;
        }
        return true;
    }

    private boolean allFarmTargetsPlanted(Belfegor mod) {
        for (BlockPos soil : _farmTargets) {
            if (mod.getWorld().getBlockState(soil).getBlock() != Blocks.FARMLAND
                    || mod.getWorld().getBlockState(soil.up()).getBlock() != Blocks.WHEAT) {
                return false;
            }
        }
        return true;
    }

    private void directFarmUse(Belfegor mod, BlockPos soil, ItemTarget item, String actionName) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return;
        if (MinecraftClient.getInstance().currentScreen != null) {
            StorageHelper.closeScreen();
            return;
        }
        if (_farmInteractCooldown-- > 0) return;
        if (mod.getPlayer().getEyePos().squaredDistanceTo(Vec3d.ofCenter(soil)) > 20.25) return;
        if (!mod.getSlotHandler().forceEquipItem(item, false)) return;
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        Vec3d hit = Vec3d.ofCenter(soil).add(0, 0.5, 0);
        BlockHitResult result = new BlockHitResult(hit, Direction.UP, soil, false);
        ActionResult action = mod.getController().interactBlock(mod.getPlayer(), Hand.MAIN_HAND, result);
        mod.getPlayer().swingHand(Hand.MAIN_HAND);
        _farmInteractCooldown = 3;
        DebugLogger.getInstance().logImmediate("BASE-FARM",
                "direct-" + actionName
                        + " soil=" + soil.toShortString()
                        + " player=" + mod.getPlayer().getBlockPos().toShortString()
                        + " result=" + action);
    }

    private Task getNearFarmSoil(Belfegor mod, BlockPos soil) {
        if (mod.getPlayer() == null) return null;
        BlockPos stand = farmStandPosition(mod, soil);
        if (stand == null) {
            logRejectedFarmStands(mod, soil);
            return null;
        }
        if (stand != null && stand.getSquaredDistance(mod.getPlayer().getBlockPos()) > 4) {
            setDebugState("Moving within reach of farm tile " + (_index + 1) + "/" + _farmTargets.size());
            return cache(mod, new GetToBlockTask(stand));
        }
        return null;
    }

    private BlockPos farmStandPosition(Belfegor mod, BlockPos soil) {
        Direction[] options = {
                Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST,
                _direction, _direction.getOpposite()
        };
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        BlockPos player = mod.getPlayer() == null ? soil : mod.getPlayer().getBlockPos();
        for (Direction option : options) {
            BlockPos stand = soil.offset(option).up();
            if (!isSafeStandPosition(mod, stand)) continue;
            double distance = stand.getSquaredDistance(player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = stand;
            }
        }
        // A reverted dirt/grass tile itself is a safe last-resort approach.
        // Standing above it keeps the interaction within reach even when all
        // adjacent cells are water, crops, or temporary obstructions.
        BlockPos aboveSoil = soil.up();
        if (best == null && isSafeStandPosition(mod, aboveSoil)) {
            best = aboveSoil;
        }
        return best;
    }

    private boolean isSafeStandPosition(Belfegor mod, BlockPos stand) {
        Block below = mod.getWorld().getBlockState(stand.down()).getBlock();
        return below != Blocks.WATER
                && below != Blocks.LAVA
                && supportsFarmStanding(mod, stand.down())
                && isFarmWalkThrough(mod, stand)
                && isFarmWalkThrough(mod, stand.up());
    }

    private boolean supportsFarmStanding(Belfegor mod, BlockPos pos) {
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        // Farmland's lowered top shape is walkable, but it is deliberately not
        // a full solid cube. Treat the farm surface family as valid support.
        return block == Blocks.FARMLAND
                || block == Blocks.DIRT
                || block == Blocks.GRASS_BLOCK
                || block == Blocks.COARSE_DIRT
                || block == Blocks.ROOTED_DIRT
                || WorldHelper.isSolid(mod, pos);
    }

    /**
     * Mature or newly planted wheat occupies the feet block but is still a
     * valid Baritone standing/pathing cell. Requiring literal air made repair
     * of one reverted soil tile stall forever when every adjacent tile already
     * contained wheat: no approach task was produced, while the direct use was
     * rejected as out of reach.
     */
    private boolean isFarmWalkThrough(Belfegor mod, BlockPos pos) {
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        return mod.getWorld().getBlockState(pos).isAir() || block instanceof CropBlock;
    }

    private void logRejectedFarmStands(Belfegor mod, BlockPos soil) {
        if (_lastNoFarmStandIndex == _index || mod.getWorld() == null) return;
        _lastNoFarmStandIndex = _index;
        StringBuilder details = new StringBuilder();
        for (Direction option : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos stand = soil.offset(option).up();
            if (!details.isEmpty()) details.append(" | ");
            details.append(option.asString())
                    .append(" stand=").append(stand.toShortString())
                    .append(" below=").append(mod.getWorld().getBlockState(stand.down()).getBlock())
                    .append(" feet=").append(mod.getWorld().getBlockState(stand).getBlock())
                    .append(" head=").append(mod.getWorld().getBlockState(stand.up()).getBlock());
        }
        DebugLogger.getInstance().logImmediate("BASE-FARM",
                "no-approach soil=" + soil.toShortString()
                        + " player=" + mod.getPlayer().getBlockPos().toShortString()
                        + " index=" + (_index + 1) + "/" + _farmTargets.size()
                        + " candidates=" + details);
    }

    private List<BlockPos> buildClearTargets() {
        ArrayList<BlockPos> result = new ArrayList<>();
        addBoxAir(result, _roomAnchor.add(-1, 0, -1), _roomSize + 2, _roomSize + 2, WALL_HEIGHT + 2);
        addHallAir(result);
        return result;
    }

    private List<BlockRegion> buildClearRegions() {
        ArrayList<BlockRegion> result = new ArrayList<>();
        result.add(new BlockRegion(_roomAnchor.add(-1, 0, -1),
                _roomAnchor.add(_roomSize, WALL_HEIGHT + 1, _roomSize)));
        List<BlockPos> hall = hallFloorPositions();
        if (!hall.isEmpty()) {
            result.add(BlockRegion.fromPositions(hall.stream()
                    .flatMap(floor -> List.of(floor.add(0, 1, 0),
                            floor.add(0, WALL_HEIGHT + 1, 0),
                            floor.offset(_direction.rotateYCounterclockwise()).add(0, 1, 0),
                            floor.offset(_direction.rotateYClockwise()).add(0, WALL_HEIGHT + 1, 0)).stream())
                    .toList()));
        }
        return result;
    }

    private List<BlockPos> buildFloorTargets() {
        ArrayList<BlockPos> result = new ArrayList<>();
        for (int dx = 0; dx < _roomSize; dx++) {
            for (int dz = 0; dz < _roomSize; dz++) {
                BlockPos floor = _roomAnchor.add(dx, -1, dz);
                result.add(floor);
                if (_type == RoomType.FARMLAND) {
                    result.add(floor.down());
                }
            }
        }
        addHallFloor(result);
        return result;
    }

    private List<BlockPos> nonFarmFloorPatchTargets(Belfegor mod) {
        ArrayList<BlockPos> result = new ArrayList<>();
        for (BlockPos floor : _floorTargets) {
            if (!isAcceptableNonFarmFloor(mod, floor)) {
                result.add(floor);
            }
        }
        logFloorPatchCount("non-farm", result.size());
        return result;
    }

    private List<BlockPos> farmFloorPatchTargets(Belfegor mod) {
        ArrayList<BlockPos> result = new ArrayList<>();
        int surfaceY = _roomAnchor.getY() - 1;
        for (BlockPos floor : _floorTargets) {
            if (floor.getY() == surfaceY) {
                if (!isAcceptableFarmSurface(mod, floor)) {
                    result.add(floor);
                }
            } else if (!isAcceptableFarmSurface(mod, floor.up()) && !isAcceptableFarmSupport(mod, floor)) {
                result.add(floor);
            }
        }
        logFloorPatchCount("farm", result.size());
        return result;
    }

    private void logFloorPatchCount(String kind, int count) {
        if (count > 0 && count != _lastLoggedFloorPatchCount) {
            _lastLoggedFloorPatchCount = count;
            DebugLogger.getInstance().log("BASE-BUILD",
                    kind + "-floor-patches room=" + _roomName
                            + " patches=" + count
                            + "/" + _floorTargets.size());
        }
    }

    private boolean isAcceptableNonFarmFloor(Belfegor mod, BlockPos pos) {
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        if (block == Blocks.AIR || block == Blocks.WATER || block == Blocks.LAVA) return false;
        if (block == Blocks.GRASS_BLOCK
                || block == Blocks.DIRT
                || block == Blocks.COARSE_DIRT
                || block == Blocks.PODZOL
                || block == Blocks.ROOTED_DIRT
                || block == Blocks.FARMLAND
                || block == Blocks.COBBLESTONE) {
            return true;
        }
        return WorldHelper.isSolid(mod, pos);
    }

    private boolean isAcceptableFarmSurface(Belfegor mod, BlockPos pos) {
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        return block == Blocks.DIRT
                || block == Blocks.GRASS_BLOCK
                || block == Blocks.FARMLAND
                || block == Blocks.WATER;
    }

    private boolean isAcceptableFarmSupport(Belfegor mod, BlockPos pos) {
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        return block != Blocks.AIR
                && block != Blocks.WATER
                && block != Blocks.LAVA
                && WorldHelper.isSolid(mod, pos);
    }

    private List<BlockPos> buildWallTargets() {
        ArrayList<BlockPos> result = new ArrayList<>();
        for (int dx = 0; dx < _roomSize; dx++) {
            for (int dz = 0; dz < _roomSize; dz++) {
                boolean perimeter = dx == 0 || dz == 0 || dx == _roomSize - 1 || dz == _roomSize - 1;
                if (!perimeter || isDoorway(dx, dz)) continue;
                for (int h = 0; h < WALL_HEIGHT; h++) {
                    result.add(_roomAnchor.add(dx, h, dz));
                }
            }
        }
        addHallWalls(result);
        return result;
    }

    private List<BlockPos> buildRoofTargets() {
        ArrayList<BlockPos> result = new ArrayList<>();
        for (int dx = 0; dx < _roomSize; dx++) {
            for (int dz = 0; dz < _roomSize; dz++) {
                result.add(_roomAnchor.add(dx, WALL_HEIGHT, dz));
            }
        }
        return result;
    }

    private List<BlockPos> buildWaterTargets() {
        if (_type != RoomType.FARMLAND) return List.of();
        BlockPos center = _roomCenter.add(0, -1, 0);
        return List.of(
                center,
                center.add(1, 0, 0),
                center.add(0, 0, 1),
                center.add(1, 0, 1)
        );
    }

    private List<BlockPos> buildFarmTargets() {
        ArrayList<BlockPos> result = new ArrayList<>();
        for (int dx = 1; dx < _roomSize - 1; dx++) {
            for (int dz = 1; dz < _roomSize - 1; dz++) {
                BlockPos soil = _roomAnchor.add(dx, -1, dz);
                if (!_waterTargets.contains(soil) && isHydratedByPlannedWater(soil)) result.add(soil);
            }
        }
        result.sort(Comparator.comparingInt(this::distanceToRoomCenter));
        return result;
    }

    private boolean isHydratedByPlannedWater(BlockPos soil) {
        if (_waterTargets == null || _waterTargets.isEmpty()) return true;
        for (BlockPos water : _waterTargets) {
            if (Math.abs(water.getX() - soil.getX()) <= 4
                    && Math.abs(water.getZ() - soil.getZ()) <= 4
                    && Math.abs(water.getY() - soil.getY()) <= 1) {
                return true;
            }
        }
        return false;
    }

    private void addBoxAir(List<BlockPos> result, BlockPos anchor, int width, int depth, int height) {
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                for (int h = 0; h < height; h++) {
                    result.add(anchor.add(dx, h, dz));
                }
            }
        }
    }

    private void addHallAir(List<BlockPos> result) {
        for (BlockPos floor : hallFloorPositions()) {
            for (int h = 0; h <= WALL_HEIGHT; h++) {
                result.add(floor.add(0, h + 1, 0));
            }
        }
    }

    private void addHallFloor(List<BlockPos> result) {
        result.addAll(hallFloorPositions());
    }

    private void addHallWalls(List<BlockPos> result) {
        Direction leftDirection = _direction.rotateYCounterclockwise();
        Direction rightDirection = _direction.rotateYClockwise();
        // The parent and room shells already provide the wall around each
        // doorway. Only wall the actual 3-5 block gap so connector columns do
        // not get placed inside either room.
        for (BlockPos centerFloor : hallGapCenterFloorPositions()) {
            BlockPos left = centerFloor.offset(leftDirection);
            BlockPos right = centerFloor.offset(rightDirection, HALL_WIDTH);
            for (int h = 0; h < 3; h++) {
                result.add(left.add(0, h + 1, 0));
                result.add(right.add(0, h + 1, 0));
            }
        }
    }

    private List<BlockPos> hallFloorPositions() {
        ArrayList<BlockPos> result = new ArrayList<>();
        for (BlockPos center : hallCenterFloorPositions()) {
            result.add(center);
            result.add(center.offset(_direction.rotateYClockwise()));
        }
        return result;
    }

    private List<BlockPos> hallCenterFloorPositions() {
        ArrayList<BlockPos> result = new ArrayList<>();
        if (_hallStart == null || _direction == null) return result;
        // Include the parent doorway, the gap, the room doorway, and one
        // interior cell. Clear/floor work therefore creates a genuinely
        // level, hollow route instead of leaving a solid wall at either end.
        for (int i = 0; i < _hallLength + 3; i++) {
            BlockPos center = _hallStart.offset(_direction, i);
            result.add(center);
        }
        return result;
    }

    private List<BlockPos> hallGapCenterFloorPositions() {
        ArrayList<BlockPos> result = new ArrayList<>();
        if (_hallStart == null || _direction == null) return result;
        for (int i = 1; i <= _hallLength; i++) {
            result.add(_hallStart.offset(_direction, i));
        }
        return result;
    }

    private boolean isDoorway(int dx, int dz) {
        int mid = _roomSize / 2;
        return switch (_direction) {
            case NORTH -> dz == _roomSize - 1 && (dx == mid || dx == mid + 1);
            case SOUTH -> dz == 0 && (dx == mid || dx == mid + 1);
            case WEST -> dx == _roomSize - 1 && (dz == mid || dz == mid + 1);
            default -> dx == 0 && (dz == mid || dz == mid + 1);
        };
    }

    private record AttachmentSite(String name, int minX, int maxX, int minZ, int maxZ,
                                  int depth, Direction outward) {
        int centerX() { return (minX + maxX) / 2; }
        int centerZ() { return (minZ + maxZ) / 2; }
    }

    private void loadRememberedRepairPlacement() {
        _roomAnchor = _rememberedRepairAnchor;
        _roomCenter = _rememberedRepairCenter != null
                ? _rememberedRepairCenter
                : _roomAnchor.add(_roomSize / 2, 0, _roomSize / 2);
        _parentName = _rememberedRepairParent == null || _rememberedRepairParent.isBlank()
                ? "core"
                : normalize(_rememberedRepairParent);
        _direction = parseHorizontalDirection(_rememberedRepairDirection);
        if (_direction == null) {
            _direction = inferDirectionFromBase(_roomCenter);
        }
        _hallLength = Math.max(1, _rememberedRepairHallLength);
        AttachmentSite parent = attachmentSiteFor(_parentName).orElseGet(this::coreAttachmentSite);
        _hallStart = hallStartFor(parent, _direction);
        _placementBlocked = _roomAnchor == null || _roomCenter == null;
        DebugLogger.getInstance().log("BASE-BUILD",
                "repair-in-place room=" + _roomName
                        + " type=" + _type
                        + " anchor=" + (_roomAnchor == null ? "none" : _roomAnchor.toShortString())
                        + " center=" + (_roomCenter == null ? "none" : _roomCenter.toShortString())
                        + " parent=" + _parentName
                        + " direction=" + _direction.asString()
                        + " hallGap=" + _hallLength
                        + " hallStart=" + _hallStart.toShortString());
    }

    private Optional<AttachmentSite> attachmentSiteFor(String name) {
        if (normalize(name).equals("core")) return Optional.of(coreAttachmentSite());
        if (_base == null) return Optional.empty();
        return _base.modules.stream()
                .filter(module -> module != null && normalize(module.name).equals(normalize(name)))
                .findFirst()
                .map(module -> new AttachmentSite(module.name,
                        module.x, module.x + Math.max(1, module.width) - 1,
                        module.z, module.z + Math.max(1, module.depth) - 1,
                        attachmentDepth(module), parseHorizontalDirection(module.direction)));
    }

    private AttachmentSite coreAttachmentSite() {
        int radius = Math.max(8, _base == null ? 8 : _base.radius);
        return new AttachmentSite("core",
                _baseCenter.getX() - radius, _baseCenter.getX() + radius,
                _baseCenter.getZ() - radius, _baseCenter.getZ() + radius,
                0, null);
    }

    private Direction inferDirectionFromBase(BlockPos roomCenter) {
        int dx = roomCenter.getX() - _baseCenter.getX();
        int dz = roomCenter.getZ() - _baseCenter.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) return dx < 0 ? Direction.WEST : Direction.EAST;
        return dz < 0 ? Direction.NORTH : Direction.SOUTH;
    }

    private void choosePlacement(Belfegor mod) {
        Direction[] order = {Direction.EAST, Direction.NORTH, Direction.SOUTH, Direction.WEST};
        int bestScore = Integer.MAX_VALUE;
        Direction bestDirection = Direction.EAST;
        int bestHallLength = 3;
        AttachmentSite bestParent = null;
        BlockPos bestHallStart = null;
        BlockPos bestCenter = null;
        BlockPos bestAnchor = null;
        for (AttachmentSite parent : attachmentSites()) {
            for (Direction direction : order) {
                int sideUse = parent.name.equals("core") ? coreSideUse(direction) : 0;
                int orientationPenalty = attachmentOrientationPenalty(parent, direction);
                for (int hall = 3; hall <= 5; hall++) {
                    BlockPos hallStart = hallStartFor(parent, direction);
                    BlockPos center = roomCenterFor(parent, direction, hall);
                    BlockPos anchor = center.add(-_roomSize / 2, 0, -_roomSize / 2);
                    if (placementFootprintOverlaps(anchor, _roomSize, _roomSize, 1)) continue;
                    if (hallConflicts(parent, hallStart, direction, hall)) continue;
                    int terrainPenalty = terrainPenalty(mod, anchor, hallStart, direction, hall);
                    int score = parent.depth * 10_000
                            + sideUse * 5_000
                            + orientationPenalty
                            + terrainPenalty
                            + hall * 10;
                    if (score < bestScore) {
                        bestScore = score;
                        bestDirection = direction;
                        bestHallLength = hall;
                        bestParent = parent;
                        bestHallStart = hallStart;
                        bestCenter = center;
                        bestAnchor = anchor;
                    }
                }
            }
        }
        if (bestCenter == null) {
            _placementBlocked = true;
            bestParent = attachmentSites().get(0);
            bestHallStart = hallStartFor(bestParent, bestDirection);
            bestCenter = roomCenterFor(bestParent, bestDirection, bestHallLength);
            bestAnchor = bestCenter.add(-_roomSize / 2, 0, -_roomSize / 2);
            DebugLogger.getInstance().log("BASE-BUILD",
                    "placement-blocked room=" + _roomName
                            + " type=" + _type
                            + " reason=no-connected-non-overlapping-footprint");
        } else {
            _placementBlocked = false;
        }
        _parentName = bestParent == null ? "core" : bestParent.name;
        _direction = bestDirection;
        _hallLength = bestHallLength;
        _hallStart = bestHallStart;
        _roomCenter = bestCenter;
        _roomAnchor = bestAnchor;
        DebugLogger.getInstance().log("BASE-BUILD",
                "placement room=" + _roomName
                        + " type=" + _type
                        + " parent=" + _parentName
                        + " direction=" + _direction.asString()
                        + " hallGap=" + _hallLength
                        + " hallStart=" + (_hallStart == null ? "none" : _hallStart.toShortString())
                        + " center=" + _roomCenter.toShortString()
                        + " score=" + bestScore);
    }

    private List<AttachmentSite> attachmentSites() {
        ArrayList<AttachmentSite> result = new ArrayList<>();
        result.add(coreAttachmentSite());
        if (_base == null) return result;
        for (BaseMemory.BaseModule module : _base.modules) {
            if (module == null || !BaseMemory.getInstance().moduleComplete(module)) continue;
            if (normalize(module.name).equals(normalize(_roomName))
                    || normalize(module.name).equals(normalize(_roomName + "_hall"))) continue;
            if (!isExpansionRoomType(module.type)) continue;
            result.add(new AttachmentSite(module.name,
                    module.x, module.x + Math.max(1, module.width) - 1,
                    module.z, module.z + Math.max(1, module.depth) - 1,
                    attachmentDepth(module), parseHorizontalDirection(module.direction)));
        }
        return result;
    }

    private boolean isExpansionRoomType(String type) {
        String normalized = normalize(type);
        return normalized.equals("storage")
                || normalized.equals("workshop")
                || normalized.equals("armory")
                || normalized.equals("farmland")
                || normalized.equals("mobfarm")
                || normalized.equals("mob_farm")
                || normalized.equals("empty");
    }

    private int expansionPriority(String type) {
        return switch (normalize(type)) {
            case "storage" -> 1;
            case "workshop" -> 2;
            case "armory" -> 3;
            case "farmland" -> 4;
            case "mobfarm", "mob_farm" -> 5;
            case "empty" -> 6;
            default -> 0;
        };
    }

    private boolean isSupersededIncompleteModule(BaseMemory.BaseModule module) {
        if (module == null) return false;
        BaseMemory.BaseModule semantic = module;
        String type = normalize(module.type);
        if ((type.equals("hall") || type.equals("access"))
                && module.parent != null && !module.parent.isBlank()) {
            String parentName = normalize(module.parent);
            semantic = _base.modules.stream()
                    .filter(candidate -> normalize(candidate.name).equals(parentName))
                    .findFirst()
                    .orElse(module);
        }
        int otherPriority = expansionPriority(semantic.type);
        int currentPriority = expansionPriority(_type.name());
        return otherPriority > currentPriority
                && !BaseMemory.getInstance().moduleComplete(semantic);
    }

    private boolean placementFootprintOverlaps(BlockPos anchor, int width, int depth, int margin) {
        if (_base == null || anchor == null) return false;
        int m = Math.max(0, margin);
        int ax1 = anchor.getX() - m;
        int az1 = anchor.getZ() - m;
        int ax2 = anchor.getX() + Math.max(1, width) - 1 + m;
        int az2 = anchor.getZ() + Math.max(1, depth) - 1 + m;
        for (BaseMemory.BaseModule module : _base.modules) {
            if (module == null) continue;
            String moduleName = normalize(module.name);
            String moduleType = normalize(module.type);
            if (moduleName.equals(normalize(_roomName))
                    || moduleName.equals(normalize(_roomName + "_hall"))) continue;
            if (moduleType.equals("hall") || moduleType.equals("access")) continue;
            if (isSupersededIncompleteModule(module)) continue;
            int bx1 = module.x - m;
            int bz1 = module.z - m;
            int bx2 = module.x + Math.max(1, module.width) - 1 + m;
            int bz2 = module.z + Math.max(1, module.depth) - 1 + m;
            if (ax1 <= bx2 && ax2 >= bx1 && az1 <= bz2 && az2 >= bz1) return true;
        }
        return false;
    }

    private int attachmentDepth(BaseMemory.BaseModule module) {
        int depth = 1;
        String parent = normalize(module.parent);
        for (int guard = 0; guard < 8 && !parent.isBlank() && !parent.equals("core"); guard++) {
            String current = parent;
            Optional<BaseMemory.BaseModule> parentModule = _base.modules.stream()
                    .filter(candidate -> normalize(candidate.name).equals(current))
                    .findFirst();
            if (parentModule.isEmpty()) break;
            depth++;
            parent = normalize(parentModule.get().parent);
        }
        return depth;
    }

    private Direction parseHorizontalDirection(String value) {
        for (Direction direction : new Direction[]{Direction.EAST, Direction.NORTH, Direction.SOUTH, Direction.WEST}) {
            if (direction.asString().equalsIgnoreCase(value)) return direction;
        }
        return null;
    }

    private int coreSideUse(Direction direction) {
        int count = 0;
        for (BaseMemory.BaseModule module : _base.modules) {
            if (module == null || !BaseMemory.getInstance().moduleComplete(module)) continue;
            if (normalize(module.name).equals(normalize(_roomName))) continue;
            if (!normalize(module.parent).equals("core")) continue;
            if (!isExpansionRoomType(module.type)) continue;
            if (direction.asString().equalsIgnoreCase(module.direction)) count++;
        }
        return count;
    }

    private int attachmentOrientationPenalty(AttachmentSite parent, Direction direction) {
        if (parent.outward == null) return 0;
        if (parent.outward == direction) return 0;
        if (parent.outward.getOpposite() == direction) return 4_000;
        return 1_000;
    }

    private BlockPos hallStartFor(AttachmentSite parent, Direction direction) {
        int y = _baseCenter.getY() - 1;
        return switch (direction) {
            case NORTH -> new BlockPos(parent.centerX(), y, parent.minZ);
            case SOUTH -> new BlockPos(parent.centerX() + 1, y, parent.maxZ);
            case WEST -> new BlockPos(parent.minX, y, parent.centerZ() + 1);
            default -> new BlockPos(parent.maxX, y, parent.centerZ());
        };
    }

    private BlockPos roomCenterFor(AttachmentSite parent, Direction direction, int hallLength) {
        int half = _roomSize / 2;
        int y = _baseCenter.getY();
        return switch (direction) {
            case NORTH -> new BlockPos(parent.centerX(), y, parent.minZ - hallLength - 1 - half);
            case SOUTH -> new BlockPos(parent.centerX(), y, parent.maxZ + hallLength + 1 + half);
            case WEST -> new BlockPos(parent.minX - hallLength - 1 - half, y, parent.centerZ());
            default -> new BlockPos(parent.maxX + hallLength + 1 + half, y, parent.centerZ());
        };
    }

    private int terrainPenalty(Belfegor mod, BlockPos roomAnchor, BlockPos hallStart,
                               Direction direction, int hallLength) {
        if (mod == null || mod.getWorld() == null) return 0;
        int unsupported = 0;
        for (int dx = 0; dx < _roomSize; dx++) {
            for (int dz = 0; dz < _roomSize; dz++) {
                if (!isSupportedFloorCell(mod, roomAnchor.add(dx, -1, dz))) {
                    unsupported++;
                }
            }
        }
        for (BlockPos floor : prospectiveHallFloorPositions(hallStart, direction, hallLength)) {
            if (!isSupportedFloorCell(mod, floor)) unsupported++;
        }
        // Terrain affects which equally connected site wins, but can no longer
        // force an arbitrary far-away step. The floor phase can safely bridge
        // modest air/water cells with the staged cobblestone/dirt supply.
        return unsupported * 10;
    }

    private boolean isSupportedFloorCell(Belfegor mod, BlockPos floor) {
        Block block = mod.getWorld().getBlockState(floor).getBlock();
        return block != Blocks.AIR
                && block != Blocks.WATER
                && block != Blocks.LAVA
                && WorldHelper.isSolid(mod, floor);
    }

    private List<BlockPos> prospectiveHallFloorPositions(BlockPos start, Direction direction, int hallLength) {
        ArrayList<BlockPos> result = new ArrayList<>();
        for (int i = 0; i < hallLength + 3; i++) {
            result.add(start.offset(direction, i));
            result.add(start.offset(direction, i).offset(direction.rotateYClockwise()));
        }
        return result;
    }

    private boolean hallConflicts(AttachmentSite parent, BlockPos start,
                                  Direction direction, int hallLength) {
        for (BlockPos floor : prospectiveHallFloorPositions(start, direction, hallLength)) {
            for (BaseMemory.BaseModule module : _base.modules) {
                if (module == null) continue;
                String moduleName = normalize(module.name);
                String moduleType = normalize(module.type);
                if (moduleName.equals(normalize(_roomName))
                        || moduleName.equals(normalize(_roomName + "_hall"))) continue;
                if (moduleType.equals("hall") || moduleType.equals("access") || moduleType.equals("fixture")) continue;
                if (isSupersededIncompleteModule(module)) continue;
                if (moduleName.equals(normalize(parent.name))) continue;
                if (parent.name.equals("core")
                        && (moduleName.equals("core") || moduleName.equals("perimeter_wall"))) continue;
                if (!isConstructedEnoughToProtect(module)) continue;
                if (floor.getX() >= module.x && floor.getX() < module.x + Math.max(1, module.width)
                        && floor.getZ() >= module.z && floor.getZ() < module.z + Math.max(1, module.depth)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String uniqueRoomName(BaseMemory.BaseRecord base, String requested, RoomType type) {
        String baseName = requested == null || requested.isBlank() ? defaultName(type) : normalize(requested);
        boolean exists = base.modules.stream().anyMatch(module -> normalize(module.name).equals(baseName));
        if (!exists) return baseName;
        Optional<BaseMemory.BaseModule> existing = base.modules.stream()
                .filter(module -> normalize(module.name).equals(baseName))
                .findFirst();
        if (existing.isPresent()
                && !BaseMemory.getInstance().moduleComplete(existing.get())
                && normalize(existing.get().type).equals(type.name().toLowerCase(Locale.ROOT))) {
            return baseName;
        }
        int index = 2;
        while (true) {
            String candidate = baseName + "_" + index;
            int finalIndex = index;
            if (base.modules.stream().noneMatch(module -> normalize(module.name).equals(baseName + "_" + finalIndex))) {
                return candidate;
            }
            index++;
        }
    }

    private void remember(String status) {
        BaseMemory memory = BaseMemory.getInstance();
        memory.rememberModule(_baseCenter, _dimension, _roomName, _type.name().toLowerCase(Locale.ROOT),
                _roomAnchor, _roomSize, _roomSize, _type == RoomType.MOBFARM ? WALL_HEIGHT + 1 : WALL_HEIGHT,
                status, "expanded room; hall=" + HALL_WIDTH + "x" + _hallLength
                        + ";parent=" + _parentName
                        + (_repairInPlace ? ";repairMode=inPlace" : ""),
                _parentName, _direction.asString(), _hallLength, HALL_WIDTH);
        List<BlockPos> hall = hallFloorPositions();
        int minX = hall.stream().mapToInt(BlockPos::getX).min().orElse(_hallStart.getX());
        int maxX = hall.stream().mapToInt(BlockPos::getX).max().orElse(_hallStart.getX());
        int minZ = hall.stream().mapToInt(BlockPos::getZ).min().orElse(_hallStart.getZ());
        int maxZ = hall.stream().mapToInt(BlockPos::getZ).max().orElse(_hallStart.getZ());
        memory.rememberModule(_baseCenter, _dimension, _roomName + "_hall", "hall",
                new BlockPos(minX, _hallStart.getY(), minZ),
                maxX - minX + 1, maxZ - minZ + 1, WALL_HEIGHT,
                status, "two-wide connector hall to " + _roomName,
                _parentName, _direction.asString(), _hallLength, HALL_WIDTH);
        memory.rememberInspection(_baseCenter, _dimension, _roomName, "construction",
                _clearTargets.size() + _floorTargets.size() + _wallTargets.size() + _roofTargets.size(),
                0, 0, _index, status, "type=" + _type + ";center=" + _roomCenter.toShortString());
        LocationMemory.getInstance().remember("home_room_" + _roomName,
                _roomCenter.getX(), _roomCenter.getY(), _roomCenter.getZ(),
                _dimension, "type=" + _type + ";direction=" + _direction.asString()
                        + ";hallLength=" + _hallLength + ";parent=" + _parentName);
        LocationMemory.getInstance().remember("home_room_" + _type.name().toLowerCase(Locale.ROOT),
                _roomCenter.getX(), _roomCenter.getY(), _roomCenter.getZ(),
                _dimension, "latest " + _type + " room;name=" + _roomName);
        LocationMemory.getInstance().save();
        memory.save();
    }

    private Task cache(Belfegor mod, Task task) {
        if (_activeTask != null && !_activeTask.stopped() && !_activeTask.isFinished(mod)) {
            return _activeTask;
        }
        _activeTask = task;
        return _activeTask;
    }

    private int distanceToRoomCenter(BlockPos pos) {
        return Math.abs(pos.getX() - _roomCenter.getX())
                + Math.abs(pos.getY() - _roomCenter.getY())
                + Math.abs(pos.getZ() - _roomCenter.getZ());
    }

    private void next(Phase next) {
        _phase = next;
        _index = 0;
        _activeTask = null;
        _lastLoggedFloorPatchCount = Integer.MIN_VALUE;
        persistPhase();
    }

    private void persistPhase() {
        if (_baseCenter == null || _dimension == null || _roomName == null || _roomName.isBlank()) return;
        String key = "expansion_" + _roomName;
        if (_phase == Phase.DONE) {
            BaseMemory.getInstance().clearBuildPhase(_baseCenter, _dimension, key);
        } else {
            BaseMemory.getInstance().rememberBuildPhase(_baseCenter, _dimension, key, _phase.name());
        }
        BaseMemory.getInstance().save();
    }

    private boolean targetDone(Belfegor mod, BlockPos target, boolean useThrowaways, Block[] desired) {
        if (useThrowaways) return WorldHelper.isSolid(mod, target);
        Block block = mod.getWorld().getBlockState(target).getBlock();
        if (_floorTargets.contains(target)) {
            if (_type == RoomType.FARMLAND) {
                return block == Blocks.DIRT
                        || block == Blocks.GRASS_BLOCK
                        || block == Blocks.FARMLAND
                        || block == Blocks.WATER;
            }
            return isAcceptableNonFarmFloor(mod, target);
        }
        for (Block allowed : desired) {
            if (block == allowed) return true;
        }
        return false;
    }

    private Item[] blockItems(Block[] blocks) {
        Item[] items = new Item[blocks.length];
        for (int i = 0; i < blocks.length; i++) {
            items[i] = blocks[i].asItem();
        }
        return items;
    }

    private Task materialTaskFor(Block[] desired, int count) {
        if (desired.length != 1) return null;
        if (desired[0] == Blocks.DIRT) {
            return TaskCatalogue.getItemTask("dirt", count);
        }
        if (desired[0] == Blocks.COBBLESTONE) {
            return TaskCatalogue.getItemTask("cobblestone", count);
        }
        return TaskCatalogue.getItemTask(desired[0].asItem(), count);
    }

    public static RoomType parseType(String value) {
        String normalized = normalize(value);
        return switch (normalized) {
            case "farm", "farmland", "crop", "crops" -> RoomType.FARMLAND;
            case "storage", "shulker", "warehouse" -> RoomType.STORAGE;
            case "workshop", "crafting", "craft" -> RoomType.WORKSHOP;
            case "armory", "armoury", "gear", "equipment" -> RoomType.ARMORY;
            case "mob", "mobfarm", "mob_farm", "spawner" -> RoomType.MOBFARM;
            default -> RoomType.EMPTY;
        };
    }

    private static String defaultName(RoomType type) {
        return switch (type) {
            case FARMLAND -> "farmland";
            case STORAGE -> "storage";
            case WORKSHOP -> "workshop";
            case ARMORY -> "armory";
            case MOBFARM -> "mob_farm";
            case EMPTY -> "room";
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static class BlockRegion {
        private final BlockPos min;
        private final BlockPos max;

        BlockRegion(BlockPos a, BlockPos b) {
            min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
            max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        }

        BlockPos min() {
            return min;
        }

        BlockPos max() {
            return max;
        }

        boolean isClear(Belfegor mod) {
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        if (!mod.getWorld().getBlockState(new BlockPos(x, y, z)).isAir()) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        static BlockRegion fromPositions(List<BlockPos> positions) {
            BlockPos first = positions.isEmpty() ? BlockPos.ORIGIN : positions.get(0);
            BlockRegion region = new BlockRegion(first, first);
            for (BlockPos pos : positions) {
                region = new BlockRegion(region.min, pos);
                region = new BlockRegion(region.min, new BlockPos(
                        Math.max(region.max.getX(), pos.getX()),
                        Math.max(region.max.getY(), pos.getY()),
                        Math.max(region.max.getZ(), pos.getZ())));
            }
            return region;
        }
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BuildBaseExpansionTask task
                && task._type == _type
                && task._requestedName.equals(_requestedName)
                && task._repairInPlace == _repairInPlace;
    }

    @Override
    protected String toDebugString() {
        return "Build base expansion " + _type + " " + _requestedName + " phase=" + _phase;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return _phase == Phase.DONE;
    }
}
