package com.grammacrackers.chatpilot.tasks;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * After a task ends or times out we run this chain:
 *
 *   0. If deep underground / near bedrock, escape upward first.
 *      If Baritone cannot surface, pillar upward with inventory blocks.
 *   1. Return through the mining exit/staging area.
 *   2. Walk home.
 *   3. If cactus is configured, toss trash items toward it.
 *   4. Drop remaining non-tool items in hopper.
 *   5. For each nearby chest, dump non-essential items.
 *   6. Walk back to bed, declare done.
 *
 * Safety:
 *   - If return-home makes no movement progress for too long, aborts and finishes
 *     so voting can reopen instead of being stuck forever.
 */
public class ReturnHomeAndDepositTask implements Task {

    private enum Stage {
        ESCAPE_UNDERGROUND,
        EXIT_MINING_AREA,
        WALK_HOME,
        CACTUS_GO,
        CACTUS_AIM,
        CACTUS_DROP,
        HOPPER_GO,
        HOPPER_OPEN,
        HOPPER_DEPOSIT,
        HOPPER_CLOSE,
        NEXT_CHEST,
        WALK_TO_CHEST,
        OPEN_CHEST,
        TRANSFER,
        CLOSE_CHEST,
        FINAL_RETURN,
        DONE
    }

    private static final int RETURN_NO_PROGRESS_TIMEOUT_TICKS = 20 * 60;
    private static final int ESCAPE_SURFACE_FIRST_TICKS = 20 * 25;
    private static final int ESCAPE_TOTAL_TIMEOUT_TICKS = 20 * 90;

    private Stage stage = Stage.WALK_HOME;
    private int stageStartTick;

    private final Deque<BlockPos> chestQueue = new ArrayDeque<>();

    private BlockPos currentChest;
    private BlockPos miningExitPos;

    private int transferCursor;
    private int hopperDepositCursor;
    private int cactusDropCursor;

    private Vec3d lastReturnProgressPos = null;
    private int lastReturnProgressCheckTick = 0;
    private int noReturnProgressTicks = 0;

    @Override
    public String displayName() {
        return switch (stage) {
            case ESCAPE_UNDERGROUND -> "Escaping underground";
            case EXIT_MINING_AREA -> "Leaving mine";
            case WALK_HOME, FINAL_RETURN -> "Heading home";
            case CACTUS_GO, CACTUS_AIM, CACTUS_DROP -> "Tossing trash on cactus";
            case HOPPER_GO, HOPPER_OPEN, HOPPER_DEPOSIT, HOPPER_CLOSE -> "Putting items in hopper";
            case NEXT_CHEST, WALK_TO_CHEST, OPEN_CHEST, TRANSFER, CLOSE_CHEST -> "Storing items";
            case DONE -> "Done";
        };
    }

    @Override
    public String id() {
        return "return_home";
    }

    @Override
    public void start() {
        if (!ChatPilotClient.HOME.hasHome()) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] No home set, skipping return");
            stage = Stage.DONE;
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();

        chestQueue.clear();
        currentChest = null;
        transferCursor = 0;
        hopperDepositCursor = 0;
        cactusDropCursor = 0;

        miningExitPos = MiningStaging.stagingSurfacePos(
                mc,
                ChatPilotClient.CONFIG.miningReturnExitDistanceFromHome
        );

        resetReturnProgress(mc);

