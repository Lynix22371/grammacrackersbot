package com.grammacrackers.chatpilot.ui;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.tasks.TaskManager;
import com.grammacrackers.chatpilot.voting.VoteManager;
import com.grammacrackers.chatpilot.voting.VoteOption;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

import java.util.Map;

/**
 * GrammaCrackers stream HUD. Three modes for v1.1.0:
 *
 *   VOTING MODE (vote window is open and no task is running):
 *      - Pulsing banner: "GRANDMA IS AFK  CHAT IS PLAYING"
 *      - Helper line tells chat which numbers are valid this round
 *      - One vote row per active option (3, 4, or 5 rows)
 *      - Footer with seconds remaining
 *
 *   ACTIVE MODE (a task is running, returning home, depositing, etc.):
 *      - Compact banner with current activity name
 *      - When the task is "indefinite duration" (Explore, Mystery), no
 *        progress bar and no countdown — just the activity name. This is
 *        what the user wanted: the bot goes "until it finds the structure"
 *        and chat shouldn't see a timer ticking down to nothing.
 *      - Otherwise the same single progress bar from v1.0.x.
 *
 *   DANCE MODE (DanceManager is running):
 *      - Big "DANCE TIME!" banner, the activity line shows the threshold
 *        that was just hit. Replaces the active-mode banner so chat knows
 *        why everything paused.
 *
 * Hidden entirely when the pilot is off so Grandma's normal play has zero
 * overlay clutter.
 */
public class HudOverlay {

    public boolean enabled = true;

    /* ---------- color tokens ---------- */
    private static final int COL_BANNER_BG_TOP     = 0xE6111726;
    private static final int COL_BANNER_BG_BOT     = 0xCC1B2233;
    private static final int COL_BANNER_BORDER     = 0xFF65D6FF;
    private static final int COL_BANNER_BORDER_DIM = 0xFF2A6E8F;
    private static final int COL_DANCE_BORDER      = 0xFFFF6CD9;
    private static final int COL_DANCE_BORDER_DIM  = 0xFFB13C9C;
    private static final int COL_TITLE             = 0xFFFFE066;
    private static final int COL_TITLE_DANCE       = 0xFFFFD3F0;
    private static final int COL_SUBTITLE          = 0xFFE5E7EB;
    private static final int COL_PHASE_BG          = 0xC0000000;
    private static final int COL_PHASE_TEXT        = 0xFFFFFFFF;
    private static final int COL_OPTION_ROW_BG     = 0x99000000;
    private static final int COL_OPTION_ROW_BG_LEAD= 0xCC1F2A44;
    private static final int COL_OPTION_TEXT       = 0xFFEDEDED;
    private static final int COL_OPTION_DIM        = 0xFFB0B7C3;
    private static final int COL_BAR_TRACK         = 0x66000000;

    /* ---------- layout ---------- */
    private static final int ROW_H    = 18;
    private static final int PAD      = 4;

