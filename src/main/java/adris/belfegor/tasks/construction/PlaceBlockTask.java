package adris.belfegor.tasks.construction;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.debug.DebugLogger;
import adris.belfegor.tasks.InteractWithBlockTask;
import adris.belfegor.tasks.movement.GetToBlockTask;
import adris.belfegor.tasks.movement.TimeoutWanderTask;
import adris.belfegor.tasksystem.ITaskRequiresGrounded;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.ItemHelper;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.helpers.WorldHelper;
import adris.belfegor.util.progresscheck.MovementProgressChecker;
import baritone.api.schematic.AbstractSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.input.Input;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;
import java.util.List;

/**
 * Place a block type at a position
 */
public class PlaceBlockTask extends Task implements ITaskRequiresGrounded {

    private static final int MIN_MATERIALS = 1;
    private static final int PREFERRED_MATERIALS = 32;
    private final BlockPos _target;
    private final Block[] _toPlace;
    private final boolean _useThrowaways;
    private final boolean _autoCollectStructureBlocks;
    private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
    private final TimeoutWanderTask _wanderTask = new TimeoutWanderTask(5); // This can get stuck forever, so we increase the range.
    private Task _materialTask;
    private int _failCount = 0;
    private int _missingPlaceableTicks = 0;
    private boolean _missingPlaceableThisBuild = false;
    private boolean _directPlacementAttempted = false;
    private Task _directPlacementTask = null;
    private int _directPlacementTicks = 0;
    private int _directPlacementSupportSkip = 0;

    public PlaceBlockTask(BlockPos target, Block[] toPlace, boolean useThrowaways, boolean autoCollectStructureBlocks) {
        _target = target;
        _toPlace = toPlace;
        _useThrowaways = useThrowaways;
        _autoCollectStructureBlocks = autoCollectStructureBlocks;
    }

    public PlaceBlockTask(BlockPos target, Block... toPlace) {
        this(target, toPlace, false, false);
    }

    public static int getMaterialCount(Belfegor mod) {
        return mod.getItemStorage().getItemCount(Items.DIRT, Items.COBBLESTONE, Items.NETHERRACK, Items.COBBLED_DEEPSLATE);
    }

    public static Task getMaterialTask(int count) {
        return TaskCatalogue.getSquashedItemTask(new ItemTarget(Items.DIRT, count), new ItemTarget(Items.COBBLESTONE,
                count), new ItemTarget(Items.NETHERRACK, count), new ItemTarget(Items.COBBLED_DEEPSLATE, count));
    }

    @Override
    protected void onStart(Belfegor mod) {
        _progressChecker.reset();
        mod.getBehaviour().push();
        // Do not add the exact block we are about to place to Baritone's
        // protected-items list. Protected items are hidden from the builder's
        // available block list, which caused crafting-table placement to fall
        // through to the "Failed to find throwaway block" fallback and freeze
        // the client in a busy build loop.
        // If we get interrupted by another task, this might cause problems...
        //_wanderTask.resetWander();
    }

    @Override
    protected Task onTick(Belfegor mod) {
        // Placement must happen in-world. If a crafting/container screen was
        // left open by the previous task, direct controller placement silently
        // fails and the task can loop forever trying the same block face.
        if (MinecraftClient.getInstance().currentScreen != null) {
            StorageHelper.closeScreen();
            return null;
        }

        if (WorldHelper.isInNetherPortal(mod)) {
            if (!mod.getClientBaritone().getPathingBehavior().isPathing()) {
                setDebugState("Getting out from nether portal");
                mod.getInputControls().hold(Input.SNEAK);
                mod.getInputControls().hold(Input.MOVE_FORWARD);
                return null;
            } else {
                mod.getInputControls().release(Input.SNEAK);
                mod.getInputControls().release(Input.MOVE_BACK);
                mod.getInputControls().release(Input.MOVE_FORWARD);
            }
        } else {
            if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
                mod.getInputControls().release(Input.SNEAK);
                mod.getInputControls().release(Input.MOVE_BACK);
                mod.getInputControls().release(Input.MOVE_FORWARD);
            }
        }
        // Perform timeout wander
        if (_wanderTask.isActive() && !_wanderTask.isFinished(mod)) {
            setDebugState("Wandering.");
            _progressChecker.reset();
            return _wanderTask;
        }
        if (_autoCollectStructureBlocks) {
            if (_materialTask != null && _materialTask.isActive() && !_materialTask.isFinished(mod)) {
                setDebugState("No exact structure items, collecting requested block material.");
                if (getExactMaterialCount(mod) < MIN_MATERIALS) {
                    return _materialTask;
                } else {
                    _materialTask = null;
                }
            }

            //Item[] items = Util.toArray(Item.class, mod.getClientBaritoneSettings().acceptableThrowawayItems.value);
            if (getExactMaterialCount(mod) < MIN_MATERIALS) {
                _materialTask = getExactMaterialTask(PREFERRED_MATERIALS);
                _progressChecker.reset();
                if (_materialTask != null) return _materialTask;
            }
        }

