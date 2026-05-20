package com.grammacrackers.chatpilot.mixin;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ui.KeybindManager;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pins the local player's air supply at maximum every client tick while
 * ChatPilot is running. Combined with the drowning-damage cancel in
 * {@link PlayerEntityDamageMixin} this is the simpler, more reliable
 * alternative to the "always carry a boat" idea Austin originally floated:
 * the bot can swim through any water it pathfinds into without ever taking
 * a drowning hit, and we never have to programmatically place / mount /
 * dismount a boat (which is genuinely fragile in 1.21.4).
 *
 * Vanilla regen still ticks because {@link HungerManagerMixin} keeps food
 * topped up; this mixin only touches the air bar.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class AirSupplyMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void chatpilot$pinAir(CallbackInfo ci) {
        if (!KeybindManager.pilotEnabled || ChatPilotClient.CONFIG == null) return;
        if (!ChatPilotClient.CONFIG.cancelDrowningDamage) return;

        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        // Only act when underwater; topping up while dry is harmless but
        // touching the value every single tick can interact poorly with
        // mods that read air to drive HUD effects.
        if (self.isSubmergedInWater() || self.getAir() < self.getMaxAir()) {
            self.setAir(self.getMaxAir());
        }
    }
}
