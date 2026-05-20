package com.grammacrackers.chatpilot.mixin;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ui.KeybindManager;
import net.minecraft.entity.EntityData;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.ServerWorldAccess;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Two layers of mob protection while ChatPilot is on:
 *   1) Total deny: any hostile mob trying to naturally spawn within
 *      hostileSpawnFreeRadius blocks of the bed is discarded outright.
 *      This keeps the area where Grandma sleeps safe.
 *   2) Far-field cull: hostile mobs spawning anywhere else in the world
 *      are kept with probability hostileSpawnMultiplier (default 0.15).
 *
 * Friendly mobs are never affected. Spawner / command / event-driven
 * spawns are never affected.
 */
@Mixin(MobEntity.class)
public abstract class MobSpawnMixin {

    @Inject(method = "initialize", at = @At("HEAD"), cancellable = true)
    private void chatpilot$reduceHostile(ServerWorldAccess world, LocalDifficulty difficulty,
                                         SpawnReason spawnReason, @Nullable EntityData entityData,
                                         CallbackInfoReturnable<EntityData> cir) {
        if (!KeybindManager.pilotEnabled || ChatPilotClient.CONFIG == null) return;
        if (spawnReason != SpawnReason.NATURAL
            && spawnReason != SpawnReason.CHUNK_GENERATION
            && spawnReason != SpawnReason.STRUCTURE) return;

        MobEntity self = (MobEntity)(Object) this;
        if (!(self instanceof HostileEntity)) return;

        // Layer 1: home dome
        if (ChatPilotClient.HOME != null && ChatPilotClient.HOME.hasHome()) {
            BlockPos bed = ChatPilotClient.HOME.getBedPos();
            int free = ChatPilotClient.CONFIG.hostileSpawnFreeRadius;
            if (free > 0) {
                long dx = self.getBlockX() - bed.getX();
                long dy = self.getBlockY() - bed.getY();
                long dz = self.getBlockZ() - bed.getZ();
                if (dx * dx + dy * dy + dz * dz <= (long) free * free) {
                    self.discard();
                    cir.setReturnValue(entityData);
                    return;
                }
            }
        }

        // Layer 2: stochastic cull
        double keep = ChatPilotClient.CONFIG.hostileSpawnMultiplier;
        if (keep >= 1.0) return;
        if (ThreadLocalRandom.current().nextDouble() >= keep) {
            self.discard();
            cir.setReturnValue(entityData);
        }
    }
}
