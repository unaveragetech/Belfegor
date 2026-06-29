package adris.belfegor.mixins;

import adris.belfegor.Belfegor;
import adris.belfegor.Debug;
import adris.belfegor.debug.DebugLogger;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catches Belfegor's emergency abort hotkey before GUI screens can consume it.
 * The tick poll remains as a fallback, but this makes '+' work while inventory,
 * recipe book search, shulker screens, and other Screen widgets own focus.
 */
@Mixin(net.minecraft.client.Keyboard.class)
public class KeyboardAbortMixin {

    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void belfegor$globalAbort(long window, int key, int scancode,
                                      int action, int modifiers, CallbackInfo ci) {
        if (action == GLFW.GLFW_RELEASE) {
            return;
        }
        boolean plus = key == GLFW.GLFW_KEY_KP_ADD
                || (key == GLFW.GLFW_KEY_EQUAL && (modifiers & GLFW.GLFW_MOD_SHIFT) != 0);
        if (!plus || !Belfegor.inGame()) {
            return;
        }
        if (Debug.jankModInstance != null) {
            DebugLogger.getInstance().logImmediate("GLOBAL-ABORT",
                    "Keyboard event captured key=" + key
                            + " action=" + action
                            + " modifiers=" + modifiers);
            Debug.jankModInstance.abortAllAutomation();
            ci.cancel();
        }
    }
}