    public void render(DrawContext ctx, RenderTickCounter tickCounter) {
        if (!enabled) return;
        if (!KeybindManager.pilotEnabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;
        TextRenderer tr = mc.textRenderer;

        VoteManager vm = ChatPilotClient.VOTES;
        TaskManager tm = ChatPilotClient.TASKS;
        if (vm == null || tm == null) return;

        boolean dancing = ChatPilotClient.DANCE != null && ChatPilotClient.DANCE.isDancing();
        if (dancing) {
            renderDance(ctx, mc, tr);
            return;
        }

        boolean voting = tm.getPhase() == TaskManager.Phase.IDLE
                         && vm.getPhase() == VoteManager.Phase.OPEN;

        if (voting) renderVoting(ctx, mc, tr, vm);
        else        renderActive(ctx, mc, tr, vm, tm);
    }

    /* ---------- VOTING MODE ---------- */
    private void renderVoting(DrawContext ctx, MinecraftClient mc, TextRenderer tr, VoteManager vm) {
        int screenW = mc.getWindow().getScaledWidth();
        var cfg = ChatPilotClient.CONFIG;
        float scale = (cfg == null || cfg.hudScale <= 0.01f) ? 1.0f : cfg.hudScale;
        int bannerW = Math.max(180, (int)(220 * scale));
        int rowsW   = Math.max(150, (int)(180 * scale));
        int top = (cfg == null) ? 8 : Math.max(0, cfg.hudOffsetY);
        int x   = anchoredX(screenW, bannerW, cfg);
        int rowsX = anchoredX(screenW, rowsW, cfg);

        long t = mc.world == null ? 0 : mc.world.getTime();
        float pulse = 0.5f + 0.5f * (float)Math.sin(t * 0.10);
        int border = blend(COL_BANNER_BORDER_DIM, COL_BANNER_BORDER, pulse);

        ctx.fillGradient(x, top, x + bannerW, top + 22, COL_BANNER_BG_TOP, COL_BANNER_BG_BOT);
        drawBorder(ctx, x, top, bannerW, 22, border);

        String title = "GRANDMA IS AFK  CHAT IS PLAYING";
        ctx.drawText(tr, Text.literal(title), x + center(bannerW, tr.getWidth(title)), top + 3, COL_TITLE, true);

        // Help line dynamically lists the valid keys so chat doesn't have to
        // guess whether tonight's round has 3, 4, or 5 options.
        String help = buildHelpLine(vm);
        ctx.drawText(tr, Text.literal(help), x + center(bannerW, tr.getWidth(help)), top + 13, COL_SUBTITLE, true);

        int y = top + 34;
        String phaseLine = "VOTING NOW  " + vm.getSecondsRemaining() + "s left";
        int phaseW = tr.getWidth(phaseLine);
        int boxW = Math.min(bannerW, phaseW + PAD * 2);
        int boxX = (screenW - boxW) / 2;
        ctx.fill(boxX, y, boxX + boxW, y + 12, COL_PHASE_BG);
        ctx.drawText(tr, Text.literal(phaseLine), boxX + (boxW - phaseW) / 2, y + 2, COL_PHASE_TEXT, true);
        y += 18;

        Map<String, Integer> tally = vm.getTallySnapshot();
        int total = Math.max(1, vm.getTotalVotes());
        String leader = leadKey(tally);
        for (VoteOption opt : vm.getOptions().values()) {
            int n = tally.getOrDefault(opt.key, 0);
            float share = (float) n / total;
            boolean isLeader = opt.key.equals(leader) && n > 0;
            drawVoteRow(ctx, tr, rowsX, y, rowsW, opt, n, share, isLeader);
            y += ROW_H + 1;
        }
    }

    /** Produce a "Type 1, 2, 3, or 4 in chat to vote" line that adapts to round size. */
    private static String buildHelpLine(VoteManager vm) {
        var keys = vm.getOptions().keySet().toArray(new String[0]);
        if (keys.length == 0) return "Type a number in chat to vote";
        if (keys.length == 1) return "Type " + keys[0] + " in chat to vote";
        StringBuilder sb = new StringBuilder("Type ");
        for (int i = 0; i < keys.length - 1; i++) {
            if (i > 0) sb.append(", ");
            sb.append(keys[i]);
        }
        sb.append(", or ").append(keys[keys.length - 1]).append(" in chat to vote");
        return sb.toString();
    }

    /* ---------- ACTIVE MODE ---------- */
    private void renderActive(DrawContext ctx, MinecraftClient mc, TextRenderer tr,
                              VoteManager vm, TaskManager tm) {
        int screenW = mc.getWindow().getScaledWidth();
        var cfg = ChatPilotClient.CONFIG;
        float scale = (cfg == null || cfg.hudScale <= 0.01f) ? 1.0f : cfg.hudScale;
        int compactW = Math.max(140, (int)(180 * scale));
        int top = (cfg == null) ? 8 : Math.max(0, cfg.hudOffsetY);
        int x = anchoredX(screenW, compactW, cfg);

        long t = mc.world == null ? 0 : mc.world.getTime();
        float pulse = 0.6f + 0.4f * (float)Math.sin(t * 0.08);
        int border = blend(COL_BANNER_BORDER_DIM, COL_BANNER_BORDER, pulse);

        ctx.fillGradient(x, top, x + compactW, top + 26, COL_BANNER_BG_TOP, COL_BANNER_BG_BOT);
        drawBorder(ctx, x, top, compactW, 26, border);

        String title = "GRANDMA IS AFK  CHAT IS PLAYING";
        ctx.drawText(tr, Text.literal(title),
                     x + center(compactW, tr.getWidth(title)), top + 4, COL_TITLE, true);

        boolean indefinite = tm.getCurrent() != null && tm.getCurrent().indefiniteDuration();
        String act = activityLine(tm, indefinite);
        ctx.drawText(tr, Text.literal(act),
                     x + center(compactW, tr.getWidth(act)), top + 14, COL_SUBTITLE, true);

        // Progress bar only for fixed-duration tasks. Indefinite tasks
        // (Explore, Mystery) hide the bar entirely so chat doesn't see a
        // misleading countdown that may not match the actual finish.
        if (tm.getPhase() == TaskManager.Phase.RUNNING && !indefinite) {
            int total = Math.max(1, tm.getTotalDurationSeconds());
            int remain = Math.max(0, (int) tm.getRemainingSeconds());
            float frac = 1f - ((float) remain / (float) total);
            frac = Math.max(0f, Math.min(1f, frac));

            int barTop = top + 30;
            int barY = barTop + 4;
            ctx.fill(x, barTop, x + compactW, barTop + 12, COL_OPTION_ROW_BG);
            int trackPad = 4;
            ctx.fill(x + trackPad, barY, x + compactW - trackPad, barY + 4, COL_BAR_TRACK);
            int fillEnd = x + trackPad + (int) ((compactW - trackPad * 2) * frac);
            int accent = accentForCurrent(vm, tm);
            if (fillEnd > x + trackPad) {
                ctx.fill(x + trackPad, barY, fillEnd, barY + 4, accent);
            }
        }
    }

    /* ---------- DANCE MODE ---------- */
    private void renderDance(DrawContext ctx, MinecraftClient mc, TextRenderer tr) {
        int screenW = mc.getWindow().getScaledWidth();
        var cfg = ChatPilotClient.CONFIG;
        float scale = (cfg == null || cfg.hudScale <= 0.01f) ? 1.0f : cfg.hudScale;
        int compactW = Math.max(180, (int)(220 * scale));
        int top = (cfg == null) ? 8 : Math.max(0, cfg.hudOffsetY);
        int x = anchoredX(screenW, compactW, cfg);

        long t = mc.world == null ? 0 : mc.world.getTime();
        float pulse = 0.55f + 0.45f * (float)Math.sin(t * 0.20);
        int border = blend(COL_DANCE_BORDER_DIM, COL_DANCE_BORDER, pulse);

        ctx.fillGradient(x, top, x + compactW, top + 26, COL_BANNER_BG_TOP, COL_BANNER_BG_BOT);
        drawBorder(ctx, x, top, compactW, 26, border);

        String title = "DANCE TIME!";
        ctx.drawText(tr, Text.literal(title),
                     x + center(compactW, tr.getWidth(title)), top + 4, COL_TITLE_DANCE, true);

        long ticksRem = ChatPilotClient.DANCE.getDanceTicksRemaining();
        long secsRem = (ticksRem + 19) / 20;
        String sub = "Hype unlock! " + secsRem + "s left";
        ctx.drawText(tr, Text.literal(sub),
                     x + center(compactW, tr.getWidth(sub)), top + 14, COL_SUBTITLE, true);
    }

    private static int accentForCurrent(VoteManager vm, TaskManager tm) {
        String label = tm.getCurrentLabel();
        if (label != null) {
            for (VoteOption opt : vm.getOptions().values()) {
                if (label.toLowerCase().startsWith(opt.label.toLowerCase().split(" ")[0])) {
                    return opt.accentColor;
                }
            }
        }
        return 0xFF65D6FF;
    }

    private static String activityLine(TaskManager tm, boolean indefinite) {
        return switch (tm.getPhase()) {
            case IDLE           -> "Standing by";
            case RUNNING        -> indefinite
                                     ? tm.getCurrentLabel()
                                     : tm.getCurrentLabel() + "  " + tm.getRemainingSeconds() + "s left";
            case COMBAT_PAUSED  -> "Defending against a mob";
            case RETURNING_HOME -> "Heading back home";
            case DEPOSITING     -> "Storing items in chest";
            case COOLDOWN       -> "Resetting";
        };
    }

    /* ---------- helpers shared by both modes ---------- */

    private void drawVoteRow(DrawContext ctx, TextRenderer tr, int x, int y, int rowW,
                             VoteOption opt, int votes, float share, boolean isLeader) {
        int bg = isLeader ? COL_OPTION_ROW_BG_LEAD : COL_OPTION_ROW_BG;
        ctx.fill(x, y, x + rowW, y + ROW_H, bg);

        int barTop = y + ROW_H - 3;
        ctx.fill(x + 2, barTop, x + rowW - 2, barTop + 2, COL_BAR_TRACK);
        int fillEnd = x + 2 + (int) ((rowW - 4) * Math.max(0f, Math.min(1f, share)));
        if (fillEnd > x + 2) {
            ctx.fill(x + 2, barTop, fillEnd, barTop + 2, opt.accentColor);
        }

        // Compact key indicator
        String keyStr = opt.key;
        ctx.fill(x + 3, y + 2, x + 3 + 11, y + 13, 0xFF000000);
        drawBorder(ctx, x + 3, y + 2, 11, 11, opt.accentColor);
        int kxOffset = (11 - tr.getWidth(keyStr)) / 2;
        ctx.drawText(tr, Text.literal(keyStr), x + 3 + kxOffset, y + 4, opt.accentColor, true);

        // Label
        ctx.drawText(tr, Text.literal(opt.label),
                     x + 18, y + 4, isLeader ? COL_OPTION_TEXT : COL_OPTION_DIM, true);

        // Just the number, right-aligned tight
        String countStr = String.valueOf(votes);
        int cw = tr.getWidth(countStr);
        ctx.drawText(tr, Text.literal(countStr),
                     x + rowW - cw - 4, y + 4,
                     isLeader ? opt.accentColor : COL_OPTION_DIM, true);
    }

    /** Compute the X coordinate based on the anchor preference. */
    private static int anchoredX(int screenW, int contentW, com.grammacrackers.chatpilot.config.ChatPilotConfig cfg) {
        if (cfg == null) return Math.max(0, (screenW - contentW) / 2);
        String anchor = (cfg.hudAnchor == null || cfg.hudAnchor.isBlank())
            ? "CENTER" : cfg.hudAnchor.toUpperCase();
        return switch (anchor) {
            case "LEFT"  -> Math.max(0, cfg.hudOffsetX);
            case "RIGHT" -> Math.max(0, screenW - contentW - cfg.hudOffsetX);
            default       -> Math.max(0, (screenW - contentW) / 2); // CENTER
        };
    }

    private static String leadKey(Map<String, Integer> tally) {
        String best = null;
        int bestN = 0;
        for (var e : tally.entrySet()) {
            if (e.getValue() > bestN) { bestN = e.getValue(); best = e.getKey(); }
        }
        return best;
    }

    private static int center(int containerW, int contentW) {
        return Math.max(0, (containerW - contentW) / 2);
    }

    private static void drawBorder(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x,         y,         x + w,     y + 1,     color);
        ctx.fill(x,         y + h - 1, x + w,     y + h,     color);
        ctx.fill(x,         y,         x + 1,     y + h,     color);
        ctx.fill(x + w - 1, y,         x + w,     y + h,     color);
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24) & 0xFF, ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >>> 16) & 0xFF, bg = (b >>> 8) & 0xFF, bb = b & 0xFF;
        int oa = (int)(aa + (ba - aa) * t);
        int or = (int)(ar + (br - ar) * t);
        int og = (int)(ag + (bg - ag) * t);
        int ob = (int)(ab + (bb - ab) * t);
        return (oa << 24) | (or << 16) | (og << 8) | ob;
    }
}
