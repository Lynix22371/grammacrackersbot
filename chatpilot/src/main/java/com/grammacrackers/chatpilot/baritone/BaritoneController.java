package com.grammacrackers.chatpilot.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import com.grammacrackers.chatpilot.ChatPilotMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Thin reflective bridge to Baritone. Centralizes every interaction with the
 * Baritone API so the rest of the mod never touches Baritone classes directly.
 * Falls back gracefully if Baritone is missing or version-mismatched.
 */
public class BaritoneController {

    private boolean initialized = false;
    private boolean available = false;
    private IBaritone baritone;

    /**
     * Fallback target used when Baritone has no readable path yet.
     * The look manager prefers the actual path lookahead when available.
     */
    private BlockPos visualLookTarget = null;

    /** Last value pushed to Baritone's allowBreak setting; avoids redundant writes. */
    private Boolean allowBreakState = null;

    /** Last value pushed to Baritone's freeLook setting; avoids redundant writes. */
    private Boolean freeLookState = null;

    public boolean ensure() {
        if (initialized) return available;

        initialized = true;

        try {
            baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            available = baritone != null;

            if (available) {
                applyBaseSettings();
            }

            ChatPilotMod.LOGGER.info("[ChatPilot] Baritone bridge ready: {}", available);
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.error("[ChatPilot] Baritone init failed", t);
            available = false;
        }

        return available;
    }

