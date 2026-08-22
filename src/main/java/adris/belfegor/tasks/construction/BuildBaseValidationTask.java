package adris.belfegor.tasks.construction;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.debug.DebugLogger;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.memory.LocationMemory;
import adris.belfegor.schematic.BelfegorSchematic;
import adris.belfegor.tasks.movement.GetToBlockTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.helpers.DoorHelper;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Validates the locked home and repairs one remembered base module at a time.
 *
 * Validation deliberately uses the per-home blueprint exported by
 * {@link BuildCampsiteTask}. A global Litematica file may describe a completely
 * different structure and origin, so treating it as every camp's authoritative
 * blueprint caused large adjacent repair regions and expensive mismatch scans.
 */
public class BuildBaseValidationTask extends Task {

    private enum Phase {
        ORIENT_HOME,
        CHECK_CORE,
        REPAIR_CORE,
        REPAIR_DOORS,
        CHECK_EXPANSIONS,
        DONE
    }

    private BlockPos _baseCenter;
    private String _dimension;
    private BaseMemory.BaseRecord _base;
    private BelfegorSchematic _coreSchematic;
    private Task _activeTask;
    private Phase _phase;
    private int _coreMismatches;

    @Override
    protected void onStart(Belfegor mod) {
        BlockPos playerPos = mod.getPlayer() == null ? BlockPos.ORIGIN : mod.getPlayer().getBlockPos();
        _dimension = WorldHelper.getCurrentDimension().name();
        BlockPos configuredHome = mod.getModSettings().getHomeBasePosition();
        if (configuredHome != null) {
            _base = BaseMemory.getInstance().baseAt(configuredHome, _dimension)
                    .orElseGet(() -> BaseMemory.getInstance().rememberBase(
                            configuredHome, _dimension, 8, 4, 5,
                            "validation_configured_home"));
            _baseCenter = configuredHome;
        } else {
            _base = BaseMemory.getInstance().nearestBase(playerPos, _dimension).orElse(null);
            _baseCenter = _base == null ? playerPos : _base.center();
        }
        _activeTask = null;
        _coreSchematic = null;
        _coreMismatches = -1;
        _phase = Phase.ORIENT_HOME;
        Debug.logMessage("[BuildValidate] Using base center " + _baseCenter.toShortString()
                + " dimension=" + _dimension
                + " configuredHome=" + (configuredHome == null ? "none" : configuredHome.toShortString()));
    }

    @Override
    protected Task onTick(Belfegor mod) {
        if (_base == null) {
            _base = BaseMemory.getInstance().rememberBase(_baseCenter, _dimension, 8, 4, 5,
                    "validation_created_base");
        }

        return switch (_phase) {
            case ORIENT_HOME -> orientHome(mod);
            case CHECK_CORE -> checkCore(mod);
            case REPAIR_CORE -> continueCoreRepair(mod);
            case REPAIR_DOORS -> continueDoorRepair(mod);
            case CHECK_EXPANSIONS -> checkExpansions(mod);
            case DONE -> null;
        };
    }

    private Task orientHome(Belfegor mod) {
        if (mod.getPlayer() != null
                && _baseCenter.getSquaredDistance(mod.getPlayer().getBlockPos()) <= 16) {
            _activeTask = null;
            _phase = Phase.CHECK_CORE;
            BaseMemory.getInstance().rememberInspection(_baseCenter, _dimension,
                    "base", "validation_orientation", 1, 0, 0, 1,
                    "oriented", "arrived at locked home before blueprint scan");
            BaseMemory.getInstance().save();
            return null;
        }
        if (_activeTask == null || _activeTask.stopped() || _activeTask.isFinished(mod)) {
            _activeTask = GetToBlockTask.baseAware(mod, _baseCenter);
        }
        setDebugState("Walking to locked home before validation " + _baseCenter.toShortString());
        return _activeTask;
    }

