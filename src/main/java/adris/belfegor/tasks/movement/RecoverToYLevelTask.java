package adris.belfegor.tasks.movement;

import adris.belfegor.Belfegor;
import adris.belfegor.debug.DebugLogger;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.helpers.StorageHelper;
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
 * Construction recovery for cases where the bot falls below the active build plane.
 *
 * This is intentionally separate from water survival. Water survival should mostly swim/path;
 * this task is used when a build task has a known target Y and needs to climb back to it.
 */
public class RecoverToYLevelTask extends Task {

    private static final Item[] PILLAR_ITEMS = {
            Items.COBBLESTONE,
            Items.COBBLED_DEEPSLATE,
            Items.STONE,
            Items.DIRT,
            Items.NETHERRACK,
            Items.OAK_PLANKS,
            Items.SPRUCE_PLANKS,
            Items.BIRCH_PLANKS
    };

    private final int _targetY;
    private final int _tolerance;
    private int _ticks;
    private int _placeAttempts;
    private int _highestY;
    private int _noRiseTicks;

    public RecoverToYLevelTask(int targetY) {
        this(targetY, 0);
    }

    public RecoverToYLevelTask(int targetY, int tolerance) {
        _targetY = targetY;
        _tolerance = Math.max(0, tolerance);
    }

    @Override
    protected void onStart(Belfegor mod) {
        _ticks = 0;
        _placeAttempts = 0;
        _highestY = mod.getPlayer() == null ? Integer.MIN_VALUE : mod.getPlayer().getBlockY();
        _noRiseTicks = 0;
        StorageHelper.closeScreen();
        if (mod.getClientBaritone() != null) {
            mod.getClientBaritone().getPathingBehavior().cancelEverything();
        }
        DebugLogger.getInstance().logImmediate("Y-RECOVERY",
                "start targetY=" + _targetY
                        + " player=" + (mod.getPlayer() == null ? "null" : mod.getPlayer().getBlockPos().toShortString()));
    }

    @Override
    protected Task onTick(Belfegor mod) {
        _ticks++;
        if (mod.getPlayer() == null || mod.getWorld() == null) return null;
        BlockPos feet = mod.getPlayer().getBlockPos();
        if (feet.getY() > _highestY) {
            _highestY = feet.getY();
            _noRiseTicks = 0;
        } else {
            _noRiseTicks++;
        }

        mod.getInputControls().hold(Input.JUMP);
        mod.getInputControls().forceLook(mod.getPlayer().getYaw(), 90);

        if (feet.getY() >= _targetY - _tolerance) {
            setDebugState("Recovered to build Y");
            return null;
        }

        if (_noRiseTicks < 10 && !mod.getPlayer().isOnGround()) {
            setDebugState("Swimming/jumping toward build Y before pillaring");
            return null;
        }

        Item item = firstAvailableBlock(mod);
        if (item == null) {
            setDebugState("Need blocks to recover to build Y");
            return null;
        }
        if (!mod.getSlotHandler().forceEquipItem(item)) {
            setDebugState("Equipping recovery block " + item);
            return null;
        }
        if (_ticks % 4 == 0) {
            _placeAttempts++;
            tryPlaceAt(mod, feet.down(), Direction.UP);
            tryPlaceAt(mod, feet, Direction.UP);
            DebugLogger.getInstance().log("Y-RECOVERY",
                    "pillar tick=" + _ticks
                            + " attempt=" + _placeAttempts
                            + " feet=" + feet.toShortString()
                            + " targetY=" + _targetY
                            + " noRiseTicks=" + _noRiseTicks
                            + " item=" + item);
        }
        setDebugState("Pillaring back to build Y " + _targetY);
        return null;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        return mod.getPlayer() == null || mod.getWorld() == null
                || mod.getPlayer().getBlockY() >= _targetY - _tolerance;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        if (mod.getInputControls() != null) {
            mod.getInputControls().release(Input.JUMP);
        }
        DebugLogger.getInstance().logImmediate("Y-RECOVERY",
                "stop targetY=" + _targetY
                        + " ticks=" + _ticks
                        + " attempts=" + _placeAttempts
                        + " interruptedBy=" + (interruptTask == null ? "clean" : interruptTask.toString()));
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof RecoverToYLevelTask task
                && task._targetY == _targetY
                && task._tolerance == _tolerance;
    }

    @Override
    protected String toDebugString() {
        return "Recover to build Y " + _targetY;
    }

    private Item firstAvailableBlock(Belfegor mod) {
        return Arrays.stream(PILLAR_ITEMS)
                .filter(item -> mod.getItemStorage().hasItem(item))
                .findFirst()
                .orElse(null);
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
}
