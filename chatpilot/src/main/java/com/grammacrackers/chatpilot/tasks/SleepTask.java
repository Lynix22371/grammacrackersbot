package com.grammacrackers.chatpilot.tasks;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Sleep task. Available only when chat votes for it during a night-time
 * vote window. Walks to the saved home bed, right-clicks it, and waits
 * for morning. The auto-bed-tracking in SessionTicker will pick up the
 * bed position the moment grandma sleeps in any new bed, so this task
 * always uses the most recently used bed.
 *
 * Stages:
 *   WALK_TO_BED  → Baritone path to the bed coordinates
 *   ENTER_BED    → right-click the bed once close enough; retry until accepted
 *   SLEEPING     → wait for the world time to roll over to morning, OR for
 *                  the player to be kicked out (mob nearby, etc.). Either way,
 *                  finish the task so the next vote can begin.
 */
public class SleepTask implements Task {

    private enum Stage { WALK_TO_BED, ENTER_BED, SLEEPING, DONE }

    /** Hard timeout in case nothing works (no bed, mob nearby, etc.). */
    private static final int TASK_TIMEOUT_SECONDS = 90;
    /** How long to spend trying to enter the bed before giving up. */
    private static final int ENTER_TIMEOUT_SECONDS = 20;
    /** How long to wait in SLEEPING before forcing finish even if not morning. */
    private static final int SLEEP_TIMEOUT_SECONDS = 30;

    private Stage stage = Stage.WALK_TO_BED;
    private int   stageStartTick;
    private int   taskStartTick;
    private boolean savedForCombat;

    @Override public String displayName() { return "Going to sleep"; }
    @Override public String id() { return "sleep"; }

    @Override
    public void start() {
        taskStartTick  = clientTick();
        stageStartTick = clientTick();
        BlockPos bed = ChatPilotClient.HOME.getBedPos();
        if (bed == null) {
            ChatPilotMod.LOGGER.info("[ChatPilot] Sleep task: no bed set, finishing");
            stage = Stage.DONE;
            return;
        }
        ChatPilotClient.BARITONE.hardReset();
        // gotoNear with radius 2: arrives within Minecraft's 2-block bed sleep
        // range. Using gotoBlock here is the original bug - Baritone tries to
        // stand exactly on the bed (a solid block) and recalculates forever.
        ChatPilotClient.BARITONE.gotoNear(bed, 2);
    }

    @Override
    public boolean tick() {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return false;

        // Hard task timeout — never run forever
        if (clientTick() - taskStartTick > TASK_TIMEOUT_SECONDS * 20) {
            ChatPilotMod.LOGGER.info("[ChatPilot] Sleep task hard-timeout, finishing");
            return true;
        }

        BlockPos bed = ChatPilotClient.HOME.getBedPos();
        if (bed == null) return true;

        switch (stage) {
            case WALK_TO_BED -> {
                if (mc.player.getBlockPos().getSquaredDistance(bed) < 9 || ticksInStage() > 20 * 60) {
                    ChatPilotClient.BARITONE.hardReset();
                    enterStage(Stage.ENTER_BED);
                }
            }
            case ENTER_BED -> {
                // If we are already in bed, advance to SLEEPING
                if (mc.player.isSleeping()) {
                    enterStage(Stage.SLEEPING);
                    break;
                }
                if (ticksInStage() > ENTER_TIMEOUT_SECONDS * 20) {
                    ChatPilotMod.LOGGER.info("[ChatPilot] Could not enter bed, finishing sleep task");
                    return true;
                }
                // Retry the right-click every 10 ticks
                if (ticksInStage() % 10 == 0) {
                    rightClickBed(bed);
                }
            }
            case SLEEPING -> {
                // Done if grandma woke up (server set time to morning, mob nearby, etc.)
                if (!mc.player.isSleeping()) {
                    ChatPilotMod.LOGGER.info("[ChatPilot] Woke up, sleep task complete");
                    return true;
                }
                // Or if we've been "sleeping" for too long (singleplayer time skip
                // sometimes keeps the screen for a few seconds)
                if (ticksInStage() > SLEEP_TIMEOUT_SECONDS * 20) {
                    ChatPilotMod.LOGGER.info("[ChatPilot] Sleep timer reached, finishing");
                    if (mc.player.isSleeping()) {
                        try { mc.player.networkHandler.sendChatCommand(""); } catch (Throwable ignored) {}
                    }
                    return true;
                }
            }
            case DONE -> { return true; }
        }
        return false;
    }

    private void rightClickBed(BlockPos bed) {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.interactionManager == null) return;
        // Look at the bed
        Vec3d eye = mc.player.getEyePos();
        Vec3d hc  = Vec3d.ofCenter(bed);
        double dx = hc.x - eye.x, dy = hc.y - eye.y, dz = hc.z - eye.z;
        double horiz = Math.sqrt(dx*dx + dz*dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
        // Build a synthetic block hit on the top face
        BlockHitResult hr = new BlockHitResult(hc, Direction.UP, bed, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hr);
    }

    private void enterStage(Stage next) {
        stage = next;
        stageStartTick = clientTick();
        ChatPilotMod.LOGGER.info("[ChatPilot] Sleep stage -> {}", next);
    }

    @Override
    public boolean onStuck() {
        ChatPilotClient.BARITONE.hardReset();
        BlockPos bed = ChatPilotClient.HOME.getBedPos();
        if (bed == null) return false;
        if (stage == Stage.WALK_TO_BED) {
            ChatPilotClient.BARITONE.gotoNear(bed, 2);
            return true;
        }
        return false;
    }

    @Override
    public void onCombatStart() {
        savedForCombat = true;
        ChatPilotClient.BARITONE.stop();
    }

    @Override
    public void onCombatEnd() {
        if (!savedForCombat) return;
        savedForCombat = false;
        BlockPos bed = ChatPilotClient.HOME.getBedPos();
        if (bed != null && stage == Stage.WALK_TO_BED) {
            ChatPilotClient.BARITONE.gotoNear(bed, 2);
        }
    }

    @Override
    public void cancel() { ChatPilotClient.BARITONE.hardReset(); }

    /* helpers */

    private int ticksInStage() { return clientTick() - stageStartTick; }

    private static int clientTick() {
        return (int) (com.grammacrackers.chatpilot.event.TickClock.now() & 0x7fffffff);
    }
}
