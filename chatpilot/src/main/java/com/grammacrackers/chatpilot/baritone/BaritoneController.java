package com.grammacrackers.chatpilot.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import com.grammacrackers.chatpilot.ChatPilotMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/**
 * Thin reflective bridge to Baritone. Centralizes every interaction with the
 * Baritone API so the rest of the mod never touches Baritone classes directly.
 * Falls back gracefully if Baritone is missing or version-mismatched.
 */
public class BaritoneController {

    private boolean initialized = false;
    private boolean available   = false;
    private IBaritone baritone;

    public boolean ensure() {
        if (initialized) return available;
        initialized = true;
        try {
            baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            available = baritone != null;
            if (available) applyBaseSettings();
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
            // Sane defaults for an autonomous stream bot
            s.freeLook.value = false;          // visible camera follows Baritone movement
            s.allowParkour.value = false;       // safer
            s.allowParkourPlace.value = false;
            s.allowSprint.value = true;
            s.allowBreak.value = true;
            s.allowPlace.value = true;
            s.legitMine.value = false;          // mine using full info (we own the world)
            s.acceptableThrowawayItems.value.clear(); // do not auto-place dirt etc near home
            s.mineScanDroppedItems.value = true;
            s.notificationOnMineFail.value = false;
            s.notificationOnPathComplete.value = false;
            s.chatControl.value = false;        // we drive it programmatically
            s.chatControlAnyway.value = false;
            s.echoCommands.value = false;
            s.censorRanCommands.value = true;
            s.censorCoordinates.value = true;

            // House-protection block list. These are the blocks Grandma's
            // house could plausibly be built from. Baritone will never break
            // any of these even if a path goal is on the other side: it will
            // route around them instead.
            applyHouseProtection(s);
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] Failed to apply Baritone settings", t);
        }
    }

    /**
     * Tell Baritone never to break common building blocks. The list is wide
     * on purpose because we cannot know in advance what materials Grandma
     * used. Mining ores, logs, leaves, etc. is unaffected because none of
     * them appear here.
     */
    private static void applyHouseProtection(Settings s) {
        try {
            java.util.List<net.minecraft.block.Block> deny = new java.util.ArrayList<>();
            net.minecraft.registry.Registries.BLOCK.forEach(b -> {
                String path = net.minecraft.registry.Registries.BLOCK.getId(b).getPath();
                if (isHouseBlock(path)) deny.add(b);
            });
            // Hard disallow: Baritone treats this as "never break under any circumstances"
            s.blocksToDisallowBreaking.value.clear();
            s.blocksToDisallowBreaking.value.addAll(deny);
            // Soft avoid as belt-and-suspenders: drives up cost so even
            // weighted pathing prefers to go around.
            s.blocksToAvoidBreaking.value.clear();
            s.blocksToAvoidBreaking.value.addAll(deny);
            ChatPilotMod.LOGGER.info("[ChatPilot] House protection: {} block types disallowed from breaking",
                deny.size());
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] House protection list failed", t);
        }
    }

    /**
     * True if a block path looks like part of a player-built structure.
     * Conservative: when in doubt, protect.
     */
    private static boolean isHouseBlock(String path) {
        // Glass and lighting
        if (path.equals("glass") || path.endsWith("_glass") || path.endsWith("_glass_pane")) return true;
        if (path.equals("torch") || path.equals("wall_torch")
            || path.equals("soul_torch") || path.equals("soul_wall_torch")
            || path.equals("redstone_torch") || path.equals("redstone_wall_torch")
            || path.equals("lantern") || path.equals("soul_lantern")) return true;

        // Beds (any color)
        if (path.endsWith("_bed")) return true;

        // Chests / barrels / shulker boxes / containers
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

        // Doors and trapdoors (any wood, including iron)
        if (path.endsWith("_door") || path.endsWith("_trapdoor")) return true;

        // Fences and fence gates and walls
        if (path.endsWith("_fence") || path.endsWith("_fence_gate")
            || path.endsWith("_wall")) return true;

        // Signs and hanging signs (any wood)
        if (path.endsWith("_sign") || path.endsWith("_wall_sign")
            || path.endsWith("_hanging_sign") || path.endsWith("_wall_hanging_sign")) return true;

        // Planks (any wood) and stairs / slabs / pressure plates / buttons made from any wood
        if (path.endsWith("_planks")) return true;
        if (path.endsWith("_stairs") || path.endsWith("_slab")
            || path.endsWith("_pressure_plate") || path.endsWith("_button")) return true;

        // Wool, carpets, beds-adjacent decor
        if (path.equals("wool") || path.endsWith("_wool")
            || path.equals("carpet") || path.endsWith("_carpet")) return true;

        // Bookshelves, paintings, item frames are entities not blocks; skip
        if (path.equals("bookshelf") || path.equals("chiseled_bookshelf")) return true;

        // Glazed terracotta and concrete used in builds
        if (path.endsWith("_glazed_terracotta") || path.endsWith("_concrete")
            || path.endsWith("_concrete_powder") || path.equals("terracotta")
            || path.endsWith("_terracotta")) return true;

        // Banners
        if (path.endsWith("_banner") || path.endsWith("_wall_banner")) return true;

        // Beehives and composters (often part of farms near the house)
        if (path.equals("beehive") || path.equals("bee_nest") || path.equals("composter")) return true;

        return false;
    }

    /** Run a Baritone chat-style command, e.g. "mine 16 coal_ore iron_ore". */
    public boolean run(String command) {
        if (!ensure()) return false;
        try {
            // Empty prefix because we go straight through the command system
            return baritone.getCommandManager().execute(command);
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] Baritone command failed: {}", command, t);
            return false;
        }
    }

    public void stop() {
        if (!ensure()) return;
        try {
            baritone.getPathingBehavior().cancelEverything();
            baritone.getCommandManager().execute("stop");
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] Baritone stop failed", t);
        }
    }

    public boolean isPathing() {
        if (!ensure()) return false;
        try {
            return baritone.getPathingBehavior().isPathing();
        } catch (Throwable t) { return false; }
    }

    public boolean isActive() {
        if (!ensure()) return false;
        try {
            // Active if any process is in control
            return baritone.getPathingControlManager().mostRecentInControl().isPresent()
                || baritone.getPathingBehavior().isPathing();
        } catch (Throwable t) { return false; }
    }

    public boolean isMining() {
        if (!ensure()) return false;
        try {
            return baritone.getMineProcess().isActive();
        } catch (Throwable t) { return false; }
    }

    public void gotoBlock(BlockPos pos) {
        run(String.format("goto %d %d %d", pos.getX(), pos.getY(), pos.getZ()));
    }

    /**
     * Path to within {@code radius} blocks of {@code pos}. Prefer this over
     * {@link #gotoBlock} for any goal that ends at a solid block (bed, hopper,
     * chest) or at the home position itself. {@code gotoBlock} uses Baritone's
     * GoalBlock which means "stand exactly on this block." That cannot be
     * satisfied when the target block is solid, so Baritone recalculates
     * forever and the bot never actually moves into a usable position.
     *
     * Recommended radii: 2 for "next to a bed/door", 1 for "right at a hopper
     * or chest", 3 for "back at home base."
     */
    public void gotoNear(BlockPos pos, int radius) {
        if (!ensure()) return;
        try {
            int r = Math.max(1, radius);
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, r));
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] gotoNear failed, falling back to gotoBlock", t);
            gotoBlock(pos);
        }
    }

    /**
     * Path to a horizontal XZ position, ignoring Y. Useful for "head back
     * roughly toward base" when elevation does not matter.
     */
    public void gotoXZ(int x, int z) {
        if (!ensure()) return;
        try {
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalXZ(x, z));
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] gotoXZ failed", t);
        }
    }

    public void mineBlocks(int quantity, String... blockIds) {
        StringBuilder sb = new StringBuilder("mine ");
        if (quantity > 0) sb.append(quantity).append(' ');
        for (int i = 0; i < blockIds.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(blockIds[i]);
        }
        run(sb.toString());
    }

    /** Hard reset: stop, wait one tick, ready for next command. */
    public void hardReset() {
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

    public boolean clientReady() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.player != null && mc.world != null;
    }
}