        if (_directPlacementTask != null) {
            if (isFinished(mod)) {
                _directPlacementTask = null;
                _directPlacementTicks = 0;
            } else if (_directPlacementTicks++ < 100) {
                setDebugState("Holding direct structure placement attempt");
                return _directPlacementTask;
            } else {
                DebugLogger.getInstance().logImmediate("PLACE-BLOCK",
                        "direct-placement timed out target=" + _target
                                + " blocks=" + Arrays.toString(_toPlace)
                                + " supportSkip=" + _directPlacementSupportSkip);
                _directPlacementTask = null;
                _directPlacementTicks = 0;
                _directPlacementAttempted = false;
                _directPlacementSupportSkip++;
            }
        }

        BlockState targetState = mod.getWorld().getBlockState(_target);
        if (!_useThrowaways
                && targetState.getBlock() != Blocks.AIR
                && targetState.getBlock() != Blocks.WATER
                && targetState.getBlock() != Blocks.LAVA
                && !ArrayUtils.contains(_toPlace, targetState.getBlock())) {
            DebugLogger.getInstance().logImmediate("PLACE-BLOCK",
                    "clearing-wrong-block target=" + _target
                            + " current=" + targetState.getBlock()
                            + " desired=" + Arrays.toString(_toPlace));
            mod.getClientBaritone().getBuilderProcess().onLostControl();
            return new DestroyBlockTask(_target);
        }


        // Check if we're approaching our point. If we fail, wander for a bit.
        if (!_progressChecker.check(mod)) {
            _failCount++;
            if (!tryingAlternativeWay()) {
                Debug.logMessage("Failed to place, wandering timeout.");
                return _wanderTask;
            } else {
                Debug.logMessage("Trying alternative way of placing block...");
            }
        }