    private Task checkCore(Belfegor mod) {
        int radius = Math.max(6, _base == null ? 8 : _base.radius);
        if (_coreSchematic == null) {
            _coreSchematic = BuildCampsiteTask.loadOrExportCoreSchematic(
                    _dimension, _baseCenter, radius);
        }
        LinkedHashMap<BlockPos, net.minecraft.block.Block[]> coreTargets =
                new LinkedHashMap<>(_coreSchematic.toBuildTargets());
        int dynamicOpenings = coreTargets.size();
        coreTargets.entrySet().removeIf(entry -> isRememberedConnectorOpening(entry.getKey()));
        dynamicOpenings -= coreTargets.size();
        _coreMismatches = countTargetMismatches(mod, coreTargets);
        boolean metadataIncomplete = coreNeedsRepair();
        boolean doorsMissing = entranceDoorsMissingInWorld(mod);

        BaseMemory.getInstance().rememberInspection(_baseCenter, _dimension,
                "core_campsite", "validation", coreTargets.size(),
                0, _coreMismatches,
                Math.max(0, coreTargets.size() - _coreMismatches),
                metadataIncomplete || _coreMismatches > 0 || doorsMissing ? "repairing" : "complete",
                "per-home blueprint mismatches=" + _coreMismatches
                        + "; metadataIncomplete=" + metadataIncomplete
                        + "; doorsMissing=" + doorsMissing
                        + "; dynamicConnectorBlocks=" + dynamicOpenings);
        BaseMemory.getInstance().save();

        if (doorsMissing) {
            _activeTask = new RepairEntranceDoorsTask(
                    _baseCenter, Math.max(6, _base == null ? 8 : _base.radius));
            _phase = Phase.REPAIR_DOORS;
            setDebugState("Repairing missing camp entrance doors in world");
            return _activeTask;
        }
        if (metadataIncomplete) {
            _activeTask = new BuildCampsiteTask(_baseCenter, radius);
            _phase = Phase.REPAIR_CORE;
            setDebugState("Repairing incomplete campsite phases at locked home");
            return _activeTask;
        }
        if (_coreMismatches > 0) {
            _activeTask = new BuildRegionSchematicTask("home core blueprint repair",
                    coreTargets, false);
            _phase = Phase.REPAIR_CORE;
            setDebugState("Repairing per-home core blueprint mismatches=" + _coreMismatches);
            return _activeTask;
        }

        _activeTask = null;
        _phase = Phase.CHECK_EXPANSIONS;
        return null;
    }

    private Task continueDoorRepair(Belfegor mod) {
        if (_activeTask != null && !_activeTask.stopped() && !_activeTask.isFinished(mod)) {
            return _activeTask;
        }
        _activeTask = null;
        _phase = Phase.CHECK_CORE;
        return null;
    }

    private boolean entranceDoorsMissingInWorld(Belfegor mod) {
        if (mod == null || mod.getWorld() == null) return false;
        BlockPos first = _baseCenter.add(Math.max(6, _base == null ? 8 : _base.radius), 0, 0);
        BlockPos second = _baseCenter.add(Math.max(6, _base == null ? 8 : _base.radius), 0, 1);
        if (_base != null) {
            for (BaseMemory.BaseModule module : _base.modules) {
                if (module == null) continue;
                String name = normalize(module.name);
                if (name.equals("entrance_door_a")) first = module.center();
                if (name.equals("entrance_door_b")) second = module.center();
            }
        }
        return !DoorHelper.isDoor(mod, first) || !DoorHelper.isDoor(mod, second);
    }

    private int countTargetMismatches(Belfegor mod,
                                      Map<BlockPos, net.minecraft.block.Block[]> targets) {
        int mismatches = 0;
        for (Map.Entry<BlockPos, net.minecraft.block.Block[]> entry : targets.entrySet()) {
            net.minecraft.block.Block current = mod.getWorld().getBlockState(entry.getKey()).getBlock();
            boolean matches = false;
            for (net.minecraft.block.Block desired : entry.getValue()) {
                if (desired == current) {
                    matches = true;
                    break;
                }
            }
            if (!matches) mismatches++;
        }
        return mismatches;
    }

    private boolean isRememberedConnectorOpening(BlockPos pos) {
        if (_base == null || pos == null) return false;
        for (BaseMemory.BaseModule module : _base.modules) {
            if (module == null) continue;
            String type = normalize(module.type);
            if (!type.equals("hall") && !type.equals("access")) continue;
            int minX = module.x;
            int maxX = module.x + Math.max(1, module.width) - 1;
            int minZ = module.z;
            int maxZ = module.z + Math.max(1, module.depth) - 1;
            int minY = module.y + 1;
            int maxY = module.y + Math.max(3, module.height);
            if (pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ
                    && pos.getY() >= minY && pos.getY() <= maxY) {
                return true;
            }
        }
        return false;
    }

