package com.grammacrackers.chatpilot.mixin;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ui.KeybindManager;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hunger immunity AND keep vanilla regen working.
 *
 * The previous version cancelled HungerManager.update() at HEAD which also
 * stopped the natural-regen branch inside that method, so the player would
 * never heal. The fix is to top up food and saturation BEFORE update runs,
 * then let update() execute normally. Vanilla then sees foodLevel=20 and
 * saturation>0 and ticks the natural regen branch, healing the player back
 * to full HP slowly.
 *
 * We still cancel addExhaustion so nothing the player does (sprinting,
 * mining, taking damage) ever drains the hunger bar.
 */
@Mixin(HungerManager.class)
public abstract class HungerManagerMixin {

    @Inject(method = "update", at = @At("HEAD"))
    private void chatpilot$topupBeforeUpdate(ServerPlayerEntity player, CallbackInfo ci) {
        if (!KeybindManager.pilotEnabled || ChatPilotClient.CONFIG == null) return;
        if (!ChatPilotClient.CONFIG.enableHungerImmunity) return;
        HungerManager hm = (HungerManager)(Object)this;
        // Pin food at 20 so the regen branch always qualifies
        if (hm.getFoodLevel() < 20) hm.setFoodLevel(20);
        // Saturation is consumed by natural regen; refill it so regen keeps ticking
        if (hm.getSaturationLevel() < 5.0f) hm.setSaturationLevel(20f);
    }

    @Inject(method = "addExhaustion", at = @At("HEAD"), cancellable = true)
    private void chatpilot$cancelExhaustion(float exhaustion, CallbackInfo ci) {
        if (KeybindManager.pilotEnabled && ChatPilotClient.CONFIG != null
            && ChatPilotClient.CONFIG.enableHungerImmunity) {
            ci.cancel();
        }
    }
}
