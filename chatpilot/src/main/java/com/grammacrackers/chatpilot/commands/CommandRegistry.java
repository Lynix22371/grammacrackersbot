package com.grammacrackers.chatpilot.commands;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

/**
 * Registers client-side slash commands.
 *
 *   /hopper           Set the hopper drop point. Look at a hopper block and
 *                     run this; the bot will from now on walk over and drop
 *                     non-tool items onto it after every task.
 *   /hopper here      Use the player's current standing position.
 *   /hopper clear     Forget the hopper position.
 *   /hopper where     Print the currently saved hopper position.
 *
 *   /restream login   Open the OAuth flow.
 *   /restream status  Print auth status.
 *   /restream refresh Force a token refresh.
 *
 *   /dance            Trigger the dance immediately (testing aid).
 *   /jewels add &lt;n&gt;   Add USD to the dance accumulator. Hook this from
 *                     external alert software when a Jewel gift fires
 *                     (since the YouTube Live Chat API does not surface
 *                     Jewel gifts as of v1.1.0; only Super Chat does).
 *   /jewels reset     Zero the accumulator.
 *   /jewels status    Print current accumulator and threshold.
 *
 *   /visited clear    Forget all explored structures.
 *   /visited count    Print how many structures have been explored.
 */
public class CommandRegistry {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> hopperRoot =
                ClientCommandManager.literal("hopper")
                    .executes(ctx -> setFromAimedBlock(ctx.getSource()))
                    .then(ClientCommandManager.literal("here")
                        .executes(ctx -> setFromPlayer(ctx.getSource())))
                    .then(ClientCommandManager.literal("clear")
                        .executes(ctx -> clear(ctx.getSource())))
                    .then(ClientCommandManager.literal("where")
                        .executes(ctx -> where(ctx.getSource())));
            dispatcher.register(hopperRoot);

            LiteralArgumentBuilder<FabricClientCommandSource> restreamRoot =
                ClientCommandManager.literal("restream")
                    .executes(ctx -> restreamStatus(ctx.getSource()))
                    .then(ClientCommandManager.literal("login")
                        .executes(ctx -> restreamLogin(ctx.getSource())))
                    .then(ClientCommandManager.literal("status")
                        .executes(ctx -> restreamStatus(ctx.getSource())))
                    .then(ClientCommandManager.literal("refresh")
                        .executes(ctx -> restreamRefresh(ctx.getSource())));
            dispatcher.register(restreamRoot);

            LiteralArgumentBuilder<FabricClientCommandSource> danceRoot =
                ClientCommandManager.literal("dance")
                    .executes(ctx -> manualDance(ctx.getSource()));
            dispatcher.register(danceRoot);

            LiteralArgumentBuilder<FabricClientCommandSource> jewelsRoot =
                ClientCommandManager.literal("jewels")
                    .executes(ctx -> jewelsStatus(ctx.getSource()))
                    .then(ClientCommandManager.literal("status")
                        .executes(ctx -> jewelsStatus(ctx.getSource())))
                    .then(ClientCommandManager.literal("reset")
                        .executes(ctx -> jewelsReset(ctx.getSource())))
                    .then(ClientCommandManager.literal("add")
                        .then(ClientCommandManager.argument("amount", DoubleArgumentType.doubleArg(0.01))
                            .executes(ctx -> jewelsAdd(ctx.getSource(),
                                DoubleArgumentType.getDouble(ctx, "amount")))));
            dispatcher.register(jewelsRoot);

            LiteralArgumentBuilder<FabricClientCommandSource> visitedRoot =
                ClientCommandManager.literal("visited")
                    .executes(ctx -> visitedCount(ctx.getSource()))
                    .then(ClientCommandManager.literal("count")
                        .executes(ctx -> visitedCount(ctx.getSource())))
                    .then(ClientCommandManager.literal("clear")
                        .executes(ctx -> visitedClear(ctx.getSource())));
            dispatcher.register(visitedRoot);

