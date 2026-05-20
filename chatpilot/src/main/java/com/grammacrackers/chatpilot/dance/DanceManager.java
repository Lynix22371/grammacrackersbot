package com.grammacrackers.chatpilot.dance;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import com.grammacrackers.chatpilot.chat.ChatMessage;
import com.grammacrackers.chatpilot.event.TickClock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;

/**
 * Hype dance system.
 *
 * Behaviour:
 *   - Accumulates USD across YouTube super chat / super sticker / manual
 *     /jewels add submissions.
 *   - When the running total crosses {@code danceJewelThresholdUsd}
 *     (default $5), the bot:
 *       1. Tells the running task to suspend Baritone.
 *       2. Switches the camera to third-person back if configured.
 *       3. Sends the configured chat command (default "/emote dance" for
 *          Essential Mod's emote system).
 *       4. Plays a music sound (default music_disc.cat).
 *       5. Holds for {@code danceDurationSeconds} (default 15).
 *       6. Stops the music, restores perspective, lets the task resume.
 *       7. Subtracts the threshold from the running total so any overflow
 *          carries into the next cycle.
 *
 * Note: the actual "stop the task" path goes through {@link
 * com.grammacrackers.chatpilot.tasks.TaskManager#notifyCombatStart} so we
 * reuse the existing pause-and-resume plumbing. From the task's point of
 * view a dance is just like a combat interruption.
 */
public class DanceManager {

    private double accumulatedUsd  = 0.0;
    private double thresholdLastSeen;

    private boolean dancing = false;
    private long    danceStartTick;
    private int     danceDurationTicks;
    private Perspective savedPerspective;
    private PositionedSoundInstance currentMusic;

    /**
     * Pump per client tick. Updates dance state, ends the dance when the
     * timer expires, and exposes a hook for the HUD to render its banner.
     */
    public void tick() {
        if (!dancing) return;
        long elapsed = TickClock.now() - danceStartTick;
        if (elapsed >= danceDurationTicks) {
            endDance();
        }
    }

    public boolean isDancing() { return dancing; }

    public double getAccumulatedUsd() { return accumulatedUsd; }

    public double getThresholdUsd() {
        if (ChatPilotClient.CONFIG == null) return 5.0;
        return Math.max(0.01, ChatPilotClient.CONFIG.danceJewelThresholdUsd);
    }

    public long getDanceTicksRemaining() {
        if (!dancing) return 0;
        long elapsed = TickClock.now() - danceStartTick;
        return Math.max(0, danceDurationTicks - elapsed);
    }

    /** Adds dollars to the accumulator and triggers a dance if we cross the threshold. */
    public synchronized void addUsd(double usd, String why) {
        if (usd <= 0.0) return;
        accumulatedUsd += usd;
        ChatPilotMod.LOGGER.info("[ChatPilot][Dance] +${} from {} (total ${})",
            String.format("%.2f", usd), why, String.format("%.2f", accumulatedUsd));
        maybeTrigger();
    }

    /** Ingest a chat message; if it carries a tip, count it. */
    public void onChatMessage(ChatMessage msg) {
        if (msg == null) return;
        if (msg.tipUsd > 0.0) {
            addUsd(msg.tipUsd, "tip from " + msg.author + " (" + msg.source + ")");
        }
    }

    /** Manual / external trigger: forces a dance now even if the accumulator is below threshold. */
    public synchronized void forceDance(String why) {
        if (dancing) return;
        ChatPilotMod.LOGGER.info("[ChatPilot][Dance] Forced dance triggered: {}", why);
        startDance();
    }

    /** Reset the running tally; useful from a /jewels reset command. */
    public synchronized void resetAccumulator() {
        accumulatedUsd = 0.0;
        ChatPilotMod.LOGGER.info("[ChatPilot][Dance] accumulator reset");
    }