        // Place block
        if (tryingAlternativeWay()) {
            BlockPos stand = findAdjacentStandPosition(mod);
            if (stand != null) {
                setDebugState("Alternative way: moving adjacent to place block.");
                return new GetToBlockTask(stand);
            }
            setDebugState("Alternative way: no adjacent stand found; staying put for builder retry.");
            return null;
        } else {
            if (_missingPlaceableThisBuild) {
                _missingPlaceableTicks++;
                _missingPlaceableThisBuild = false;
                mod.getClientBaritone().getBuilderProcess().onLostControl();
                DebugLogger.getInstance().logImmediate("PLACE-BLOCK",
                        "builder could not see requested block; target=" + _target
                                + " blocks=" + Arrays.toString(_toPlace)
                                + " missingTicks=" + _missingPlaceableTicks);
                if (_missingPlaceableTicks >= 3) {
                    _failCount++;
                    if (tryDirectInteractPlacement(mod)) {
                        _missingPlaceableTicks = 0;
                        setDebugState("Direct controller placement succeeded");
                        return null;
                    }
                    Task directPlacement = createDirectPlacementTask(mod);
                    if (directPlacement != null) {
                        _directPlacementTask = directPlacement;
                        _directPlacementTicks = 0;
                        _directPlacementAttempted = true;
                        _missingPlaceableTicks = 0;
                        setDebugState("Direct structure placement from adjacent support face");
                        return _directPlacementTask;
                    }
                    setDebugState("Missing placeable block in builder view, no adjacent stand found");
                    return null;
                }
                return null;
            }
            setDebugState("Letting baritone place a block.");
            // Perform baritone placement
            if (!mod.getClientBaritone().getBuilderProcess().isActive()) {
                Debug.logInternal("Run Structure Build");
                _missingPlaceableThisBuild = false;
                ISchematic schematic = new PlaceStructureSchematic(mod);
                mod.getClientBaritone().getBuilderProcess().build("structure", schematic, _target);
            }
        }
        return null;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        mod.getBehaviour().pop();
        mod.getClientBaritone().getBuilderProcess().onLostControl();
        _directPlacementTask = null;
        _directPlacementTicks = 0;
    }

    //TODO: Place structure where a leaf block was???? Might need to delete the block first if it's not empty/air/water.

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof PlaceBlockTask task) {
            return task._target.equals(_target) && task._useThrowaways == _useThrowaways && Arrays.equals(task._toPlace, _toPlace);
        }
        return false;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        assert MinecraftClient.getInstance().world != null;
        if (_useThrowaways) {
            return WorldHelper.isSolid(mod, _target);
        }
        BlockState state = mod.getWorld().getBlockState(_target);
        return ArrayUtils.contains(_toPlace, state.getBlock());
    }

    @Override
    protected String toDebugString() {
        return "Place structure" + ArrayUtils.toString(_toPlace) + " at " + _target.toShortString();
    }

    private boolean tryingAlternativeWay() {
        return _failCount % 4 == 3;
    }

    private int getExactMaterialCount(Belfegor mod) {
        if (_useThrowaways) {
            return getMaterialCount(mod);
        }
        int count = 0;
        for (Block block : _toPlace) {
            if (block == null || block.asItem() == Items.AIR) continue;
            count += mod.getItemStorage().getItemCount(block.asItem());
        }
        return count;
    }

    private Task getExactMaterialTask(int count) {
        if (_useThrowaways) {
            return getMaterialTask(count);
        }
        if (_toPlace.length == 1 && _toPlace[0] == Blocks.COBBLESTONE) {
            return TaskCatalogue.getItemTask("cobblestone", count);
        }
        if (_toPlace.length == 1 && _toPlace[0] == Blocks.DIRT) {
            return TaskCatalogue.getItemTask("dirt", count);
        }
        if (_toPlace.length == 1 && _toPlace[0].asItem() != Items.AIR) {
            return TaskCatalogue.getItemTask(_toPlace[0].asItem(), count);
        }
        return null;
    }

    private BlockPos findAdjacentStandPosition(Belfegor mod) {
        Direction[] directions = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        BlockPos player = mod.getPlayer() == null ? _target : mod.getPlayer().getBlockPos();
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Direction direction : directions) {
            BlockPos stand = _target.offset(direction);
            if (!WorldHelper.isSolid(mod, stand.down())) continue;
            if (!mod.getWorld().getBlockState(stand).isAir()) continue;
            if (!mod.getWorld().getBlockState(stand.up()).isAir()) continue;
            double distance = stand.getSquaredDistance(player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = stand;
            }
        }
        return best;
    }

    private Task createDirectPlacementTask(Belfegor mod) {
        if (_directPlacementAttempted || _useThrowaways || _toPlace.length != 1) return null;
        Block block = _toPlace[0];
        if (block == null || block.asItem() == Items.AIR) return null;
        if (mod.getItemStorage().getItemCount(block.asItem()) < 1) return null;

        Direction[] supportDirections = supportDirections(mod);
        int validSupportIndex = 0;
        for (Direction supportDirection : supportDirections) {
            BlockPos support = _target.offset(supportDirection);
            if (!WorldHelper.isSolid(mod, support)) continue;
            if (validSupportIndex++ < _directPlacementSupportSkip) continue;
            Direction faceTowardTarget = supportDirection.getOpposite();
            DebugLogger.getInstance().logImmediate("PLACE-BLOCK",
                    "direct-placement target=" + _target
                            + " block=" + block
                            + " support=" + support
                            + " face=" + faceTowardTarget
                            + " supportSkip=" + _directPlacementSupportSkip);
            return new InteractWithBlockTask(new ItemTarget(block.asItem(), 1),
                    faceTowardTarget, support, Input.CLICK_RIGHT, false, true);
        }
        DebugLogger.getInstance().logImmediate("PLACE-BLOCK",
                "direct-placement no support found; target=" + _target + " block=" + block);
        _directPlacementSupportSkip = 0;
        _directPlacementAttempted = false;
        return null;
    }

    private boolean tryDirectInteractPlacement(Belfegor mod) {
        if (_useThrowaways || _toPlace.length != 1) return false;
        Block block = _toPlace[0];
        if (block == null || block.asItem() == Items.AIR) return false;
        if (mod.getItemStorage().getItemCount(block.asItem()) < 1) return false;
        if (!mod.getSlotHandler().forceEquipItem(new ItemTarget(block.asItem(), 1), false)) return false;

        Direction[] directions = supportDirections(mod);
        int validSupportIndex = 0;
        for (Direction supportDirection : directions) {
            BlockPos support = _target.offset(supportDirection);
            if (!WorldHelper.isSolid(mod, support)) continue;
            if (validSupportIndex++ < _directPlacementSupportSkip) continue;
            Direction faceTowardTarget = supportDirection.getOpposite();
            Vec3d hit = Vec3d.ofCenter(support).add(
                    faceTowardTarget.getOffsetX() * 0.5,
                    faceTowardTarget.getOffsetY() * 0.5,
                    faceTowardTarget.getOffsetZ() * 0.5);
            BlockHitResult result = new BlockHitResult(hit, faceTowardTarget, support, false);
            mod.getInputControls().hold(Input.SNEAK);
            ActionResult action = mod.getController().interactBlock(mod.getPlayer(), Hand.MAIN_HAND, result);
            mod.getInputControls().release(Input.SNEAK);
            DebugLogger.getInstance().logImmediate("PLACE-BLOCK",
                    "direct-controller target=" + _target
                            + " block=" + block
                            + " support=" + support
                            + " face=" + faceTowardTarget
                            + " supportSkip=" + _directPlacementSupportSkip
                            + " action=" + action);
            if (action != ActionResult.FAIL) {
                mod.getPlayer().swingHand(Hand.MAIN_HAND);
                if (ArrayUtils.contains(_toPlace, mod.getWorld().getBlockState(_target).getBlock())) {
                    return true;
                }
                DebugLogger.getInstance().logImmediate("PLACE-BLOCK",
                        "direct-controller no-verify target=" + _target
                                + " expected=" + block
                                + " actual=" + mod.getWorld().getBlockState(_target).getBlock()
                                + " trying-next-support");
                _directPlacementSupportSkip++;
                return false;
            }
            return false;
        }
        _directPlacementSupportSkip = 0;
        _directPlacementAttempted = false;
        return false;
    }

    private Direction[] supportDirections(Belfegor mod) {
        if (mod.getPlayer() != null && _target.getY() <= mod.getPlayer().getBlockPos().getY()) {
            return new Direction[]{
                    Direction.NORTH,
                    Direction.SOUTH,
                    Direction.EAST,
                    Direction.WEST,
                    Direction.DOWN,
                    Direction.UP
            };
        }
        return new Direction[]{
                Direction.DOWN,
                Direction.NORTH,
                Direction.SOUTH,
                Direction.EAST,
                Direction.WEST,
                Direction.UP
        };
    }

    private class PlaceStructureSchematic extends AbstractSchematic {

        private final Belfegor _mod;

        public PlaceStructureSchematic(Belfegor mod) {
            super(1, 1, 1);
            _mod = mod;
        }

        @Override
        public BlockState desiredState(int x, int y, int z, BlockState blockState, List<BlockState> available) {
            if (x == 0 && y == 0 && z == 0) {
                // Place!!
                if (!available.isEmpty()) {
                    for (BlockState possible : available) {
                        if (possible == null) continue;
                        if (_useThrowaways && _mod.getClientBaritoneSettings().acceptableThrowawayItems.value.contains(possible.getBlock().asItem())) {
                            return possible;
                        }
                        if (Arrays.asList(_toPlace).contains(possible.getBlock())) {
                            return possible;
                        }
                    }
                }
                _missingPlaceableThisBuild = true;
                Debug.logInternal("Failed to find requested placeable block");
                return blockState;
            }
            // Don't care.
            return blockState;
        }
    }
}
