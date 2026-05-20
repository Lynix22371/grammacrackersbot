package com.grammacrackers.chatpilot.chat;

public class ChatMessage {
    public final String author;
    public final String text;
    public final long   timestampMillis;
    public final String source;

    /**
     * USD value attached to this message, when applicable. Populated by the
     * YouTube poller for Super Chat / Super Sticker / future jewel events
     * that the API surfaces. 0.0 means "regular chat with no tip attached".
     * Restream feed currently passes 0.0 for everything; we'd need per-platform
     * field digging to extract paid Twitch bits / cheers later.
     */
    public final double tipUsd;

    public ChatMessage(String author, String text, long ts, String source) {
        this(author, text, ts, source, 0.0);
    }

    public ChatMessage(String author, String text, long ts, String source, double tipUsd) {
        this.author = author == null ? "anon" : author;
        this.text = text == null ? "" : text;
        this.timestampMillis = ts;
        this.source = source;
        this.tipUsd = tipUsd;
    }
}
