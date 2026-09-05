package ac.cult.cultac.packet;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.NoSuchElementException;

/** Handles a player's connection by injecting a custom handler. */
// Originally licensed under LGPL from Noxesium
// https://github.com/Noxcrew/noxesium/blob/main/paper/packet/src/main/kotlin/com/noxcrew/packet/PlayerConnectionHandler.kt
final class PlayerConnectionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerConnectionHandler.class);
    static final String MINECRAFT_PACKET_HANDLER_KEY = "packet_handler";

    private final String key;
    private final PacketApi packetApi;
    private final Connection connection;
    private final Channel channel;
    private boolean registered;

    PlayerConnectionHandler(String key, PacketApi packetApi, Connection connection, Channel channel) {
        this.key = key;
        this.packetApi = packetApi;
        this.connection = connection;
        this.channel = channel;
    }

    /** Registers this handler, hooking into the pipeline. */
    void register() {
        if (registered) {
            return;
        }
        registered = true;
        channel.pipeline().addBefore(
                MINECRAFT_PACKET_HANDLER_KEY,
                key,
                new ChannelPacketHandler(packetApi, connection)
        );
    }

    /** Unregisters this handler, removing the interceptor from the pipeline if not on disconnect. */
    void unregister() {
        unregister(false);
    }

    void unregister(boolean disconnect) {
        if (!registered) {
            return;
        }
        registered = false;

        if (!disconnect) {
            try {
                channel.pipeline().remove(key);
            } catch (Exception exception) {
                if (!(exception instanceof NoSuchElementException)) {
                    LOGGER.error("An unknown error occurred whilst removing a packet handler from a player", exception);
                }
            }
        }
    }
}
