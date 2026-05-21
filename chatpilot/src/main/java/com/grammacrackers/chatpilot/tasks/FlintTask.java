package com.grammacrackers.chatpilot.tasks;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import com.grammacrackers.chatpilot.event.TickClock;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class FlintTask implements Task {
    private enum Stage {
        COLLECT_GRAVEL,
        BUILD_COLUMN,
        MINE_COLUMN,
        DONE
    }

    private Stage stage = Stage.COLLECT_GRAVEL;

    private int stageStartTick;
    private int placedHeight;
    private int airTicksAtBase;
    private int placeCooldown;
    private int mineCooldown;

    private int lastFlintCount;
    private int lastGravelCount;
    private int lastProgressTick;

    private BlockPos columnBase;

    @Override
    public String displayName() {
        return "Farming flint";
    }

    @Override
    public String id() {
        return "flint";
    }

    @Override
    public void start() {
        ChatPilotMod.LOGGER.info("[ChatPilot][Flint] Starting flint farm");
        ChatPilotClient.BARITONE.hardReset();

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            lastFlintCount = countItem(mc.player, Items.FLINT);
            lastGravelCount = countItem(mc.player, Items.GRAVEL);
        }

        enterStage(Stage.COLLECT_GRAVEL);
    }

    @Override
    public boolean tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.world == null || mc.interactionManager == null) {
            return false;
        }

        int flint = countItem(mc.player, Items.FLINT);
        int target = Math.max(1, ChatPilotClient.CONFIG.flintTargetCount);

        if (flint >= target) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Flint] Target reached: {}/{}", flint, target);
            enterStage(Stage.DONE);
            return true;
        }

        switch (stage) {
            case COLLECT_GRAVEL -> tickCollectGravel(mc);
            case BUILD_COLUMN -> tickBuildColumn(mc);
            case MINE_COLUMN -> tickMineColumn(mc);
            case DONE -> {
                return true;
            }
        }

        return false;
    }

    private void tickCollectGravel(MinecraftClient mc) {
        int gravel = countItem(mc.player, Items.GRAVEL);
        int minBeforeCycle = Math.max(1, ChatPilotClient.CONFIG.flintMinGravelBeforeCycle);

        if (gravel >= minBeforeCycle) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Flint] Gravel ready: {}", gravel);
            ChatPilotClient.BARITONE.hardReset();
            enterStage(Stage.BUILD_COLUMN);
            return;
        }

        int elapsed = ticksInStage();
        if (gravel > 0 && elapsed > ChatPilotClient.CONFIG.flintCollectTimeoutTicks) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Flint] Collect timeout, using available gravel: {}", gravel);
            ChatPilotClient.BARITONE.hardReset();
            enterStage(Stage.BUILD_COLUMN);
            return;
        }

        if (!ChatPilotClient.BARITONE.isMining()) {
            int want = Math.max(minBeforeCycle, ChatPilotClient.CONFIG.flintGravelBatchSize);
            ChatPilotMod.LOGGER.info("[ChatPilot][Flint] Mining gravel batch: {}", want);
            ChatPilotClient.BARITONE.mineBlocks(want, "gravel");
        }
    }

    private void tickBuildColumn(MinecraftClient mc) {
        ChatPilotClient.BARITONE.stop();

        int gravel = countItem(mc.player, Items.GRAVEL);
        if (gravel <= 0) {
            enterStage(Stage.COLLECT_GRAVEL);
            return;
        }

        int maxHeight = Math.max(1, Math.min(4, ChatPilotClient.CONFIG.flintTowerHeight));
        maxHeight = Math.min(maxHeight, gravel);

        if (columnBase == null || !isValidColumnBase(mc, columnBase, maxHeight)) {
            columnBase = findColumnBase(mc, maxHeight);
            placedHeight = 0;
            airTicksAtBase = 0;

            if (columnBase == null) {
                ChatPilotMod.LOGGER.info("[ChatPilot][Flint] No safe build spot, exploring a little");
                ChatPilotClient.BARITONE.run("explore");
                return;
            }

            ChatPilotMod.LOGGER.info("[ChatPilot][Flint] Column base selected at {}", columnBase);
        }

        if (!selectItem(mc, Items.GRAVEL, ChatPilotClient.CONFIG.flintBuildHotbarSlot)) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Flint] Could not select gravel");
            enterStage(Stage.COLLECT_GRAVEL);
            return;
        }

        if (placeCooldown > 0) {
            placeCooldown--;
            return;
        }

        if (placedHeight >= maxHeight) {
            enterStage(Stage.MINE_COLUMN);
            return;
        }

        BlockPos target = columnBase.up(placedHeight);

        if (isGravel(mc, target)) {
            placedHeight++;
            return;
        }

        if (!mc.world.isAir(target)) {
            columnBase = null;
            placedHeight = 0;
            return;
        }

        BlockPos clicked = target.down();
        if (!isReachable(mc.player, clicked)) {
            columnBase = null;
            placedHeight = 0;
            return;
        }

        lookAt(mc.player, Vec3d.ofCenter(target));

        BlockHitResult hit = new BlockHitResult(
                Vec3d.ofCenter(clicked).add(0.0, 0.5, 0.0),
                Direction.UP,
                clicked,
                false
        );

        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);

        placeCooldown = 5;

        if (isGravel(mc, target)) {
            placedHeight++;
        }
    }

    private void tickMineColumn(MinecraftClient mc) {
        ChatPilotClient.BARITONE.stop();

        if (columnBase == null) {
            enterStage(Stage.BUILD_COLUMN);
            return;
        }

        int flint = countItem(mc.player, Items.FLINT);
        int gravel = countItem(mc.player, Items.GRAVEL);

        if (flint > lastFlintCount || gravel < lastGravelCount) {
            lastFlintCount = flint;
            lastGravelCount = gravel;
            lastProgressTick = clientTick();
        }

        if (ticksInStage() > ChatPilotClient.CONFIG.flintMineCycleTimeoutTicks) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Flint] Mine cycle timeout, rebuilding/collecting");
            columnBase = null;
            if (countItem(mc.player, Items.GRAVEL) > 0) {
                enterStage(Stage.BUILD_COLUMN);
            } else {
                enterStage(Stage.COLLECT_GRAVEL);
            }
            return;
        }

        if (!hasAnyGravelInColumn(mc, columnBase)) {
            airTicksAtBase++;

            if (airTicksAtBase > 15) {
                if (countItem(mc.player, Items.GRAVEL) > 0) {
                    enterStage(Stage.BUILD_COLUMN);
                } else {
                    enterStage(Stage.COLLECT_GRAVEL);
                }
            }

            return;
        }

        airTicksAtBase = 0;

        selectBestFlintTool(mc);

        BlockPos minePos = columnBase;

        // If the bottom is temporarily air while falling gravel is landing, wait.
        if (!isGravel(mc, minePos)) {
            return;
        }

        if (!isReachable(mc.player, minePos)) {
            columnBase = null;
            enterStage(Stage.BUILD_COLUMN);
            return;
        }

        lookAt(mc.player, Vec3d.ofCenter(minePos));

        if (mineCooldown > 0) {
            mineCooldown--;
            return;
        }

        mc.interactionManager.updateBlockBreakingProgress(minePos, Direction.UP);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private void enterStage(Stage next) {
        stage = next;
        stageStartTick = clientTick();
        placeCooldown = 0;
        mineCooldown = 0;
        airTicksAtBase = 0;

        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.player != null) {
            lastFlintCount = countItem(mc.player, Items.FLINT);
            lastGravelCount = countItem(mc.player, Items.GRAVEL);
        }

        lastProgressTick = clientTick();

        switch (next) {
            case COLLECT_GRAVEL -> {
                columnBase = null;
                placedHeight = 0;
                ChatPilotClient.BARITONE.hardReset();
            }

            case BUILD_COLUMN -> {
                ChatPilotClient.BARITONE.hardReset();
                placedHeight = 0;
            }

            case MINE_COLUMN -> ChatPilotClient.BARITONE.hardReset();

            case DONE -> ChatPilotClient.BARITONE.hardReset();
        }

        ChatPilotMod.LOGGER.info("[ChatPilot][Flint] Stage -> {}", next);
    }

    @Override
    public boolean onStuck() {
        ChatPilotMod.LOGGER.info("[ChatPilot][Flint] Stuck recovery during {}", stage);

        if (stage == Stage.COLLECT_GRAVEL) {
            ChatPilotClient.BARITONE.hardReset();
            ChatPilotClient.BARITONE.mineBlocks(
                    Math.max(16, ChatPilotClient.CONFIG.flintGravelBatchSize),
                    "gravel"
            );
            return true;
        }

        columnBase = null;
        placedHeight = 0;
        enterStage(Stage.BUILD_COLUMN);
        return true;
    }

    @Override
    public void onCombatStart() {
        ChatPilotClient.BARITONE.stop();
        releaseKeys();
    }

    @Override
    public void onCombatEnd() {
        if (stage == Stage.COLLECT_GRAVEL) {
            ChatPilotClient.BARITONE.mineBlocks(
                    Math.max(16, ChatPilotClient.CONFIG.flintGravelBatchSize),
                    "gravel"
            );
        }
    }

    @Override
    public void cancel() {
        ChatPilotClient.BARITONE.hardReset();
        releaseKeys();
    }

    private static BlockPos findColumnBase(MinecraftClient mc, int height) {
        BlockPos playerPos = mc.player.getBlockPos();

        for (int radius = 2; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) < 2) continue;

                    BlockPos base = playerPos.add(dx, 0, dz);

                    if (isValidColumnBase(mc, base, height)) {
                        return base.toImmutable();
                    }
                }
            }
        }

        return null;
    }

    private static boolean isValidColumnBase(MinecraftClient mc, BlockPos base, int height) {
        if (mc.world == null || mc.player == null) return false;

        if (!isReachable(mc.player, base)) return false;

        BlockPos floor = base.down();

        if (mc.world.getBlockState(floor).getCollisionShape(mc.world, floor).isEmpty()) {
            return false;
        }

        if (!mc.world.getFluidState(base).isEmpty()) {
            return false;
        }

        for (int i = 0; i < height; i++) {
            BlockPos p = base.up(i);

            if (!mc.world.isAir(p) && !isGravel(mc, p)) {
                return false;
            }

            if (!mc.world.getFluidState(p).isEmpty()) {
                return false;
            }
        }

        return true;
    }

    private static boolean hasAnyGravelInColumn(MinecraftClient mc, BlockPos base) {
        int h = Math.max(1, Math.min(4, ChatPilotClient.CONFIG.flintTowerHeight));

        for (int i = 0; i < h; i++) {
            if (isGravel(mc, base.up(i))) {
                return true;
            }
        }

        return false;
    }

    private static boolean isGravel(MinecraftClient mc, BlockPos pos) {
        return mc.world != null && mc.world.getBlockState(pos).isOf(Blocks.GRAVEL);
    }

    private static void selectBestFlintTool(MinecraftClient mc) {
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.interactionManager == null) return;

        int bestSlot = -1;
        double bestScore = 0.0;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            double score = flintToolScore(stack);

            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        if (bestSlot >= 0) {
            selectInventorySlot(mc, bestSlot, ChatPilotClient.CONFIG.flintToolHotbarSlot);
        }
    }

    private static double flintToolScore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0.0;

        Item item = stack.getItem();
        String path = Registries.ITEM.getId(item).getPath();

        if (!path.endsWith("_shovel")) return 0.0;

        // Silk Touch prevents flint, so never pick that shovel for this task.
        if (hasEnchant(stack, Enchantments.SILK_TOUCH)) return 0.0;

        double score;

        if (path.startsWith("netherite_")) score = 6.0;
        else if (path.startsWith("diamond_")) score = 5.0;
        else if (path.startsWith("iron_")) score = 4.0;
        else if (path.startsWith("stone_")) score = 3.0;
        else if (path.startsWith("golden_")) score = 2.0;
        else if (path.startsWith("wooden_")) score = 1.0;
        else score = 0.5;

        score += enchantLevel(stack, Enchantments.FORTUNE) * 1.5;

        if (stack.isDamageable() && stack.getMaxDamage() > 0) {
            score += 0.1 * (1.0 - ((double) stack.getDamage() / (double) stack.getMaxDamage()));
        }

        return score;
    }

    private static boolean hasEnchant(ItemStack stack, RegistryKey<Enchantment> key) {
        return enchantLevel(stack, key) > 0;
    }

    private static int enchantLevel(ItemStack stack, RegistryKey<Enchantment> key) {
        try {
            ItemEnchantmentsComponent enchants = stack.getOrDefault(
                    DataComponentTypes.ENCHANTMENTS,
                    ItemEnchantmentsComponent.DEFAULT
            );

            int best = 0;

            for (RegistryEntry<Enchantment> entry : enchants.getEnchantments()) {
                if (entry.matchesKey(key)) {
                    best = Math.max(best, enchants.getLevel(entry));
                }
            }

            return best;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static boolean selectItem(MinecraftClient mc, Item item, int preferredHotbarSlot) {
        ClientPlayerEntity player = mc.player;
        if (player == null) return false;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getStack(i);

            if (!stack.isEmpty() && stack.isOf(item)) {
                selectInventorySlot(mc, i, preferredHotbarSlot);
                return true;
            }
        }

        return false;
    }

    private static void selectInventorySlot(MinecraftClient mc, int invSlot, int preferredHotbarSlot) {
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.interactionManager == null) return;

        if (invSlot >= 0 && invSlot <= 8) {
            player.getInventory().selectedSlot = invSlot;
            return;
        }

        int hotbarSlot = preferredHotbarSlot;
        if (hotbarSlot < 0 || hotbarSlot > 8) hotbarSlot = 0;

        int handlerSlot = inventorySlotToHandlerSlot(invSlot);

        if (handlerSlot < 0) return;

        try {
            mc.interactionManager.clickSlot(
                    player.playerScreenHandler.syncId,
                    handlerSlot,
                    hotbarSlot,
                    SlotActionType.SWAP,
                    player
            );

            player.getInventory().selectedSlot = hotbarSlot;
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][Flint] Could not swap item to hotbar", t);
        }
    }

    private static int inventorySlotToHandlerSlot(int invSlot) {
        if (invSlot >= 0 && invSlot <= 8) return 36 + invSlot;
        if (invSlot >= 9 && invSlot <= 35) return invSlot;
        return -1;
    }

    private static int countItem(PlayerEntity player, Item item) {
        if (player == null) return 0;

        int total = 0;

        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);

            if (!stack.isEmpty() && stack.isOf(item)) {
                total += stack.getCount();
            }
        }

        return total;
    }

    private static boolean isReachable(PlayerEntity player, BlockPos pos) {
        if (player == null) return false;
        return player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= 20.25;
    }

    private static void lookAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d eye = player.getEyePos();

        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;

        double horiz = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));

        player.setYaw(yaw);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);
        player.setPitch(pitch);
    }

    private static void releaseKeys() {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc != null) {
            mc.options.forwardKey.setPressed(false);
            mc.options.backKey.setPressed(false);
            mc.options.leftKey.setPressed(false);
            mc.options.rightKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);
        }
    }

    private int ticksInStage() {
        return clientTick() - stageStartTick;
    }

    private static int clientTick() {
        return (int) TickClock.now();
    }
}
