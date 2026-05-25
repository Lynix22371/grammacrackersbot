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
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.Box;

public class FlintTask implements Task {
    private enum Stage {
        COLLECT_GRAVEL,
        MOVE_TO_OPEN_SPACE,
        BUILD_COLUMN,
        MINE_COLUMN,
        PICKUP_DROPS,
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
    private static final double PICKUP_RADIUS = 8.0;
    private static final int PICKUP_TIMEOUT_TICKS = 20 * 12;
    private BlockPos openSpaceTarget;
    private int openSpaceAttempts;

    private int pickupStartTick;

    private BlockPos columnBase;


    @Override
    public String displayName() {
        return switch (stage) {
            case COLLECT_GRAVEL -> "Collecting gravel";
            case MOVE_TO_OPEN_SPACE -> "Finding open space";
            case BUILD_COLUMN -> "Building gravel column";
            case MINE_COLUMN -> "Farming flint";
            case PICKUP_DROPS -> "Picking up flint";
            case DONE -> "Done";
        };
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

        if (flint >= target && stage != Stage.PICKUP_DROPS && stage != Stage.DONE) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Flint] Target reached: {}/{}, picking up nearby drops", flint, target);
            enterStage(Stage.PICKUP_DROPS);
            return false;
        }

        switch (stage) {
            case COLLECT_GRAVEL -> tickCollectGravel(mc);
            case MOVE_TO_OPEN_SPACE -> tickMoveToOpenSpace(mc);
            case BUILD_COLUMN -> tickBuildColumn(mc);
            case MINE_COLUMN -> tickMineColumn(mc);
            case PICKUP_DROPS -> tickPickupDrops(mc);
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
                ChatPilotMod.LOGGER.info("[ChatPilot][Flint] No safe gravel-column spot here, moving to open space");
                enterStage(Stage.MOVE_TO_OPEN_SPACE);
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
            ChatPilotMod.LOGGER.info("[ChatPilot][Flint] Mine cycle timeout, collecting drops");
            columnBase = null;
            enterStage(Stage.PICKUP_DROPS);
            return;
        }