    private Task continueCoreRepair(Belfegor mod) {
        if (_activeTask != null && !_activeTask.stopped() && !_activeTask.isFinished(mod)) {
            return _activeTask;
        }
        if (_activeTask != null && _activeTask.stopped() && !_activeTask.isFinished(mod)) {
            Debug.logWarning("[BuildValidate] Core repair stopped before completion; rechecking the same home blueprint");
        }
        refreshBase();
        _activeTask = null;
        _phase = Phase.CHECK_CORE;
        return null;
    }

    private Task checkExpansions(Belfegor mod) {
        if (_activeTask != null && !_activeTask.stopped() && !_activeTask.isFinished(mod)) {
            return _activeTask;
        }
        if (_activeTask != null) {
            refreshBase();
            _activeTask = null;
        }

        recoverCorruptedStorageGeometry(mod);

        Optional<BaseMemory.BaseModule> next = _base.modules.stream()
                .filter(module -> isRepairableExpansion(module)
                        && (!BaseMemory.getInstance().moduleComplete(module)
                        || !expansionWorldStateValid(mod, module)))
                .min(Comparator.comparingLong(module -> module.lastUpdated));
        if (next.isPresent()) {
            BaseMemory.BaseModule module = next.get();
            BuildBaseExpansionTask.RoomType type = repairType(module.type);
            boolean completedButDamaged = BaseMemory.getInstance().moduleComplete(module)
                    && !expansionWorldStateValid(mod, module);
            boolean interruptedInPlaceRepair = normalize(module.note).contains("repairmode=inplace");
            // A planned/partially built room is already a committed spatial
            // decision. Re-planning it on every resume overwrote its anchor and
            // could create overlapping or abandoned shells. Continue at the
            // remembered geometry whenever its room and connector metadata are
            // structurally usable; corrupted legacy storage is recovered just
            // above before this decision.
            boolean repairAtRememberedGeometry = completedButDamaged
                    || interruptedInPlaceRepair
                    || hasUsableExpansionGeometry(module);
            BuildBaseExpansionTask repairTask = new BuildBaseExpansionTask(
                    type, module.name, repairAtRememberedGeometry ? module : null);
            if (completedButDamaged) {
                BaseMemory.getInstance().rememberModuleProgress(_baseCenter, _dimension,
                        module.name, module.progressDone, module.progressTotal,
                        "validation_failed", "remembered complete but world-state audit found missing room blocks/fixtures");
            }
            BaseMemory.getInstance().rememberInspection(_baseCenter, _dimension,
                    module.name, "validation", 1, 0, 1, 0,
                    "repairing", "rerunning named expansion type=" + type
                            + ";inPlace=" + repairAtRememberedGeometry);
            BaseMemory.getInstance().save();
            _activeTask = repairTask;
            setDebugState("Repairing room " + module.name + " type=" + type
                    + (repairAtRememberedGeometry ? " in-place" : " from incomplete plan"));
            return _activeTask;
        }

        BaseMemory.getInstance().rememberInspection(_baseCenter, _dimension,
                "base", "validation", _base.modules.size(), 0, 0,
                _base.modules.size(), "complete", "all remembered repairable rooms complete");
        BaseMemory.getInstance().markBaseStatus(_baseCenter, _dimension, "validated_complete");
        BaseMemory.getInstance().save();
        _phase = Phase.DONE;
        return null;
    }

    private boolean hasUsableExpansionGeometry(BaseMemory.BaseModule module) {
        if (module == null) return false;
        String direction = normalize(module.direction);
        boolean cardinalDirection = direction.equals("north")
                || direction.equals("south")
                || direction.equals("east")
                || direction.equals("west");
        return module.width >= 5
                && module.depth >= 5
                && module.height >= 3
                && module.hallLength >= 3
                && module.hallWidth >= 1
                && cardinalDirection
                && module.parent != null
                && !module.parent.isBlank();
    }

