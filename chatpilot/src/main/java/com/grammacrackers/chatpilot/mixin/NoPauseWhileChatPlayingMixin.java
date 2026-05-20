package com.grammacrackers.chatpilot.mixin;

import com.grammacrackers.chatpilot.ui.KeybindManager;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the pilot is on, swallow attempts to open the pause menu. Grandma
 * uses F10 to toggle the pilot off when she's back, which is the only path
 * out. This guarantees the pause overlay never blocks the stream and that
 * chat input keeps flowing while she is away.
 *
 * Note: window-focus-loss "auto pause" is unrelated and uses a different
 * code path; this mixin only blocks the user-initiated Escape menu.
 */
@Mixin(MinecraftClient.class)
public abstract class NoPauseWhileChatPlayingMixin {

    @Inject(method = "openGameMenu", at = @At("HEAD"), cancellable = true)
    private void chatpilot$blockPauseWhileEnabled(boolean pause, CallbackInfo ci) {
        if (KeybindManager.pilotEnabled) {
            ci.cancel();
        }
    }
}
