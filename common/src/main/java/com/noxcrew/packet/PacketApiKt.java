package com.noxcrew.packet;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.Collection;

/** Compatibility home for the static packet send helpers. */
public final class PacketApiKt {
    private PacketApiKt() {
    }

    /** Sends the given packets to a player. */
    public static void sendPacket(Player player, Packet<?>... packets) {
        if (!(player instanceof CraftPlayer craftPlayer)) {
            return;
        }

        ServerGamePacketListenerImpl connection = craftPlayer.getHandle().connection;
        for (Packet<?> packet : packets) {
            if (packet != null) {
                writePacketImmediately(connection, packet);
            }
        }
    }

    /** Sends the given packets to a collection of players. */
    public static void sendPacket(Collection<? extends Player> players, Packet<?>... packets) {
        for (Player player : players) {
            sendPacket(player, packets);
        }
    }

    /** Sends the given packets to a player. */
    public static void sendPacket(Player player, Iterable<? extends Packet<?>> packets) {
        if (!(player instanceof CraftPlayer craftPlayer)) {
            return;
        }

        ServerGamePacketListenerImpl connection = craftPlayer.getHandle().connection;
        for (Packet<?> packet : packets) {
            if (packet != null) {
                writePacketImmediately(connection, packet);
            }
        }
    }

    /** Sends the given packets to players. */
    public static void sendPacket(Iterable<? extends Player> players, Iterable<? extends Packet<?>> packets) {
        for (Player player : players) {
            sendPacket(player, packets);
        }
    }

    private static void writePacketImmediately(ServerGamePacketListenerImpl connection, Packet<?> packet) {
        connection.connection.channel.writeAndFlush(packet);
    }
}
