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

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Return-home and deposit task.
 *
 * Behavior:
 *   1. If the bot is too close to bedrock, manually pillar up first.
 *   2. Return through the mining exit/staging area.
 *   3. Walk home.
 *   4. Toss configured trash items at cactus.
 *   5. Deposit remaining non-essential items into hopper/chests.
 *   6. Return to bed and finish.
 *
 * Important:
 *   - No Baritone "surface" command.
 *   - No bedrock-hole surface searching.
 *   - Return-home never gives up: WALK_HOME and FINAL_RETURN keep re-pathing
 *     until the bot is actually at the bed. Stalls trigger a hard-reset retry.
 */
public class ReturnHomeAndDepositTask implements Task {

    private enum Stage {
        PILLAR_UP_FROM_BEDROCK,
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

    /**
     * In the Overworld, bottom is usually -64.
     * Offset 6 means the pillar-escape only triggers at about y -58 or below -
     * deep enough to be near actual bedrock, not normal deep caves / ancient
     * cities (which sit around y -52).
     */
    private static final int BEDROCK_SAFE_Y_OFFSET = 6;
    private static final int BEDROCK_PILLAR_TIMEOUT_TICKS = 20 * 8;

    /** One stack of cobblestone is kept for travel; gravel is capped at two. */
    private static final int COBBLE_KEEP_LIMIT = 64;
    private static final int GRAVEL_KEEP_LIMIT = 128;

    private Stage stage = Stage.WALK_HOME;
    private int stageStartTick;

    private final Deque<BlockPos> chestQueue = new ArrayDeque<>();

    private BlockPos currentChest;
    private BlockPos miningExitPos;

    private int transferCursor;
    private int hopperDepositCursor;
    private int cactusDropCursor;

    /**
     * How much cobblestone / gravel has been kept (not trashed or deposited)
     * so far this return trip. Used to keep one stack of cobblestone for
     * travel and cap gravel at two stacks. Reset in start().
     */
    private int cobbleKept;
    private int gravelKept;

    /** Closest the bot has gotten to the bed so far in the current stage. */
    private double bestDistToBed = Double.MAX_VALUE;
    private int lastReturnProgressCheckTick = 0;
    private int noReturnProgressTicks = 0;

    /**
     * Latched true once the bot has been stuck long enough that it must be
     * allowed to break blocks to free itself, even inside the no-dig zone.
     * Reset on every stage change.
     */
    private boolean emergencyDig = false;

    /**
     * Latched true once the manual bedrock pillar-escape has failed (e.g. the
     * bot is in an enclosed deep-cave space and cannot pillar up). Baritone
     * then handles getting out, and the pillar stage is never re-entered for
     * the rest of this return trip.
     */
    private boolean bedrockPillarFailed = false;

