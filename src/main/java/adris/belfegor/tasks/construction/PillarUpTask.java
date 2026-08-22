package adris.belfegor.tasks.construction;

import adris.belfegor.Belfegor;
import adris.belfegor.TaskCatalogue;
import adris.belfegor.debug.DebugLogger;
import adris.belfegor.tasksystem.Task;
import adris.belfegor.util.ItemTarget;
import adris.belfegor.util.helpers.LookHelper;
import adris.belfegor.util.helpers.WorldHelper;
import baritone.api.utils.input.Input;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Pillars straight up the way a player actually does it:
 *  1) jump,
 *  2) while airborne, place a cobblestone block into the cell you just left
 *     (right-click the top face of the block below that cell),
 *  3) land on the new block,
 *  4) repeat until the target height is reached.
 *
 * You cannot place a block into the cell your feet currently occupy, so the
 * jump is required. The task verifies the pillar after every landing and
 * re-jumps to fix any missing block, then confirms the final column from the
 * original start Y.
 */
public class PillarUpTask extends Task {

    private static final int MAX_PILLAR_BLOCKS = 128;
    private static final int JUMP_TICKS = 6;
    private static final int CLICK_COOLDOWN_TICKS = 5;
    private static final int STALL_WARN_TICKS = 100;

    private enum Phase {
        JUMP,
        PLACE,
        LAND
    }

    private final int _blocks;
    private int _startY = Integer.MIN_VALUE;
    private Phase _phase = Phase.LAND;
    private BlockPos _fillCell;
    private int _jumpTicks;
    private int _cooldown;
    private int _stallTicks;
    private int _lastProgressY = Integer.MIN_VALUE;
    private boolean _confirmed;
    private Task _activeTask;

    public PillarUpTask(int blocks) {
        _blocks = Math.max(1, Math.min(MAX_PILLAR_BLOCKS, blocks));
    }

    @Override
    protected void onStart(Belfegor mod) {
        _startY = Integer.MIN_VALUE;
        _phase = Phase.LAND;
        _fillCell = null;
        _jumpTicks = 0;
        _cooldown = 0;
        _stallTicks = 0;
        _lastProgressY = Integer.MIN_VALUE;
        _confirmed = false;
        _activeTask = null;
        mod.getInputControls().release(Input.JUMP);
    }

