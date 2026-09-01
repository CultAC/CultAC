package com.noxcrew.packet;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;

import java.util.List;

/** A custom packet handler that modifies incoming and outgoing packets. */
// Originally licensed under LGPL from Noxesium
// https://github.com/Noxcrew/noxesium/blob/main/paper/packet/src/main/kotlin/com/noxcrew/packet/ChannelPacketHandler.kt
final class ChannelPacketHandler extends ChannelDuplexHandler {
    private final PacketApi packetApi;
    private final Connection connection;

    ChannelPacketHandler(PacketApi packetApi, Connection connection) {
        this.packetApi = packetApi;
        this.connection = connection;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        // Hidden packets can be sent as direct byte buffers instead of packet instances.
        if (!(msg instanceof Packet<?> packet)) {
            super.write(ctx, msg, promise);
            return;
        }

        boolean inputWasBundle = packet instanceof ClientboundBundlePacket;
        List<Packet<?>> packets = packetApi.handlePacket(new PacketContext(connection, ctx.channel()), packet);
        if (packets.isEmpty()) {
            // A cancelled write still consumed the caller's operation. Leaving
            // its promise incomplete can stall flush/close listeners forever.
            promise.trySuccess();
            return;
        }
        if (packets.size() == 1) {
            super.write(ctx, inputWasBundle ? PacketApi.bundle(packets) : packets.getFirst(), promise);
            return;
        }

        super.write(ctx, PacketApi.bundle(packets), promise);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof Packet<?> packet)) {
            super.channelRead(ctx, msg);
            return;
        }

        List<Packet<?>> packets = packetApi.handlePacket(new PacketContext(connection, ctx.channel()), packet);
        if (packets.isEmpty()) {
            return;
        }
        if (packets.size() != 1) {
            throw new IllegalArgumentException("Cannot read multiple packets at once");
        }
        super.channelRead(ctx, packets.getFirst());
    }
}