    private void applyBaseSettings() {
        try {
            Settings s = BaritoneAPI.getSettings();

            // Initial value only. freeLook is then managed dynamically by
            // LookWhereWalkingManager via setFreeLook(): true while walking
            // (the smooth look manager drives the camera) and false while
            // mining (Baritone aims at the ore itself).
            s.freeLook.value = false;

            // Hide Baritone visuals for stream/viewers.
            try {
                s.renderPath.value = false;
                s.renderGoal.value = false;
                s.renderSelectionBoxes.value = false;
                s.renderGoalXZBeacon.value = false;
            } catch (Throwable ignored) {
                // Some Baritone builds may rename/remove render settings.
            }

            s.allowParkour.value = false;
            s.allowParkourPlace.value = false;
            s.allowSprint.value = true;
            s.allowBreak.value = true;
            s.allowPlace.value = true;
            s.legitMine.value = false;

            // Cheap blocks Baritone may place for movement - pillaring out of
            // holes, bridging gaps. With this list empty Baritone cannot climb
            // out of a deep hole at all and the bot gets trapped.
            s.acceptableThrowawayItems.value.clear();
            s.acceptableThrowawayItems.value.addAll(java.util.List.of(
                    Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT,
                    Items.STONE, Items.DEEPSLATE, Items.NETHERRACK,
                    Items.GRANITE, Items.DIORITE, Items.ANDESITE, Items.TUFF
            ));
            s.mineScanDroppedItems.value = true;
            s.notificationOnMineFail.value = false;
            s.notificationOnPathComplete.value = false;

            s.chatControl.value = false;
            s.chatControlAnyway.value = false;
            s.echoCommands.value = false;
            s.censorRanCommands.value = true;
            s.censorCoordinates.value = true;

            applyHouseProtection(s);
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] Failed to apply Baritone settings", t);
        }
    }

    private static void applyHouseProtection(Settings s) {
        try {
            java.util.List<net.minecraft.block.Block> deny = new java.util.ArrayList<>();

            net.minecraft.registry.Registries.BLOCK.forEach(b -> {
                String path = net.minecraft.registry.Registries.BLOCK.getId(b).getPath();

                if (isHouseBlock(path)) {
                    deny.add(b);
                }
            });

            s.blocksToDisallowBreaking.value.clear();
            s.blocksToDisallowBreaking.value.addAll(deny);

            s.blocksToAvoidBreaking.value.clear();
            s.blocksToAvoidBreaking.value.addAll(deny);

            ChatPilotMod.LOGGER.info(
                    "[ChatPilot] House protection: {} block types disallowed from breaking",
                    deny.size()
            );
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] House protection list failed", t);
        }
    }

    private static boolean isHouseBlock(String path) {
        if (path.equals("glass") || path.endsWith("_glass") || path.endsWith("_glass_pane")) return true;

        if (path.equals("torch") || path.equals("wall_torch")
                || path.equals("soul_torch") || path.equals("soul_wall_torch")
                || path.equals("redstone_torch") || path.equals("redstone_wall_torch")
                || path.equals("lantern") || path.equals("soul_lantern")) return true;

        if (path.endsWith("_bed")) return true;

        if (path.equals("chest") || path.equals("trapped_chest") || path.equals("ender_chest")
                || path.equals("barrel") || path.equals("hopper") || path.equals("dispenser")
                || path.equals("dropper") || path.equals("furnace") || path.equals("blast_furnace")
                || path.equals("smoker") || path.equals("brewing_stand") || path.equals("crafting_table")
                || path.equals("loom") || path.equals("anvil") || path.equals("chipped_anvil")
                || path.equals("damaged_anvil") || path.equals("smithing_table")
                || path.equals("cartography_table") || path.equals("fletching_table")
                || path.equals("grindstone") || path.equals("stonecutter") || path.equals("lectern")
                || path.equals("enchanting_table") || path.equals("beacon")
                || path.endsWith("_shulker_box") || path.equals("shulker_box")) return true;

        if (path.endsWith("_door") || path.endsWith("_trapdoor")) return true;

        if (path.endsWith("_fence") || path.endsWith("_fence_gate")
                || path.endsWith("_wall")) return true;

        if (path.endsWith("_sign") || path.endsWith("_wall_sign")
                || path.endsWith("_hanging_sign") || path.endsWith("_wall_hanging_sign")) return true;

        if (path.endsWith("_planks")) return true;

        if (path.endsWith("_stairs") || path.endsWith("_slab")
                || path.endsWith("_pressure_plate") || path.endsWith("_button")) return true;

        if (path.equals("wool") || path.endsWith("_wool")
                || path.equals("carpet") || path.endsWith("_carpet")) return true;

        if (path.equals("bookshelf") || path.equals("chiseled_bookshelf")) return true;

        if (path.endsWith("_glazed_terracotta") || path.endsWith("_concrete")
                || path.endsWith("_concrete_powder") || path.equals("terracotta")
                || path.endsWith("_terracotta")) return true;

        if (path.endsWith("_banner") || path.endsWith("_wall_banner")) return true;

        if (path.equals("beehive") || path.equals("bee_nest") || path.equals("composter")) return true;

        return false;
    }

    public boolean run(String command) {
        if (!ensure()) return false;

        try {
            return baritone.getCommandManager().execute(command);
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] Baritone command failed: {}", command, t);
            return false;
        }
    }

    public void stop() {
        visualLookTarget = null;

        if (!ensure()) return;

        try {
            baritone.getPathingBehavior().cancelEverything();
            baritone.getCommandManager().execute("stop");
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] Baritone stop failed", t);
        }
    }

    /**
     * Toggles whether Baritone may break blocks at all. Used to enforce a
     * no-dig zone around the house so the bot never leaves holes near home.
     * Mining far from home still works because the no-dig zone only covers
     * the area around the house.
     */
    public void setAllowBreak(boolean allow) {
        if (allowBreakState != null && allowBreakState == allow) {
            return;
        }

        if (!ensure()) {
            return;
        }

        try {
            BaritoneAPI.getSettings().allowBreak.value = allow;
            allowBreakState = allow;
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] Failed to set Baritone allowBreak={}", allow, t);
        }
    }

    /**
     * Toggles Baritone's freeLook. When false, Baritone hard-snaps the visible
     * player rotation to its movement/target every tick. When true, Baritone
     * keeps its rotations internal (for raytracing) and leaves the visible
     * camera alone, so the smooth look manager can drive it without a fight.
     */
    public void setFreeLook(boolean freeLook) {
        if (freeLookState != null && freeLookState == freeLook) {
            return;
        }

        if (!ensure()) {
            return;
        }

        try {
            BaritoneAPI.getSettings().freeLook.value = freeLook;
            freeLookState = freeLook;
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] Failed to set Baritone freeLook={}", freeLook, t);
        }
    }

    public boolean isPathing() {
        if (!ensure()) return false;

        try {
            return baritone.getPathingBehavior().isPathing();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isActive() {
        if (!ensure()) return false;

        try {
            return baritone.getPathingControlManager().mostRecentInControl().isPresent()
                    || baritone.getPathingBehavior().isPathing();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isMining() {
        if (!ensure()) return false;

        try {
            return baritone.getMineProcess().isActive();
        } catch (Throwable t) {
            return false;
        }
    }

    public void gotoBlock(BlockPos pos) {
        visualLookTarget = pos == null ? null : pos.toImmutable();

        if (pos == null) {
            return;
        }

        run(String.format("goto %d %d %d", pos.getX(), pos.getY(), pos.getZ()));
    }

    public void gotoNear(BlockPos pos, int radius) {
        visualLookTarget = pos == null ? null : pos.toImmutable();

        if (!ensure() || pos == null) return;

        try {
            int r = Math.max(1, radius);
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, r));
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] gotoNear failed, falling back to gotoBlock", t);
            gotoBlock(pos);
        }
    }

    public void gotoXZ(int x, int z) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int y = mc != null && mc.player != null ? mc.player.getBlockY() : 64;

        visualLookTarget = new BlockPos(x, y, z);

        if (!ensure()) return;

        try {
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalXZ(x, z));
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] gotoXZ failed", t);
        }
    }

    public void mineBlocks(int quantity, String... blockIds) {
        visualLookTarget = null;

        StringBuilder sb = new StringBuilder("mine ");

        if (quantity > 0) {
            sb.append(quantity).append(' ');
        }

        for (int i = 0; i < blockIds.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }

            sb.append(blockIds[i]);
        }

        run(sb.toString());
    }

    public void hardReset() {
        visualLookTarget = null;

        try {
            if (!ensure()) return;

            baritone.getPathingBehavior().cancelEverything();
            baritone.getMineProcess().cancel();
            baritone.getCustomGoalProcess().setGoalAndPath(null);
            baritone.getCommandManager().execute("stop");
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] hardReset failed", t);
        }
    }

    public BlockPos getVisualLookTarget() {
        return visualLookTarget;
    }

    /**
     * Camera target for normal walking.
     *
     * This reads Baritone's active path and returns a point several blocks ahead
     * along the path. If a corner is coming soon, it returns a point just after
     * the corner. This makes the bot look into the turn like a player would.
     */
    public BlockPos getPathLookaheadTarget(double lookaheadBlocks) {
        if (!ensure()) {
            return visualLookTarget;
        }

        try {
            var pathOpt = baritone.getPathingBehavior().getPath();

            if (pathOpt == null || pathOpt.isEmpty()) {
                return visualLookTarget;
            }

            var path = pathOpt.get();
            var positions = path.positions();

            if (positions == null || positions.isEmpty()) {
                return visualLookTarget;
            }

            MinecraftClient mc = MinecraftClient.getInstance();

            if (mc == null || mc.player == null) {
                return visualLookTarget;
            }

            Vec3d playerPos = mc.player.getPos();

            int nearestIndex = 0;
            double nearestDist = Double.MAX_VALUE;

            for (int i = 0; i < positions.size(); i++) {
                BlockPos p = positions.get(i);
                double d = Vec3d.ofCenter(p).squaredDistanceTo(playerPos);

                if (d < nearestDist) {
                    nearestDist = d;
                    nearestIndex = i;
                }
            }

            double lookahead = Math.max(4.0, lookaheadBlocks);

            BlockPos cornerTarget = findUpcomingCornerTarget(
                    positions,
                    nearestIndex,
                    playerPos,
                    lookahead
            );

            if (cornerTarget != null) {
                return cornerTarget;
            }

            return findNormalLookaheadTarget(positions, nearestIndex, lookahead);
        } catch (Throwable t) {
            return visualLookTarget;
        }
    }

    private static BlockPos findNormalLookaheadTarget(
            java.util.List<? extends BlockPos> positions,
            int nearestIndex,
            double lookahead
    ) {
        BlockPos previous = positions.get(nearestIndex);
        BlockPos candidate = previous;

        double walked = 0.0;

        for (int i = nearestIndex + 1; i < positions.size(); i++) {
            BlockPos current = positions.get(i);

            walked += Vec3d.ofCenter(current).distanceTo(Vec3d.ofCenter(previous));
            candidate = current;

            if (walked >= lookahead) {
                return candidate.toImmutable();
            }

            previous = current;
        }

        return candidate.toImmutable();
    }

    private static BlockPos findUpcomingCornerTarget(
            java.util.List<? extends BlockPos> positions,
            int nearestIndex,
            Vec3d playerPos,
            double lookahead
    ) {
        int start = Math.max(nearestIndex + 2, 2);
        int end = Math.min(positions.size() - 3, nearestIndex + 24);

        for (int i = start; i <= end; i++) {
            BlockPos a = positions.get(i - 2);
            BlockPos b = positions.get(i);
            BlockPos c = positions.get(i + 2);

            int dx1 = Integer.compare(b.getX() - a.getX(), 0);
            int dz1 = Integer.compare(b.getZ() - a.getZ(), 0);

            int dx2 = Integer.compare(c.getX() - b.getX(), 0);
            int dz2 = Integer.compare(c.getZ() - b.getZ(), 0);

            boolean firstValid = dx1 != 0 || dz1 != 0;
            boolean secondValid = dx2 != 0 || dz2 != 0;
            boolean changedDirection = firstValid && secondValid && (dx1 != dx2 || dz1 != dz2);

            if (!changedDirection) {
                continue;
            }

            double distToCorner = Vec3d.ofCenter(b).distanceTo(playerPos);

            if (distToCorner <= lookahead + 6.0) {
                int afterCorner = Math.min(i + 7, positions.size() - 1);
                return positions.get(afterCorner).toImmutable();
            }
        }

        return null;
    }

    public boolean clientReady() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.player != null && mc.world != null;
    }
}