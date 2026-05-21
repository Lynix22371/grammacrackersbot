package com.grammacrackers.chatpilot.tasks;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * After a task ends or times out we run this chain (v1.2.0):
 *   1. Walk to within a few blocks of the bed.
 *   2. (NEW) If a cactus is configured, walk to it and toss every trash item
 *      from inventory toward it. Cactus blocks destroy items they touch, so
 *      this is how cobblestone/dirt/etc get cleared without filling chests.
 *   3. Drop remaining non-tool items in the hopper.
 *   4. For each nearby chest, walk to it, open it, dump non-essential items.
 *   5. Walk back to the bed, declare done.
 *
 * Items that are KEPT in inventory (never deposited):
 *   - Tools, weapons, armour, food, torches, the bed itself, fishing rod.
 * Items that are TRASHED (cactus dump):
 *   - Anything in {@code config.trashItemIds} (cobblestone, dirt, etc).
 * Items that are DEPOSITED:
 *   - Everything else (raw_iron, raw_gold, emerald, fish, treasures, etc).
 */
public class ReturnHomeAndDepositTask implements Task {

    private enum Stage {
        WALK_HOME,
        // v1.2.0 cactus stages
        CACTUS_GO, CACTUS_AIM, CACTUS_DROP,
        HOPPER_GO, HOPPER_OPEN, HOPPER_DEPOSIT, HOPPER_CLOSE,
        NEXT_CHEST, WALK_TO_CHEST, OPEN_CHEST, TRANSFER, CLOSE_CHEST,
        FINAL_RETURN, DONE
    }

    private Stage stage = Stage.WALK_HOME;
    private int   stageStartTick;
    private final Deque<BlockPos> chestQueue = new ArrayDeque<>();
    private BlockPos currentChest;
    private int      transferCursor;
    private int      hopperDepositCursor;
    /** Inventory cursor for the cactus dump pass; iterates 0..35 (hotbar + main inv). */
    private int      cactusDropCursor;

    @Override
    public String displayName() {
        return switch (stage) {
            case WALK_HOME, FINAL_RETURN -> "Heading home";
            case CACTUS_GO, CACTUS_AIM, CACTUS_DROP -> "Tossing trash on cactus";
            case HOPPER_GO, HOPPER_OPEN, HOPPER_DEPOSIT, HOPPER_CLOSE -> "Putting items in hopper";
            case NEXT_CHEST, WALK_TO_CHEST, OPEN_CHEST, TRANSFER, CLOSE_CHEST -> "Storing items";
            case DONE -> "Done";
        };
    }

    @Override
    public String id() { return "return_home"; }

    @Override
    public void start() {
        if (!ChatPilotClient.HOME.hasHome()) {
            ChatPilotMod.LOGGER.warn("[ChatPilot] No home set, skipping return");
            stage = Stage.DONE;
            return;
        }
        stage = Stage.WALK_HOME;
        stageStartTick = clientTick();
        ChatPilotClient.BARITONE.gotoNear(ChatPilotClient.HOME.getBedPos(), 3);
    }

