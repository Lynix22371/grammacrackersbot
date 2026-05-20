package com.grammacrackers.chatpilot.tasks;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;

public class TaskManager {

    public enum Phase { IDLE, RUNNING, COMBAT_PAUSED, RETURNING_HOME, DEPOSITING, COOLDOWN }

    private Phase  phase = Phase.IDLE;
    private Phase  prePausePhase = Phase.RUNNING;
    private Task   current;
    private long   startedAtTick;
    private long   maxDurationTicks;
    private int    stuckRecoveriesUsed;
    private int    cooldownTicks;
    private int    combatResumeTicks;
    private int    combatPauseTicks;

    public Phase getPhase() { return phase; }
    public Task  getCurrent() { return current; }
    public String getCurrentLabel() { return current == null ? "Idle" : current.displayName(); }
    public long  getRemainingSeconds() {
        if (phase != Phase.RUNNING || current == null) return 0;
        long elapsed = currentTick() - startedAtTick;
        long remTicks = Math.max(0, maxDurationTicks - elapsed);
        return remTicks / 20;
    }
    public int  getTotalDurationSeconds() {
        return (int) (maxDurationTicks / 20);
    }

    public void start(Task task, int durationSeconds) {
        if (current != null) current.cancel();
        current = task;
        phase = Phase.RUNNING;
        startedAtTick = currentTick();
        maxDurationTicks = durationSeconds * 20L;
        stuckRecoveriesUsed = 0;
        ChatPilotMod.LOGGER.info("[ChatPilot] Starting task '{}' for {}s", task.displayName(), durationSeconds);
        ChatPilotClient.STUCK.reset();
        try { task.start(); } catch (Throwable t) {
            ChatPilotMod.LOGGER.error("[ChatPilot] Task start threw", t);
            cancel();
        }
    }

    public void tick() {
        if (phase == Phase.IDLE) return;

        // Cooldown phase -> count down then go idle
        if (phase == Phase.COOLDOWN) {
            if (--cooldownTicks <= 0) phase = Phase.IDLE;
            return;
        }

        // Combat resume delay
        if (phase == Phase.COMBAT_PAUSED) {
            combatPauseTicks++;
            if (combatResumeTicks > 0) {
                combatResumeTicks--;
            }
            // Wall-clock ceiling: never sit in COMBAT_PAUSED more than 20s.
            // If CombatHandler somehow forgot to call notifyCombatEnd (target
            // despawned in an unreachable spot, etc.), we force-resume here
            // so the bot doesn't appear AFK on stream.
            if (combatPauseTicks > 20 * 20) {
                ChatPilotMod.LOGGER.info("[ChatPilot] COMBAT_PAUSED ceiling reached, force-resuming task");
                combatPauseTicks = 0;
                phase = prePausePhase == Phase.COMBAT_PAUSED ? Phase.RUNNING : prePausePhase;
                ChatPilotClient.STUCK.reset();
                if (current != null) {
                    try { current.onCombatEnd(); } catch (Throwable ignored) {}
                }
            }
            return;
        }

        if (current == null) { phase = Phase.IDLE; return; }

        // Hard timeout: bring her home, do not let a task run forever.
        long elapsed = currentTick() - startedAtTick;
        if (phase == Phase.RUNNING && elapsed >= maxDurationTicks) {
            ChatPilotMod.LOGGER.info("[ChatPilot] Task '{}' time elapsed, returning home", current.displayName());
            beginReturnHome();
            return;
        }

        boolean done;
        try { done = current.tick(); }
        catch (Throwable t) {
            ChatPilotMod.LOGGER.error("[ChatPilot] Task tick threw", t);
            beginReturnHome();
            return;
        }

        if (done) {
            ChatPilotMod.LOGGER.info("[ChatPilot] Task '{}' completed naturally", current.displayName());
            if (phase == Phase.RETURNING_HOME || phase == Phase.DEPOSITING) {
                // The return-home chain just finished. Drop into cooldown so
                // VoteManager can open a fresh vote on the next tick.
                finishToCooldown();
            } else {
                // A normal task (mining, wood, explore, mystery, sleep)
                // finished. Start the return-home flow.
                beginReturnHome();
            }
            return;
        }

        // Watchdog: only check during phases that should be moving
        if (phase == Phase.RUNNING || phase == Phase.RETURNING_HOME || phase == Phase.DEPOSITING) {
            boolean stalled = ChatPilotClient.STUCK.tick(
                ChatPilotClient.CONFIG.stuckThresholdTicks,
                ChatPilotClient.BARITONE.isActive());
            if (stalled) {
                stuckRecoveriesUsed++;
                ChatPilotMod.LOGGER.warn("[ChatPilot] Stall detected ({} of {})",
                    stuckRecoveriesUsed, ChatPilotClient.CONFIG.maxConsecutiveStuckRecoveries);
                if (stuckRecoveriesUsed > ChatPilotClient.CONFIG.maxConsecutiveStuckRecoveries) {
                    ChatPilotMod.LOGGER.warn("[ChatPilot] Too many stalls");
                    if (phase == Phase.RETURNING_HOME || phase == Phase.DEPOSITING) {
                        // Already trying to go home but stuck. Don't loop —
                        // give up and let the next vote pick something else.
                        ChatPilotMod.LOGGER.warn("[ChatPilot] Already returning home, finalizing to cooldown");
                        finishToCooldown();
                    } else {
                        beginReturnHome();
                    }
                    return;
                }
                boolean recovered = current.onStuck();
                if (!recovered) {
                    ChatPilotMod.LOGGER.warn("[ChatPilot] Task could not recover");
                    if (phase == Phase.RETURNING_HOME || phase == Phase.DEPOSITING) {
                        finishToCooldown();
                    } else {
                        beginReturnHome();
                    }
                }
            }
        }
    }

