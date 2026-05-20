package com.grammacrackers.chatpilot.mixin;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ui.KeybindManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * When a HOSTILE entity takes damage from the local player, scale up by
 * config.damageDealtMultiplier. Friendly mobs (cows, sheep, pigs, villagers,
 * horses, etc.) take normal damage so Grandma can still butcher animals
 * for food without one-shotting her whole farm.
 */
@Mixin(LivingEntity.class)
public abstract class PlayerEntityAttackMixin {

    @ModifyVariable(method = "damage", at = @At("HEAD"), argsOnly = true)
    private float chatpilot$scaleOutgoing(float amount, net.minecraft.server.world.ServerWorld world,
                                          DamageSource source) {
        if (!KeybindManager.pilotEnabled || ChatPilotClient.CONFIG == null) return amount;
        Entity attacker = source.getAttacker();
        if (!(attacker instanceof PlayerEntity)) return amount;
        // Only buff damage against hostile targets
        LivingEntity self = (LivingEntity)(Object)this;
        if (!(self instanceof net.minecraft.entity.mob.HostileEntity)) return amount;
        return (float) (amount * ChatPilotClient.CONFIG.damageDealtMultiplier);
    }
}
