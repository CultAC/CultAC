package ac.grim.grimac.network.protocol.player;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.network.protocol.util.FoliaCompatUtil;
import io.netty.channel.Channel;
import net.kyori.adventure.text.Component;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class User {
    @Nullable
    private Player player;
    @Nullable
    private ServerPlayer handle;
    private final Connection connection;
    private final Channel channel;
    private final Profile profile;
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    // Bound lazily by the private embedded-Geyser payload on this User's
    // server-side Netty event loop. Kept untyped so User remains loadable when
    // the optional Geyser classes are absent.
    private Object bedrockBridgeConnection;
    private volatile ConnectionProtocol connectionState;
    private volatile ConnectionProtocol encoderState;

    public User(Player player, ServerPlayer handle, Connection connection, Channel channel) {
        this(new Profile(player.getUniqueId(), player.getName()), player, handle, connection, channel);
    }

    public User(Profile profile, @Nullable Player player, @Nullable ServerPlayer handle, Connection connection, Channel channel) {
        this.profile = profile;
        this.player = player;
        this.handle = handle;
        this.connection = connection;
        this.channel = channel;
        this.connectionState = ConnectionProtocol.PLAY;
        this.encoderState = ConnectionProtocol.PLAY;
    }

    public UUID getUUID() {
        return profile.getUUID();
    }

    public String getName() {
        return profile.getName();
    }

    public Profile getProfile() {
        return profile;
    }

    @Nullable
    public Player getPlayer() {
        return player;
    }

    @Nullable
    public ServerPlayer getHandle() {
        return handle;
    }

    public Connection getConnection() {
        return connection;
    }

    public Object getChannel() {
        return channel;
    }

    @Nullable
    public Object getBedrockBridgeConnection() {
        return bedrockBridgeConnection;
    }

    public void setBedrockBridgeConnection(Object connection) {
        bedrockBridgeConnection = connection;
    }

    public ConnectionProtocol getConnectionState() {
        return connectionState;
    }

    public void setConnectionState(ConnectionProtocol connectionState) {
        this.connectionState = connectionState;
    }

    public ConnectionProtocol getEncoderState() {
        return encoderState;
    }

    public void setEncoderState(ConnectionProtocol encoderState) {
        this.encoderState = encoderState;
    }

    public void writePacket(Object packet) {
        sendPacket(packet);
    }

    public void sendPacket(Object packet) {
        Object nmsPacket = unwrap(packet);
        if (nmsPacket instanceof net.minecraft.network.protocol.Packet<?> packetToSend) {
            GrimAPI.INSTANCE.getNetworkManager().sendPacket(channel, packetToSend, false);
        }
    }

    public void sendPacketSilently(Object packet) {
        Object nmsPacket = unwrap(packet);
        if (nmsPacket instanceof Packet<?> packetToSend) {
            GrimAPI.INSTANCE.getNetworkManager().sendPacket(channel, packetToSend, true);
        }
    }

    public void sendPacketWithSilentPackets(Object packet, Collection<? extends Packet<?>> silentPackets) {
        Object nmsPacket = unwrap(packet);
        if (nmsPacket instanceof Packet<?> packetToSend) {
            GrimAPI.INSTANCE.getNetworkManager().sendPacketWithSilentPackets(channel, packetToSend, silentPackets);
        }
    }

    public void receivePacket(Object packet) {
        Object nmsPacket = unwrap(packet);
        if (nmsPacket instanceof Packet<?> packetToReceive) {
            GrimAPI.INSTANCE.getNetworkManager().receivePacket(channel, packetToReceive, false);
        }
    }

    public void receivePacketSilently(Object packet) {
        Object nmsPacket = unwrap(packet);
        if (nmsPacket instanceof Packet<?> packetToReceive) {
            GrimAPI.INSTANCE.getNetworkManager().receivePacket(channel, packetToReceive, true);
        }
    }

    public void closeConnection() {
        if (!closeRequested.compareAndSet(false, true)) {
            return;
        }

        if (player != null) {
            FoliaCompatUtil.runTaskForEntity(player, GrimAPI.INSTANCE.getPlugin(), () -> player.kick(Component.text("Disconnected")), null, 0);
        } else {
            connection.disconnect(net.minecraft.network.chat.Component.literal("Disconnected"));
        }
    }

    public void sendMessage(Component component) {
        if (player != null) {
            player.sendMessage(component);
        }
    }

    public void bind(@Nullable Player player, @Nullable ServerPlayer handle) {
        this.player = player;
        this.handle = handle;
    }

    private Object unwrap(Object packet) {
        if (packet == null) {
            return null;
        }
        if (packet instanceof net.minecraft.network.protocol.Packet<?>) {
            return packet;
        }
        try {
            Method getPacket = packet.getClass().getMethod("getPacket");
            Object unwrapped = getPacket.invoke(packet);
            if (unwrapped != null) {
                return unwrapped;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return packet;
    }

    public static final class Profile {
        private final UUID uuid;
        private final String name;

        public Profile(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        public UUID getUUID() {
            return uuid;
        }

        public String getName() {
            return name;
        }
    }
}
