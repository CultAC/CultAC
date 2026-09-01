package com.noxcrew.packet;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;

/**
 * Packet bridge context for a packet currently moving through the injected channel handler.
 */
public final class PacketContext {
    private final Connection connection;
    private final Channel channel;
    private final boolean insideBundle;

    public PacketContext(Connection connection, Channel channel) {
        this(connection, channel, false);
    }

    private PacketContext(Connection connection, Channel channel, boolean insideBundle) {
        this.connection = connection;
        this.channel = channel;
        this.insideBundle = insideBundle;
    }

    public Connection connection() {
        return connection;
    }

    public Channel channel() {
        return channel;
    }

    public boolean insideBundle() {
        return insideBundle;
    }

    PacketContext asBundled() {
        return insideBundle ? this : new PacketContext(connection, channel, true);
    }

}
