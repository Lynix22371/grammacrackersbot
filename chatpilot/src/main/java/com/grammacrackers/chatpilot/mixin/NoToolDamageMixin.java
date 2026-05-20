package com.grammacrackers.chatpilot.mixin;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ui.KeybindManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * While ChatPilot is enabled, prevent any item stack from losing durability.
 *
 * In yarn 1.21.4 the canonical durability entry point is:
 *     ItemStack#damage(int amount, ServerWorld world,
 *                       @Nullable ServerPlayerEntity player,
 *                       Consumer&lt;Item&gt; breakCallback)
 *
 * The handler signature must match the original argument list EXACTLY. Using
 * Object for the player is rejected because mixin compares descriptors
 * literally, not by assignability.
 *
 * Once Grandma toggles ChatPilot off with F10, vanilla durability resumes
 * immediately for any subsequent use.
 */
@Mixin(ItemStack.class)
public abstract class NoToolDamageMixin {

    @Inject(method = "damage(ILnet/minecraft/server/world/ServerWorld;Lnet/minecraft/server/network/ServerPlayerEntity;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"), cancellable = true)
    private void chatpilot$skipDurability(int amount, ServerWorld world,
                                          @Nullable ServerPlayerEntity player,
                                          Consumer<Item> breakCallback,
                                          CallbackInfo ci) {
        if (KeybindManager.pilotEnabled
            && ChatPilotClient.CONFIG != null
            && ChatPilotClient.CONFIG.protectToolDurability) {
            ci.cancel();
        }
    }
}
