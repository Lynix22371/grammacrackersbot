package com.grammacrackers.chatpilot.mixin;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ui.KeybindManager;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.DamageTypeTags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Damage shaping for the autonomous pilot:
 *
 *   1. Fully cancel certain "stuck-in-environment" damage types that have
 *      no entity counterattack possible: drowning, suffocation, in-wall,
 *      cramming, and (optionally) fall. Configured via {@link
 *      com.grammacrackers.chatpilot.config.ChatPilotConfig#cancelDrowningDamage}
 *      and friends. This keeps the bot alive when Baritone routes through
 *      a tight passage or a 4-block drop.
 *
 *   2. Heavily reduce lava damage so a brief lip-touch on a path past a
 *      lava lake doesn't end the stream.
 *
 *   3. For everything else, scale by {@code damageTakenMultiplier} (the
 *      v1.0.x behaviour, default 0.25 = quarter damage).
 *
 * The cancel-on-HEAD inject runs first; if it cancels the damage call,
 * the {@link ModifyVariable} amount-scaling never runs.
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityDamageMixin {

    /**
     * Fully cancel damage from environmental sources we don't want the bot
     * to die from. Returns true (damage was applied) so callers don't
     * misinterpret the cancel as "didn't connect".
     */
    @Inject(method = "damage", at = @At("HEAD"), cancellable = true)
    private void chatpilot$cancelEnvironmentalDeaths(net.minecraft.server.world.ServerWorld world,
                                                      DamageSource source, float amount,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (!KeybindManager.pilotEnabled || ChatPilotClient.CONFIG == null) return;
        if (source == null) return;
        var cfg = ChatPilotClient.CONFIG;

        try {
            if (cfg.cancelDrowningDamage && source.isOf(DamageTypes.DROWN)) {
                cir.setReturnValue(false);
                return;
            }
            if (cfg.cancelSuffocationDamage
                && (source.isOf(DamageTypes.IN_WALL)
                    || source.isOf(DamageTypes.CRAMMING))) {
                cir.setReturnValue(false);
                return;
            }
            if (cfg.cancelFallDamage && source.isIn(DamageTypeTags.IS_FALL)) {
                cir.setReturnValue(false);
                return;
            }
        } catch (Throwable ignored) {
            // If yarn renamed any of these between 1.21.x patches we want
            // the rest of the mod to keep working; a damage tag miss is
            // not worth crashing the client.
        }
    }

    /**
     * Scale the surviving damage values: lava gets a hard reduction so
     * brief lava contact along a path is survivable, everything else gets
     * the standard {@code damageTakenMultiplier}.
     */
    @ModifyVariable(method = "damage", at = @At("HEAD"), argsOnly = true)
    private float chatpilot$scaleIncoming(float amount, net.minecraft.server.world.ServerWorld world,
                                          DamageSource source) {
        if (!KeybindManager.pilotEnabled || ChatPilotClient.CONFIG == null) return amount;
        var cfg = ChatPilotClient.CONFIG;
        try {
            if (source != null
                && (source.isOf(DamageTypes.LAVA) || source.isOf(DamageTypes.HOT_FLOOR))) {
                return (float) (amount * cfg.lavaDamageMultiplier);
            }
        } catch (Throwable ignored) {}
        return (float) (amount * cfg.damageTakenMultiplier);
    }
}
