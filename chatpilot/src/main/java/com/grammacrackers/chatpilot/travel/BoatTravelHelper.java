package com.grammacrackers.chatpilot.travel;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class BoatTravelHelper {
    private static int placeCooldown = 0;
    private static int dismountCooldown = 0;

    public static boolean tickBoatAssist(MinecraftClient mc, BlockPos goal) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || goal == null) {
            return false;
        }

        if (placeCooldown > 0) placeCooldown--;
        if (dismountCooldown > 0) dismountCooldown--;

        double dist2 = mc.player.getBlockPos().getSquaredDistance(goal);
        int minDist = Math.max(16, ChatPilotClient.CONFIG.mysteryBoatMinTravelDistance);

        if (dist2 < minDist * minDist) {
            if (mc.player.hasVehicle()) {
                // Near the goal but still in the boat - hold sneak to dismount,
                // and keep boat-assist active so we run again afterwards and
                // actually release the key. Returning false here would leave
                // sneak stuck pressed and the bot would then crawl everywhere.
                mc.options.sneakKey.setPressed(true);
                return true;
            }

            // Out of the boat - make sure sneak is released.
            mc.options.sneakKey.setPressed(false);
            return false;
        }

        if (mc.player.hasVehicle()) {
            steerBoatToward(mc, goal);
            return true;
        }

        mc.options.sneakKey.setPressed(false);

        if (!hasWaterAhead(mc)) {
            return false;
        }

        if (!selectBoat(mc)) {
            return false;
        }

        if (placeCooldown <= 0) {
            aimAtWaterAhead(mc);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            placeCooldown = 30;
            ChatPilotMod.LOGGER.info("[ChatPilot][Boat] Tried placing boat for mystery travel");
        }

        BoatEntity boat = nearestBoat(mc, 5.0);
        if (boat != null) {
            try {
                mc.interactionManager.interactEntity(mc.player, boat, Hand.MAIN_HAND);
                ChatPilotMod.LOGGER.info("[ChatPilot][Boat] Tried entering boat");
                return true;
            } catch (Throwable t) {
                ChatPilotMod.LOGGER.warn("[ChatPilot][Boat] Could not enter boat", t);
            }
        }

        return false;
    }

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
        String path = Registries.ITEM.getId(s.getItem()).getPath();
        return path.endsWith("_boat") && !path.endsWith("_chest_boat");
    }

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

        for (int i = 2; i <= Math.max(3, ChatPilotClient.CONFIG.mysteryBoatWaterLookahead); i++) {
            BlockPos p = here.add(dx * i, 0, dz * i);

            if (isWater(mc, p) || isWater(mc, p.down())) {
                lookAt(mc, Vec3d.ofCenter(p));
                return;
            }
        }
    }

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

    private static void steerBoatToward(MinecraftClient mc, BlockPos goal) {
        Vec3d target = Vec3d.ofCenter(goal);

        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(false);

        // Steer with A/D using the BOAT's heading - not the player's look yaw.
        // The boat turns toward the goal; the player look is purely cosmetic.
        // (The old code compared against the player yaw right after lookAt()
        // aligned it, so the error was always 0 and the boat never turned.)
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