    @Override
    public String displayName() {
        return switch (stage) {
            case PILLAR_UP_FROM_BEDROCK -> "Escaping bedrock hole";
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

        // Clear any movement key (especially sneak) the previous task may have
        // left pressed, so the bot doesn't crawl home sneaking.
        releaseKeys();

        chestQueue.clear();
        currentChest = null;
        transferCursor = 0;
        hopperDepositCursor = 0;
        cactusDropCursor = 0;
        cobbleKept = 0;
        gravelKept = 0;

        resetReturnProgress(mc);

        // Go straight home. The old flow first detoured to a fixed "mining
        // exit" point ~130 blocks out at a fixed bearing - which sent the bot
        // running away from the house even when it was already right next to
        // it, and stalled for minutes if that fixed point was unreachable.
        // Baritone paths home fine from anywhere (the Sleep task proves it);
        // the no-dig zone still keeps it from digging holes near the house.
        enterStage(Stage.WALK_HOME);

        ChatPilotMod.LOGGER.info("[ChatPilot] Returning home");

        gotoHome(3);
    }

    @Override
    public boolean tick() {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.player == null || mc.world == null) {
            return false;
        }

        if (returnHomeNoProgressTimeout(mc)) {
            recoverReturnProgress(mc);
        }

        applyReturnHomeDigPolicy(mc);

        switch (stage) {
            case PILLAR_UP_FROM_BEDROCK -> tickPillarUpFromBedrock(mc);

            case EXIT_MINING_AREA -> {
                if (shouldPillar(mc)) {
                    ChatPilotMod.LOGGER.warn("[ChatPilot][ReturnHome] Fell back near bedrock, pillaring up");
                    enterStage(Stage.PILLAR_UP_FROM_BEDROCK);
                    break;
                }

                if (miningExitPos == null) {
                    enterStage(Stage.WALK_HOME);
                    gotoHome(3);
                    break;
                }

                int radius = Math.max(4, ChatPilotClient.CONFIG.miningStagingArrivalRadius);

                boolean atExitXZ = MiningStaging.isNearXZ(mc.player.getBlockPos(), miningExitPos, radius);
                boolean surfaced = mc.player.getBlockY() >= miningExitPos.getY() - 4;

                // Only move on once actually surfaced at the exit point, so the
                // walk home happens on top of the ground and the bot does not
                // enter the no-dig zone still underground. The tick cap is a
                // safety hatch in case surfacing genuinely cannot complete.
                if ((atExitXZ && surfaced) || ticksInStage() > 20 * 120) {
                    releaseKeys();
                    ChatPilotClient.BARITONE.hardReset();
                    enterStage(Stage.WALK_HOME);
                    gotoHome(3);
                } else if (!ChatPilotClient.BARITONE.isPathing()) {
                    ChatPilotClient.BARITONE.gotoNear(miningExitPos, radius);
                }
            }

            case WALK_HOME -> {
                if (shouldPillar(mc)) {
                    ChatPilotMod.LOGGER.warn("[ChatPilot][ReturnHome] Too close to bedrock while walking home, pillaring up");
                    enterStage(Stage.PILLAR_UP_FROM_BEDROCK);
                    break;
                }

                BlockPos bed = ChatPilotClient.HOME.getBedPos();

                if (bed == null) {
                    enterStage(Stage.DONE);
                    return true;
                }

                // Only advance once actually home - never on a timer.
                if (mc.player.getBlockPos().getSquaredDistance(bed) < 25) {
                    ChatPilotClient.BARITONE.hardReset();
                    advanceFromHome();
                } else if (!ChatPilotClient.BARITONE.isPathing()) {
                    gotoHome(3);
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

                if (!stack.isEmpty() && shouldThrowAtCactus(stack)) {
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

                if (!stack.isEmpty() && decideDeposit(stack)) {
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
                    gotoHome(2);
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

                if (!stack.isEmpty() && decideDeposit(stack)) {
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

                if (bed == null) {
                    enterStage(Stage.DONE);
                    break;
                }

                // Only finish once actually at the bed - never on a timer.
                if (mc.player.getBlockPos().getSquaredDistance(bed) < 9) {
                    ChatPilotClient.BARITONE.hardReset();
                    enterStage(Stage.DONE);
                } else if (!ChatPilotClient.BARITONE.isPathing()) {
                    gotoHome(2);
                }
            }

            case DONE -> {
                return true;
            }
        }

        return false;
    }

    private void tickPillarUpFromBedrock(MinecraftClient mc) {
        int safeY = safeBedrockY(mc);

        if (mc.player.getBlockY() > safeY) {
            ChatPilotMod.LOGGER.info(
                    "[ChatPilot][ReturnHome] Pillared above danger zone y={} safeY={}, resuming return",
                    mc.player.getBlockY(),
                    safeY
            );

            releaseKeys();
            ChatPilotClient.BARITONE.hardReset();

            enterStage(Stage.EXIT_MINING_AREA);
            gotoMiningExitOrHome();
            return;
        }

        if (ticksInStage() > BEDROCK_PILLAR_TIMEOUT_TICKS) {
            // The manual pillar is not working - the bot is in an enclosed
            // space with no room to jump and place. Stop pillaring and let
            // Baritone path out instead: it can break through and navigate the
            // cave. Latch the failure so this stage is never re-entered.
            ChatPilotMod.LOGGER.warn("[ChatPilot][ReturnHome] Manual pillar stuck, handing off to Baritone");
            bedrockPillarFailed = true;
            releaseKeys();
            ChatPilotClient.BARITONE.hardReset();
            enterStage(Stage.EXIT_MINING_AREA);
            gotoMiningExitOrHome();
            return;
        }

        tryPillarUp(mc);
    }

    private static int safeBedrockY(MinecraftClient mc) {
        if (mc == null || mc.world == null) {
            return -52;
        }

        return mc.world.getBottomY() + BEDROCK_SAFE_Y_OFFSET;
    }

    private static boolean shouldPillarFromBedrock(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.world == null) {
            return false;
        }

        return mc.player.getBlockY() <= safeBedrockY(mc);
    }

    /**
     * The manual bedrock pillar-escape is DISABLED.
     *
     * Its block placement is unreliable - it aims the placement at the wrong
     * spot and frequently fails to place even in fully open space, which left
     * the bot stuck during return-home jumping in place forever.
     *
     * Baritone already ascends out of deep areas correctly on its own: it
     * places blocks to climb as a normal part of pathing. So return-home just
     * lets Baritone path out instead of running the broken manual pillar.
     */
    private boolean shouldPillar(MinecraftClient mc) {
        return false;
    }

    /**
     * Manages Baritone's allowBreak during the return-home flow.
     *
     * Inside the no-dig ring around the house, breaking is normally disabled so
     * the bot does not leave holes near home. But if the bot has been stuck for
     * a while, "emergency dig" latches on and breaking is re-enabled so it can
     * dig itself free instead of freezing forever - actually getting home wins
     * over keeping the area tidy.
     */
    private void applyReturnHomeDigPolicy(MinecraftClient mc) {
        if (ChatPilotClient.BARITONE == null) {
            return;
        }

        // Once the bot has clearly stopped progressing, latch emergency dig.
        if (noReturnProgressTicks > 20 * 12) {
            emergencyDig = true;
        }

        boolean allowBreak = true;

        if (!emergencyDig
                && mc != null && mc.player != null
                && ChatPilotClient.HOME != null && ChatPilotClient.HOME.hasHome()
                && ChatPilotClient.CONFIG != null) {

            BlockPos bed = ChatPilotClient.HOME.getBedPos();

            if (bed != null) {
                int noDig = Math.min(
                        ChatPilotClient.CONFIG.houseNoDigRadius,
                        ChatPilotClient.CONFIG.miningReturnExitDistanceFromHome
                );

                double dx = mc.player.getX() - (bed.getX() + 0.5);
                double dz = mc.player.getZ() - (bed.getZ() + 0.5);

                // Inside the no-dig ring and not stuck: forbid breaking.
                if (dx * dx + dz * dz < (double) noDig * noDig) {
                    allowBreak = false;
                }
            }
        }

        ChatPilotClient.BARITONE.setAllowBreak(allowBreak);
    }

    /**
     * No-progress recovery. The bot must ALWAYS make it home, so instead of
     * aborting the task we hard-reset Baritone and re-issue the current
     * stage's goal. This may run any number of times.
     */
    private void recoverReturnProgress(MinecraftClient mc) {
        ChatPilotMod.LOGGER.warn(
                "[ChatPilot][ReturnHome] No progress in stage {}, hard-resetting and retrying",
                stage
        );

        releaseKeys();

        if (ChatPilotClient.BARITONE != null) {
            ChatPilotClient.BARITONE.hardReset();
        }

        if (mc != null && mc.player != null) {
            mc.player.closeHandledScreen();
        }

        // onStuck() already holds the correct per-stage re-pathing logic.
        onStuck();

        resetReturnProgress(mc);
    }

    private void gotoMiningExitOrHome() {
        if (miningExitPos != null) {
            ChatPilotClient.BARITONE.gotoNear(
                    miningExitPos,
                    Math.max(4, ChatPilotClient.CONFIG.miningStagingArrivalRadius)
            );
        } else {
            gotoHome(3);
        }
    }

    private void gotoHome(int radius) {
        BlockPos bed = ChatPilotClient.HOME.getBedPos();

        if (bed != null) {
            ChatPilotClient.BARITONE.gotoNear(bed, radius);
        }
    }

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
        emergencyDig = false;

        if (next == Stage.PILLAR_UP_FROM_BEDROCK) {
            releaseKeys();

            if (ChatPilotClient.BARITONE != null) {
                ChatPilotClient.BARITONE.hardReset();
            }
        }

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

    private boolean returnHomeNoProgressTimeout(MinecraftClient mc) {
        if (!isMovementStage(stage)) {
            resetReturnProgress(mc);
            return false;
        }

        if (mc == null || mc.player == null) {
            return false;
        }

        BlockPos bed = ChatPilotClient.HOME == null ? null : ChatPilotClient.HOME.getBedPos();

        if (bed == null) {
            return false;
        }

        int now = clientTick();

        if (now - lastReturnProgressCheckTick < 20) {
            return false;
        }

        /*
         * Progress means getting CLOSER TO HOME, not merely moving. A bot that
         * paces back and forth near the no-dig boundary - or circles between
         * spots - covers plenty of distance but makes no progress home. The old
         * "did it move?" check never tripped for that, so the emergency-dig
         * recovery never kicked in. Measure distance to the bed instead.
         */
        double dist = mc.player.getPos().distanceTo(Vec3d.ofCenter(bed));

        if (dist < bestDistToBed - 1.0) {
            bestDistToBed = dist;
            noReturnProgressTicks = 0;
        } else {
            noReturnProgressTicks += now - lastReturnProgressCheckTick;
        }

        lastReturnProgressCheckTick = now;

        return noReturnProgressTicks > RETURN_NO_PROGRESS_TIMEOUT_TICKS;
    }

    private static boolean isMovementStage(Stage s) {
        return switch (s) {
            case PILLAR_UP_FROM_BEDROCK,
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

        BlockPos bed = ChatPilotClient.HOME == null ? null : ChatPilotClient.HOME.getBedPos();

        if (mc != null && mc.player != null && bed != null) {
            bestDistToBed = mc.player.getPos().distanceTo(Vec3d.ofCenter(bed));
        } else {
            bestDistToBed = Double.MAX_VALUE;
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
        mc.options.sprintKey.setPressed(false);

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
        int gravel = 0;

        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getStack(i);

            if (s.isEmpty()) {
                continue;
            }

            // Cobblestone is always kept for travel - never counts as deposit.
            if (s.isOf(Items.COBBLESTONE)) {
                continue;
            }

            // Gravel is counted separately so only the excess over two stacks
            // is treated as depositable.
            if (s.isOf(Items.GRAVEL)) {
                gravel += s.getCount();
                continue;
            }

            if (shouldDeposit(s)) {
                n += s.getCount();
            }
        }

        return n + Math.max(0, gravel - GRAVEL_KEEP_LIMIT);
    }

    /**
     * Whether to throw this stack at the trash cactus. Identical to {@link
     * #isTrash} except one stack of cobblestone is kept back for travel
     * (Baritone uses it to pillar out of holes); cobblestone beyond that is
     * still trashed.
     */
    private boolean shouldThrowAtCactus(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        if (stack.isOf(Items.COBBLESTONE)) {
            if (cobbleKept < COBBLE_KEEP_LIMIT) {
                cobbleKept += stack.getCount();
                return false;
            }
            return true;
        }

        return isTrash(stack);
    }

    /**
     * Whether to deposit this stack into the hopper/chest. Like {@link
     * #shouldDeposit} but keeps cobblestone (for travel) and caps gravel at
     * two stacks, depositing only the excess.
     */
    private boolean decideDeposit(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        // Cobblestone is kept for travel - never deposited.
        if (stack.isOf(Items.COBBLESTONE)) {
            return false;
        }

        // Gravel: keep up to two stacks for the flint task, deposit the rest.
        if (stack.isOf(Items.GRAVEL)) {
            if (gravelKept < GRAVEL_KEEP_LIMIT) {
                gravelKept += stack.getCount();
                return false;
            }
            return true;
        }

        return shouldDeposit(stack);
    }

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

    private static boolean isTrash(ItemStack s) {
        if (s.isEmpty()) {
            return false;
        }

        var cfg = ChatPilotClient.CONFIG;

        if (cfg == null || cfg.trashItemIds == null || cfg.trashItemIds.isEmpty()) {
            return false;
        }

        if (cfg.keepGravelForFlintTask && s.isOf(Items.GRAVEL)) {
            return false;
        }

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

        switch (stage) {
            case PILLAR_UP_FROM_BEDROCK -> {
                tryPillarUp(MinecraftClient.getInstance());
                return true;
            }

            case EXIT_MINING_AREA -> {
                gotoMiningExitOrHome();
                return true;
            }

            case WALK_HOME, FINAL_RETURN -> {
                MinecraftClient mc = MinecraftClient.getInstance();

                if (shouldPillar(mc)) {
                    enterStage(Stage.PILLAR_UP_FROM_BEDROCK);
                } else {
                    gotoHome(3);
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
                gotoHome(2);
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
            case PILLAR_UP_FROM_BEDROCK -> tryPillarUp(MinecraftClient.getInstance());
            case EXIT_MINING_AREA -> gotoMiningExitOrHome();
            case WALK_HOME, FINAL_RETURN -> gotoHome(3);

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