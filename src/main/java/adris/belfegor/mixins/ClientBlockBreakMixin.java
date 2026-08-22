package adris.belfegor.mixins;

import adris.belfegor.debug.DebugLogger;
import adris.belfegor.eventbus.EventBus;
import adris.belfegor.eventbus.events.BlockBreakingCancelEvent;
import adris.belfegor.eventbus.events.BlockBreakingEvent;
import adris.belfegor.memory.ConstructionBreakGuard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public final class ClientBlockBreakMixin {

    // for SOME REASON baritone triggers a block cancel breaking every other frame, so we have a 2 frame requirement for that?
    private static int _breakCancelFrames;
    private static long _lastGuardLogMs;

    @Inject(
            method = "attackBlock",
            at = @At("HEAD"),
            cancellable = true
    )
    private void guardAttackBlock(BlockPos pos, Direction direction,
                                  CallbackInfoReturnable<Boolean> ci) {
        if (rejectProtectedBreak(pos, "attack")) {
            ci.setReturnValue(false);
        }
    }

    @Inject(
            method = "updateBlockBreakingProgress",
            at = @At("HEAD")
    )
    private void onBreakUpdate(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> ci) {
        if (rejectProtectedBreak(pos, "progress")) {
            ci.setReturnValue(false);
            return;
        }
        ClientBlockBreakAccessor breakAccessor = (ClientBlockBreakAccessor) (MinecraftClient.getInstance().interactionManager);
        if (breakAccessor != null) {
            _breakCancelFrames = 2;
            EventBus.publish(new BlockBreakingEvent(pos, breakAccessor.getCurrentBreakingProgress()));
        }
    }

    @Inject(
            method = "breakBlock",
            at = @At("HEAD"),
            cancellable = true
    )
    private void guardBreakCompletion(BlockPos pos, CallbackInfoReturnable<Boolean> ci) {
        if (rejectProtectedBreak(pos, "complete")) {
            ci.setReturnValue(false);
        }
    }

    @Inject(
            method = "cancelBlockBreaking",
            at = @At("HEAD")
    )
    private void cancelBlockBreaking(CallbackInfo ci) {
        if (_breakCancelFrames-- == 0) {
            EventBus.publish(new BlockBreakingCancelEvent());
        }
    }

    private static boolean rejectProtectedBreak(BlockPos pos, String phase) {
        var guard = ConstructionBreakGuard.protectedBy(pos);
        if (guard.isEmpty()) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.options != null) {
            client.options.attackKey.setPressed(false);
        }
        long now = System.currentTimeMillis();
        if (now - _lastGuardLogMs >= 500L) {
            _lastGuardLogMs = now;
            DebugLogger.getInstance().logImmediate("BUILD-BREAK-REJECT",
                    "phase=" + phase
                            + " pos=" + pos.toShortString()
                            + " guard=" + guard.get());
        }
        return true;
    }
}
