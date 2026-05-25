package com.grammacrackers.chatpilot.travel;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Boat-assisted water crossing.
 *
 * A full trip runs as a small state machine so the bot actually rides the
 * boat instead of placing one and swimming past it:
 *
 *   PLACE   - stop Baritone, place a boat on the water just ahead.
 *   BOARD   - walk up to the placed boat and right-click to climb in.
 *   SAIL    - steer the boat toward the goal.
 *   LAND    - hold sneak to step out once near the goal / run aground.
 *   BREAK   - punch the empty boat so it drops as an item.
 *   COLLECT - walk onto the dropped boat item to pick it back up.
 *
 * Baritone is kept stopped for every phase except none, so the bot can never
 * walk straight over the boat into open water the way it used to.
 */
public class BoatTravelHelper {

    private enum Phase { PLACE, BOARD, SAIL, LAND, BREAK, COLLECT }

    /** Distance to the goal at which the bot gets out of the boat and walks. */
    private static final double DISMOUNT_DIST = 14.0;
    /** Close enough to a boat to climb in / punch it (under entity reach ~3). */
    private static final double MOUNT_RANGE   = 2.6;

    private static final int PLACE_TIMEOUT_TICKS   = 20 * 6;
    private static final int BOARD_TIMEOUT_TICKS   = 20 * 10;
    private static final int BREAK_TIMEOUT_TICKS   = 20 * 8;
    private static final int COLLECT_TIMEOUT_TICKS = 20 * 6;

    private static Phase phase = null;
    private static int   phaseTicks = 0;
    private static int   actionCooldown = 0;
    /** After a failed boat attempt, travel on foot for a while before retrying. */
    private static int   retryCooldown = 0;