            // /trash sets/changes/clears the cactus that the bot tosses
            // junk items at after every task. Same UX as /hopper.
            LiteralArgumentBuilder<FabricClientCommandSource> trashRoot =
                ClientCommandManager.literal("trash")
                    .executes(ctx -> setCactusFromAimedBlock(ctx.getSource()))
                    .then(ClientCommandManager.literal("here")
                        .executes(ctx -> setCactusFromPlayer(ctx.getSource())))
                    .then(ClientCommandManager.literal("clear")
                        .executes(ctx -> clearCactus(ctx.getSource())))
                    .then(ClientCommandManager.literal("where")
                        .executes(ctx -> whereCactus(ctx.getSource())));
            dispatcher.register(trashRoot);
        });
    }

    /* ------------- /restream ------------- */

    private static int restreamLogin(FabricClientCommandSource src) {
        var auth = ChatPilotClient.AUTH;
        if (auth == null || !auth.hasCredentials()) {
            src.sendFeedback(net.minecraft.text.Text.literal(
                "No credentials file. Create config/chatpilot/restream_credentials.json with " +
                "{\"clientId\":\"...\",\"clientSecret\":\"...\"} then run /restream login again."));
            return 0;
        }
        String redirect = auth.getCredentials().redirectUri;
        ChatPilotClient.OAUTH_SERVER.start();
        String url = auth.buildAuthorizeUrl(redirect);

        boolean opened = false;
        try {
            net.minecraft.util.Util.getOperatingSystem().open(java.net.URI.create(url));
            opened = true;
        } catch (Throwable ignored) {}
        if (!opened) {
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
                    opened = true;
                }
            } catch (Throwable ignored) {}
        }

        if (opened) {
            src.sendFeedback(net.minecraft.text.Text.literal(
                "Opening Restream authorize page in your browser..."));
        } else {
            src.sendFeedback(net.minecraft.text.Text.literal(
                "Could not auto-open browser. Click the link below to authorize:"));
        }

        final String urlFinal = url;
        var styled = net.minecraft.text.Text.literal("[Click to authorize]")
            .styled(s -> s.withColor(net.minecraft.util.Formatting.AQUA)
                          .withUnderline(true)
                          .withClickEvent(new net.minecraft.text.ClickEvent(
                              net.minecraft.text.ClickEvent.Action.OPEN_URL, urlFinal))
                          .withHoverEvent(new net.minecraft.text.HoverEvent(
                              net.minecraft.text.HoverEvent.Action.SHOW_TEXT,
                              net.minecraft.text.Text.literal(urlFinal))));
        src.sendFeedback(styled);

        src.sendFeedback(net.minecraft.text.Text.literal(
            "After authorizing, your browser will be redirected to localhost. " +
            "When you see 'Authorized successfully', come back to Minecraft."));
        return Command.SINGLE_SUCCESS;
    }

    private static int restreamStatus(FabricClientCommandSource src) {
        var auth = ChatPilotClient.AUTH;
        if (auth == null) {
            src.sendFeedback(net.minecraft.text.Text.literal("Auth manager not initialized."));
            return 0;
        }
        if (!auth.hasCredentials()) {
            src.sendFeedback(net.minecraft.text.Text.literal(
                "No credentials file at config/chatpilot/restream_credentials.json. " +
                "Create it and restart Minecraft."));
            return Command.SINGLE_SUCCESS;
        }
        if (!auth.hasAccessToken()) {
            src.sendFeedback(net.minecraft.text.Text.literal(
                "Credentials loaded but not authorized yet. Run /restream login."));
            return Command.SINGLE_SUCCESS;
        }
        src.sendFeedback(net.minecraft.text.Text.literal(
            "Restream OAuth: authorized. Tokens auto-refresh in the background."));
        return Command.SINGLE_SUCCESS;
    }

    private static int restreamRefresh(FabricClientCommandSource src) {
        var auth = ChatPilotClient.AUTH;
        if (auth == null || !auth.hasAccessToken()) {
            src.sendFeedback(net.minecraft.text.Text.literal("Not authorized yet. Run /restream login."));
            return 0;
        }
        boolean ok = auth.refresh();
        src.sendFeedback(net.minecraft.text.Text.literal(
            ok ? "Access token refreshed." : "Refresh failed. Check latest.log."));
        return ok ? Command.SINGLE_SUCCESS : 0;
    }

    /* ------------- /hopper ------------- */

    private static int setFromAimedBlock(FabricClientCommandSource src) {
        MinecraftClient mc = MinecraftClient.getInstance();
        BlockPos pos = null;
        if (mc.crosshairTarget instanceof BlockHitResult bhr
            && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            pos = bhr.getBlockPos();
        }
        if (pos == null && mc.player != null) {
            pos = mc.player.getBlockPos().offset(mc.player.getHorizontalFacing());
        }
        if (pos == null) {
            src.sendFeedback(Text.literal("Could not determine a position. Look at a hopper and try again."));
            return 0;
        }
        ChatPilotClient.HOME.setHopper(pos);
        src.sendFeedback(Text.literal("Hopper drop point set to " + posStr(pos)
            + ". The bot will deposit non-tool items here after each task."));
        return Command.SINGLE_SUCCESS;
    }

    private static int setFromPlayer(FabricClientCommandSource src) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            src.sendFeedback(Text.literal("No player."));
            return 0;
        }
        BlockPos pos = mc.player.getBlockPos();
        ChatPilotClient.HOME.setHopper(pos);
        src.sendFeedback(Text.literal("Hopper drop point set at your feet: " + posStr(pos)));
        return Command.SINGLE_SUCCESS;
    }

    private static int clear(FabricClientCommandSource src) {
        ChatPilotClient.HOME.clearHopper();
        src.sendFeedback(Text.literal("Hopper drop point cleared."));
        return Command.SINGLE_SUCCESS;
    }

    private static int where(FabricClientCommandSource src) {
        if (!ChatPilotClient.HOME.hasHopper()) {
            src.sendFeedback(Text.literal("No hopper drop point is set. Look at a hopper and run /hopper to set one."));
            return Command.SINGLE_SUCCESS;
        }
        src.sendFeedback(Text.literal("Hopper drop point is at " + posStr(ChatPilotClient.HOME.getHopperPos())));
        return Command.SINGLE_SUCCESS;
    }

    /* ------------- /dance + /jewels ------------- */

    private static int manualDance(FabricClientCommandSource src) {
        if (ChatPilotClient.DANCE == null) {
            src.sendFeedback(Text.literal("Dance manager not initialized."));
            return 0;
        }
        if (ChatPilotClient.DANCE.isDancing()) {
            src.sendFeedback(Text.literal("Already dancing."));
            return Command.SINGLE_SUCCESS;
        }
        ChatPilotClient.DANCE.forceDance("manual /dance command");
        src.sendFeedback(Text.literal("Dance triggered."));
        return Command.SINGLE_SUCCESS;
    }

    private static int jewelsStatus(FabricClientCommandSource src) {
        if (ChatPilotClient.DANCE == null) {
            src.sendFeedback(Text.literal("Dance manager not initialized."));
            return 0;
        }
        double cur = ChatPilotClient.DANCE.getAccumulatedUsd();
        double thr = ChatPilotClient.DANCE.getThresholdUsd();
        src.sendFeedback(Text.literal(String.format(
            "Jewels accumulator: $%.2f / $%.2f", cur, thr)));
        return Command.SINGLE_SUCCESS;
    }

    private static int jewelsReset(FabricClientCommandSource src) {
        if (ChatPilotClient.DANCE == null) return 0;
        ChatPilotClient.DANCE.resetAccumulator();
        src.sendFeedback(Text.literal("Jewels accumulator reset to $0.00."));
        return Command.SINGLE_SUCCESS;
    }

    private static int jewelsAdd(FabricClientCommandSource src, double amount) {
        if (ChatPilotClient.DANCE == null) return 0;
        ChatPilotClient.DANCE.addUsd(amount, "/jewels add command");
        double cur = ChatPilotClient.DANCE.getAccumulatedUsd();
        double thr = ChatPilotClient.DANCE.getThresholdUsd();
        src.sendFeedback(Text.literal(String.format(
            "Added $%.2f. Total now $%.2f / $%.2f.", amount, cur, thr)));
        return Command.SINGLE_SUCCESS;
    }

    /* ------------- /visited ------------- */

    private static int visitedCount(FabricClientCommandSource src) {
        if (ChatPilotClient.VISITED == null) {
            src.sendFeedback(Text.literal("Visited tracker not initialized."));
            return 0;
        }
        src.sendFeedback(Text.literal(
            "Explored " + ChatPilotClient.VISITED.totalVisited() + " structure(s) so far."));
        return Command.SINGLE_SUCCESS;
    }

    private static int visitedClear(FabricClientCommandSource src) {
        // We don't expose a clear() method; instead, write an empty list by
        // deleting the file. Easier: restart-based clear, or expose it on
        // the manager. Doing the simple in-place clear here.
        if (ChatPilotClient.VISITED == null) return 0;
        try {
            // Reflectively clear the entries via a reset method we add later;
            // for now, the user can delete config/chatpilot/visited.json.
            java.nio.file.Path p = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getConfigDir().resolve("chatpilot").resolve("visited.json");
            java.nio.file.Files.writeString(p, "[]");
            src.sendFeedback(Text.literal(
                "Wrote empty visited.json. Restart Minecraft for the change to take full effect."));
        } catch (Exception e) {
            src.sendFeedback(Text.literal("Could not clear visited.json: " + e.getMessage()));
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    /* ------------- /trash ------------- */

    private static int setCactusFromAimedBlock(FabricClientCommandSource src) {
        MinecraftClient mc = MinecraftClient.getInstance();
        BlockPos pos = null;
        if (mc.crosshairTarget instanceof BlockHitResult bhr
            && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            pos = bhr.getBlockPos();
        }
        if (pos == null && mc.player != null) {
            pos = mc.player.getBlockPos().offset(mc.player.getHorizontalFacing());
        }
        if (pos == null) {
            src.sendFeedback(Text.literal("Could not determine a position. Look at the cactus and try again."));
            return 0;
        }
        ChatPilotClient.HOME.setCactus(pos);
        src.sendFeedback(Text.literal("Trash cactus set to " + posStr(pos)
            + ". The bot will toss cobblestone, dirt, copper, lapis, sand, tuff, andesite, "
            + "and seeds onto this cactus after each task."));
        return Command.SINGLE_SUCCESS;
    }

    private static int setCactusFromPlayer(FabricClientCommandSource src) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            src.sendFeedback(Text.literal("No player."));
            return 0;
        }
        BlockPos pos = mc.player.getBlockPos();
        ChatPilotClient.HOME.setCactus(pos);
        src.sendFeedback(Text.literal("Trash cactus set at your feet: " + posStr(pos)));
        return Command.SINGLE_SUCCESS;
    }

    private static int clearCactus(FabricClientCommandSource src) {
        ChatPilotClient.HOME.clearCactus();
        src.sendFeedback(Text.literal("Trash cactus cleared. Bot will skip the trash step until /trash is set again."));
        return Command.SINGLE_SUCCESS;
    }

    private static int whereCactus(FabricClientCommandSource src) {
        if (!ChatPilotClient.HOME.hasCactus()) {
            src.sendFeedback(Text.literal("No trash cactus set. Look at a cactus and run /trash to set one."));
            return Command.SINGLE_SUCCESS;
        }
        src.sendFeedback(Text.literal("Trash cactus is at " + posStr(ChatPilotClient.HOME.getCactusPos())));
        return Command.SINGLE_SUCCESS;
    }

    private static String posStr(BlockPos p) {
        return "(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
    }
}