    @Override
    protected Task onTick(Belfegor mod) {
        if (mod.getPlayer() == null) return null;
        if (_startY == Integer.MIN_VALUE) {
            _startY = mod.getPlayer().getBlockPos().getY();
        }
        if (_activeTask != null && !_activeTask.stopped() && !_activeTask.isFinished(mod)) {
            return _activeTask;
        }
        _activeTask = null;

        if (!mod.getItemStorage().hasItem(Items.COBBLESTONE)) {
            setDebugState("Gathering cobblestone for pillar");
            _activeTask = TaskCatalogue.getItemTask("cobblestone", 32);
            return _activeTask;
        }

        BlockPos feet = mod.getPlayer().getBlockPos();
        int risen = feet.getY() - _startY;
        if (risen >= _blocks) {
            confirmAndFinish(mod, feet);
            return null;
        }

        // Informational stall watchdog: log when the bot is not gaining height
        // so the cause is visible, but never give up while supplies exist.
        if (_lastProgressY == feet.getY()) {
            _stallTicks++;
            if (_stallTicks % STALL_WARN_TICKS == 0) {
                DebugLogger.getInstance().logImmediate("PILLAR-STALL",
                        "no height change for " + _stallTicks + " ticks"
                                + " (startY=" + _startY + " feet=" + feet.getY()
                                + "); retrying placement");
            }
        } else {
            _lastProgressY = feet.getY();
            _stallTicks = 0;
        }

        switch (_phase) {
            case LAND -> {
                if (!mod.getPlayer().isOnGround()) {
                    return null;
                }
                if (isPillarBlock(mod, feet.down()) && risen > 0) {
                    // Landed on the new block; take the next step.
                    _jumpTicks = JUMP_TICKS;
                    _phase = Phase.JUMP;
                    return null;
                }
                // The block below the feet is missing (placement failed or the
                // bot fell into a hole): jump and fill the first missing cell.
                BlockPos missing = firstMissingCellBelow(mod, feet);
                if (missing == null) {
                    _phase = Phase.JUMP;
                    return null;
                }
                _fillCell = missing;
                _jumpTicks = JUMP_TICKS;
                _phase = Phase.JUMP;
                return null;
            }
            case JUMP -> {
                if (_jumpTicks-- > 0) {
                    mod.getInputControls().hold(Input.JUMP);
                    return null;
                }
                mod.getInputControls().release(Input.JUMP);
                if (mod.getPlayer().isOnGround()) {
                    // Jump never left the ground; try again.
                    _jumpTicks = JUMP_TICKS;
                    return null;
                }
                _phase = Phase.PLACE;
                return null;
            }
            case PLACE -> {
                mod.getInputControls().release(Input.JUMP);
                if (_cooldown-- > 0) return null;
                if (mod.getPlayer().isOnGround()) {
                    _phase = Phase.LAND;
                    return null;
                }
                BlockPos target = _fillCell != null
                        ? _fillCell
                        : mod.getPlayer().getBlockPos().down();
                if (target == null || target.getY() < _startY - 1) {
                    _phase = Phase.LAND;
                    return null;
                }
                if (isPillarBlock(mod, target)) {
                    // Already filled (another click landed); land and continue.
                    _fillCell = null;
                    _phase = Phase.LAND;
                    return null;
                }
                BlockPos support = target.down();
                if (!WorldHelper.isSolid(mod, support)) {
                    // No support below the missing cell: cannot place yet. If the
                    // support is below the pillar base, fall through to landing.
                    if (support.getY() < _startY - 1) {
                        _phase = Phase.LAND;
                        return null;
                    }
                    _phase = Phase.LAND;
                    return null;
                }
                if (!mod.getSlotHandler().forceEquipItem(
                        new ItemTarget(Items.COBBLESTONE, 1), false)) {
                    _activeTask = TaskCatalogue.getItemTask("cobblestone", 32);
                    return _activeTask;
                }
                LookHelper.lookAt(mod, support, Direction.UP);
                Vec3d hit = Vec3d.ofCenter(support).add(0, 0.5, 0);
                BlockHitResult result = new BlockHitResult(
                        hit, Direction.UP, support, false);
                ActionResult action = mod.getController().interactBlock(
                        mod.getPlayer(), Hand.MAIN_HAND, result);
                mod.getPlayer().swingHand(Hand.MAIN_HAND);
                _cooldown = CLICK_COOLDOWN_TICKS;
                _fillCell = null;
                _phase = Phase.LAND;
                return null;
            }
        }
        return null;
    }

    /**
     * Scans from the feet cell down to the pillar base (start Y) and returns
     * the first cell that is not yet cobblestone, or null when the column is
     * complete up to the target.
     */
    private BlockPos firstMissingCellBelow(Belfegor mod, BlockPos feet) {
        for (int dy = 0; feet.getY() - dy >= _startY; dy++) {
            BlockPos cell = feet.add(0, -dy, 0);
            if (!isPillarBlock(mod, cell)) return cell;
        }
        return null;
    }

    private void confirmAndFinish(Belfegor mod, BlockPos feet) {
        int verified = 0;
        for (int y = _startY; y < _startY + _blocks; y++) {
            BlockPos cell = new BlockPos(feet.getX(), y, feet.getZ());
            if (isPillarBlock(mod, cell)) verified++;
        }
        String message = "Pillared up " + _blocks + " blocks from Y=" + _startY
                + " to Y=" + feet.getY()
                + " (verified " + verified + " cobblestone in the column)";
        setDebugState(message);
        DebugLogger.getInstance().logImmediate("PILLAR", message);
        _confirmed = true;
    }

    private boolean isPillarBlock(Belfegor mod, BlockPos pos) {
        if (mod == null || mod.getWorld() == null || pos == null) return false;
        Block block = mod.getWorld().getBlockState(pos).getBlock();
        return block == Blocks.COBBLESTONE;
    }

    @Override
    protected void onStop(Belfegor mod, Task interruptTask) {
        _activeTask = null;
        mod.getInputControls().release(Input.JUMP);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof PillarUpTask task && task._blocks == _blocks;
    }

    @Override
    protected String toDebugString() {
        return "Pillar up " + _blocks + " blocks startY=" + _startY
                + " phase=" + _phase + " stallTicks=" + _stallTicks;
    }

    @Override
    public boolean isFinished(Belfegor mod) {
        if (_confirmed) return true;
        if (mod == null || mod.getPlayer() == null || _startY == Integer.MIN_VALUE) return false;
        return mod.getPlayer().getBlockPos().getY() - _startY >= _blocks;
    }
}
