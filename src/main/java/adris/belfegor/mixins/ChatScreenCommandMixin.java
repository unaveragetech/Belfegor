package adris.belfegor.mixins;

import adris.belfegor.Belfegor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.gui.screen.ChatScreen;

/**
 * Capture Belfegor commands at chat-screen submit time.
 *
 * Client-side command mods such as Baritone can consume messages before they
 * ever reach ClientPlayNetworkHandler#sendChatMessage. The older network-layer
 * mixin is still useful as a fallback, but this UI-layer hook ensures @...
 * commands reach Belfegor before Baritone treats them as native input.
 */
@Mixin(ChatScreen.class)
public final class ChatScreenCommandMixin {

    @Inject(method = "sendMessage", at = @At("HEAD"), cancellable = true)
    private void belfegor$sendMessage(String chatText, boolean addToHistory, CallbackInfo ci) {
        try {
            if (Belfegor.getCommandExecutor() != null
                    && Belfegor.getCommandExecutor().isClientCommand(chatText)) {
                Belfegor.getCommandExecutor().execute(chatText);
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // Let vanilla/other client command handlers continue if Belfegor is
            // not fully initialized yet. The network-layer mixin remains as a
            // second chance once initialization completes.
        }
    }
}
