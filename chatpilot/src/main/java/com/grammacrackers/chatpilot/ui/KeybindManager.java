package com.grammacrackers.chatpilot.ui;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class KeybindManager {

    public static KeyBinding toggleHud;
    public static KeyBinding emergencyStop;
    public static KeyBinding setHomeFromBed;
    public static KeyBinding forceVote;
    public static KeyBinding togglePilot;
    public static KeyBinding triggerDance;

    public static boolean pilotEnabled = true;

    public static void register() {
        toggleHud = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.chatpilot.toggle_hud", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F6, "category.chatpilot"));
        emergencyStop = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.chatpilot.estop", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F7, "category.chatpilot"));
        setHomeFromBed = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.chatpilot.set_home", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, "category.chatpilot"));
        forceVote = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.chatpilot.force_vote", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9, "category.chatpilot"));
        togglePilot = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.chatpilot.toggle_pilot", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F10, "category.chatpilot"));
        // F11 is normally Minecraft's fullscreen toggle, so we bind to RIGHT_BRACKET
        // by default to avoid clobbering that. Users can rebind freely in
        // controls. Choosing a non-conflicting default keeps "fresh install"
        // behaviour predictable.
        triggerDance = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.chatpilot.dance", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, "category.chatpilot"));

        ClientTickEvents.END_CLIENT_TICK.register(KeybindManager::tick);
    }

    private static void tick(MinecraftClient mc) {
        while (toggleHud.wasPressed()) {
            ChatPilotClient.HUD.enabled = !ChatPilotClient.HUD.enabled;
            ChatPilotMod.LOGGER.info("[ChatPilot] HUD enabled = {}", ChatPilotClient.HUD.enabled);
        }
        while (emergencyStop.wasPressed()) {
            ChatPilotMod.LOGGER.info("[ChatPilot] EMERGENCY STOP pressed");
            ChatPilotClient.TASKS.cancel();
            ChatPilotClient.BARITONE.hardReset();
        }
        while (setHomeFromBed.wasPressed()) {
            ChatPilotMod.LOGGER.info("[ChatPilot] Set home from nearest bed");
            ChatPilotClient.HOME.autoSetFromNearestBed(8);
        }
        while (forceVote.wasPressed()) {
            ChatPilotMod.LOGGER.info("[ChatPilot] Force open new vote window");
            ChatPilotClient.TASKS.cancel();
            ChatPilotClient.VOTES.openVote();
        }
        while (togglePilot.wasPressed()) {
            pilotEnabled = !pilotEnabled;
            ChatPilotMod.LOGGER.info("[ChatPilot] Pilot enabled = {}", pilotEnabled);
            if (!pilotEnabled) {
                ChatPilotClient.TASKS.cancel();
                ChatPilotClient.BARITONE.hardReset();
            }
        }
        while (triggerDance.wasPressed()) {
            if (ChatPilotClient.DANCE != null) {
                ChatPilotClient.DANCE.forceDance("manual keybind");
            }
        }
    }
}
