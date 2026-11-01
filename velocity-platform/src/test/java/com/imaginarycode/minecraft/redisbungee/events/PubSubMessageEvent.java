package com.imaginarycode.minecraft.redisbungee.events;

public final class PubSubMessageEvent {
    private final String channel;
    private final String message;

    public PubSubMessageEvent(String channel, String message) {
        this.channel = channel;
        this.message = message;
    }

    public String getChannel() {
        return channel;
    }

    public String getMessage() {
        return message;
    }
}