    public void notifyCombatStart() {
        if (phase == Phase.RUNNING || phase == Phase.RETURNING_HOME || phase == Phase.DEPOSITING) {
            ChatPilotMod.LOGGER.info("[ChatPilot] Combat interrupting phase {}", phase);
            try { current.onCombatStart(); } catch (Throwable t) {
                ChatPilotMod.LOGGER.error("[ChatPilot] onCombatStart threw", t);
            }
            // Remember which phase we were in so we can restore it on resume.
            // Without this, a dance or combat pause that fires during a
            // RETURNING_HOME phase would resume into RUNNING and the post-task
            // completion check would incorrectly start a fresh return-home
            // chain instead of finishing to COOLDOWN.
            prePausePhase = phase;
            phase = Phase.COMBAT_PAUSED;
            combatResumeTicks = ChatPilotClient.CONFIG.combatResumeDelayTicks;
            combatPauseTicks = 0;
            ChatPilotClient.BARITONE.stop();
        }
    }

    public void notifyCombatEnd() {
        if (phase != Phase.COMBAT_PAUSED) return;
        if (combatResumeTicks > 0) return;   // grace period not over yet
        if (current == null) { phase = Phase.IDLE; combatPauseTicks = 0; return; }
        ChatPilotMod.LOGGER.info("[ChatPilot] Combat over, resuming {} in phase {}",
            current.displayName(), prePausePhase);
        // Restore whichever phase we paused out of so completion handling
        // (return-home -> finishToCooldown vs RUNNING -> beginReturnHome)
        // matches what was actually happening before the interruption.
        phase = prePausePhase == Phase.COMBAT_PAUSED ? Phase.RUNNING : prePausePhase;
        combatPauseTicks = 0;
        try { current.onCombatEnd(); } catch (Throwable t) {
            ChatPilotMod.LOGGER.error("[ChatPilot] onCombatEnd threw", t);
            beginReturnHome();
        }
    }

    public boolean isInCombatPause() { return phase == Phase.COMBAT_PAUSED; }

    public void beginReturnHome() {
        ChatPilotClient.BARITONE.hardReset();
        ChatPilotClient.STUCK.reset();
        Task chain = new ReturnHomeAndDepositTask();
        current = chain;
        phase = Phase.RETURNING_HOME;
        startedAtTick = currentTick();
        maxDurationTicks = 600 * 20L;         // up to 10 minutes to get home; deep caves take time
        stuckRecoveriesUsed = 0;
        try { chain.start(); } catch (Throwable t) {
            ChatPilotMod.LOGGER.error("[ChatPilot] return-home start threw", t);
            cancel();
        }
    }

    public void cancel() {
        ChatPilotMod.LOGGER.info("[ChatPilot] TaskManager cancelling");
        if (current != null) {
            try { current.cancel(); } catch (Throwable ignored) {}
        }
        ChatPilotClient.BARITONE.hardReset();
        ChatPilotClient.STUCK.reset();
        current = null;
        phase = Phase.COOLDOWN;
        cooldownTicks = ChatPilotClient.CONFIG.softRestartCooldownTicks;
    }

    /**
     * The return-home chain succeeded. Wind down to COOLDOWN; the next IDLE
     * transition will let VoteManager open a brand new vote so chat picks
     * the next activity.
     */
    private void finishToCooldown() {
        if (current != null) {
            try { current.cancel(); } catch (Throwable ignored) {}
        }
        ChatPilotClient.BARITONE.hardReset();
        ChatPilotClient.STUCK.reset();
        current = null;
        phase = Phase.COOLDOWN;
        cooldownTicks = ChatPilotClient.CONFIG.softRestartCooldownTicks;
        ChatPilotMod.LOGGER.info("[ChatPilot] Return-home complete. Cooldown then new vote.");
    }

    private static long currentTick() {
        return com.grammacrackers.chatpilot.event.TickClock.now();
    }
}