    @Override
    public boolean tick() {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return false;

        switch (stage) {
            case WALK_HOME -> {
                BlockPos bed = ChatPilotClient.HOME.getBedPos();
                if (mc.player.getBlockPos().getSquaredDistance(bed) < 25 || ticksInStage() > 20 * 90) {
                    ChatPilotClient.BARITONE.hardReset();
                    advanceFromHome();
                }
            }

            case CACTUS_GO -> {
                BlockPos cactus = ChatPilotClient.HOME.getCactusPos();
                if (cactus == null) {
                    advanceFromCactus();
                    break;
                }
                if (mc.player.getBlockPos().getSquaredDistance(cactus) < 9 || ticksInStage() > 20 * 30) {
                    ChatPilotClient.BARITONE.hardReset();
                    enterStage(Stage.CACTUS_AIM);
                }
            }

            case CACTUS_AIM -> {
                BlockPos cactus = ChatPilotClient.HOME.getCactusPos();
                if (cactus == null) {
                    advanceFromCactus();
                    break;
                }
                aimAtBlock(mc, cactus);
                cactusDropCursor = 0;
                enterStage(Stage.CACTUS_DROP);
            }

            case CACTUS_DROP -> {
                if (cactusDropCursor >= 36) {
                    ChatPilotMod.LOGGER.info("[ChatPilot] Cactus drop pass complete");
                    advanceFromCactus();
                    break;
                }
                int invSlot = cactusDropCursor++;
                ItemStack stack = mc.player.getInventory().getStack(invSlot);
                if (!stack.isEmpty() && isTrash(stack)) {
                    int handlerSlot = inventorySlotToHandlerSlot(invSlot);
                    int syncId = mc.player.playerScreenHandler.syncId;
                    try {
                        // SlotActionType.THROW button=1 throws the entire stack.
                        // The slot must be in the player's own screen handler;
                        // we use the always-attached playerScreenHandler so we
                        // don't need an open inventory GUI.
                        mc.interactionManager.clickSlot(syncId, handlerSlot, 1,
                            SlotActionType.THROW, mc.player);
                    } catch (Throwable t) {
                        ChatPilotMod.LOGGER.warn("[ChatPilot] Cactus throw failed for slot {}", invSlot, t);
                    }
                }
            }

            case HOPPER_GO -> {
                BlockPos hopper = ChatPilotClient.HOME.getHopperPos();
                if (hopper == null) { enterStage(Stage.NEXT_CHEST); break; }
                if (mc.player.getBlockPos().getSquaredDistance(hopper) < 9 || ticksInStage() > 20 * 30) {
                    ChatPilotClient.BARITONE.hardReset();
                    aimAtBlock(mc, hopper);
                    enterStage(Stage.HOPPER_OPEN);
                }
            }
            case HOPPER_OPEN -> {
                BlockPos hopper = ChatPilotClient.HOME.getHopperPos();
                if (hopper == null) { enterStage(Stage.NEXT_CHEST); break; }
                if (mc.player.currentScreenHandler instanceof net.minecraft.screen.HopperScreenHandler) {
                    hopperDepositCursor = 0;
                    ChatPilotMod.LOGGER.info("[ChatPilot] Hopper opened, depositing {} items",
                        inventoryDepositCount(mc.player));
                    enterStage(Stage.HOPPER_DEPOSIT);
                    break;
                }
                if (ticksInStage() > 20 * 5) {
                    ChatPilotMod.LOGGER.info("[ChatPilot] Hopper open timed out, skipping to chests");
                    enterStage(Stage.NEXT_CHEST);
                    break;
                }
                if (ticksInStage() % 10 == 0) {
                    openContainerBlock(hopper);
                }
            }
            case HOPPER_DEPOSIT -> {
                ScreenHandler sh = mc.player.currentScreenHandler;
                if (!(sh instanceof net.minecraft.screen.HopperScreenHandler)) {
                    enterStage(Stage.NEXT_CHEST);
                    break;
                }
                int hopperSize = 5;
                if (hopperDepositCursor >= 36) {
                    enterStage(Stage.HOPPER_CLOSE);
                    break;
                }
                int handlerSlot = hopperSize + hopperDepositCursor++;
                ItemStack stack = sh.getSlot(handlerSlot).getStack();
                if (!stack.isEmpty() && shouldDeposit(stack)) {
                    mc.interactionManager.clickSlot(sh.syncId, handlerSlot, 0,
                        SlotActionType.QUICK_MOVE, mc.player);
                }
            }
            case HOPPER_CLOSE -> {
                mc.player.closeHandledScreen();
                enterStage(Stage.NEXT_CHEST);
            }
            case NEXT_CHEST -> {
                if (chestQueue.isEmpty()) {
                    chestQueue.addAll(ChatPilotClient.HOME.findNearbyChests(mc.world,
                        ChatPilotClient.CONFIG.chestSearchRadius));
                }
                if (chestQueue.isEmpty() || inventoryDepositCount(mc.player) == 0) {
                    enterStage(Stage.FINAL_RETURN);
                } else {
                    currentChest = chestQueue.pollFirst();
                    enterStage(Stage.WALK_TO_CHEST);
                    ChatPilotClient.BARITONE.gotoNear(currentChest, 1);
                }
            }
            case WALK_TO_CHEST -> {
                if (currentChest == null) { enterStage(Stage.NEXT_CHEST); break; }
                if (mc.player.getBlockPos().getSquaredDistance(currentChest) < 9 || ticksInStage() > 20 * 30) {
                    ChatPilotClient.BARITONE.hardReset();
                    enterStage(Stage.OPEN_CHEST);
                }
            }
            case OPEN_CHEST -> {
                if (openChest(currentChest)) {
                    enterStage(Stage.TRANSFER);
                    transferCursor = 0;
                } else if (ticksInStage() > 20 * 5) {
                    enterStage(Stage.NEXT_CHEST);
                }
            }
            case TRANSFER -> {
                ScreenHandler sh = mc.player.currentScreenHandler;
                if (!(sh instanceof GenericContainerScreenHandler chest)) {
                    enterStage(Stage.NEXT_CHEST);
                    break;
                }
                int chestSize = chest.getRows() * 9;
                int playerSlotInHandler = chestSize + transferCursor;
                if (transferCursor >= 36) {
                    enterStage(Stage.CLOSE_CHEST);
                    break;
                }
                ItemStack stack = sh.getSlot(playerSlotInHandler).getStack();
                if (!stack.isEmpty() && shouldDeposit(stack)) {
                    mc.interactionManager.clickSlot(sh.syncId, playerSlotInHandler, 0,
                        SlotActionType.QUICK_MOVE, mc.player);
                }
                transferCursor++;
            }
            case CLOSE_CHEST -> {
                mc.player.closeHandledScreen();
                enterStage(Stage.NEXT_CHEST);
            }
            case FINAL_RETURN -> {
                BlockPos bed = ChatPilotClient.HOME.getBedPos();
                if (mc.player.getBlockPos().getSquaredDistance(bed) < 9 || ticksInStage() > 20 * 60) {
                    ChatPilotClient.BARITONE.hardReset();
                    enterStage(Stage.DONE);
                } else if (!ChatPilotClient.BARITONE.isPathing()) {
                    ChatPilotClient.BARITONE.gotoNear(bed, 2);
                }
            }
            case DONE -> { return true; }
        }
        return false;
    }