        if (shouldEscapeUndergroundFirst(mc)) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][ReturnHome] Underground return detected, surfacing first");
            enterStage(Stage.ESCAPE_UNDERGROUND);
            ChatPilotClient.BARITONE.hardReset();
            ChatPilotClient.BARITONE.run("surface");
            return;
        }

        enterStage(Stage.EXIT_MINING_AREA);

        ChatPilotMod.LOGGER.info(
                "[ChatPilot] Returning via outside mining exit point {}",
                miningExitPos
        );

        ChatPilotClient.BARITONE.gotoNear(
                miningExitPos,
                ChatPilotClient.CONFIG.miningStagingArrivalRadius
        );
    }

    @Override
    public boolean tick() {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.player == null || mc.world == null) {
            return false;
        }

        if (returnHomeNoProgressTimeout(mc)) {
            ChatPilotMod.LOGGER.warn(
                    "[ChatPilot][ReturnHome] No movement progress for {}s in stage {}, aborting return so voting can reopen",
                    RETURN_NO_PROGRESS_TIMEOUT_TICKS / 20,
                    stage
            );

            releaseKeys();
            ChatPilotClient.BARITONE.hardReset();

            if (mc.player != null) {
                mc.player.closeHandledScreen();
            }

            enterStage(Stage.DONE);
            return true;
        }

        switch (stage) {
            case ESCAPE_UNDERGROUND -> tickEscapeUnderground(mc);

            case EXIT_MINING_AREA -> {
                if (miningExitPos == null) {
                    enterStage(Stage.WALK_HOME);
                    ChatPilotClient.BARITONE.gotoNear(ChatPilotClient.HOME.getBedPos(), 3);
                    break;
                }

                int radius = Math.max(4, ChatPilotClient.CONFIG.miningStagingArrivalRadius);

                if (MiningStaging.isNearXZ(mc.player.getBlockPos(), miningExitPos, radius)
                        || ticksInStage() > 20 * 120) {
                    releaseKeys();
                    ChatPilotClient.BARITONE.hardReset();
                    enterStage(Stage.WALK_HOME);
                    ChatPilotClient.BARITONE.gotoNear(ChatPilotClient.HOME.getBedPos(), 3);
                } else if (!ChatPilotClient.BARITONE.isPathing()) {
                    ChatPilotClient.BARITONE.gotoNear(miningExitPos, radius);
                }
            }

            case WALK_HOME -> {
                BlockPos bed = ChatPilotClient.HOME.getBedPos();

                if (shouldEscapeUndergroundFirst(mc)) {
                    enterStage(Stage.ESCAPE_UNDERGROUND);
                    ChatPilotClient.BARITONE.hardReset();
                    ChatPilotClient.BARITONE.run("surface");
                    break;
                }

                if (mc.player.getBlockPos().getSquaredDistance(bed) < 25 || ticksInStage() > 20 * 90) {
                    ChatPilotClient.BARITONE.hardReset();
                    advanceFromHome();
                } else if (!ChatPilotClient.BARITONE.isPathing()) {
                    ChatPilotClient.BARITONE.gotoNear(bed, 3);
                }
            }

            case CACTUS_GO -> {
                BlockPos cactus = ChatPilotClient.HOME.getCactusPos();

                if (cactus == null) {
                    advanceFromCactus();
                    break;
                }

                if (mc.player.getBlockPos().getSquaredDistance(cactus) < 9 || ticksInStage() > 20 * 30) {
                    ChatPilotClient.BARITONE.hardReset();
                    enterStage(Stage.CACTUS_AIM);
                } else if (!ChatPilotClient.BARITONE.isPathing()) {
                    ChatPilotClient.BARITONE.gotoNear(cactus, 2);
                }
            }

            case CACTUS_AIM -> {
                BlockPos cactus = ChatPilotClient.HOME.getCactusPos();

                if (cactus == null) {
                    advanceFromCactus();
                    break;
                }

                aimAtBlock(mc, cactus);
                cactusDropCursor = 0;
                enterStage(Stage.CACTUS_DROP);
            }

            case CACTUS_DROP -> {
                if (cactusDropCursor >= 36) {
                    ChatPilotMod.LOGGER.info("[ChatPilot] Cactus drop pass complete");
                    advanceFromCactus();
                    break;
                }

                int invSlot = cactusDropCursor++;
                ItemStack stack = mc.player.getInventory().getStack(invSlot);

                if (!stack.isEmpty() && isTrash(stack)) {
                    int handlerSlot = inventorySlotToHandlerSlot(invSlot);
                    int syncId = mc.player.playerScreenHandler.syncId;

                    try {
                        mc.interactionManager.clickSlot(
                                syncId,
                                handlerSlot,
                                1,
                                SlotActionType.THROW,
                                mc.player
                        );
                    } catch (Throwable t) {
                        ChatPilotMod.LOGGER.warn("[ChatPilot] Cactus throw failed for slot {}", invSlot, t);
                    }
                }
            }

            case HOPPER_GO -> {
                BlockPos hopper = ChatPilotClient.HOME.getHopperPos();

                if (hopper == null) {
                    enterStage(Stage.NEXT_CHEST);
                    break;
                }

                if (mc.player.getBlockPos().getSquaredDistance(hopper) < 9 || ticksInStage() > 20 * 30) {
                    ChatPilotClient.BARITONE.hardReset();
                    aimAtBlock(mc, hopper);
                    enterStage(Stage.HOPPER_OPEN);
                } else if (!ChatPilotClient.BARITONE.isPathing()) {
                    ChatPilotClient.BARITONE.gotoNear(hopper, 1);
                }
            }

            case HOPPER_OPEN -> {
                BlockPos hopper = ChatPilotClient.HOME.getHopperPos();

                if (hopper == null) {
                    enterStage(Stage.NEXT_CHEST);
                    break;
                }

                if (mc.player.currentScreenHandler instanceof net.minecraft.screen.HopperScreenHandler) {
                    hopperDepositCursor = 0;
                    ChatPilotMod.LOGGER.info(
                            "[ChatPilot] Hopper opened, depositing {} items",
                            inventoryDepositCount(mc.player)
                    );
                    enterStage(Stage.HOPPER_DEPOSIT);
                    break;
                }

                if (ticksInStage() > 20 * 5) {
                    ChatPilotMod.LOGGER.info("[ChatPilot] Hopper open timed out, skipping to chests");
                    enterStage(Stage.NEXT_CHEST);
                    break;
                }

                if (ticksInStage() % 10 == 0) {
                    openContainerBlock(hopper);
                }
            }

            case HOPPER_DEPOSIT -> {
                ScreenHandler sh = mc.player.currentScreenHandler;

                if (!(sh instanceof net.minecraft.screen.HopperScreenHandler)) {
                    enterStage(Stage.NEXT_CHEST);
                    break;
                }

                int hopperSize = 5;

                if (hopperDepositCursor >= 36) {
                    enterStage(Stage.HOPPER_CLOSE);
                    break;
                }

                int handlerSlot = hopperSize + hopperDepositCursor++;
                ItemStack stack = sh.getSlot(handlerSlot).getStack();

                if (!stack.isEmpty() && shouldDeposit(stack)) {
                    mc.interactionManager.clickSlot(
                            sh.syncId,
                            handlerSlot,
                            0,
                            SlotActionType.QUICK_MOVE,
                            mc.player
                    );
                }
            }

            case HOPPER_CLOSE -> {
                mc.player.closeHandledScreen();
                enterStage(Stage.NEXT_CHEST);
            }

            case NEXT_CHEST -> {
                if (chestQueue.isEmpty()) {
                    chestQueue.addAll(
                            ChatPilotClient.HOME.findNearbyChests(
                                    mc.world,
                                    ChatPilotClient.CONFIG.chestSearchRadius
                            )
                    );
                }

                if (chestQueue.isEmpty() || inventoryDepositCount(mc.player) == 0) {
                    enterStage(Stage.FINAL_RETURN);
                    ChatPilotClient.BARITONE.gotoNear(ChatPilotClient.HOME.getBedPos(), 2);
                } else {
                    currentChest = chestQueue.pollFirst();
                    enterStage(Stage.WALK_TO_CHEST);
                    ChatPilotClient.BARITONE.gotoNear(currentChest, 1);
                }
            }

            case WALK_TO_CHEST -> {
                if (currentChest == null) {
                    enterStage(Stage.NEXT_CHEST);
                    break;
                }

                if (mc.player.getBlockPos().getSquaredDistance(currentChest) < 9 || ticksInStage() > 20 * 30) {
                    ChatPilotClient.BARITONE.hardReset();
                    enterStage(Stage.OPEN_CHEST);
                } else if (!ChatPilotClient.BARITONE.isPathing()) {
                    ChatPilotClient.BARITONE.gotoNear(currentChest, 1);
                }
            }

            case OPEN_CHEST -> {
                if (openChest(currentChest)) {
                    enterStage(Stage.TRANSFER);
                    transferCursor = 0;
                } else if (ticksInStage() > 20 * 5) {
                    enterStage(Stage.NEXT_CHEST);
                }
            }

            case TRANSFER -> {
                ScreenHandler sh = mc.player.currentScreenHandler;

                if (!(sh instanceof GenericContainerScreenHandler chest)) {
                    enterStage(Stage.NEXT_CHEST);
                    break;
                }

                int chestSize = chest.getRows() * 9;
                int playerSlotInHandler = chestSize + transferCursor;

                if (transferCursor >= 36) {
                    enterStage(Stage.CLOSE_CHEST);
                    break;
                }

                ItemStack stack = sh.getSlot(playerSlotInHandler).getStack();

                if (!stack.isEmpty() && shouldDeposit(stack)) {
                    mc.interactionManager.clickSlot(
                            sh.syncId,
                            playerSlotInHandler,
                            0,
                            SlotActionType.QUICK_MOVE,
                            mc.player
                    );
                }

                transferCursor++;
            }

            case CLOSE_CHEST -> {
                mc.player.closeHandledScreen();
                enterStage(Stage.NEXT_CHEST);
            }

            case FINAL_RETURN -> {
                BlockPos bed = ChatPilotClient.HOME.getBedPos();

                if (mc.player.getBlockPos().getSquaredDistance(bed) < 9 || ticksInStage() > 20 * 60) {
                    ChatPilotClient.BARITONE.hardReset();
                    enterStage(Stage.DONE);
                } else if (!ChatPilotClient.BARITONE.isPathing()) {
                    ChatPilotClient.BARITONE.gotoNear(bed, 2);
                }
            }

            case DONE -> {
                return true;
            }
        }

        return false;
    }

    private void tickEscapeUnderground(MinecraftClient mc) {
        int y = mc.player.getBlockY();

        int surfaceY = surfaceYAt(mc, mc.player.getBlockX(), mc.player.getBlockZ());

        if (y >= surfaceY - 5 || ticksInStage() > ESCAPE_TOTAL_TIMEOUT_TICKS) {
            ChatPilotMod.LOGGER.info("[ChatPilot][ReturnHome] Underground escape finished/timeout, moving toward exit/home");

            releaseKeys();
            ChatPilotClient.BARITONE.hardReset();

            enterStage(Stage.EXIT_MINING_AREA);

            if (miningExitPos != null) {
                ChatPilotClient.BARITONE.gotoNear(
                        miningExitPos,
                        Math.max(4, ChatPilotClient.CONFIG.miningStagingArrivalRadius)
                );
            } else {
                ChatPilotClient.BARITONE.gotoNear(ChatPilotClient.HOME.getBedPos(), 3);
            }

            return;
        }

        if (ticksInStage() < ESCAPE_SURFACE_FIRST_TICKS) {
            if (!ChatPilotClient.BARITONE.isPathing()) {
                ChatPilotClient.BARITONE.run("surface");
            }

            return;
        }

        // Baritone did not solve it quickly. This handles vertical shafts / bedrock traps.
        ChatPilotClient.BARITONE.stop();
        tryPillarUp(mc);
    }

    /** Pick the next stage after WALK_HOME based on what features are configured. */
    private void advanceFromHome() {
        if (ChatPilotClient.HOME.hasCactus() && hasAnyTrash()) {
            enterStage(Stage.CACTUS_GO);
            ChatPilotClient.BARITONE.gotoNear(ChatPilotClient.HOME.getCactusPos(), 2);
        } else if (ChatPilotClient.HOME.hasHopper()) {
            enterStage(Stage.HOPPER_GO);
            ChatPilotClient.BARITONE.gotoNear(ChatPilotClient.HOME.getHopperPos(), 1);
        } else {
            enterStage(Stage.NEXT_CHEST);
        }
    }

    /** Pick the next stage after the cactus pass: hopper or chests. */
    private void advanceFromCactus() {
        if (ChatPilotClient.HOME.hasHopper()) {
            enterStage(Stage.HOPPER_GO);
            ChatPilotClient.BARITONE.gotoNear(ChatPilotClient.HOME.getHopperPos(), 1);
        } else {
            enterStage(Stage.NEXT_CHEST);
        }
    }

    private boolean hasAnyTrash() {
        PlayerEntity p = MinecraftClient.getInstance().player;

        if (p == null) {
            return false;
        }

        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getStack(i);

            if (!s.isEmpty() && isTrash(s)) {
                return true;
            }
        }

        return false;
    }

    private void enterStage(Stage next) {
        stage = next;
        stageStartTick = clientTick();
        resetReturnProgress(MinecraftClient.getInstance());

        if (next == Stage.DONE) {
            releaseKeys();
        }
    }

    private boolean openChest(BlockPos pos) {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            return false;
        }

        if (pos == null || !(mc.world.getBlockEntity(pos) instanceof ChestBlockEntity)) {
            return false;
        }

        return openContainerBlock(pos);
    }

    /**
     * Right-click a container block to open its UI. Returns true if the
     * interaction was accepted by the client. Screen handler may take a tick
     * or two to attach, so callers should poll currentScreenHandler.
     */
    private boolean openContainerBlock(BlockPos pos) {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.player == null || mc.world == null || mc.interactionManager == null || pos == null) {
            return false;
        }

        Vec3d hit = Vec3d.ofCenter(pos);
        BlockHitResult hr = new BlockHitResult(hit, Direction.UP, pos, false);

        aimAtBlock(mc, pos);

        var result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hr);
        return result.isAccepted();
    }

    private void aimAtBlock(MinecraftClient mc, BlockPos pos) {
        if (mc.player == null || pos == null) {
            return;
        }

        Vec3d eye = mc.player.getEyePos();
        Vec3d hc = Vec3d.ofCenter(pos);

        double dx = hc.x - eye.x;
        double dy = hc.y - eye.y;
        double dz = hc.z - eye.z;

        double horiz = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));

        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
    }

    private static boolean shouldEscapeUndergroundFirst(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.world == null) {
            return false;
        }

        int y = mc.player.getBlockY();
        int bottom = mc.world.getBottomY();

        if (y <= bottom + 8) {
            return true;
        }

        int surfaceY = surfaceYAt(mc, mc.player.getBlockX(), mc.player.getBlockZ());

        return y < surfaceY - 20;
    }

    private static int surfaceYAt(MinecraftClient mc, int x, int z) {
        if (mc == null || mc.world == null) {
            return 64;
        }

        try {
            return mc.world.getTopY(
                    Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                    x,
                    z
            );
        } catch (Throwable ignored) {
            return 64;
        }
    }

    private boolean returnHomeNoProgressTimeout(MinecraftClient mc) {
        if (!isMovementStage(stage)) {
            resetReturnProgress(mc);
            return false;
        }

        if (mc == null || mc.player == null) {
            return false;
        }

        int now = clientTick();

        if (lastReturnProgressPos == null) {
            resetReturnProgress(mc);
            return false;
        }

        if (now - lastReturnProgressCheckTick < 20) {
            return false;
        }

        double moved = mc.player.getPos().distanceTo(lastReturnProgressPos);

        if (moved < 1.0) {
            noReturnProgressTicks += now - lastReturnProgressCheckTick;
        } else {
            noReturnProgressTicks = 0;
            lastReturnProgressPos = mc.player.getPos();
        }

        lastReturnProgressCheckTick = now;

        return noReturnProgressTicks > RETURN_NO_PROGRESS_TIMEOUT_TICKS;
    }

    private static boolean isMovementStage(Stage s) {
        return switch (s) {
            case ESCAPE_UNDERGROUND,
                 EXIT_MINING_AREA,
                 WALK_HOME,
                 CACTUS_GO,
                 HOPPER_GO,
                 WALK_TO_CHEST,
                 FINAL_RETURN -> true;

            default -> false;
        };
    }

    private void resetReturnProgress(MinecraftClient mc) {
        lastReturnProgressCheckTick = clientTick();
        noReturnProgressTicks = 0;

        if (mc != null && mc.player != null) {
            lastReturnProgressPos = mc.player.getPos();
        } else {
            lastReturnProgressPos = null;
        }
    }

    private static void tryPillarUp(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.world == null || mc.interactionManager == null) {
            return;
        }

        if (!selectPlaceableBlock(mc)) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][ReturnHome] No placeable blocks for pillar escape");
            return;
        }

        mc.options.jumpKey.setPressed(true);
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);

        // Look down and place below while jumping.
        mc.player.setPitch(88.0f);

        BlockPos below = mc.player.getBlockPos().down();

        BlockHitResult hit = new BlockHitResult(
                Vec3d.ofCenter(below).add(0.0, 0.5, 0.0),
                Direction.UP,
                below,
                false
        );

        try {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            mc.player.swingHand(Hand.MAIN_HAND);
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][ReturnHome] Pillar placement failed", t);
        }
    }

    private static boolean selectPlaceableBlock(MinecraftClient mc) {
        if (mc.player == null || mc.interactionManager == null) {
            return false;
        }

        String[] preferred = {
                "cobblestone",
                "cobbled_deepslate",
                "deepslate",
                "dirt",
                "stone",
                "andesite",
                "diorite",
                "granite",
                "netherrack"
        };

        for (String wanted : preferred) {
            int slot = findBlockSlot(mc, wanted);

            if (slot >= 0) {
                selectInventorySlot(mc, slot, 8);
                return true;
            }
        }

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);

            if (stack.isEmpty()) {
                continue;
            }

            if (!(stack.getItem() instanceof BlockItem)) {
                continue;
            }

            String path = Registries.ITEM.getId(stack.getItem()).getPath();

            if (path.contains("chest")) continue;
            if (path.contains("bed")) continue;
            if (path.contains("torch")) continue;
            if (path.contains("crafting_table")) continue;
            if (path.contains("furnace")) continue;
            if (path.contains("hopper")) continue;
            if (path.contains("cactus")) continue;

            selectInventorySlot(mc, i, 8);
            return true;
        }

        return false;
    }

    private static int findBlockSlot(MinecraftClient mc, String pathName) {
        if (mc.player == null) {
            return -1;
        }

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);

            if (stack.isEmpty()) {
                continue;
            }

            String path = Registries.ITEM.getId(stack.getItem()).getPath();

            if (path.equals(pathName)) {
                return i;
            }
        }

        return -1;
    }

    private static void selectInventorySlot(MinecraftClient mc, int invSlot, int preferredHotbarSlot) {
        if (mc.player == null || mc.interactionManager == null) {
            return;
        }

        if (invSlot >= 0 && invSlot <= 8) {
            mc.player.getInventory().selectedSlot = invSlot;
            return;
        }

        int hotbarSlot = preferredHotbarSlot;

        if (hotbarSlot < 0 || hotbarSlot > 8) {
            hotbarSlot = 8;
        }

        int handlerSlot = inventorySlotToHandlerSlot(invSlot);

        if (handlerSlot < 0) {
            return;
        }

        try {
            mc.interactionManager.clickSlot(
                    mc.player.playerScreenHandler.syncId,
                    handlerSlot,
                    hotbarSlot,
                    SlotActionType.SWAP,
                    mc.player
            );

            mc.player.getInventory().selectedSlot = hotbarSlot;
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][ReturnHome] Could not swap placeable block to hotbar", t);
        }
    }

    /**
     * Map an inventory slot index (0-35: hotbar 0-8 + main inventory 9-35)
     * to the equivalent slot in the player's screen handler. The hotbar is
     * 36-44 in the screen handler, while the main inventory shares indices
     * 9-35 with the inventory itself.
     */
    private static int inventorySlotToHandlerSlot(int invSlot) {
        if (invSlot >= 0 && invSlot <= 8) {
            return 36 + invSlot;
        }

        if (invSlot >= 9 && invSlot <= 35) {
            return invSlot;
        }

        return -1;
    }

    private static int inventoryDepositCount(PlayerEntity p) {
        int n = 0;

        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getStack(i);

            if (!s.isEmpty() && shouldDeposit(s)) {
                n += s.getCount();
            }
        }

        return n;
    }

    /** Keep tools/weapons/armour/food/torches/bed/rod; deposit everything else after cactus pass. */
    private static boolean shouldDeposit(ItemStack s) {
        if (s.isEmpty()) {
            return false;
        }

        var item = s.getItem();

        if (item == Items.FLINT) return true;
        if (item == Items.GRAVEL && ChatPilotClient.CONFIG.keepGravelForFlintTask) return false;

        if (item == Items.TORCH) return false;
        if (item == Items.FISHING_ROD) return false;

        if (item == Items.BREAD
                || item == Items.COOKED_BEEF
                || item == Items.COOKED_PORKCHOP
                || item == Items.COOKED_CHICKEN
                || item == Items.COOKED_MUTTON
                || item == Items.GOLDEN_APPLE
                || item == Items.APPLE
                || item == Items.CARROT) {
            return false;
        }

        if (item.getComponents().contains(net.minecraft.component.DataComponentTypes.MAX_DAMAGE)) {
            return false;
        }

        String path = Registries.ITEM.getId(item).getPath();

        if (path.endsWith("_boat")) return false;
        if (path.endsWith("_bed")) return false;

        if (path.endsWith("_pickaxe")
                || path.endsWith("_axe")
                || path.endsWith("_sword")
                || path.endsWith("_shovel")
                || path.endsWith("_hoe")) {
            return false;
        }

        if (path.endsWith("_helmet")
                || path.endsWith("_chestplate")
                || path.endsWith("_leggings")
                || path.endsWith("_boots")) {
            return false;
        }

        return true;
    }

    /** Match the item against the trash list in config. */
    private static boolean isTrash(ItemStack s) {
        if (s.isEmpty()) {
            return false;
        }

        var cfg = ChatPilotClient.CONFIG;

        if (cfg == null || cfg.trashItemIds == null || cfg.trashItemIds.isEmpty()) {
            return false;
        }

        // Protect flint-task materials from cactus trash.
        if (cfg.keepGravelForFlintTask && s.isOf(Items.GRAVEL)) {
            return false;
        }

        // Prevent accidents if "minecraft:flint" is ever added to trashItemIds.
        if (s.isOf(Items.FLINT)) {
            return false;
        }

        Identifier id = Registries.ITEM.getId(s.getItem());

        if (id == null) {
            return false;
        }

        String full = id.toString();
        String shortId = id.getPath();

        for (String t : cfg.trashItemIds) {
            if (t == null) {
                continue;
            }

            if (t.equalsIgnoreCase(full) || t.equalsIgnoreCase(shortId)) {
                return true;
            }

            if (t.startsWith("minecraft:") && t.substring(10).equalsIgnoreCase(shortId)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean onStuck() {
        ChatPilotMod.LOGGER.info("[ChatPilot] return-home stuck in {}, soft reset", stage);
        ChatPilotClient.BARITONE.hardReset();

        switch (stage) {
            case ESCAPE_UNDERGROUND -> {
                releaseKeys();
                ChatPilotClient.BARITONE.run("surface");
                return true;
            }

            case EXIT_MINING_AREA -> {
                if (miningExitPos != null) {
                    ChatPilotClient.BARITONE.gotoNear(
                            miningExitPos,
                            Math.max(4, ChatPilotClient.CONFIG.miningStagingArrivalRadius)
                    );
                } else {
                    ChatPilotClient.BARITONE.gotoNear(ChatPilotClient.HOME.getBedPos(), 3);
                }
                return true;
            }

            case WALK_HOME, FINAL_RETURN -> {
                MinecraftClient mc = MinecraftClient.getInstance();

                if (shouldEscapeUndergroundFirst(mc)) {
                    enterStage(Stage.ESCAPE_UNDERGROUND);
                    ChatPilotClient.BARITONE.run("surface");
                } else {
                    ChatPilotClient.BARITONE.gotoNear(ChatPilotClient.HOME.getBedPos(), 3);
                }

                return true;
            }

            case CACTUS_GO -> {
                BlockPos cactus = ChatPilotClient.HOME.getCactusPos();

                if (cactus != null) {
                    ChatPilotClient.BARITONE.gotoNear(cactus, 2);
                } else {
                    advanceFromCactus();
                }

                return true;
            }

            case CACTUS_AIM, CACTUS_DROP -> {
                advanceFromCactus();
                return true;
            }

            case WALK_TO_CHEST -> {
                if (currentChest != null) {
                    ChatPilotClient.BARITONE.gotoNear(currentChest, 1);
                }

                return true;
            }

            case OPEN_CHEST, TRANSFER, CLOSE_CHEST -> {
                MinecraftClient mc = MinecraftClient.getInstance();

                if (mc.player != null) {
                    mc.player.closeHandledScreen();
                }

                enterStage(Stage.NEXT_CHEST);
                return true;
            }

            case HOPPER_GO -> {
                BlockPos hopper = ChatPilotClient.HOME.getHopperPos();

                if (hopper != null) {
                    ChatPilotClient.BARITONE.gotoNear(hopper, 1);
                } else {
                    enterStage(Stage.NEXT_CHEST);
                }

                return true;
            }

            case HOPPER_OPEN, HOPPER_DEPOSIT, HOPPER_CLOSE -> {
                MinecraftClient mc = MinecraftClient.getInstance();

                if (mc.player != null) {
                    mc.player.closeHandledScreen();
                }

                enterStage(Stage.NEXT_CHEST);
                return true;
            }

            case NEXT_CHEST -> {
                enterStage(Stage.FINAL_RETURN);
                ChatPilotClient.BARITONE.gotoNear(ChatPilotClient.HOME.getBedPos(), 2);
                return true;
            }

            case DONE -> {
                return false;
            }
        }

        return false;
    }

    @Override
    public void onCombatStart() {
        releaseKeys();
        ChatPilotClient.BARITONE.stop();
    }

    @Override
    public void onCombatEnd() {
        switch (stage) {
            case ESCAPE_UNDERGROUND -> ChatPilotClient.BARITONE.run("surface");

            case EXIT_MINING_AREA -> {
                if (miningExitPos != null) {
                    ChatPilotClient.BARITONE.gotoNear(
                            miningExitPos,
                            Math.max(4, ChatPilotClient.CONFIG.miningStagingArrivalRadius)
                    );
                }
            }

            case WALK_HOME, FINAL_RETURN -> ChatPilotClient.BARITONE.gotoNear(
                    ChatPilotClient.HOME.getBedPos(),
                    3
            );

            case CACTUS_GO -> {
                BlockPos cactus = ChatPilotClient.HOME.getCactusPos();

                if (cactus != null) {
                    ChatPilotClient.BARITONE.gotoNear(cactus, 2);
                }
            }

            case HOPPER_GO -> {
                BlockPos hopper = ChatPilotClient.HOME.getHopperPos();

                if (hopper != null) {
                    ChatPilotClient.BARITONE.gotoNear(hopper, 1);
                }
            }

            case WALK_TO_CHEST -> {
                if (currentChest != null) {
                    ChatPilotClient.BARITONE.gotoNear(currentChest, 1);
                }
            }

            default -> {
            }
        }
    }

    @Override
    public void cancel() {
        releaseKeys();
        ChatPilotClient.BARITONE.hardReset();

        var p = MinecraftClient.getInstance().player;

        if (p != null) {
            p.closeHandledScreen();
        }
    }

    private static void releaseKeys() {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc == null || mc.options == null) {
            return;
        }

        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
    }

    private int ticksInStage() {
        return clientTick() - stageStartTick;
    }

    private static int clientTick() {
        return (int) (com.grammacrackers.chatpilot.event.TickClock.now() & 0x7fffffff);
    }
}
