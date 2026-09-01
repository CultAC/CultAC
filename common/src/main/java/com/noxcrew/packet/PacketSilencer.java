package com.noxcrew.packet;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks packet object identities that should pass through the bridge without handler dispatch.
 */
public final class PacketSilencer {
    private final AttributeKey<CopyOnWriteArrayList<Packet<?>>> key;

    public PacketSilencer(String attributeName) {
        this.key = AttributeKey.valueOf(attributeName);
    }

    public void markExact(Channel channel, Packet<?> packet) {
        if (channel == null || packet == null) {
            return;
        }
        packets(channel).add(packet);
    }

    public void markRecursive(Channel channel, Packet<?> packet) {
        if (channel == null || packet == null) {
            return;
        }
        markExact(channel, packet);
        if (packet instanceof BundlePacket<?> bundle) {
            for (Packet<?> subPacket : PacketBundleUtil.subPackets(bundle)) {
                markRecursive(channel, subPacket);
            }
        }
    }

    public boolean consume(Channel channel, Packet<?> packet) {
        if (channel == null || packet == null) {
            return false;
        }

        CopyOnWriteArrayList<Packet<?>> packets = channel.attr(key).get();
        if (packets == null) {
            return false;
        }

        for (Packet<?> candidate : packets) {
            if (candidate == packet) {
                packets.remove(candidate);
                return true;
            }
        }

        return false;
    }

    public void clear(Channel channel) {
        if (channel != null) {
            channel.attr(key).set(null);
        }
    }

    private CopyOnWriteArrayList<Packet<?>> packets(Channel channel) {
        CopyOnWriteArrayList<Packet<?>> packets = channel.attr(key).get();
        if (packets != null) {
            return packets;
        }

        CopyOnWriteArrayList<Packet<?>> created = new CopyOnWriteArrayList<>();
        if (!channel.attr(key).compareAndSet(null, created)) {
            return channel.attr(key).get();
        }
        return created;
    }
}
