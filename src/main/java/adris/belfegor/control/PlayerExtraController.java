package adris.belfegor.control;

import adris.belfegor.Belfegor;
import adris.belfegor.eventbus.EventBus;
import adris.belfegor.eventbus.events.BlockBreakingCancelEvent;
import adris.belfegor.eventbus.events.BlockBreakingEvent;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

public class PlayerExtraController {

    private final Belfegor _mod;
    private BlockPos _blockBreakPos;
    private double _blockBreakProgress;

    public PlayerExtraController(Belfegor mod) {
        _mod = mod;

        EventBus.subscribe(BlockBreakingEvent.class, evt -> onBlockBreak(evt.blockPos, evt.progress));
        EventBus.subscribe(BlockBreakingCancelEvent.class, evt -> onBlockStopBreaking());
    }

    private void onBlockBreak(BlockPos pos, double progress) {
        _blockBreakPos = pos;
        _blockBreakProgress = progress;
    }

    private void onBlockStopBreaking() {
        _blockBreakPos = null;
        _blockBreakProgress = 0;
    }

    public BlockPos getBreakingBlockPos() {
        return _blockBreakPos;
    }

    public boolean isBreakingBlock() {
        return _blockBreakPos != null;
    }

    public double getBreakingBlockProgress() {
        return _blockBreakProgress;
    }

    public boolean inRange(Entity entity) {
        if (_mod.getPlayer() == null) return false;
        return _mod.getPlayer().isInRange(entity, _mod.getModSettings().getEntityReachRange());
    }

    public void attack(Entity entity) {
        if (_mod.getPlayer() == null) return;
        if (inRange(entity)) {
            _mod.getController().attackEntity(_mod.getPlayer(), entity);
            _mod.getPlayer().swingHand(Hand.MAIN_HAND);
        }
    }
}
