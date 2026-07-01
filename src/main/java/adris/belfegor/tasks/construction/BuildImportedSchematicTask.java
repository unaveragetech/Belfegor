package adris.belfegor.tasks.construction;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.debug.DebugLogger;
import adris.belfegor.memory.BaseMemory;
import adris.belfegor.memory.LocationMemory;
import adris.belfegor.schematic.BelfegorSchematic;
import adris.belfegor.schematic.LitematicSchematicLoader;
import adris.belfegor.tasks.container.StoreInContainerTask;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.WorldHelper;
import net.minecraft.block.Blocks;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Imports a user-provided schematic, stages its resources, and builds it from
 * the parsed Belfegor blueprint. This is intentionally independent of the
 * Litematica UI; the file is copied into .minecraft/belfegor/schematics/imported
 * and converted into the internal JSON model used by validation/repair.
 */
public class BuildImportedSchematicTask extends Task {

    private static final int STARTER_BATCH_PER_ITEM = 64;

    private enum Phase {
        IMPORT,
        STAGING,
        COLLECT,
        DEPOSIT,
        BUILD,
        DONE
    }

    private final File _sourceFile;
    private final String _requestedName;
    private Phase _phase = Phase.IMPORT;
    private Task _activeTask;
    private BelfegorSchematic _schematic;
    private File _copiedSource;
    private File _internalFile;
    private BlockPos _origin;
    private BlockPos _stagingChest;
    private BlockPos _stagingStand;
    private String _dimension;
    private Map<Item, Integer> _requirements = new LinkedHashMap<>();
    private Map<BlockPos, Block[]> _directTargets;
    private Item _currentDepositItem;

    public BuildImportedSchematicTask(File sourceFile, String requestedName) {
        _sourceFile = sourceFile;
        _requestedName = requestedName == null ? "" : requestedName.trim();
    }

    @Override
    protected void onStart(Belfegor mod) {
        _phase = Phase.IMPORT;
        _activeTask = null;
        _currentDepositItem = null;
        _origin = findGroundedOrigin(mod, mod.getPlayer().getBlockPos());
        _dimension = WorldHelper.getCurrentDimension().name();
    }

    private BlockPos findGroundedOrigin(Belfegor mod, BlockPos requested) {
        if (requested == null || mod.getWorld() == null) return BlockPos.ORIGIN;
        if (WorldHelper.isSolid(mod, requested.down())) return requested;
        for (int y = requested.getY(); y >= Math.max(mod.getWorld().getBottomY(), requested.getY() - 6); y--) {
            BlockPos candidate = new BlockPos(requested.getX(), y, requested.getZ());
            if (WorldHelper.isSolid(mod, candidate.down())
                    && mod.getWorld().getBlockState(candidate).isAir()
                    && mod.getWorld().getBlockState(candidate.up()).isAir()) {
                DebugLogger.getInstance().log("SCHEMATIC-IMPORT",
                        "normalized-origin requested=" + requested.toShortString()
                                + " grounded=" + candidate.toShortString());
                return candidate;
            }
        }
        DebugLogger.getInstance().log("SCHEMATIC-IMPORT",
                "origin-not-grounded requested=" + requested.toShortString()
                        + " note=using current player feet instead of scanning deep underground");
        return requested;
    }