    /**
     * Recovers a storage room whose module coordinates were overwritten by an
     * interrupted legacy repair. A candidate is accepted only when a tracked
     * chest sits at the center of a strongly matching 7x7 cobblestone shell.
     */
    private void recoverCorruptedStorageGeometry(Belfegor mod) {
        if (_base == null || mod == null || mod.getWorld() == null) return;
        Optional<BaseMemory.BaseModule> storage = _base.modules.stream()
                .filter(module -> normalize(module.name).equals("camp_stockpile_storage")
                        && normalize(module.type).equals("storage"))
                .findFirst();
        if (storage.isEmpty()
                || BaseMemory.getInstance().moduleComplete(storage.get())
                || normalize(storage.get().note).contains("repairmode=inplace")) {
            return;
        }

        int radius = Math.max(8, _base.radius);
        BlockPos bestCenter = null;
        Direction bestDirection = null;
        int bestScore = 0;
        int trackedCandidates = 0;
        for (LocationMemory.RememberedLocation location : LocationMemory.getInstance().getAllInRange(
                "chest", _baseCenter.getX(), _baseCenter.getY(), _baseCenter.getZ(), 64 * 64)) {
            if (location == null || !_dimension.equals(location.dimension)) continue;
            trackedCandidates++;
            BlockPos center = location.toBlockPos();
            if (!mod.getChunkTracker().isChunkLoaded(center)
                    || mod.getWorld().getBlockState(center).getBlock() != Blocks.CHEST) continue;
            int dx = center.getX() - _baseCenter.getX();
            int dz = center.getZ() - _baseCenter.getZ();
            if (Math.max(Math.abs(dx), Math.abs(dz)) <= radius + 3) continue;
            Direction direction = Math.abs(dx) >= Math.abs(dz)
                    ? (dx < 0 ? Direction.WEST : Direction.EAST)
                    : (dz < 0 ? Direction.NORTH : Direction.SOUTH);
            BlockPos anchor = center.add(-3, 0, -3);
            int score = storageShellScore(mod, anchor, direction);
            DebugLogger.getInstance().log("BASE-RECOVERY",
                    "storage-candidate center=" + center.toShortString()
                            + " anchor=" + anchor.toShortString()
                            + " direction=" + direction.asString()
                            + " shellScore=" + score + " required=70");
            if (score > bestScore) {
                bestScore = score;
                bestCenter = center;
                bestDirection = direction;
            }
        }
        if (bestCenter == null || bestDirection == null || bestScore < 70) {
            DebugLogger.getInstance().log("BASE-RECOVERY",
                    "storage-recovery-deferred trackedCandidates=" + trackedCandidates
                            + " bestScore=" + bestScore + " required=70");
            return;
        }

        BlockPos anchor = bestCenter.add(-3, 0, -3);
        int hallLength = recoveredCoreHallLength(anchor, 7, bestDirection, radius);
        BaseMemory memory = BaseMemory.getInstance();
        String note = "recovered from center chest and storage-shell signature;repairMode=inPlace";
        memory.rememberModule(_baseCenter, _dimension, storage.get().name, "storage",
                anchor, 7, 7, 4, "recovery_pending", note,
                "core", bestDirection.asString(), hallLength, 2);
        rememberRecoveredHall(memory, storage.get().name, bestDirection, hallLength);
        LocationMemory.getInstance().remember("home_room_" + storage.get().name,
                bestCenter.getX(), bestCenter.getY(), bestCenter.getZ(), _dimension,
                "type=STORAGE;direction=" + bestDirection.asString()
                        + ";hallLength=" + hallLength + ";parent=core;recovered=true");
        LocationMemory.getInstance().remember("home_room_storage",
                bestCenter.getX(), bestCenter.getY(), bestCenter.getZ(), _dimension,
                "latest STORAGE room;name=" + storage.get().name + ";recovered=true");
        LocationMemory.getInstance().save();
        memory.save();
        refreshBase();
        Debug.logWarning("[BuildValidate] Recovered storage room geometry at "
                + bestCenter.toShortString() + " shellScore=" + bestScore
                + " direction=" + bestDirection.asString() + " hall=" + hallLength);
    }

    private int storageShellScore(Belfegor mod, BlockPos anchor, Direction direction) {
        int score = 0;
        for (int dx = 0; dx < 7; dx++) {
            for (int dz = 0; dz < 7; dz++) {
                boolean perimeter = dx == 0 || dz == 0 || dx == 6 || dz == 6;
                if (perimeter && !recoveredDoorway(direction, dx, dz)) {
                    for (int h = 0; h < 4; h++) {
                        if (mod.getWorld().getBlockState(anchor.add(dx, h, dz)).getBlock()
                                == Blocks.COBBLESTONE) score++;
                    }
                }
                if (mod.getWorld().getBlockState(anchor.add(dx, -1, dz)).isSolidBlock(
                        mod.getWorld(), anchor.add(dx, -1, dz))) {
                    score++;
                }
            }
        }
        return score;
    }

