package com.grammacrackers.chatpilot.tasks;

import com.grammacrackers.chatpilot.ChatPilotClient;
import com.grammacrackers.chatpilot.ChatPilotMod;

public class UnstuckTask implements Task {
    @Override
    public String displayName() {
        return "Unstucking";
    }

    @Override
    public String id() {
        return "unstuck";
    }

    @Override
    public void onCombatStart() {
        if (ChatPilotClient.BARITONE != null) {
            ChatPilotClient.BARITONE.hardReset();
        }
    }
    
    @Override
    public void onCombatEnd() {
        if (ChatPilotClient.BARITONE != null) {
            ChatPilotClient.BARITONE.hardReset();
        }
    }

    @Override
    public void start() {
        ChatPilotMod.LOGGER.warn("[ChatPilot][Unstuck] Chat voted for emergency reset + return home");

        if (ChatPilotClient.BARITONE != null) {
            ChatPilotClient.BARITONE.hardReset();
        }

        if (ChatPilotClient.STUCK != null) {
            ChatPilotClient.STUCK.reset();
        }

        if (ChatPilotClient.UNSTUCK != null) {
            ChatPilotClient.UNSTUCK.resetAfterUnstuck();
        }
    }

    @Override
    public boolean tick() {
        // Immediately finish. TaskManager will then start ReturnHomeAndDepositTask.
        return true;
    }

    @Override
    public boolean onStuck() {
        return false;
    }

    @Override
    public void cancel() {
        if (ChatPilotClient.BARITONE != null) {
            ChatPilotClient.BARITONE.hardReset();
        }
    }
}
