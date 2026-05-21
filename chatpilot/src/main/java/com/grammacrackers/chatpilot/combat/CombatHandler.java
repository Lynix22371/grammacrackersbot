package com.grammacrackers.chatpilot.combat;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Defensive-only combat. Grandma's bot ignores hostile mobs by default and
 * just walks past them. Combat ONLY engages when:
 *   1) The player has actually been damaged recently (the mixin marks this
 *      via {@link #notifyPlayerDamaged()}), or
 *   2) A hostile mob is within {@link #MELEE_DEFEND_RADIUS} blocks of the
 *      player AND the player is currently in danger (low health).
 *
 * Once engaged, the bot fights back at the attacker until the threat is
 * gone or out of range, then releases combat and the original task resumes.
 *
 * After combat ends or the task completes, the bot returns home and ignores
 * mobs along the way unless they attack again.
 */
public class CombatHandler {

    /** Hostiles within this radius are still considered threats during active combat. */
    private static final double ENGAGE_RADIUS = 18.0;
    /** Melee striking distance. */
    private static final double ATTACK_RANGE  = 3.5;
    /**
     * How long after taking damage we stay aggressive even if no obvious
     * attacker is nearby (useful for skeleton arrow trades or fading line of sight).
     */
    private static final int    DAMAGE_AGGRO_TICKS = 80; // 4 seconds
    /**
     * If a mob is THIS close, treat it as a defensive threat regardless of
     * recent damage. Catches the case of a creeper walking right up.
     */
    private static final double MELEE_DEFEND_RADIUS = 2.5;

    private boolean inCombat = false;
    private LivingEntity target;
    private int  attackCooldown = 0;
    private int  ticksSinceDamage = Integer.MAX_VALUE / 2;
    private int  ticksSinceLastThreat = 0;
    private int  lastHurtTime = 0;
    private float lastHealth = -1f;
    private int  combatTotalTicks = 0;
    private static final int HARD_COMBAT_CEILING_TICKS = 15 * 20;

    public boolean isInCombat() { return inCombat; }

    // === Combat weapons ===
    public boolean combatUseBestWeapon = true;
    
    /**
     * Hotbar slot used when the best weapon is in main inventory.
     * 0 = first hotbar slot, 8 = last hotbar slot.
     */
    public int combatWeaponHotbarSlot = 0;
    
    /**
     * true = prefer axe if axe and sword score equally.
     * false = prefer sword if tied.
     */
    public boolean combatPreferAxeOnTie = false;

    public void tick() {
        var mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null) return;

        // HARD CEILING: regardless of any state, never sit in combat for
        // more than 15 seconds. If we hit this, something has gone wrong
        // (target despawned, fell into a pit, etc.) and we MUST resume the
        // task or the bot will appear AFK on stream forever.
        if (inCombat) {
            combatTotalTicks++;
            if (combatTotalTicks > HARD_COMBAT_CEILING_TICKS) {
                ChatPilotMod.LOGGER.info("[ChatPilot] Combat hard-ceiling hit, forcing exit");
                exitCombat();
                return;
            }
        }

        // Detect that the player just took damage by watching hurtTime go up
        // OR health dropping. hurtTime is set to 10 each time damage lands.
        int ht = player.hurtTime;
        float hp = player.getHealth();
        boolean justHurt = (ht > lastHurtTime && ht > 0) || (lastHealth >= 0 && hp < lastHealth - 0.05f);
        lastHurtTime = ht;
        lastHealth = hp;

        if (justHurt) {
            // Only re-arm combat if there's an actual hostile attacker we can
            // identify. Generic damage (fall, fire, drowning, suffocation,
            // status effects) is NOT a reason to enter combat — there's
            // nothing to fight.
            if (player.getAttacker() instanceof LivingEntity attacker
                && attacker instanceof HostileEntity) {
                ticksSinceDamage = 0;
                target = attacker;
            }
        }

        ticksSinceDamage = Math.min(ticksSinceDamage + 1, Integer.MAX_VALUE / 2);
        boolean recentlyHit = ticksSinceDamage < DAMAGE_AGGRO_TICKS;

        // Always check for an adjacent hostile so a creeper or zombie hugging
        // the player triggers defense even if it has not yet hit.
        LivingEntity adjacent = findNearestHostile(player, MELEE_DEFEND_RADIUS);

        // Choose target: keep the existing one if alive and in range, else
        // the adjacent threat if any
        if (target != null && (!target.isAlive() || target.distanceTo(player) > ENGAGE_RADIUS)) {
            target = null;
        }
        if (target == null && adjacent != null) {
            target = adjacent;
        }

        // CRITICAL: if we have no target AND nothing adjacent, we cannot be
        // "in combat" no matter what the recent-damage flag says. Exit now.
        if (target == null && adjacent == null) {
            if (inCombat) {
                ticksSinceLastThreat++;
                if (ticksSinceLastThreat > 20) { // 1 second with literally no target
                    exitCombat();
                }
            }
            return;
        }

        ticksSinceLastThreat = 0;
        if (!inCombat) enterCombat();

        // Move toward the target if not in melee range, BUT do not chase
        // farther than ENGAGE_RADIUS so we never go off hunting.
        double dist = player.getPos().distanceTo(target.getPos());
        if (dist > ATTACK_RANGE && dist <= ENGAGE_RADIUS) {
            ChatPilotClient.BARITONE.gotoBlock(target.getBlockPos());
        }
        if (dist > ENGAGE_RADIUS) {
            // Threat slipped away. Drop combat.
            target = null;
            return;
        }
        // attack with correct weapon when in range 
        if (dist <= ATTACK_RANGE + 0.5 && attackCooldown <= 0 && player.getAttackCooldownProgress(0) > 0.95f) {
            faceEntity(player, target);
        
            if (ChatPilotClient.CONFIG.combatUseBestWeapon) {
                selectBestCombatWeapon(mc, player);
            }
        
            mc.interactionManager.attackEntity(player, target);
            player.swingHand(Hand.MAIN_HAND);
            attackCooldown = 8;
        }
        if (attackCooldown > 0) attackCooldown--;
    }

    private void enterCombat() {
        inCombat = true;
        combatTotalTicks = 0;
        ChatPilotMod.LOGGER.info("[ChatPilot] Defensive combat engaged: {}",
            target == null ? "?" : target.getType().toString());
        ChatPilotClient.TASKS.notifyCombatStart();
    }

    public void forceExit() {
        if (inCombat) {
            exitCombat();
        } else {
            target = null;
            combatTotalTicks = 0;
            ticksSinceLastThreat = 0;
            ticksSinceDamage = Integer.MAX_VALUE / 2;
        }
    }

    private void exitCombat() {
        inCombat = false;
        target = null;
        combatTotalTicks = 0;
        ticksSinceLastThreat = 0;
        ticksSinceDamage = Integer.MAX_VALUE / 2;
        ChatPilotMod.LOGGER.info("[ChatPilot] Threat clear, resuming");
        ChatPilotClient.TASKS.notifyCombatEnd();
    }

    private static LivingEntity findNearestHostile(ClientPlayerEntity player, double radius) {
        Box area = player.getBoundingBox().expand(radius, radius / 2.0, radius);
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : player.getWorld().getOtherEntities(player, area)) {
            if (e instanceof HostileEntity h && h.isAlive()) {
                double d = h.squaredDistanceTo(player);
                if (d < bestDist) { bestDist = d; best = h; }
            }
        }
        return best;
    }

    private static void faceEntity(ClientPlayerEntity player, Entity entity) {
        Vec3d eye = player.getEyePos();
        Vec3d t = entity.getEyePos();
        double dx = t.x - eye.x, dy = t.y - eye.y, dz = t.z - eye.z;
        double horiz = Math.sqrt(dx*dx + dz*dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        player.setYaw(yaw);
        player.setPitch(pitch);
    }


    private static void selectBestCombatWeapon(MinecraftClient mc, ClientPlayerEntity player) {
        if (mc == null || player == null || mc.interactionManager == null) return;
    
        int bestInvSlot = -1;
        double bestScore = 0.0;
    
        for (int invSlot = 0; invSlot < 36; invSlot++) {
            ItemStack stack = player.getInventory().getStack(invSlot);
            double score = combatWeaponScore(stack);
    
            if (score > bestScore) {
                bestScore = score;
                bestInvSlot = invSlot;
            }
        }
    
        if (bestInvSlot < 0) {
            return;
        }
    
        // Already in hotbar.
        if (bestInvSlot >= 0 && bestInvSlot <= 8) {
            player.getInventory().selectedSlot = bestInvSlot;
            return;
        }
    
        int targetHotbarSlot = ChatPilotClient.CONFIG.combatWeaponHotbarSlot;
        if (targetHotbarSlot < 0 || targetHotbarSlot > 8) {
            targetHotbarSlot = 0;
        }
    
        int handlerSlot = inventorySlotToHandlerSlot(bestInvSlot);
    
        try {
            mc.interactionManager.clickSlot(
                    player.playerScreenHandler.syncId,
                    handlerSlot,
                    targetHotbarSlot,
                    SlotActionType.SWAP,
                    player
            );
    
            player.getInventory().selectedSlot = targetHotbarSlot;
        } catch (Throwable t) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] Failed to swap combat weapon into hotbar", t);
        }
    }
    
    private static double combatWeaponScore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0.0;
    
        Item item = stack.getItem();
        String path = Registries.ITEM.getId(item).getPath();
    
        boolean sword = path.endsWith("_sword");
        boolean axe = path.endsWith("_axe");
    
        if (!sword && !axe) return 0.0;
    
        double materialScore;
    
        if (path.startsWith("netherite_")) {
            materialScore = 6.0;
        } else if (path.startsWith("diamond_")) {
            materialScore = 5.0;
        } else if (path.startsWith("iron_")) {
            materialScore = 4.0;
        } else if (path.startsWith("stone_")) {
            materialScore = 3.0;
        } else if (path.startsWith("golden_")) {
            materialScore = 2.0;
        } else if (path.startsWith("wooden_")) {
            materialScore = 1.0;
        } else {
            materialScore = 0.5;
        }
    
        double typeScore;
    
        if (axe) {
            typeScore = ChatPilotClient.CONFIG.combatPreferAxeOnTie ? 0.20 : 0.10;
        } else {
            typeScore = ChatPilotClient.CONFIG.combatPreferAxeOnTie ? 0.10 : 0.20;
        }
    
        // Prefer less damaged weapons if otherwise similar.
        double durabilityScore = 0.0;
        if (stack.isDamageable()) {
            int max = stack.getMaxDamage();
            int dmg = stack.getDamage();
    
            if (max > 0) {
                durabilityScore = 0.05 * (1.0 - ((double) dmg / (double) max));
            }
        }
    
        return materialScore + typeScore + durabilityScore;
    }
    
    private static int inventorySlotToHandlerSlot(int invSlot) {
        if (invSlot >= 0 && invSlot <= 8) return 36 + invSlot;
        if (invSlot >= 9 && invSlot <= 35) return invSlot;
        return -1;
    }

    
    /** Public probe still useful for the watchdog. */
    public boolean hostilesInRange() {
        return inCombat;
    }
}