    private boolean recoveredDoorway(Direction direction, int dx, int dz) {
        return switch (direction) {
            case NORTH -> dz == 6 && (dx == 3 || dx == 4);
            case SOUTH -> dz == 0 && (dx == 3 || dx == 4);
            case WEST -> dx == 6 && (dz == 3 || dz == 4);
            default -> dx == 0 && (dz == 3 || dz == 4);
        };
    }

    private int recoveredCoreHallLength(BlockPos anchor, int size, Direction direction, int radius) {
        int gap = switch (direction) {
            case NORTH -> (_baseCenter.getZ() - radius) - (anchor.getZ() + size - 1) - 1;
            case SOUTH -> anchor.getZ() - (_baseCenter.getZ() + radius) - 1;
            case WEST -> (_baseCenter.getX() - radius) - (anchor.getX() + size - 1) - 1;
            default -> anchor.getX() - (_baseCenter.getX() + radius) - 1;
        };
        return Math.max(1, Math.min(16, gap));
    }

    private void rememberRecoveredHall(BaseMemory memory, String roomName,
                                       Direction direction, int hallLength) {
        int radius = Math.max(8, _base.radius);
        BlockPos start = switch (direction) {
            case NORTH -> new BlockPos(_baseCenter.getX(), _baseCenter.getY() - 1,
                    _baseCenter.getZ() - radius);
            case SOUTH -> new BlockPos(_baseCenter.getX() + 1, _baseCenter.getY() - 1,
                    _baseCenter.getZ() + radius);
            case WEST -> new BlockPos(_baseCenter.getX() - radius, _baseCenter.getY() - 1,
                    _baseCenter.getZ() + 1);
            default -> new BlockPos(_baseCenter.getX() + radius, _baseCenter.getY() - 1,
                    _baseCenter.getZ());
        };
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int step = 0; step < hallLength + 3; step++) {
            BlockPos first = start.offset(direction, step);
            BlockPos second = first.offset(direction.rotateYClockwise());
            minX = Math.min(minX, Math.min(first.getX(), second.getX()));
            minZ = Math.min(minZ, Math.min(first.getZ(), second.getZ()));
            maxX = Math.max(maxX, Math.max(first.getX(), second.getX()));
            maxZ = Math.max(maxZ, Math.max(first.getZ(), second.getZ()));
        }
        memory.rememberModule(_baseCenter, _dimension, roomName + "_hall", "hall",
                new BlockPos(minX, _baseCenter.getY() - 1, minZ),
                maxX - minX + 1, maxZ - minZ + 1, 4,
                "recovery_pending", "recovered connector;repairMode=inPlace",
                "core", direction.asString(), hallLength, 2);
    }

    private void refreshBase() {
        _base = BaseMemory.getInstance().baseAt(_baseCenter, _dimension)
                .or(() -> BaseMemory.getInstance().nearestBase(_baseCenter, _dimension))
                .orElse(_base);
    }

    private boolean coreNeedsRepair() {
        if (_base == null || _base.modules.isEmpty()) return true;
        String[] coreNames = {
                "core", "perimeter_wall", "interior_dividers",
                "crafting_workshop", "smelting_workshop", "storage_wing",
                "entrance_door_a", "entrance_door_b"
        };
        for (String name : coreNames) {
            Optional<BaseMemory.BaseModule> module = _base.modules.stream()
                    .filter(candidate -> normalize(candidate.name).equals(name))
                    .findFirst();
            if (module.isEmpty() || !BaseMemory.getInstance().moduleComplete(module.get())) {
                return true;
            }
        }
        Optional<BaseMemory.BaseModule> bed = _base.modules.stream()
                .filter(candidate -> normalize(candidate.name).equals("bed"))
                .findFirst();
        if (bed.isEmpty()) return true;
        String bedStatus = normalize(bed.get().status);
        if (!bedStatus.equals("spawn_clicked") && !bedStatus.equals("spawn_click_attempted")) {
            return true;
        }
        return false;
    }

    private boolean expansionWorldStateValid(Belfegor mod, BaseMemory.BaseModule module) {
        if (mod == null || mod.getWorld() == null || module == null) return false;
        String type = normalize(module.type);
        int width = Math.max(1, module.width);
        int depth = Math.max(1, module.depth);
        int wallHeight = 4;
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                boolean perimeter = dx == 0 || dz == 0 || dx == width - 1 || dz == depth - 1;
                if (perimeter && !isExpansionDoorway(module, dx, dz)) {
                    for (int h = 0; h < wallHeight; h++) {
                        BlockPos wall = new BlockPos(module.x + dx, module.y + h, module.z + dz);
                        if (mod.getWorld().getBlockState(wall).getBlock() != Blocks.COBBLESTONE) return false;
                    }
                }
                BlockPos floor = new BlockPos(module.x + dx, module.y - 1, module.z + dz);
                if (type.equals("farmland")) {
                    if (!farmCellValid(mod, module, floor, dx, dz)) return false;
                } else if (!WorldHelper.isSolid(mod, floor)) {
                    return false;
                }
            }
        }
        if (type.equals("mobfarm") || type.equals("mob_farm")) {
            for (int dx = 0; dx < width; dx++) {
                for (int dz = 0; dz < depth; dz++) {
                    BlockPos roof = new BlockPos(module.x + dx, module.y + wallHeight, module.z + dz);
                    if (mod.getWorld().getBlockState(roof).getBlock() != Blocks.COBBLESTONE) return false;
                }
            }
        }
        BlockPos center = module.center();
        if (type.equals("storage")) {
            return mod.getWorld().getBlockState(center).getBlock() == Blocks.CHEST;
        }
        if (type.equals("workshop")) {
            return mod.getWorld().getBlockState(center.add(-1, 0, 0)).getBlock() == Blocks.CRAFTING_TABLE
                    && mod.getWorld().getBlockState(center.add(1, 0, 0)).getBlock() == Blocks.FURNACE;
        }
        if (type.equals("armory")) {
            return mod.getWorld().getBlockState(center.add(-2, 0, 0)).getBlock() == Blocks.CHEST
                    && mod.getWorld().getBlockState(center.add(2, 0, 0)).getBlock() == Blocks.CHEST
                    && mod.getWorld().getBlockState(center).getBlock() == Blocks.CRAFTING_TABLE;
        }
        return true;
    }

    private boolean farmCellValid(Belfegor mod, BaseMemory.BaseModule module,
                                  BlockPos floor, int dx, int dz) {
        boolean interior = dx > 0 && dz > 0
                && dx < Math.max(1, module.width) - 1
                && dz < Math.max(1, module.depth) - 1;
        if (!interior) return WorldHelper.isSolid(mod, floor);
        BlockPos center = module.center().down();
        boolean plannedWater = (floor.getX() == center.getX() || floor.getX() == center.getX() + 1)
                && (floor.getZ() == center.getZ() || floor.getZ() == center.getZ() + 1);
        if (plannedWater) return mod.getWorld().getBlockState(floor).getBlock() == Blocks.WATER;
        return mod.getWorld().getBlockState(floor).getBlock() == Blocks.FARMLAND
                && mod.getWorld().getBlockState(floor.up()).getBlock() == Blocks.WHEAT;
    }

    private boolean isExpansionDoorway(BaseMemory.BaseModule module, int dx, int dz) {
        int width = Math.max(1, module.width);
        int depth = Math.max(1, module.depth);
        int midX = width / 2;
        int midZ = depth / 2;
        return switch (normalize(module.direction)) {
            case "north" -> dz == depth - 1 && (dx == midX || dx == midX + 1);
            case "south" -> dz == 0 && (dx == midX || dx == midX + 1);
            case "west" -> dx == width - 1 && (dz == midZ || dz == midZ + 1);
            default -> dx == 0 && (dz == midZ || dz == midZ + 1);
        };
    }

    private boolean isRepairableExpansion(BaseMemory.BaseModule module) {
        if (module == null) return false;
        String name = normalize(module.name);
        if (name.equals("mob_farm_entrance")
                || name.equals("construction_staging")
                || name.equals("construction_staging_chest")) {
            return false;
        }
        String type = normalize(module.type);
        return type.equals("farmland")
                || type.equals("storage")
                || type.equals("workshop")
                || type.equals("armory")
                || type.equals("mobfarm")
                || type.equals("mob_farm")
                || type.equals("empty");
    }

    private BuildBaseExpansionTask.RoomType repairType(String type) {
        return BuildBaseExpansionTask.parseType(type == null ? "" : type);
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
        return other instanceof BuildBaseValidationTask;
    }

    @Override
    protected String toDebugString() {
        return "Validate and repair remembered base phase=" + _phase
                + " home=" + (_baseCenter == null ? "unset" : _baseCenter.toShortString())
                + " mismatches=" + _coreMismatches;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return _phase == Phase.DONE;
    }
}