    private synchronized void maybeTrigger() {
        if (dancing) return;
        double thr = getThresholdUsd();
        if (accumulatedUsd + 1e-6 < thr) return;
        thresholdLastSeen = thr;
        accumulatedUsd -= thr;  // carry overflow into the next cycle
        startDance();
    }

    private void startDance() {
        var mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][Dance] No player, skipping");
            return;
        }
        var cfg = ChatPilotClient.CONFIG;
        dancing = true;
        danceStartTick = TickClock.now();
        danceDurationTicks = Math.max(20, cfg.danceDurationSeconds * 20);

        // 1. Pause the running task (reusing combat-pause plumbing).
        try {
            ChatPilotClient.TASKS.notifyCombatStart();
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][Dance] task pause threw", t);
        }
        // 2. Stop Baritone explicitly so the bot stands still while dancing.
        try { ChatPilotClient.BARITONE.hardReset(); } catch (Throwable ignored) {}

        // 3. Switch perspective (F5).
        if (cfg.danceUseThirdPerson) {
            try {
                savedPerspective = mc.options.getPerspective();
                mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            } catch (Throwable t) {
                ChatPilotMod.LOGGER.warn("[ChatPilot][Dance] perspective switch failed", t);
            }
        }

        // 4. Send emote command. Schedule on the client thread to be safe.
        mc.execute(() -> {
            try {
                if (mc.player == null) return;
                String cmd = cfg.danceCommand == null ? "" : cfg.danceCommand.trim();
                if (cmd.isEmpty()) return;
                if (cmd.startsWith("/")) {
                    // sendChatCommand wants the slashless form.
                    mc.player.networkHandler.sendChatCommand(cmd.substring(1));
                } else {
                    mc.player.networkHandler.sendChatMessage(cmd);
                }
            } catch (Throwable t) {
                ChatPilotMod.LOGGER.warn("[ChatPilot][Dance] emote command failed", t);
            }
        });

        // 5. Play music.
        try {
            SoundEvent ev = resolveMusicEvent(cfg.danceMusicSound);
            if (ev != null) {
                currentMusic = PositionedSoundInstance.master(ev, 1.0F, 1.0F);
                mc.getSoundManager().play(currentMusic);
            }
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][Dance] sound play failed", t);
        }

        ChatPilotMod.LOGGER.info("[ChatPilot][Dance] dancing for {}s (threshold ${})",
            cfg.danceDurationSeconds, String.format("%.2f", thresholdLastSeen));
    }

    private void endDance() {
        if (!dancing) return;
        dancing = false;
        var mc = MinecraftClient.getInstance();
        // Stop music
        try {
            if (currentMusic != null && mc != null) {
                mc.getSoundManager().stop(currentMusic);
            }
        } catch (Throwable ignored) {}
        currentMusic = null;
        // Restore perspective
        try {
            if (savedPerspective != null && mc != null) {
                mc.options.setPerspective(savedPerspective);
            }
        } catch (Throwable ignored) {}
        savedPerspective = null;
        // Resume task via combat-end plumbing
        try {
            ChatPilotClient.TASKS.notifyCombatEnd();
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot][Dance] task resume threw", t);
        }
        ChatPilotMod.LOGGER.info("[ChatPilot][Dance] done, task resumed");
    }

    /**
     * Resolve a SoundEvent by ID string. Falls back to the built-in
     * music_disc.cat constant if the configured ID can't be parsed or
     * registered, so we never crash from a config typo.
     */
    private static SoundEvent resolveMusicEvent(String id) {
        try {
            if (id == null || id.isBlank()) return SoundEvents.MUSIC_DISC_CAT.value();
            Identifier ident = Identifier.tryParse(id);
            if (ident == null) return SoundEvents.MUSIC_DISC_CAT.value();
            SoundEvent ev = Registries.SOUND_EVENT.get(ident);
            if (ev != null) return ev;
        } catch (Throwable ignored) {}
        return SoundEvents.MUSIC_DISC_CAT.value();
    }
}