        if (!hasAnyGravelInColumn(mc, columnBase)) {
            airTicksAtBase++;

            if (airTicksAtBase > 15) {
                // Column mined out - go pick up the flint/gravel it dropped
                // before doing anything else, so nothing is left behind.
                enterStage(Stage.PICKUP_DROPS);
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
            case MOVE_TO_OPEN_SPACE -> {
                ChatPilotClient.BARITONE.hardReset();
                columnBase = null;
                placedHeight = 0;
                openSpaceTarget = null;
            }

            case BUILD_COLUMN -> {
                ChatPilotClient.BARITONE.hardReset();
                placedHeight = 0;
                openSpaceTarget = null;
            }


            case MINE_COLUMN -> ChatPilotClient.BARITONE.hardReset();

            case PICKUP_DROPS -> {
                ChatPilotClient.BARITONE.hardReset();
                pickupStartTick = clientTick();
            }

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

        if (stage == Stage.MOVE_TO_OPEN_SPACE) {
            openSpaceTarget = null;
            columnBase = null;
            placedHeight = 0;
            enterStage(Stage.BUILD_COLUMN);
            return true;
        }

        if (stage == Stage.PICKUP_DROPS) {
            advanceFromPickup(MinecraftClient.getInstance());
            return true;
        }

        columnBase = null;
        placedHeight = 0;
        openSpaceTarget = null;
        enterStage(Stage.MOVE_TO_OPEN_SPACE);
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
    /**
     * Called after a drop-collection pass. If the flint target is met the task
     * finishes; otherwise it loops back to mining another column. This makes
     * drop pickup happen after EVERY column, so flint/gravel is never left
     * scattered behind when the task ends.
     */
    private void advanceFromPickup(MinecraftClient mc) {
        releaseKeys();

        int flint = countItem(mc.player, Items.FLINT);
        int target = Math.max(1, ChatPilotClient.CONFIG.flintTargetCount);

        if (flint >= target) {
            enterStage(Stage.DONE);
        } else if (countItem(mc.player, Items.GRAVEL) > 0) {
            enterStage(Stage.BUILD_COLUMN);
        } else {
            enterStage(Stage.COLLECT_GRAVEL);
        }
    }

    private void tickPickupDrops(MinecraftClient mc) {
        ChatPilotClient.BARITONE.stop();

        ItemEntity nearest = findNearestFlintOrGravelDrop(mc);

        if (nearest == null) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Flint] Drops collected");
            advanceFromPickup(mc);
            return;
        }

        if (clientTick() - pickupStartTick > PICKUP_TIMEOUT_TICKS) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Flint] Pickup timeout");
            advanceFromPickup(mc);
            return;
        }

        lookAt(mc.player, nearest.getPos());

        double dist2 = mc.player.squaredDistanceTo(nearest);

        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(false);
        mc.options.backKey.setPressed(false);

        // Small wiggle helps if the item is on the edge of a block.
        boolean wiggleLeft = ((clientTick() / 10) % 2) == 0;
        mc.options.leftKey.setPressed(dist2 < 2.0 && wiggleLeft);
        mc.options.rightKey.setPressed(dist2 < 2.0 && !wiggleLeft);
    }
    private static ItemEntity findNearestFlintOrGravelDrop(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.world == null) return null;

        Box box = mc.player.getBoundingBox().expand(PICKUP_RADIUS);

        ItemEntity best = null;
        double bestDist = PICKUP_RADIUS * PICKUP_RADIUS;

        for (ItemEntity item : mc.world.getEntitiesByClass(ItemEntity.class, box, FlintTask::isWantedDrop)) {
            double d = item.squaredDistanceTo(mc.player);

            if (d < bestDist) {
                bestDist = d;
                best = item;
            }
        }

        return best;
    }

    private static BlockPos findOpenBuildStandPos(MinecraftClient mc, int columnHeight) {
        if (mc == null || mc.player == null || mc.world == null) return null;

        BlockPos origin = mc.player.getBlockPos();

        for (int radius = 4; radius <= 24; radius += 4) {
            for (int dy = -4; dy <= 6; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;

                        BlockPos stand = origin.add(dx, dy, dz);

                        if (!isGoodFlintStandSpot(mc, stand, columnHeight)) {
                            continue;
                        }

                        return stand.toImmutable();
                    }
                }
            }
        }

        return null;
    }

    private static boolean isGoodFlintStandSpot(MinecraftClient mc, BlockPos stand, int columnHeight) {
        if (!isSafeStandSpot(mc, stand)) return false;

        // Need some breathing room around the bot, not just a 1-wide tunnel.
        int clear = 0;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos feet = stand.add(dx, 0, dz);
                BlockPos head = feet.up();

                if (mc.world.isAir(feet) && mc.world.isAir(head)
                        && mc.world.getFluidState(feet).isEmpty()
                        && mc.world.getFluidState(head).isEmpty()) {
                    clear++;
                }
            }
        }

        if (clear < 6) return false;

        return hasValidColumnBaseNearStand(mc, stand, columnHeight);
    }

    private static boolean hasValidColumnBaseNearStand(MinecraftClient mc, BlockPos stand, int height) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (Math.abs(dx) + Math.abs(dz) < 2) continue;

                BlockPos base = stand.add(dx, 0, dz);

                if (isValidColumnBaseForStand(mc, stand, base, height)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isValidColumnBaseForStand(MinecraftClient mc, BlockPos stand, BlockPos base, int height) {
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

        // Approximate reach from where the player will stand.
        Vec3d eye = Vec3d.ofBottomCenter(stand).add(0.0, 1.62, 0.0);
        return eye.squaredDistanceTo(Vec3d.ofCenter(base)) <= 20.25;
    }

    private static boolean isSafeStandSpot(MinecraftClient mc, BlockPos feet) {
        if (mc.world == null) return false;

        BlockPos head = feet.up();
        BlockPos floor = feet.down();

        if (!mc.world.getBlockState(feet).getCollisionShape(mc.world, feet).isEmpty()) {
            return false;
        }

        if (!mc.world.getBlockState(head).getCollisionShape(mc.world, head).isEmpty()) {
            return false;
        }

        if (mc.world.getBlockState(floor).getCollisionShape(mc.world, floor).isEmpty()) {
            return false;
        }

        if (!mc.world.getFluidState(feet).isEmpty()) return false;
        if (!mc.world.getFluidState(head).isEmpty()) return false;

        return true;
    }

    private void tickMoveToOpenSpace(MinecraftClient mc) {
        int height = Math.max(1, Math.min(4, ChatPilotClient.CONFIG.flintTowerHeight));

        if (openSpaceTarget == null) {
            openSpaceTarget = findOpenBuildStandPos(mc, height);

            if (openSpaceTarget == null) {
                openSpaceAttempts++;

                ChatPilotMod.LOGGER.info(
                        "[ChatPilot][Flint] Could not find open flint area nearby, exploring ({})",
                        openSpaceAttempts
                );

                ChatPilotClient.BARITONE.run("explore");

                if (ticksInStage() > 20 * 8) {
                    enterStage(Stage.BUILD_COLUMN);
                }

                return;
            }

            ChatPilotMod.LOGGER.info(
                    "[ChatPilot][Flint] Walking to open flint build area at {}",
                    openSpaceTarget
            );

            ChatPilotClient.BARITONE.gotoNear(openSpaceTarget, 2);
        }

        if (mc.player.getBlockPos().getSquaredDistance(openSpaceTarget) <= 4.0) {
            ChatPilotClient.BARITONE.hardReset();
            enterStage(Stage.BUILD_COLUMN);
            return;
        }

        if (ticksInStage() > 20 * 20) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Flint] Open-space walk timed out, trying another spot");
            openSpaceTarget = null;
            enterStage(Stage.MOVE_TO_OPEN_SPACE);
            return;
        }

        if (!ChatPilotClient.BARITONE.isPathing()) {
            ChatPilotClient.BARITONE.gotoNear(openSpaceTarget, 2);
        }
    }

    private static boolean isWantedDrop(ItemEntity item) {
        if (item == null || !item.isAlive()) return false;

        ItemStack stack = item.getStack();

        return !stack.isEmpty()
                && (stack.isOf(Items.FLINT) || stack.isOf(Items.GRAVEL));
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
