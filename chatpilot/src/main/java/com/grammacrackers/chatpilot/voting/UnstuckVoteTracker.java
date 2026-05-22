package com.grammacrackers.chatpilot.voting;

import com.grammacrackers.chatpilot.ChatPilotClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;

public class UnstuckVoteTracker {
    private Vec3d lastPos = null;
    private double distanceSinceLastVote = 0.0;
    private final Deque<Double> voteDistances = new ArrayDeque<>();

    public void tick(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.world == null) {
            lastPos = null;
            return;
        }

        Vec3d now = mc.player.getPos();

        if (lastPos == null) {
            lastPos = now;
            return;
        }

        double d = now.distanceTo(lastPos);

        // Ignore teleport / dimension-change spikes.
        if (d > 0.001 && d < 20.0) {
            distanceSinceLastVote += d;
        }

        lastPos = now;
    }

    public void onVoteOpened() {
        int lookback = Math.max(1, ChatPilotClient.CONFIG.unstuckVoteLookbackVotes);

        voteDistances.addLast(distanceSinceLastVote);

        while (voteDistances.size() > lookback) {
            voteDistances.removeFirst();
        }

        distanceSinceLastVote = 0.0;
    }

    public boolean shouldOfferUnstuck() {
        if (ChatPilotClient.CONFIG == null || !ChatPilotClient.CONFIG.unstuckVoteEnabled) {
            return false;
        }

        int lookback = Math.max(1, ChatPilotClient.CONFIG.unstuckVoteLookbackVotes);

        if (voteDistances.size() < lookback) {
            return false;
        }

        double total = 0.0;

        for (double d : voteDistances) {
            total += d;
        }

        return total < ChatPilotClient.CONFIG.unstuckVoteMinDistanceBlocks;
    }

    public void resetAfterUnstuck() {
        voteDistances.clear();
        distanceSinceLastVote = 0.0;
        lastPos = null;
    }

    public String debugSummary() {
        double total = 0.0;

        for (double d : voteDistances) {
            total += d;
        }

        return String.format("lastVotes=%s total=%.1f", voteDistances, total);
    }
}
