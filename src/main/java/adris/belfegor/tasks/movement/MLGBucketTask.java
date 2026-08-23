package adris.belfegor.tasks.movement;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.helpers.ConfigHelper;
import adris.belfegor.util.helpers.EntityHelper;
import adris.belfegor.util.helpers.ItemHelper;
import adris.belfegor.util.helpers.LookHelper;
import adris.belfegor.util.helpers.MathsHelper;
import adris.belfegor.util.helpers.StorageHelper;
import adris.belfegor.util.helpers.WorldHelper;
import adris.belfegor.util.serialization.ItemDeserializer;
import adris.belfegor.util.serialization.ItemSerializer;
import adris.belfegor.util.slots.PlayerSlot;
import adris.belfegor.util.time.TimerGame;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BedItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class MLGBucketTask extends Task {

    private static MLGClutchConfig _config;

    static {
        ConfigHelper.loadConfig("configs/mlg_clutch_settings.json", MLGClutchConfig::new, MLGClutchConfig.class, newConfig -> _config = newConfig);
    }

    /**
     * How a configured "clutch item" must actually be used mid-fall.
     * <p>
     * The old code treated every item like a water bucket (equip, right-click the
     * landing block once). That only works for a small handful of items, which is
     * why buckets and hay felt fine but beds, ladders, totems, pearls, etc. failed.
     */
    private enum ClutchMode {
        NONE,          // Cannot save us from a fall (e.g. sweet berries)
        WATER,         // Right-click the landing block to place water into the block we fall into
        FLUID_BLOCK,   // Powder snow: right-click the landing block so powder snow is where we land
        PLACE_TOP,     // Place a block onto the landing block's top face (hay, slime, honey, cobweb, scaffolding, twisting vines...)
        PLACE_SIDE,    // Place against a side face of the landing block, then steer into it (ladder, weeping vines)
        PLACE_BED,     // Place a 2-block bed, choosing a facing where the head has room
        EQUIP_OFFHAND, // Do not place; hold in the offhand and let it save us (totem of undying)
        THROW_PEARL    // Throw an ender pearl just before impact to teleport the fall away
    }

    // How far above the landing block we throw the ender pearl. Too low and the
    // pearl won't land before we hit the ground; too high and we may waste it.
    private static final double PEARL_THROW_MIN_HEIGHT = 9;
    private static final double PEARL_THROW_MAX_HEIGHT = 18;

    private BlockPos _placedPos; // Only set for water clutches; used by the fall chain to recollect the water.
    private BlockPos _clutchPlacedPos; // Where we attempted/placed the clutch (any mode).
    private BlockPos _movingTorwards;
    private Item _placedItem;
    private Direction _placedSide;
    private boolean _pearlThrown;
    private int _failedPlacementAttempts;
    private final TimerGame _placementRetryTimer = new TimerGame(0.2);
    private final Set<Item> _warnedNonClutchItems = new HashSet<>();

    private static boolean isLava(BlockPos pos) {
        assert MinecraftClient.getInstance().world != null;
        return MinecraftClient.getInstance().world.getBlockState(pos).getBlock() == Blocks.LAVA;
    }

    private static boolean lavaWillProtect(BlockPos pos) {
        assert MinecraftClient.getInstance().world != null;
        BlockState state = MinecraftClient.getInstance().world.getBlockState(pos);
        if (state.getBlock() == Blocks.LAVA) {
            int level = state.getFluidState().getLevel();
            return level == 0 || level >= _config.lavaLevelOrGreaterWillCancelFallDamage;
        }
        return false;
    }

    private static boolean isWater(BlockPos pos) {
        assert MinecraftClient.getInstance().world != null;
        return MinecraftClient.getInstance().world.getBlockState(pos).getBlock() == Blocks.WATER;
    }

    /**
     * Can we reach this block while falling, or will gravity pull us too far?
     */
    private static boolean canTravelToInAir(BlockPos pos) {
        Entity player = MinecraftClient.getInstance().player;
        if (player == null) return false;
        double verticalDist = player.getPos().getY() - pos.getY() - 1;
        double verticalVelocity = -1 * player.getVelocity().y;
        double grav = EntityHelper.ENTITY_GRAVITY;
        double movementSpeedPerTick = _config.averageHorizontalMovementSpeedPerTick; // Calculated, but also somewhat conservative
        // 1d projectile motion
        double ticksToTravelSq = (-verticalVelocity + Math.sqrt(verticalVelocity * verticalVelocity + 2 * grav * verticalDist)) / grav;
        double maxMoveDistanceSq = movementSpeedPerTick * movementSpeedPerTick * ticksToTravelSq * ticksToTravelSq;
        // We need to get within 1 block, so subtract a "radius" or something idk
        double horizontalDistance = WorldHelper.distanceXZ(player.getPos(), WorldHelper.toVec3d(pos)) - 0.8;
        if (horizontalDistance < 0)
            horizontalDistance = 0;
        return maxMoveDistanceSq > horizontalDistance * horizontalDistance;
    }

    private static boolean isFallDeadly(BlockPos pos) {
        PlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return false;
        double damage = calculateFallDamageToLandOn(pos);
        if (MinecraftClient.getInstance().world == null) return false;
        Block b = MinecraftClient.getInstance().world.getBlockState(pos).getBlock();
        if (b == Blocks.HAY_BLOCK || b == Blocks.HONEY_BLOCK) {
            damage *= 0.2f;
        } else if (b instanceof BedBlock) {
            // Beds cut fall damage in half and bounce you in 1.21+.
            damage *= 0.5f;
        } else if (b == Blocks.SLIME_BLOCK || b == Blocks.COBWEB || b == Blocks.POWDER_SNOW || b == Blocks.WATER) {
            // These fully cancel the fall.
            return false;
        }
        double resultingHealth = player.getHealth() - (float) damage;
        return resultingHealth < _config.preferLavaWhenFallDropsHealthBelowThreshold;
    }

    private static double calculateFallDamageToLandOn(BlockPos pos) {
        ClientWorld world = MinecraftClient.getInstance().world;
        PlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return 0;
        double totalFallDistance = player.fallDistance + (player.getY() - pos.getY() - 1);
        // Copied from living entity I think, somewhere idk you get the picture.
        double baseFallDamage = MathHelper.ceil(totalFallDistance - 3.0F);
        // Be a bit conservative, assume MORE damage
        if (world == null) return baseFallDamage;
        return EntityHelper.calculateResultingPlayerDamage(player, world.getDamageSources().fall(), baseFallDamage);
    }

    private static void moveLeftRight(Belfegor mod, int delta) {
        if (delta == 0) {
            mod.getInputControls().release(Input.MOVE_LEFT);
            mod.getInputControls().release(Input.MOVE_RIGHT);
        } else if (delta > 0) {
            mod.getInputControls().release(Input.MOVE_LEFT);
            mod.getInputControls().hold(Input.MOVE_RIGHT);
        } else {
            mod.getInputControls().hold(Input.MOVE_LEFT);
            mod.getInputControls().release(Input.MOVE_RIGHT);
        }
    }

    private static void moveForwardBack(Belfegor mod, int delta) {
        if (delta == 0) {
            mod.getInputControls().release(Input.MOVE_FORWARD);
            mod.getInputControls().release(Input.MOVE_BACK);
        } else if (delta > 0) {
            mod.getInputControls().hold(Input.MOVE_FORWARD);
            mod.getInputControls().release(Input.MOVE_BACK);
        } else {
            mod.getInputControls().release(Input.MOVE_FORWARD);
            mod.getInputControls().hold(Input.MOVE_BACK);
        }
    }

    // -------------------------------------------------------------------------
    // Clutch item classification
    // -------------------------------------------------------------------------

    private static ClutchMode getClutchMode(Item item) {
        if (item == Items.WATER_BUCKET) return ClutchMode.WATER;
        if (item == Items.POWDER_SNOW_BUCKET) return ClutchMode.FLUID_BLOCK;
        if (item == Items.LADDER) return ClutchMode.PLACE_SIDE;
        if (item == Items.WEEPING_VINES) return ClutchMode.PLACE_SIDE;
        if (item == Items.TOTEM_OF_UNDYING) return ClutchMode.EQUIP_OFFHAND;
        if (item == Items.ENDER_PEARL) return ClutchMode.THROW_PEARL;
        if (item == Items.SWEET_BERRIES) return ClutchMode.NONE;
        if (item instanceof BedItem) return ClutchMode.PLACE_BED;
        if (item instanceof BlockItem) return ClutchMode.PLACE_TOP;
        return ClutchMode.NONE;
    }

    /**
     * Higher is better. We always use the best clutch item we actually have,
     * regardless of the order items appear in the config.
     */
    private static int getClutchScore(ClutchMode mode, Item item) {
        switch (mode) {
            case WATER:
                return 100;
            case FLUID_BLOCK:
                return 90;
            case PLACE_TOP:
                if (item == Items.SLIME_BLOCK) return 85;
                if (item == Items.COBWEB) return 80;
                if (item == Items.HONEY_BLOCK) return 75;
                if (item == Items.HAY_BLOCK) return 70;
                if (item == Items.SCAFFOLDING) return 60;
                if (item == Items.TWISTING_VINES) return 55;
                // Unknown block item: we'll still try, but it probably isn't a real clutch.
                return 5;
            case PLACE_SIDE:
                return 50;
            case PLACE_BED:
                return 40;
            case EQUIP_OFFHAND:
                return 30;
            case THROW_PEARL:
                return 25;
            default:
                return -1;
        }
    }

    private Optional<Item> getBestClutchItem(Belfegor mod) {
        Item best = null;
        int bestScore = -1;
        if (!mod.getWorld().getDimension().ultrawarm() && mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
            best = Items.WATER_BUCKET;
            bestScore = getClutchScore(ClutchMode.WATER, Items.WATER_BUCKET);
        }
        if (_config != null) {
            for (Item candidate : _config.clutchItems) {
                if (candidate == null || !mod.getItemStorage().hasItem(candidate)) continue;
                ClutchMode mode = getClutchMode(candidate);
                if (mode == ClutchMode.NONE) {
                    if (_warnedNonClutchItems.add(candidate)) {
                        Debug.logWarning("MLG: " + ItemHelper.stripItemName(candidate) + " cannot save you from fall damage, ignoring it as a clutch item.");
                    }
                    continue;
                }
                int score = getClutchScore(mode, candidate);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private boolean configHasClutchItem(Item item) {
        return _config != null && _config.clutchItems.contains(item);
    }

    private static Block getBlockForItem(Item item) {
        return item instanceof BlockItem blockItem ? blockItem.getBlock() : null;
    }

    private static float getYawForDirection(Direction dir) {
        // Vanilla yaw: 0 = south, 90 = west, 180 = north, -90 = east.
        return switch (dir) {
            case SOUTH -> 0f;
            case WEST -> 90f;
            case NORTH -> 180f;
            default -> -90f;
        };
    }

    private static Direction directionFromYaw(float yaw) {
        return switch (Math.floorMod(Math.round(yaw / 90f), 4)) {
            case 0 -> Direction.SOUTH;
            case 1 -> Direction.WEST;
            case 2 -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    private Direction getBestSideDirection(Belfegor mod, BlockPos toPlaceOn) {
        Direction prefer = directionFromYaw(LookHelper.getLookRotation().getYaw());
        Direction[] order = new Direction[]{prefer, prefer.getOpposite(), prefer.rotateYClockwise(), prefer.rotateYCounterclockwise()};
        for (Direction d : order) {
            BlockPos cell = toPlaceOn.offset(d);
            BlockState s = mod.getWorld().getBlockState(cell);
            if (s.isAir() || s.getBlock() == Blocks.WATER) return d;
        }
        return prefer;
    }

    private Direction getBestBedFacing(Belfegor mod, BlockPos foot) {
        BlockState footState = mod.getWorld().getBlockState(foot);
        if (!footState.isAir() && !footState.isReplaceable()) return null;
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos head = foot.offset(d);
            BlockState s = mod.getWorld().getBlockState(head);
            if (s.isAir() || s.isReplaceable()) return d;
        }
        return null;
    }

    private boolean isUsableClutchBlock(Belfegor mod, Item item, BlockPos pos) {
        Block b = mod.getWorld().getBlockState(pos).getBlock();
        if (item == Items.WATER_BUCKET) return b == Blocks.WATER;
        if (item == Items.POWDER_SNOW_BUCKET) return b == Blocks.POWDER_SNOW;
        if (item instanceof BedItem) return b instanceof BedBlock;
        Block expected = getBlockForItem(item);
        return expected != null && b == expected;
    }

    private void equipTotemInOffhand(Belfegor mod) {
        if (StorageHelper.getItemStackInSlot(PlayerSlot.OFFHAND_SLOT).getItem() == Items.TOTEM_OF_UNDYING) {
            return;
        }
        if (mod.getItemStorage().hasItem(Items.TOTEM_OF_UNDYING)) {
            mod.getSlotHandler().forceEquipItemToOffhand(Items.TOTEM_OF_UNDYING);
        }
    }

    // -------------------------------------------------------------------------
    // Per-tick logic
    // -------------------------------------------------------------------------

    private Task onTickInternal(Belfegor mod, BlockPos oldMovingTorwards) {
        Optional<BlockPos> willLandOn = getBlockWeWillLandOn(mod);
        Optional<BlockPos> bestClutchPos = getBestConeClutchBlock(mod, oldMovingTorwards);
        // Move torwards our best "clutch" position
        if (bestClutchPos.isPresent()) {
            _movingTorwards = bestClutchPos.get().mutableCopy();
            if (!_movingTorwards.equals(oldMovingTorwards)) {
                if (oldMovingTorwards == null)
                    Debug.logMessage("(NEW clutch target: " + _movingTorwards + ")");
                else
                    Debug.logMessage("(changed clutch target: " + _movingTorwards + ")");
            }
        } else if (oldMovingTorwards != null) {
            Debug.logMessage("(LOST clutch position!)");
        }
        if (willLandOn.isPresent()) {
            Task result = placeMLGBucketTask(mod, willLandOn.get());
            handleJumpForLand(mod, willLandOn.get());
            return result;
        } else {
            setDebugState("Wait for it...");
            // We must trigger jump as soon as we enter a "climbable" object
            mod.getInputControls().release(Input.JUMP);
            return null;
        }
    }

    private Task placeMLGBucketTask(Belfegor mod, BlockPos toPlaceOn) {
        Optional<Item> clutch = getBestClutchItem(mod);
        if (clutch.isEmpty()) {
            setDebugState("No clutch item");
            return null;
        }

        // If our raycast hit a non-solid block, go DOWN one.
        if (!WorldHelper.isSolid(mod, toPlaceOn)) {
            toPlaceOn = toPlaceOn.down();
        }
        BlockPos willLandIn = toPlaceOn.up();

        // If we're falling into water, we're already safe. Do nothing.
        BlockState willLandInState = mod.getWorld().getBlockState(willLandIn);
        if (willLandInState.getBlock() == Blocks.WATER) {
            setDebugState("Waiting to fall into water");
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            return null;
        }

        Item item = clutch.get();
        ClutchMode mode = getClutchMode(item);

        // If the block we would fall into is already a usable clutch block, do
        // NOT stack another one on top (this can happen when the fall chain
        // re-triggers after a slime/honey bounce).
        if (mode == ClutchMode.PLACE_TOP || mode == ClutchMode.PLACE_BED || mode == ClutchMode.FLUID_BLOCK) {
            if (isUsableClutchBlock(mod, item, willLandIn)) {
                setDebugState("Clutch block already below, waiting to land");
                mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
                return null;
            }
        }

        // Totem of undying is a passive save: keep it in the offhand while we use
        // the best real clutch item in our hands. Two safety nets are better than one.
        if (mode != ClutchMode.EQUIP_OFFHAND
                && configHasClutchItem(Items.TOTEM_OF_UNDYING)
                && (mod.getItemStorage().hasItem(Items.TOTEM_OF_UNDYING) || mod.getItemStorage().hasItemInOffhand(Items.TOTEM_OF_UNDYING))) {
            equipTotemInOffhand(mod);
        }

        switch (mode) {
            case EQUIP_OFFHAND:
                equipTotemInOffhand(mod);
                setDebugState("Totem clutch ready");
                return null;
            case THROW_PEARL:
                return tryThrowEnderPearl(mod, toPlaceOn);
            case WATER:
            case FLUID_BLOCK:
            case PLACE_TOP:
            case PLACE_SIDE:
            case PLACE_BED:
                return placeClutchBlock(mod, item, mode, toPlaceOn, willLandIn);
            default:
                setDebugState("Clutch item unusable");
                return null;
        }
    }

    private Task placeClutchBlock(Belfegor mod, Item item, ClutchMode mode, BlockPos toPlaceOn, BlockPos willLandIn) {
        // Side clutches are placed next to the landing block; we need to steer into
        // that cell while falling so we actually grab the ladder/vine.
        if (mode == ClutchMode.PLACE_SIDE) {
            if (_placedSide == null) {
                _placedSide = getBestSideDirection(mod, toPlaceOn);
            }
            _movingTorwards = toPlaceOn.offset(_placedSide).mutableCopy();
        }

        // Beds need a facing chosen before we aim at anything.
        if (mode == ClutchMode.PLACE_BED && _placedSide == null) {
            _placedSide = getBestBedFacing(mod, willLandIn);
            if (_placedSide == null) {
                setDebugState("No room for a bed head");
                return null;
            }
        }

        // Already placed? Then just wait; do NOT keep clicking (that would stack
        // blocks upward into the player).
        if (_placedItem == item && _clutchPlacedPos != null && clutchPlacedAt(mod, item, _clutchPlacedPos)) {
            setDebugState("Clutch placed, waiting to land");
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            return null;
        }

        Optional<Rotation> reach = getClutchReach(mod, mode, toPlaceOn);
        if (reach.isEmpty()) {
            setDebugState("Waiting to reach target block...");
            return null;
        }

        setDebugState("Performing MLG");
        LookHelper.lookAt(mod, reach.get());
        if (!mod.getSlotHandler().forceEquipItem(item)) {
            setDebugState("Failed to equip " + ItemHelper.stripItemName(item));
            return null;
        }

        if (!_placementRetryTimer.elapsed()) {
            return null;
        }
        _placementRetryTimer.reset();
        _failedPlacementAttempts++;
        _placedItem = item;
        _clutchPlacedPos = getPlacementTargetPos(mode, toPlaceOn, willLandIn);
        if (mode == ClutchMode.WATER) {
            _placedPos = _clutchPlacedPos;
        }
        mod.getInputControls().tryPress(Input.CLICK_RIGHT);
        Debug.logMessage("MLG clutch: " + ItemHelper.stripItemName(item) + " attempt #" + _failedPlacementAttempts + " at " + _clutchPlacedPos);
        return null;
    }

    private Optional<Rotation> getClutchReach(Belfegor mod, ClutchMode mode, BlockPos toPlaceOn) {
        switch (mode) {
            case PLACE_SIDE:
                if (_placedSide == null) return Optional.empty();
                return LookHelper.getReach(toPlaceOn, _placedSide);
            case PLACE_BED:
                if (_placedSide == null) return Optional.empty();
                Optional<Rotation> bedReach = LookHelper.getReach(toPlaceOn, Direction.UP);
                if (bedReach.isEmpty()) return Optional.empty();
                // The bed's head extends in the direction the player faces when clicking.
                return Optional.of(new Rotation(getYawForDirection(_placedSide), bedReach.get().getPitch()));
            default:
                return LookHelper.getReach(toPlaceOn, Direction.UP);
        }
    }

    private BlockPos getPlacementTargetPos(ClutchMode mode, BlockPos toPlaceOn, BlockPos willLandIn) {
        if (mode == ClutchMode.PLACE_SIDE && _placedSide != null) {
            return toPlaceOn.offset(_placedSide);
        }
        return willLandIn;
    }

    private boolean clutchPlacedAt(Belfegor mod, Item item, BlockPos pos) {
        if (item == Items.WATER_BUCKET) {
            return mod.getWorld().getBlockState(pos).getBlock() == Blocks.WATER;
        }
        if (item == Items.POWDER_SNOW_BUCKET) {
            return mod.getWorld().getBlockState(pos).getBlock() == Blocks.POWDER_SNOW;
        }
        if (item instanceof BedItem) {
            if (mod.getWorld().getBlockState(pos).getBlock() instanceof BedBlock) return true;
            return _placedSide != null && mod.getWorld().getBlockState(pos.offset(_placedSide)).getBlock() instanceof BedBlock;
        }
        Block expected = getBlockForItem(item);
        return expected != null && mod.getWorld().getBlockState(pos).getBlock() == expected;
    }

    private Task tryThrowEnderPearl(Belfegor mod, BlockPos toPlaceOn) {
        if (_pearlThrown) {
            setDebugState("Pearl thrown, waiting for teleport");
            return null;
        }
        double verticalDist = mod.getPlayer().getY() - (toPlaceOn.getY() + 1.0);
        if (verticalDist > PEARL_THROW_MAX_HEIGHT || verticalDist < PEARL_THROW_MIN_HEIGHT) {
            setDebugState("Waiting for pearl throw range");
            return null;
        }
        if (!mod.getSlotHandler().forceEquipItem(Items.ENDER_PEARL)) {
            setDebugState("No ender pearl");
            return null;
        }
        Optional<Rotation> reach = LookHelper.getReach(toPlaceOn, Direction.UP);
        float yaw = reach.map(Rotation::getYaw).orElse(LookHelper.getLookRotation().getYaw());
        // Throw steeply downward so the pearl lands on/near the landing block a
        // couple of ticks before we do.
        float pitch = Math.max(75f, reach.map(Rotation::getPitch).orElse(90f));
        LookHelper.lookAt(mod, new Rotation(yaw, pitch));
        mod.getInputControls().tryPress(Input.CLICK_RIGHT);
        _pearlThrown = true;
        Debug.logMessage("MLG: throwing ender pearl toward " + toPlaceOn);
        return null;
    }

    /**
     * We will land in this block, handle our jump.
     * <p>
     * Climbable blocks (ladders, vines, scaffolding) require pressing space ONLY
     * while we're inside them so we grab on and climb instead of falling.
     */
    private void handleJumpForLand(Belfegor mod, BlockPos willLandOn) {
        PlayerEntity player = mod.getPlayer();
        if (player.isClimbing()) {
            mod.getInputControls().hold(Input.JUMP);
            return;
        }
        BlockPos willLandIn = WorldHelper.isSolid(mod, willLandOn) ? willLandOn.up() : willLandOn;
        BlockState s = mod.getWorld().getBlockState(willLandIn);
        if (s.getBlock() == Blocks.LAVA) {
            // ALWAYS hold jump for lava
            mod.getInputControls().hold(Input.JUMP);
            return;
        }
        // No point in jumping into fluids or powder snow; we just fall safely.
        if (!s.getFluidState().isEmpty() || s.getBlock() == Blocks.POWDER_SNOW) {
            mod.getInputControls().release(Input.JUMP);
            return;
        }
        // Slime bounces us automatically; jumping on contact bounces us higher.
        if (s.getBlock() == Blocks.SLIME_BLOCK) {
            double feetToTop = willLandIn.getY() + 1.0 - player.getY();
            if (feetToTop > -0.5 && feetToTop < 3.0) {
                mod.getInputControls().hold(Input.JUMP);
            } else {
                mod.getInputControls().release(Input.JUMP);
            }
            return;
        }
        Box blockBounds;
        try {
            blockBounds = s.getCollisionShape(mod.getWorld(), willLandIn).getBoundingBox();
        } catch (UnsupportedOperationException ex) {
            blockBounds = Box.of(WorldHelper.toVec3d(willLandIn), 1, 1, 1);
        }
        boolean inside = player.getBoundingBox().intersects(blockBounds);
        if (inside)
            mod.getInputControls().hold(Input.JUMP);
        else
            mod.getInputControls().release(Input.JUMP);
    }

    @Override
    protected Task onTick(Belfegor mod) {
        // ALWAYS faster
        mod.getInputControls().hold(Input.SPRINT);
        // Check AROUND player instead of directly under.
        // We may crop the edge of a block or wall.
        BlockPos oldMovingTorwards = _movingTorwards != null ? _movingTorwards.mutableCopy() : null;
        _movingTorwards = null;
        Task result = onTickInternal(mod, oldMovingTorwards);

        handleForwardVelocity(mod, !Objects.equals(oldMovingTorwards, _movingTorwards));
        handleCancellingSidewaysVelocity(mod);

        return result;
    }

    private void handleForwardVelocity(Belfegor mod, boolean newForwardTarget) {
        if (mod.getPlayer().isOnGround() || _movingTorwards == null || WorldHelper.inRangeXZ(mod.getPlayer(), _movingTorwards, 0.05f)) {
            moveForwardBack(mod, 0);
            return;
        }
        Rotation look = LookHelper.getLookRotation();
        look = new Rotation(look.getYaw(), 0);
        Vec3d forwardFacing = LookHelper.toVec3d(look).multiply(1, 0, 1).normalize();
        Vec3d delta = WorldHelper.toVec3d(_movingTorwards).subtract(mod.getPlayer().getPos()).multiply(1, 0, 1);
        Vec3d velocity = mod.getPlayer().getVelocity().multiply(1, 0, 1);
        Vec3d pd = delta.subtract(velocity.multiply(3f));
        double forwardStrength = pd.dotProduct(forwardFacing);
        if (newForwardTarget) {
            LookHelper.lookAt(mod, _movingTorwards);
        }
        Debug.logInternal("F:" + forwardStrength);
        moveForwardBack(mod, (int) Math.signum(forwardStrength));
    }

    @Override
    protected void onStart(Belfegor mod) {
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        _placedPos = null;
        _clutchPlacedPos = null;
        _placedItem = null;
        _placedSide = null;
        _pearlThrown = false;
        _failedPlacementAttempts = 0;
        _placementRetryTimer.forceElapse();
        // hold shift while falling.
        // Look down at first, might help
        mod.getPlayer().setPitch(90);
    }

    private Optional<BlockPos> getBlockWeWillLandOn(Belfegor mod) {
        Vec3d velCheck = mod.getPlayer().getVelocity();
        Box b = mod.getPlayer().getBoundingBox().offset(velCheck);
        Vec3d c = b.getCenter();
        Vec3d[] coords = new Vec3d[]{
                c,
                new Vec3d(b.minX, c.y, b.minZ),
                new Vec3d(b.maxX, c.y, b.minZ),
                new Vec3d(b.minX, c.y, b.maxZ),
                new Vec3d(b.maxX, c.y, b.maxZ),
        };
        BlockHitResult result = null;
        double bestSqDist = Double.POSITIVE_INFINITY;
        for (Vec3d rayOrigin : coords) {
            RaycastContext rctx = castDown(rayOrigin);
            BlockHitResult hit = mod.getWorld().raycast(rctx);
            if (hit.getType() == HitResult.Type.BLOCK) {
                double curDis = hit.getPos().squaredDistanceTo(rayOrigin);
                if (curDis < bestSqDist) {
                    result = hit;
                    bestSqDist = curDis;
                }
            }
        }

        if (result == null || result.getType() != HitResult.Type.BLOCK) {
            return Optional.empty();
        }
        return Optional.ofNullable(result.getBlockPos());
    }

    /**
     * While falling to a target, we look towards the center and press forwards.
     * However, if we change our direction we end up moving sideways with respect to our look direction, which
     * often messes us up.
     * <p>
     * This will nudge the bot left/right so we're no longer "slipping" to the side.
     */
    private void handleCancellingSidewaysVelocity(Belfegor mod) {
        if (_movingTorwards == null) {
            moveLeftRight(mod, 0);
            return;
        }
        // Cancel our left/right velocity with respect to block
        Vec3d velocity = mod.getPlayer().getVelocity();
        Vec3d deltaTarget = WorldHelper.toVec3d(_movingTorwards).subtract(mod.getPlayer().getPos());
        // "right" velocity relative to delta
        Rotation look = LookHelper.getLookRotation();
        Vec3d forwardFacing = LookHelper.toVec3d(look).multiply(1, 0, 1).normalize();
        Vec3d rightVelocity = MathsHelper.projectOntoPlane(velocity, forwardFacing).multiply(1, 0, 1); // Flatten
        // Also consider how much further to the right we should move
        Vec3d rightDelta = MathsHelper.projectOntoPlane(deltaTarget, forwardFacing).multiply(1, 0, 1);
        // Do a little PD loop
        Vec3d pd = rightDelta.subtract(rightVelocity.multiply(2));
        // We're traveling too fast sideways
        Vec3d faceRight = forwardFacing.crossProduct(new Vec3d(0, 1, 0));
        boolean moveRight = pd.dotProduct(faceRight) > 0;
        if (moveRight) {
            moveLeftRight(mod, 1);
        } else {
            moveLeftRight(mod, -1);
        }
    }

    private Optional<BlockPos> getBestConeClutchBlock(Belfegor mod, BlockPos oldClutchTarget) {
        double pitchHalfWidth = _config.epicClutchConePitchAngle;
        double dpitchStart = pitchHalfWidth / _config.epicClutchConePitchResolution;

        // Our priority is:
        // - Safe to land (water)
        // - Highest block
        // IF WE HAVE MLG
        // - Closer to player

        ConeClutchContext cctx = new ConeClutchContext(mod);

        // Always check our previous best so we don't lose it
        if (oldClutchTarget != null)
            cctx.checkBlock(mod, oldClutchTarget);

        // Perform cone
        for (double pitch = dpitchStart; pitch <= pitchHalfWidth; pitch += pitchHalfWidth / _config.epicClutchConePitchResolution) {
            double pitchProgress = (pitch - dpitchStart) / (pitchHalfWidth - dpitchStart);
            double yawResolution = _config.epicClutchConeYawDivisionStart + pitchProgress * (_config.epicClutchConeYawDivisionEnd - _config.epicClutchConeYawDivisionStart); // lerp from start to end
            for (double yaw = 0; yaw < 360; yaw += 360.0 / yawResolution) {
                RaycastContext rctx = castCone(yaw, pitch);
                cctx.checkRay(mod, rctx);
            }
        }

        // Perform NEARBY sweep
        //int nearbySweepSize =
        Vec3d center = mod.getPlayer().getPos();
        for (int dx = -2; dx <= 2; ++dx) {
            for (int dz = -2; dz <= 2; ++dz) {
                RaycastContext ctx = castDown(center.add(dx, 0, dz));
                cctx.checkRay(mod, ctx);
            }
        }

        return Optional.ofNullable(cctx.bestBlock);
    }

    private RaycastContext castDown(Vec3d origin) {
        Entity player = MinecraftClient.getInstance().player;
        if (player == null) return null;
        return new RaycastContext(origin, origin.add(0, -1 * _config.castDownDistance, 0), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, player);
    }

    private RaycastContext castCone(double yaw, double pitch) {
        Entity player = MinecraftClient.getInstance().player;
        if (player == null) return null;
        Vec3d origin = player.getPos();
        double dy = _config.epicClutchConeCastHeight;
        double dH = dy * Math.sin(Math.toRadians(pitch)); // horizontal distance
        double yawRad = Math.toRadians(yaw);
        double dx = dH * Math.cos(yawRad);
        double dz = dH * Math.sin(yawRad);
        Vec3d end = origin.add(dx, -1 * dy, dz);
        return new RaycastContext(origin, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, player);
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        _movingTorwards = null;
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        moveLeftRight(mod, 0);
        moveForwardBack(mod, 0);
        mod.getInputControls().release(Input.SPRINT);
        mod.getInputControls().release(Input.JUMP);
    }

    private boolean hasClutchItem(Belfegor mod) {
        return getBestClutchItem(mod).isPresent();
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return mod.getPlayer().isSwimming() || mod.getPlayer().isTouchingWater() || mod.getPlayer().isOnGround() || mod.getPlayer().isClimbing();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof MLGBucketTask;
    }

    @Override
    protected String toDebugString() {
        String result = "Epic gaemer moment";
        if (_movingTorwards != null) {
            result += " (CLUTCH AT: " + _movingTorwards + ")";
        }
        if (_placedItem != null) {
            result += " (using " + ItemHelper.stripItemName(_placedItem) + ")";
        }
        return result;
    }

    public BlockPos getWaterPlacedPos() {
        return _placedPos;
    }

    private static class MLGClutchConfig {
        public double castDownDistance = 40;
        public double averageHorizontalMovementSpeedPerTick = 0.25; // How "far" the player moves horizontally per tick. Set too low and the bot will ignore viable clutches. Set too high and the bot will go for clutches it can't reach.
        public double epicClutchConeCastHeight = 40; // How high the "epic clutch" ray cone is
        public double epicClutchConePitchAngle = 25; // How wide (degrees) the "epic clutch" ray cone is
        public int epicClutchConePitchResolution = 8; // How many divisions in each direction the cone's pitch has
        public int epicClutchConeYawDivisionStart = 6; // How many divisions to start the cone clutch at in the center
        public int epicClutchConeYawDivisionEnd = 20; // How many divisions to move the cone clutch at torwars the end
        public int preferLavaWhenFallDropsHealthBelowThreshold = 3; // If a fall results in our player's health going below this value, consider it deadly.
        public int lavaLevelOrGreaterWillCancelFallDamage = 5; // Lava at this level will cancel our fall damage if we hold space.
        @JsonSerialize(using = ItemSerializer.class)
        @JsonDeserialize(using = ItemDeserializer.class)
        public List<Item> clutchItems = List.of(Items.HAY_BLOCK, Items.TWISTING_VINES);
    }

    class ConeClutchContext {
        private final boolean hasClutchItem;
        public BlockPos bestBlock = null;
        private double highestY = Double.NEGATIVE_INFINITY;
        private double closestXZ = Double.POSITIVE_INFINITY;
        private boolean bestBlockIsSafe = false;
        private boolean bestBlockIsDeadlyFall = false;
        private boolean bestBlockIsLava = false;

        public ConeClutchContext(Belfegor mod) {
            hasClutchItem = hasClutchItem(mod);
        }

        public void checkBlock(Belfegor mod, BlockPos check) {
            // Already checked
            if (Objects.equals(bestBlock, check))
                return;
            if (WorldHelper.isAir(mod, check)) {
                Debug.logMessage("(MLG Air block checked for landing, the block broke. We'll try another): " + check);
                return;
            }
            boolean lava = isLava(check);
            boolean lavaWillProtect = lava && lavaWillProtect(check);
            boolean water = isWater(check);
            boolean isDeadlyFall = !hasClutchItem && isFallDeadly(check);
            // Prioritize safe blocks ALWAYS
            if (bestBlockIsSafe && !water)
                return;
            double height = check.getY();
            double distSqXZ = WorldHelper.distanceXZSquared(WorldHelper.toVec3d(check), mod.getPlayer().getPos());
            boolean highestSoFar = height > highestY;
            boolean closestSoFar = distSqXZ < closestXZ;
            // We found a new contender
            if (
                    bestBlock == null || // No target was found.
                            (water && !bestBlockIsSafe) || // We ALWAYS land in water if we can
                            (lava && lavaWillProtect && bestBlockIsDeadlyFall && !hasClutchItem) || // Land in lava if our best alternative is death by fall damage
                            (!lava && !isDeadlyFall && ((closestSoFar && hasClutchItem) && highestSoFar || bestBlockIsLava)) // If it's not lava and is not deadly, land on it if it's higher than before OR if our best alternative is lava
            ) {
                if (canTravelToInAir((lava || water) ? check.down() : check)) {
                    if (highestSoFar) {
                        highestY = height;
                    }
                    if (closestSoFar) {
                        closestXZ = distSqXZ;
                    }
                    bestBlockIsSafe = water;
                    bestBlockIsDeadlyFall = isDeadlyFall;
                    bestBlockIsLava = lava;
                    bestBlock = check;
                }
            }
        }

        public void checkRay(Belfegor mod, RaycastContext rctx) {
            BlockHitResult hit = mod.getWorld().raycast(rctx);
            if (hit.getType() == HitResult.Type.BLOCK) {
                BlockPos check = hit.getBlockPos();
                // For now, REQUIRE we land on this
                if (hit.getSide().getOffsetY() <= 0)
                    return;
                checkBlock(mod, check);
            }
        }
    }

}
