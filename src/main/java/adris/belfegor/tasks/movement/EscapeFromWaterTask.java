package adris.belfegor.tasks.movement;

import adris.belfegor.Belfegor;
import adris.belfegor.debug.DebugLogger;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.helpers.WorldHelper;
import baritone.api.utils.input.Input;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;

/**
 * Emergency ocean/water recovery.
 *
 * Baritone can spend a long time trying to path out of water, especially when
 * the command starts in an ocean or beside a water wall. This task uses the
 * Minecraft scaffolding motion humans use: hold jump/swim, look down, equip a
 * disposable solid block, and repeatedly place below/at the player's feet until
 * the bot has footing outside water.
 */
public class EscapeFromWaterTask extends Task {

    private static final Item[] PILLAR_ITEMS = new Item[]{
            Items.COBBLESTONE,
            Items.COBBLED_DEEPSLATE,
            Items.DIRT,
            Items.NETHERRACK,
            Items.STONE,
            Items.OAK_PLANKS,
            Items.SPRUCE_PLANKS,
            Items.BIRCH_PLANKS
    };

    private static final int MAX_TICKS = 220;
    private int _ticks;
    private int _placeAttempts;

    @Override
    protected void onStart(Belfegor mod) {
        _ticks = 0;
        _placeAttempts = 0;
        mod.getBehaviour().push();
        mod.getClientBaritone().getPathingBehavior().cancelEverything();
        DebugLogger.getInstance().logImmediate("WATER-ESCAPE",
                "start pos=" + (mod.getPlayer() == null ? "null" : mod.getPlayer().getBlockPos()));
    }

    @Override
    protected Task onTick(Belfegor mod) {
        _ticks++;
        if (mod.getPlayer() == null || mod.getWorld() == null) return null;

        BlockPos feet = mod.getPlayer().getBlockPos();
        boolean touchingWater = mod.getPlayer().isTouchingWater()
                || isWater(mod, feet)
                || isWater(mod, feet.down());

        if (!touchingWater && (mod.getPlayer().isOnGround() || WorldHelper.isSolid(mod, feet.down()))) {
            setDebugState("Escaped water");
            return null;
        }

        mod.getClientBaritone().getPathingBehavior().cancelEverything();
        mod.getInputControls().hold(Input.JUMP);
        mod.getInputControls().forceLook(mod.getPlayer().getYaw(), 90);

        Item equip = firstAvailableBlock(mod);
        if (equip == null) {
            setDebugState("Swimming up; no pillar blocks available");
            return null;
        }
        if (!mod.getSlotHandler().forceEquipItem(equip)) {
            setDebugState("Equipping water escape block " + equip);
            return null;
        }

        if (_ticks % 4 == 0) {
            _placeAttempts++;
            tryPlaceAt(mod, feet.down(), Direction.UP);
            tryPlaceAt(mod, feet, Direction.UP);
            DebugLogger.getInstance().log("WATER-ESCAPE",
                    "pillar-attempt tick=" + _ticks
                            + " attempt=" + _placeAttempts
                            + " feet=" + feet.toShortString()
                            + " item=" + equip
                            + " touchingWater=" + touchingWater
                            + " air=" + mod.getPlayer().getAir() + "/" + mod.getPlayer().getMaxAir());
        }

        setDebugState("Pillaring/swimming out of water");
        return null;
    }

    private ActionResult tryPlaceAt(Belfegor mod, BlockPos pos, Direction face) {
        if (pos == null || mod.getPlayer() == null || mod.getWorld() == null) return ActionResult.FAIL;
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        if (WorldHelper.isSolid(mod, pos) && block != Blocks.WATER && block != Blocks.LAVA) {
            return ActionResult.PASS;
        }
        Vec3d hit = Vec3d.ofCenter(pos).add(0, 0.5, 0);
        BlockHitResult result = new BlockHitResult(hit, face, pos, false);
        ActionResult action = mod.getController().interactBlock(mod.getPlayer(), Hand.MAIN_HAND, result);
        if (action != ActionResult.FAIL) {
            mod.getPlayer().swingHand(Hand.MAIN_HAND);
        }
        return action;
    }

    private Item firstAvailableBlock(Belfegor mod) {
        return Arrays.stream(PILLAR_ITEMS)
                .filter(item -> mod.getItemStorage().hasItem(item))
                .findFirst()
                .orElse(null);
    }

    private boolean isWater(Belfegor mod, BlockPos pos) {
        if (mod.getWorld() == null || pos == null) return false;
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        return block == Blocks.WATER || mod.getWorld().getBlockState(pos).getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER);
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        if (mod.getPlayer() == null || mod.getWorld() == null) return true;
        BlockPos feet = mod.getPlayer().getBlockPos();
        boolean touchingWater = mod.getPlayer().isTouchingWater()
                || isWater(mod, feet)
                || isWater(mod, feet.down());
        return (!touchingWater && (mod.getPlayer().isOnGround() || WorldHelper.isSolid(mod, feet.down())))
                || _ticks > MAX_TICKS;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        mod.getInputControls().release(Input.JUMP);
        if (mod.getClientBaritone() != null) {
            mod.getClientBaritone().getBuilderProcess().onLostControl();
            mod.getClientBaritone().getPathingBehavior().cancelEverything();
        }
        mod.getBehaviour().pop();
        DebugLogger.getInstance().logImmediate("WATER-ESCAPE",
                "stop ticks=" + _ticks
                        + " attempts=" + _placeAttempts
                        + " interruptedBy=" + (interruptTask == null ? "clean" : interruptTask.toString()));
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof EscapeFromWaterTask;
    }

    @Override
    protected String toDebugString() {
        return "Escape from water by pillaring";
    }
}
