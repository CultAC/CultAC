package com.imaginarycode.minecraft.redisbungee;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The slice of RedisBungee's API that CultAC reaches by reflection, recording what it registers and publishes. */
public class AbstractRedisBungeeAPI {
    public static AbstractRedisBungeeAPI instance;

    public String proxyId = "proxy-a";
    public final List<String> published = new ArrayList<>();
    public final List<String> publishedChannels = new ArrayList<>();
    public final List<String> registered = new ArrayList<>();

    public static AbstractRedisBungeeAPI getAbstractRedisBungeeAPI() {
        return instance;
    }

    public UUID getUuidFromName(String name, boolean expensiveLookup) {
        return null;
    }

    public String getNameFromUuid(UUID uuid, boolean expensiveLookup) {
        return null;
    }

    public boolean isPlayerOnline(UUID uuid) {
        return false;
    }

    public String getProxy(UUID uuid) {
        return null;
    }

    public String getServerNameFor(UUID uuid) {
        return null;
    }

    public String getProxyId() {
        return proxyId;
    }

    public synchronized void sendChannelMessage(String channel, String message) {
        publishedChannels.add(channel);
        published.add(message);
    }

    public synchronized void registerPubSubChannels(String... channels) {
        registered.addAll(List.of(channels));
    }
}