    private BlockPos findStagingChestPosition(Belfegor mod, BlockPos origin) {
        BlockPos preferred = origin.add(2, 0, -2);
        if (isGoodChestSpot(mod, preferred)) {
            _stagingStand = findAdjacentStandSpot(mod, preferred);
            return preferred;
        }
        BlockPos best = preferred;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int radius = 1; radius <= 6; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    for (int dy = 2; dy >= -4; dy--) {
                        BlockPos candidate = preferred.add(dx, dy, dz);
                        if (!isGoodChestSpot(mod, candidate)) continue;
                        double distance = candidate.getSquaredDistance(preferred);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = candidate;
                            _stagingStand = findAdjacentStandSpot(mod, candidate);
                        }
                    }
                }
            }
            if (bestDistance < Double.POSITIVE_INFINITY) {
                DebugLogger.getInstance().log("SCHEMATIC-IMPORT",
                        "staging-adjusted preferred=" + preferred.toShortString()
                                + " chosen=" + best.toShortString());
                return best;
            }
        }
        BlockPos grounded = findNearbyGroundedChestSpot(mod, origin, 12, 8);
        if (grounded != null) {
            _stagingStand = findAdjacentStandSpot(mod, grounded);
            DebugLogger.getInstance().log("SCHEMATIC-IMPORT",
                    "staging-ground-search preferred=" + preferred.toShortString()
                            + " chosen=" + grounded.toShortString());
            return grounded;
        }
        BlockPos fallbackBase = mod.getPlayer() == null ? origin : mod.getPlayer().getBlockPos();
        BlockPos fallback = findPlayerAdjacentStagingSpot(mod, fallbackBase);
        if (fallback == null) {
            fallback = findFallbackStagingAirSpot(mod, fallbackBase.add(-4, 0, -4), 6);
            if (fallback != null) {
                _stagingStand = fallback.west();
            }
        }
        if (fallback == null) {
            fallback = fallbackBase.add(1, 0, 0);
            _stagingStand = fallbackBase;
        }
        if (_stagingStand == null) _stagingStand = fallback.west();
        DebugLogger.getInstance().log("SCHEMATIC-IMPORT",
                "staging-fallback-pad preferred=" + preferred.toShortString()
                        + " origin=" + origin.toShortString()
                        + " playerBase=" + fallbackBase.toShortString()
                        + " chosen=" + fallback.toShortString()
                        + " stand=" + _stagingStand.toShortString()
                        + " reason=no natural grounded air chest spot found");
        return fallback;
    }

    private BlockPos findPlayerAdjacentStagingSpot(Belfegor mod, BlockPos playerFeet) {
        if (mod.getWorld() == null || playerFeet == null) return null;
        if (!isClearAir(mod, playerFeet) || !isClearAir(mod, playerFeet.up())) return null;
        if (!WorldHelper.isSolid(mod, playerFeet.down())) return null;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos chest = playerFeet.offset(direction);
            if (isInsideImportedSchematicFootprint(chest) || isInsideImportedSchematicFootprint(playerFeet)) continue;
            if (!isClearAir(mod, chest) || !isClearAir(mod, chest.up())) continue;
            _stagingStand = playerFeet;
            return chest;
        }
        return null;
    }

    private BlockPos findFallbackStagingAirSpot(Belfegor mod, BlockPos center, int radius) {
        if (mod.getWorld() == null || center == null) return null;
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        int minY = Math.max(mod.getWorld().getBottomY() + 1, center.getY() - 6);
        int maxY = Math.min(mod.getWorld().getBottomY() + 382, center.getY() + 8);
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    for (int y = maxY; y >= minY; y--) {
                        BlockPos candidate = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                        BlockPos stand = candidate.west();
                        if (!isClearAir(mod, candidate) || !isClearAir(mod, candidate.up())) continue;
                        if (!WorldHelper.isSolid(mod, candidate.down())) continue;
                        if (!isClearAir(mod, stand) || !isClearAir(mod, stand.up())) continue;
                        if (!WorldHelper.isSolid(mod, stand.down())) continue;
                        if (isInsideImportedSchematicFootprint(candidate) || isInsideImportedSchematicFootprint(stand)) continue;
                        double distance = candidate.getSquaredDistance(center);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = candidate;
                        }
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private boolean isGoodChestSpot(Belfegor mod, BlockPos pos) {
        return pos != null
                && WorldHelper.isSolid(mod, pos.down())
                && isClearAir(mod, pos)
                && isClearAir(mod, pos.up())
                && hasAdjacentChestAccess(mod, pos)
                && !isInsideImportedSchematicFootprint(pos)
                && !isInsideImportedSchematicFootprint(pos.down())
                && !isInsideImportedSchematicFootprint(pos.up());
    }

    private boolean hasAdjacentChestAccess(Belfegor mod, BlockPos chestPos) {
        return findAdjacentStandSpot(mod, chestPos) != null;
    }

    private BlockPos findAdjacentStandSpot(Belfegor mod, BlockPos chestPos) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos stand = chestPos.offset(direction);
            if (isSafeStandSpot(mod, stand)) return stand;
        }
        return null;
    }

    private boolean isSafeStandSpot(Belfegor mod, BlockPos pos) {
        return pos != null
                && WorldHelper.isSolid(mod, pos.down())
                && isClearAir(mod, pos)
                && isClearAir(mod, pos.up())
                && !isInsideImportedSchematicFootprint(pos);
    }

    private boolean isClearAir(Belfegor mod, BlockPos pos) {
        if (mod.getWorld() == null || pos == null) return false;
        var state = mod.getWorld().getBlockState(pos);
        return state.isAir() && state.getFluidState().isEmpty();
    }

    private boolean isInsideImportedSchematicFootprint(BlockPos pos) {
        if (_schematic == null || pos == null) return false;
        return pos.getX() >= _schematic.minX - 1
                && pos.getX() <= _schematic.maxX + 1
                && pos.getZ() >= _schematic.minZ - 1
                && pos.getZ() <= _schematic.maxZ + 1
                && pos.getY() >= _schematic.minY - 1
                && pos.getY() <= _schematic.maxY + 2;
    }

    private BlockPos findNearbyGroundedChestSpot(Belfegor mod, BlockPos center, int radius, int vertical) {
        if (mod.getWorld() == null || center == null) return null;
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        int minY = Math.max(mod.getWorld().getBottomY() + 1, center.getY() - vertical);
        int maxY = Math.min(mod.getWorld().getBottomY() + 382, center.getY() + vertical);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int y = maxY; y >= minY; y--) {
                    BlockPos candidate = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    if (!isGoodChestSpot(mod, candidate)) continue;
                    double distance = candidate.getSquaredDistance(center);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                    break;
                }
            }
        }
        return best;
    }

    @Override
    protected Task onTick(Belfegor mod) {
        return switch (_phase) {
            case IMPORT -> {
                importSchematic(mod);
                next(Phase.STAGING);
                yield null;
            }
            case STAGING -> {
                Task staging = ensureStagingChest(mod);
                if (staging != null) yield staging;
                remember("staging_ready");
                next(Phase.COLLECT);
                yield null;
            }
            case COLLECT -> {
                Task collect = collectMissingMaterial(mod);
                if (collect != null) yield collect;
                next(Phase.DEPOSIT);
                yield null;
            }
            case DEPOSIT -> {
                Task deposit = depositMaterials(mod);
                if (deposit != null) yield deposit;
                next(Phase.BUILD);
                yield null;
            }
            case BUILD -> {
                if (_schematic == null) {
                    next(Phase.DONE);
                    yield null;
                }
                if (_directTargets == null) {
                    _directTargets = directPlaceableTargets();
                }
                if (_directTargets.isEmpty()) {
                    DebugLogger.getInstance().log("SCHEMATIC-IMPORT",
                            "build-skip-no-direct-targets name=" + schematicName()
                                    + " total=" + _schematic.totalBlocks());
                    remember("built_direct_blocks");
                    next(Phase.DONE);
                    yield null;
                }
                Task build = cache(mod, new BuildRegionSchematicTask(
                        "imported schematic " + _schematic.name,
                        _directTargets,
                        false));
                if (!build.isFinished(mod)) yield build;
                remember("built");
                next(Phase.DONE);
                yield null;
            }
            case DONE -> null;
        };
    }

    private void importSchematic(Belfegor mod) {
        if (_sourceFile == null || !_sourceFile.exists() || !_sourceFile.isFile()) {
            throw new IllegalArgumentException("Schematic file does not exist: " + _sourceFile);
        }
        try {
            File importDir = BelfegorSchematic.importedDir();
            importDir.mkdirs();
            String safeName = safeName(_requestedName.isBlank()
                    ? stripExtension(_sourceFile.getName())
                    : _requestedName);
            String extension = extension(_sourceFile.getName());
            _copiedSource = new File(importDir, safeName + extension);
            Files.copy(_sourceFile.toPath(), _copiedSource.toPath(), StandardCopyOption.REPLACE_EXISTING);

            if (extension.equalsIgnoreCase(".litematic")) {
                _schematic = LitematicSchematicLoader.load(_copiedSource, _origin, _dimension);
                _schematic.name = safeName;
            } else if (extension.equalsIgnoreCase(".json")
                    || extension.equalsIgnoreCase(".belfegor_schematic")) {
                _schematic = BelfegorSchematic.load(_copiedSource)
                        .orElseThrow(() -> new IllegalArgumentException("Could not parse Belfegor schematic JSON"));
                _schematic = _schematic.translatedTo(_origin);
                _schematic.name = safeName;
                _schematic.dimension = _dimension;
            } else {
                throw new IllegalArgumentException("Unsupported schematic extension: " + extension
                        + ". Supported: .litematic, .json");
            }

            _requirements = directPlaceableRequirements();
            _directTargets = null;
            _stagingChest = findStagingChestPosition(mod, _origin);
            _internalFile = new File(importDir, safeName + ".belfegor_schematic.json");
            _schematic.save(_internalFile);
            DebugLogger.getInstance().log("SCHEMATIC-IMPORT",
                    "imported name=" + safeName
                            + " source=" + _sourceFile.getAbsolutePath()
                            + " copy=" + _copiedSource.getAbsolutePath()
                            + " internal=" + _internalFile.getAbsolutePath()
                            + " blocks=" + _schematic.totalBlocks()
                            + " bounds=" + _schematic.minX + "," + _schematic.minY + "," + _schematic.minZ
                            + "->" + _schematic.maxX + "," + _schematic.maxY + "," + _schematic.maxZ
                            + " staging=" + _stagingChest.toShortString()
                            + " requirements=" + _requirements.size());
            remember("imported");
        } catch (Exception e) {
            Debug.logWarning("Failed to import schematic " + _sourceFile + ": " + e.getMessage());
            DebugLogger.getInstance().log("SCHEMATIC-IMPORT", "failed source=" + _sourceFile
                    + " error=" + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private Task ensureStagingChest(Belfegor mod) {
        if (_stagingStand == null) _stagingStand = findAdjacentStandSpot(mod, _stagingChest);
        if (_stagingStand == null) _stagingStand = _stagingChest.west();
        if (!WorldHelper.isSolid(mod, _stagingStand.down())) {
            return cache(mod, new PlaceBlockTask(_stagingStand.down(),
                    new net.minecraft.block.Block[]{Blocks.COBBLESTONE}, false, true));
        }
        if (!isClearAir(mod, _stagingStand)) {
            return cache(mod, new DestroyBlockTask(_stagingStand));
        }
        if (!isClearAir(mod, _stagingStand.up())) {
            return cache(mod, new DestroyBlockTask(_stagingStand.up()));
        }
        if (mod.getWorld().getBlockState(_stagingChest).getBlock() == Blocks.CHEST) return null;
        if (!WorldHelper.isSolid(mod, _stagingChest.down())) {
            return cache(mod, new PlaceBlockTask(_stagingChest.down(),
                    new net.minecraft.block.Block[]{Blocks.COBBLESTONE}, false, true));
        }
        if (!mod.getWorld().getBlockState(_stagingChest).isAir()) {
            return cache(mod, new DestroyBlockTask(_stagingChest));
        }
        if (!mod.getItemStorage().hasItem(Items.CHEST)) {
            return TaskCatalogue.getItemTask("chest", 1);
        }
        return cache(mod, new PlaceBlockTask(_stagingChest, Blocks.CHEST));
    }

    private Task collectMissingMaterial(Belfegor mod) {
        for (Map.Entry<Item, Integer> entry : _requirements.entrySet()) {
            Item item = entry.getKey();
            int needed = entry.getValue();
            if (isSpecialSchematicItem(item)) {
                DebugLogger.getInstance().log("SCHEMATIC-IMPORT",
                        "collect-defer-special name=" + schematicName()
                                + " item=" + item
                                + " neededTotal=" + needed
                                + " note=special block requires specialist task or pre-staged material");
                continue;
            }
            int have = mod.getItemStorage().getItemCount(item);
            int starterTarget = starterTarget(item, needed);
            if (have >= starterTarget) continue;
            setDebugState("Collecting imported schematic material "
                    + item + " " + have + "/" + starterTarget
                    + " starter for total " + needed);
            DebugLogger.getInstance().log("SCHEMATIC-IMPORT",
                    "collect name=" + schematicName()
                            + " item=" + item
                            + " have=" + have
                            + " starter=" + starterTarget
                            + " neededTotal=" + needed);
            Task task = TaskCatalogue.getItemTask(new ItemTarget(item, starterTarget));
            if (task != null) return task;
        }
        return null;
    }

    private Map<Item, Integer> directPlaceableRequirements() {
        LinkedHashMap<Item, Integer> result = new LinkedHashMap<>();
        if (_schematic == null || _schematic.blocks == null) return result;
        int deferred = 0;
        for (String blockId : _schematic.blocks.values()) {
            Block block = Registries.BLOCK.get(Identifier.of(blockId));
            if (isDeferredFunctionalBlock(block)) {
                deferred++;
                continue;
            }
            Item item = block.asItem();
            if (item == Items.AIR) {
                deferred++;
                continue;
            }
            result.put(item, result.getOrDefault(item, 0) + 1);
        }
        if (deferred > 0) {
            DebugLogger.getInstance().log("SCHEMATIC-IMPORT",
                    "requirements-filtered name=" + schematicName()
                            + " directItems=" + result.size()
                            + " deferredBlocks=" + deferred
                            + " note=resource preflight only collects directly placeable schematic blocks");
        }
        return result;
    }

    private int starterTarget(Item item, int needed) {
        if (item == Items.CHEST || item == Items.FURNACE || item == Items.CRAFTING_TABLE) {
            return Math.min(needed, 1);
        }
        if (needed <= STARTER_BATCH_PER_ITEM) return needed;
        return STARTER_BATCH_PER_ITEM;
    }

    private boolean isSpecialSchematicItem(Item item) {
        Block block = Block.getBlockFromItem(item);
        return block == Blocks.OAK_LEAVES
                || block == Blocks.SPRUCE_LEAVES
                || block == Blocks.BIRCH_LEAVES
                || block == Blocks.JUNGLE_LEAVES
                || block == Blocks.ACACIA_LEAVES
                || block == Blocks.DARK_OAK_LEAVES
                || block == Blocks.MANGROVE_LEAVES
                || block == Blocks.CHERRY_LEAVES
                || block == Blocks.AZALEA_LEAVES
                || block == Blocks.FLOWERING_AZALEA_LEAVES;
    }

    private Map<BlockPos, Block[]> directPlaceableTargets() {
        LinkedHashMap<BlockPos, Block[]> result = new LinkedHashMap<>();
        if (_schematic == null || _schematic.blocks == null) return result;
        int deferred = 0;
        for (Map.Entry<String, String> entry : _schematic.blocks.entrySet()) {
            Optional<BlockPos> pos = parseKey(entry.getKey());
            if (pos.isEmpty()) continue;
            Block block = Registries.BLOCK.get(Identifier.of(entry.getValue()));
            if (isDeferredFunctionalBlock(block)) {
                deferred++;
                continue;
            }
            result.put(pos.get(), new Block[]{block});
        }
        if (deferred > 0) {
            DebugLogger.getInstance().log("SCHEMATIC-IMPORT",
                    "deferred-functional-blocks name=" + schematicName()
                            + " deferred=" + deferred
                            + " direct=" + result.size()
                            + " note=water/farmland/crops require specialist placement tasks");
        }
        return result;
    }

    private boolean isDeferredFunctionalBlock(Block block) {
        if (block == null || block == Blocks.AIR) return true;
        if (block == Blocks.WATER || block == Blocks.LAVA) return true;
        if (block == Blocks.FARMLAND) return true;
        if (isSpecialSchematicItem(block.asItem())) return true;
        if (block.asItem() == Items.AIR) return true;
        return block == Blocks.WHEAT
                || block == Blocks.CARROTS
                || block == Blocks.POTATOES
                || block == Blocks.BEETROOTS
                || block == Blocks.MELON_STEM
                || block == Blocks.PUMPKIN_STEM;
    }

    private static Optional<BlockPos> parseKey(String key) {
        if (key == null) return Optional.empty();
        String[] parts = key.split(",");
        if (parts.length != 3) return Optional.empty();
        try {
            return Optional.of(new BlockPos(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Task depositMaterials(Belfegor mod) {
        for (Map.Entry<Item, Integer> entry : _requirements.entrySet()) {
            Item item = entry.getKey();
            int needed = entry.getValue();
            int carried = mod.getItemStorage().getItemCountInventoryOnly(item);
            if (carried <= 0) continue;
            int deposit = Math.min(carried, needed);
            if (_currentDepositItem == item && _activeTask != null
                    && !_activeTask.stopped() && !_activeTask.isFinished(mod)) {
                return _activeTask;
            }
            _currentDepositItem = item;
            setDebugState("Depositing imported schematic material "
                    + item + " x" + deposit + " into staging chest");
            _activeTask = new StoreInContainerTask(_stagingChest, false,
                    new ItemTarget(item, deposit));
            return _activeTask;
        }
        _currentDepositItem = null;
        _activeTask = null;
        return null;
    }

    private void remember(String status) {
        if (_schematic == null) return;
        int width = Math.max(1, _schematic.maxX - _schematic.minX + 1);
        int height = Math.max(1, _schematic.maxY - _schematic.minY + 1);
        int depth = Math.max(1, _schematic.maxZ - _schematic.minZ + 1);
        BaseMemory memory = BaseMemory.getInstance();
        memory.rememberBase(_origin, _dimension, Math.max(width, depth), height, 5, "imported_schematic");
        memory.rememberModule(_origin, _dimension, schematicName(), "imported_schematic",
                new BlockPos(_schematic.minX, _schematic.minY, _schematic.minZ),
                width, depth, height, status,
                "source=" + (_copiedSource == null ? "" : _copiedSource.getName())
                        + " internal=" + (_internalFile == null ? "" : _internalFile.getName())
                        + " blocks=" + _schematic.totalBlocks());
        memory.rememberModule(_origin, _dimension, schematicName() + "_staging", "construction_staging_chest",
                _stagingChest, 1, 1, 1, "ready",
                "staging chest for imported schematic " + schematicName());
        memory.rememberInspection(_origin, _dimension, schematicName(), "schematic_import",
                _schematic.totalBlocks(), 0, _schematic.countMismatches(null), 0,
                status, "requirements=" + _requirements.size());
        memory.save();
        LocationMemory.getInstance().remember("imported_schematic_" + safeName(schematicName()),
                _origin.getX(), _origin.getY(), _origin.getZ(), _dimension,
                "imported schematic origin: " + schematicName());
        LocationMemory.getInstance().save();
    }

    private String schematicName() {
        return _schematic == null || _schematic.name == null || _schematic.name.isBlank()
                ? "imported_schematic"
                : _schematic.name;
    }

    private Task cache(Belfegor mod, Task task) {
        if (_activeTask != null && !_activeTask.stopped() && !_activeTask.isFinished(mod)) {
            return _activeTask;
        }
        _activeTask = task;
        return _activeTask;
    }

    private void next(Phase phase) {
        _phase = phase;
        _activeTask = null;
        _currentDepositItem = null;
    }

    private static String safeName(String name) {
        String safe = name == null ? "imported_schematic" : name.trim()
                .replaceAll("[^a-zA-Z0-9_.-]", "_");
        return safe.isBlank() ? "imported_schematic" : safe;
    }

    private static String stripExtension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot <= 0 ? (name == null ? "imported_schematic" : name) : name.substring(0, dot);
    }

    private static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _activeTask = null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BuildImportedSchematicTask task
                && task._sourceFile.equals(_sourceFile);
    }

    @Override
    protected String toDebugString() {
        return "Build imported schematic phase=" + _phase
                + " file=" + (_sourceFile == null ? "null" : _sourceFile.getName());
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return _phase == Phase.DONE;
    }
}