    /**
     * Drops all boat-trip state and releases any movement keys the helper was
     * holding. Call when the owning task starts or is cancelled so a half
     * finished trip never bleeds into the next run.
     */
    public static void reset() {
        phase = null;
        phaseTicks = 0;
        actionCooldown = 0;
        retryCooldown = 0;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) {
            releaseAll(mc);
        }
    }

    public static boolean tickBoatAssist(MinecraftClient mc, BlockPos goal) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null
                || mc.options == null || goal == null) {
            return false;
        }

        if (actionCooldown > 0) actionCooldown--;
        if (retryCooldown > 0) retryCooldown--;

        boolean nearGoal = mc.player.getBlockPos().getSquaredDistance(goal)
                < DISMOUNT_DIST * DISMOUNT_DIST;

        // ===== Currently in a boat: sail, or step out near the goal. =====
        if (mc.player.hasVehicle()) {
            if (phase != Phase.SAIL && phase != Phase.LAND) {
                setPhase(Phase.SAIL);
                ChatPilotClient.BARITONE.stop();
                ChatPilotMod.LOGGER.info("[ChatPilot][Boat] Boarded, sailing to goal");
            }

            if (phase == Phase.SAIL && (nearGoal || !boatOnWater(mc))) {
                setPhase(Phase.LAND);
            }

            if (phase == Phase.LAND) {
                // Hold sneak to dismount; drop the steering keys.
                releaseSteering(mc);
                mc.options.sneakKey.setPressed(true);
                return true;
            }

            mc.options.sneakKey.setPressed(false);
            steerBoatToward(mc, goal);
            return true;
        }

        // ===== On foot. =====
        mc.options.sneakKey.setPressed(false);

        // Just stepped off the boat - go fetch it back.
        if (phase == Phase.LAND) {
            setPhase(Phase.BREAK);
        }

        if (phase == Phase.BREAK)   return tickBreak(mc);
        if (phase == Phase.COLLECT) return tickCollect(mc);
        if (phase == Phase.PLACE)   return tickPlace(mc);
        if (phase == Phase.BOARD)   return tickBoard(mc);

        // No trip in progress - decide whether to start one.
        if (nearGoal || retryCooldown > 0 || !hasWaterAhead(mc) || !hasBoat(mc)) {
            return false;
        }

        setPhase(Phase.PLACE);
        return tickPlace(mc);
    }

    /* ---------- phases ---------- */

    /** Stop, place a boat on the water just ahead, wait for it to appear. */
    private static boolean tickPlace(MinecraftClient mc) {
        ChatPilotClient.BARITONE.stop();
        releaseSteering(mc);
        phaseTicks++;

        // The placed boat has spawned - go climb into it.
        if (nearestBoat(mc, 7.0) != null) {
            setPhase(Phase.BOARD);
            return true;
        }

        // Gave up placing - fall back to travelling on foot.
        if (phaseTicks > PLACE_TIMEOUT_TICKS) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Boat] Could not place a boat, travelling on foot");
            abortTrip(mc);
            return false;
        }

        if (actionCooldown <= 0) {
            if (!selectBoat(mc)) {
                abortTrip(mc);
                return false;
            }
            aimAtWaterAhead(mc);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            actionCooldown = 16;
            ChatPilotMod.LOGGER.info("[ChatPilot][Boat] Placing a boat to cross water");
        }
        return true;
    }

    /** Walk up to the placed boat and right-click it to climb in. */
    private static boolean tickBoard(MinecraftClient mc) {
        ChatPilotClient.BARITONE.stop();
        phaseTicks++;

        BoatEntity boat = nearestBoat(mc, 9.0);
        if (boat == null || phaseTicks > BOARD_TIMEOUT_TICKS) {
            ChatPilotMod.LOGGER.info("[ChatPilot][Boat] Could not board, travelling on foot");
            abortTrip(mc);
            return false;
        }

        lookAt(mc, boat.getPos());

        if (boat.squaredDistanceTo(mc.player) <= MOUNT_RANGE * MOUNT_RANGE) {
            // In range - stop walking and climb in.
            releaseSteering(mc);
            if (actionCooldown <= 0) {
                mc.interactionManager.interactEntity(mc.player, boat, Hand.MAIN_HAND);
                actionCooldown = 6;
            }
            return true;   // hasVehicle() next tick flips us into SAIL
        }

        // Walk to the boat. Baritone is stopped, so the bot cannot stride past
        // it - it advances under manual control and stops once in reach.
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        return true;
    }

    /** Punch the empty boat until it breaks and drops as an item. */
    private static boolean tickBreak(MinecraftClient mc) {
        ChatPilotClient.BARITONE.stop();
        releaseSteering(mc);
        mc.options.sneakKey.setPressed(false);
        phaseTicks++;

        BoatEntity boat = nearestBoat(mc, 6.0);
        if (boat == null) {
            // Boat gone - go pick up whatever it dropped.
            setPhase(Phase.COLLECT);
            return true;
        }
        if (phaseTicks > BREAK_TIMEOUT_TICKS) {
            abortTrip(mc);
            return false;
        }

        lookAt(mc, boat.getPos());

        if (boat.squaredDistanceTo(mc.player) > MOUNT_RANGE * MOUNT_RANGE) {
            mc.options.forwardKey.setPressed(true);
            return true;
        }

        mc.options.forwardKey.setPressed(false);
        if (actionCooldown <= 0) {
            mc.interactionManager.attackEntity(mc.player, boat);
            mc.player.swingHand(Hand.MAIN_HAND);
            actionCooldown = 5;
        }
        return true;
    }

    /** Walk onto the dropped boat item so it gets picked up. */
    private static boolean tickCollect(MinecraftClient mc) {
        ChatPilotClient.BARITONE.stop();
        mc.options.sneakKey.setPressed(false);
        phaseTicks++;

        ItemEntity drop = nearestBoatDrop(mc, 6.0);
        if (drop == null || phaseTicks > COLLECT_TIMEOUT_TICKS) {
            // Picked up, or it drifted off / never dropped - finish either way.
            ChatPilotMod.LOGGER.info("[ChatPilot][Boat] Boat trip done");
            endTrip(mc);
            return false;
        }

        lookAt(mc, drop.getPos());
        // Stop right on top of it so auto-pickup grabs it.
        mc.options.forwardKey.setPressed(drop.squaredDistanceTo(mc.player) > 0.7);
        mc.options.sprintKey.setPressed(false);
        return true;
    }

    /* ---------- state helpers ---------- */

    private static void setPhase(Phase next) {
        phase = next;
        phaseTicks = 0;
        actionCooldown = 0;
    }

    private static void endTrip(MinecraftClient mc) {
        phase = null;
        phaseTicks = 0;
        actionCooldown = 0;
        releaseAll(mc);
    }

    /**
     * Ends the trip AND blocks new boat attempts for a while. Used when a
     * placement or boarding genuinely failed, so the bot travels on foot
     * instead of standing on the same spot retrying every tick.
     */
    private static void abortTrip(MinecraftClient mc) {
        endTrip(mc);
        retryCooldown = 20 * 30;
    }

    private static void releaseSteering(MinecraftClient mc) {
        if (mc.options == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
    }

    private static void releaseAll(MinecraftClient mc) {
        if (mc.options == null) return;
        releaseSteering(mc);
        mc.options.sneakKey.setPressed(false);
        mc.options.backKey.setPressed(false);
    }

    /* ---------- inventory ---------- */

    public static boolean hasBoat(MinecraftClient mc) {
        if (mc.player == null) return false;

        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isEmpty() && isBoat(s)) return true;
        }

        return false;
    }

    private static boolean selectBoat(MinecraftClient mc) {
        if (mc.player == null || mc.interactionManager == null) return false;

        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isEmpty() && isBoat(s)) {
                mc.player.getInventory().selectedSlot = i;
                return true;
            }
        }

        for (int i = 9; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isEmpty() && isBoat(s)) {
                int hotbarSlot = 8;
                int handlerSlot = inventorySlotToHandlerSlot(i);

                try {
                    mc.interactionManager.clickSlot(
                            mc.player.playerScreenHandler.syncId,
                            handlerSlot,
                            hotbarSlot,
                            SlotActionType.SWAP,
                            mc.player
                    );
                    mc.player.getInventory().selectedSlot = hotbarSlot;
                    return true;
                } catch (Throwable t) {
                    ChatPilotMod.LOGGER.warn("[ChatPilot][Boat] Could not swap boat to hotbar", t);
                    return false;
                }
            }
        }

        return false;
    }

    private static boolean isBoat(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        String path = Registries.ITEM.getId(s.getItem()).getPath();
        return path.endsWith("_boat") && !path.endsWith("_chest_boat");
    }

    /* ---------- water / aiming ---------- */

    private static boolean hasWaterAhead(MinecraftClient mc) {
        int lookahead = Math.max(3, ChatPilotClient.CONFIG.mysteryBoatWaterLookahead);
        float yaw = mc.player.getYaw();

        double rad = Math.toRadians(yaw);
        int dx = (int) Math.round(-Math.sin(rad));
        int dz = (int) Math.round(Math.cos(rad));

        BlockPos here = mc.player.getBlockPos();

        // Scan a short vertical band at each step ahead, so water below a low
        // bank still counts (the bot is often a block or two above the water).
        for (int i = 1; i <= lookahead; i++) {
            for (int dy = 1; dy >= -3; dy--) {
                if (isWater(mc, here.add(dx * i, dy, dz * i))) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isWater(MinecraftClient mc, BlockPos p) {
        return mc.world.getBlockState(p).isOf(Blocks.WATER)
                || mc.world.getFluidState(p).isIn(net.minecraft.registry.tag.FluidTags.WATER);
    }

    private static void aimAtWaterAhead(MinecraftClient mc) {
        float yaw = mc.player.getYaw();
        double rad = Math.toRadians(yaw);
        int dx = (int) Math.round(-Math.sin(rad));
        int dz = (int) Math.round(Math.cos(rad));

        BlockPos here = mc.player.getBlockPos();

        // Start one block ahead so the boat lands as close to the bot as
        // possible - then there is barely any water to wade before boarding.
        for (int i = 1; i <= Math.max(3, ChatPilotClient.CONFIG.mysteryBoatWaterLookahead); i++) {
            BlockPos p = here.add(dx * i, 0, dz * i);

            if (isWater(mc, p) || isWater(mc, p.down())) {
                lookAt(mc, Vec3d.ofCenter(p));
                return;
            }
        }
    }

    /* ---------- entity lookups ---------- */

    private static BoatEntity nearestBoat(MinecraftClient mc, double radius) {
        Vec3d pos = mc.player.getPos();
        BoatEntity best = null;
        double bestD = radius * radius;

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof BoatEntity boat)) continue;

            double d = e.squaredDistanceTo(pos);
            if (d < bestD) {
                bestD = d;
                best = boat;
            }
        }

        return best;
    }

    private static ItemEntity nearestBoatDrop(MinecraftClient mc, double radius) {
        if (mc.player == null || mc.world == null) return null;

        Box box = mc.player.getBoundingBox().expand(radius);
        ItemEntity best = null;
        double bestD = radius * radius;

        for (ItemEntity item : mc.world.getEntitiesByClass(ItemEntity.class, box,
                it -> it.isAlive() && isBoat(it.getStack()))) {
            double d = item.squaredDistanceTo(mc.player);
            if (d < bestD) {
                bestD = d;
                best = item;
            }
        }

        return best;
    }

    /** True if the boat the bot is riding is actually floating on water. */
    private static boolean boatOnWater(MinecraftClient mc) {
        Entity v = mc.player.getVehicle();
        if (v == null) {
            return false;
        }
        BlockPos p = v.getBlockPos();
        return isWater(mc, p) || isWater(mc, p.down());
    }

    /* ---------- steering ---------- */

    private static void steerBoatToward(MinecraftClient mc, BlockPos goal) {
        Vec3d target = Vec3d.ofCenter(goal);

        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(false);

        // Steer with A/D using the BOAT's heading - not the player's look yaw.
        // The boat turns toward the goal; the player look is purely cosmetic.
        double yawToGoal = yawTo(mc.player.getPos(), target);
        Entity boat = mc.player.getVehicle();
        double boatYaw = boat != null ? boat.getYaw() : mc.player.getYaw();
        double diff = wrapDegrees(yawToGoal - boatYaw);

        mc.options.leftKey.setPressed(diff < -8);
        mc.options.rightKey.setPressed(diff > 8);

        // Point the camera toward the goal for the stream.
        lookAt(mc, target);
    }

    private static double yawTo(Vec3d from, Vec3d to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
    }

    private static double wrapDegrees(double deg) {
        deg %= 360.0;
        if (deg >= 180.0) deg -= 360.0;
        if (deg < -180.0) deg += 360.0;
        return deg;
    }

    private static void lookAt(MinecraftClient mc, Vec3d target) {
        Vec3d eye = mc.player.getEyePos();

        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;

        double horiz = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));

        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
    }

    private static int inventorySlotToHandlerSlot(int invSlot) {
        if (invSlot >= 0 && invSlot <= 8) return 36 + invSlot;
        if (invSlot >= 9 && invSlot <= 35) return invSlot;
        return -1;
    }
}