    /** Pick the next stage after WALK_HOME based on what features are configured. */
    private void advanceFromHome() {
        if (ChatPilotClient.HOME.hasCactus() && hasAnyTrash()) {
            enterStage(Stage.CACTUS_GO);
            ChatPilotClient.BARITONE.gotoNear(ChatPilotClient.HOME.getCactusPos(), 2);
        } else if (ChatPilotClient.HOME.hasHopper()) {
            enterStage(Stage.HOPPER_GO);
            ChatPilotClient.BARITONE.gotoNear(ChatPilotClient.HOME.getHopperPos(), 1);
        } else {
            enterStage(Stage.NEXT_CHEST);
        }
    }

    /** Pick the next stage after the cactus pass: hopper or chests. */
    private void advanceFromCactus() {
        if (ChatPilotClient.HOME.hasHopper()) {
            enterStage(Stage.HOPPER_GO);
            ChatPilotClient.BARITONE.gotoNear(ChatPilotClient.HOME.getHopperPos(), 1);
        } else {
            enterStage(Stage.NEXT_CHEST);
        }
    }

    private boolean hasAnyTrash() {
        var p = MinecraftClient.getInstance().player;
        if (p == null) return false;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (!s.isEmpty() && isTrash(s)) return true;
        }
        return false;
    }

    private void enterStage(Stage next) {
        stage = next;
        stageStartTick = clientTick();
    }

    private boolean openChest(BlockPos pos) {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        if (!(mc.world.getBlockEntity(pos) instanceof ChestBlockEntity)) return false;
        return openContainerBlock(pos);
    }

    /**
     * Right-click a container block to open its UI. Returns true if the
     * interaction was accepted by the client. Screen handler may take a tick
     * or two to attach, so callers should poll {@code currentScreenHandler}.
     */
    private boolean openContainerBlock(BlockPos pos) {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        Vec3d hit = Vec3d.ofCenter(pos);
        BlockHitResult hr = new BlockHitResult(hit, Direction.UP, pos, false);
        aimAtBlock(mc, pos);
        var result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hr);
        return result.isAccepted();
    }

    private void aimAtBlock(MinecraftClient mc, BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d hc  = Vec3d.ofCenter(pos);
        double dx = hc.x - eye.x, dy = hc.y - eye.y, dz = hc.z - eye.z;
        double horiz = Math.sqrt(dx*dx + dz*dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
    }

    /**
     * Map an inventory slot index (0-35: hotbar 0-8 + main inventory 9-35)
     * to the equivalent slot in the player's screen handler. The hotbar is
     * 36-44 in the screen handler, while the main inventory shares indices
     * 9-35 with the inventory itself.
     */
    private static int inventorySlotToHandlerSlot(int invSlot) {
        if (invSlot >= 0 && invSlot <= 8) return 36 + invSlot;
        if (invSlot >= 9 && invSlot <= 35) return invSlot;
        return -1;
    }

    private static int inventoryDepositCount(PlayerEntity p) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (!s.isEmpty() && shouldDeposit(s)) n += s.getCount();
        }
        return n;
    }

    /** Keep tools/weapons/armour/food/torches/bed/rod; deposit everything else (after cactus pass). */
    private static boolean shouldDeposit(ItemStack s) {
        if (s.isEmpty()) return false;
        var item = s.getItem();
        if (item == Items.TORCH) return false;
        if (item == Items.FISHING_ROD) return false; // keep the rod for next vote
        if (item == Items.BREAD || item == Items.COOKED_BEEF || item == Items.COOKED_PORKCHOP
            || item == Items.COOKED_CHICKEN || item == Items.COOKED_MUTTON
            || item == Items.GOLDEN_APPLE || item == Items.APPLE || item == Items.CARROT) return false;
        if (item.getComponents().contains(net.minecraft.component.DataComponentTypes.MAX_DAMAGE)) return false;
        String path = Registries.ITEM.getId(item).getPath();
        if (path.endsWith("_boat")) return false;
        if (path.endsWith("_bed")) return false;
        if (path.endsWith("_pickaxe") || path.endsWith("_axe") || path.endsWith("_sword")
            || path.endsWith("_shovel") || path.endsWith("_hoe")) return false;
        if (path.endsWith("_helmet") || path.endsWith("_chestplate")
            || path.endsWith("_leggings") || path.endsWith("_boots")) return false;
        return true;
    }

    /** Match the item against the trash list in config. */
    private static boolean isTrash(ItemStack s) {
        if (s.isEmpty()) return false;
        var cfg = ChatPilotClient.CONFIG;
        if (cfg == null || cfg.trashItemIds == null || cfg.trashItemIds.isEmpty()) return false;
        Identifier id = Registries.ITEM.getId(s.getItem());
        if (id == null) return false;
        String full = id.toString();         // e.g. "minecraft:cobblestone"
        String shortId = id.getPath();       // e.g. "cobblestone"
        for (String t : cfg.trashItemIds) {
            if (t == null) continue;
            if (t.equalsIgnoreCase(full) || t.equalsIgnoreCase(shortId)) return true;
            // accept "minecraft:foo" entries against bare "foo" inputs and vice versa
            if (t.startsWith("minecraft:") && t.substring(10).equalsIgnoreCase(shortId)) return true;
        }
        return false;
    }

    @Override
    public boolean onStuck() {
        ChatPilotMod.LOGGER.info("[ChatPilot] return-home stuck in {}, soft reset", stage);
        ChatPilotClient.BARITONE.hardReset();
        switch (stage) {
            case WALK_HOME, FINAL_RETURN -> {
                ChatPilotClient.BARITONE.gotoNear(ChatPilotClient.HOME.getBedPos(), 3);
                return true;
            }
            case CACTUS_GO -> {
                BlockPos cactus = ChatPilotClient.HOME.getCactusPos();
                if (cactus != null) ChatPilotClient.BARITONE.gotoNear(cactus, 2);
                else advanceFromCactus();
                return true;
            }
            case CACTUS_AIM, CACTUS_DROP -> {
                // Bot is stuck while standing still next to cactus; just move on.
                advanceFromCactus();
                return true;
            }
            case WALK_TO_CHEST -> {
                if (currentChest != null) ChatPilotClient.BARITONE.gotoNear(currentChest, 1);
                return true;
            }
            case OPEN_CHEST, TRANSFER, CLOSE_CHEST -> {
                MinecraftClient.getInstance().player.closeHandledScreen();
                enterStage(Stage.NEXT_CHEST);
                return true;
            }
            case HOPPER_GO -> {
                BlockPos hopper = ChatPilotClient.HOME.getHopperPos();
                if (hopper != null) ChatPilotClient.BARITONE.gotoNear(hopper, 1);
                return true;
            }
            case HOPPER_OPEN, HOPPER_DEPOSIT, HOPPER_CLOSE -> {
                MinecraftClient.getInstance().player.closeHandledScreen();
                enterStage(Stage.NEXT_CHEST);
                return true;
            }
            default -> { return false; }
        }
    }

    @Override
    public void onCombatStart() { ChatPilotClient.BARITONE.stop(); }

    @Override
    public void onCombatEnd() {
        switch (stage) {
            case WALK_HOME, FINAL_RETURN -> ChatPilotClient.BARITONE.gotoNear(ChatPilotClient.HOME.getBedPos(), 3);
            case CACTUS_GO -> {
                BlockPos cactus = ChatPilotClient.HOME.getCactusPos();
                if (cactus != null) ChatPilotClient.BARITONE.gotoNear(cactus, 2);
            }
            case HOPPER_GO -> {
                BlockPos hopper = ChatPilotClient.HOME.getHopperPos();
                if (hopper != null) ChatPilotClient.BARITONE.gotoNear(hopper, 1);
            }
            case WALK_TO_CHEST -> { if (currentChest != null) ChatPilotClient.BARITONE.gotoNear(currentChest, 1); }
            default -> {}
        }
    }

    @Override
    public void cancel() {
        ChatPilotClient.BARITONE.hardReset();
        var p = MinecraftClient.getInstance().player;
        if (p != null) p.closeHandledScreen();
    }

    private int ticksInStage() { return clientTick() - stageStartTick; }

    private static int clientTick() {
        return (int) (com.grammacrackers.chatpilot.event.TickClock.now() & 0x7fffffff);
    }
}
