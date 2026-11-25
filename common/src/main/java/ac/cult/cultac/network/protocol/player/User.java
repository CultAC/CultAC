package ac.cult.cultac.network.protocol.player;

import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import io.netty.channel.ChannelFuture;
import io.netty.util.concurrent.EventExecutor;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

public final class User {
    @Nullable
    private volatile PlatformPlayer player;

    private final Profile profile;
    private final UUID connectionId;

    public UUID getConnectionId() {
        return connectionId;
    }

    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final CultConnection cultConnection;

    public User(Profile profile, CultConnection connection) {
        this.profile = Objects.requireNonNull(profile);
        this.cultConnection = Objects.requireNonNull(connection);
        var continued = connection.continuation();
        connectionId =
                continued != null && continued.connectionId() != null ? continued.connectionId() : UUID.randomUUID();
        connection.bind(this);
    }

    public CultConnection getCultConnection() {
        return cultConnection;
    }

    public ProtocolVersion getObservedProtocol() {
        return cultConnection.getObservedProtocol();
    }

    @Nullable
    public ProtocolVersion getClientProtocol() {
        var cultPlayer = getCultPlayer();
        return cultPlayer == null ? cultConnection.getClientProtocol() : cultPlayer.getClientProtocol();
    }

    public CultPlayer getCultPlayer() {
        return cultConnection.player();
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
    public PlatformPlayer getPlayer() {
        return player;
    }

    public Object getChannel() {
        return cultConnection.channel();
    }

    public EventExecutor getPacketExecutor() {
        return cultConnection.owner();
    }

    public void execute(Runnable task) {
        cultConnection.execute(task);
    }

    public void executeLater(Runnable task) {
        cultConnection.executeLater(task);
    }

    public void executeAfterWrites(Runnable task) {
        cultConnection.executeAfterWrites(task);
    }

    public void flushAfterWrites() {
        cultConnection.flushAfterWrites();
    }

    public boolean isPreparingWrites() {
        return cultConnection.isPreparingWrites();
    }

    @Nullable
    public Object getBedrockBridgeConnection() {
        return cultConnection.bedrockBridge();
    }

    public ConnectionPhase getConnectionState() {
        return cultConnection.phase(PacketDirection.SERVERBOUND);
    }

    public ConnectionPhase getEncoderState() {
        return cultConnection.phase(PacketDirection.CLIENTBOUND);
    }

    public CompletionStage<Void> write(Object packet) {
        return completion(
                getCultConnection().write(packet instanceof CultWrite write ? write : new CultWrite(packet, false)));
    }

    public CompletionStage<Void> write(List<CultWrite> packets, boolean bundle) {
        return completion(getCultConnection().write(packets, bundle));
    }

    private static CompletionStage<Void> completion(ChannelFuture future) {
        var result = new CompletableFuture<Void>();
        future.addListener(done -> {
            if (done.isSuccess()) result.complete(null);
            else result.completeExceptionally(done.cause());
        });
        return result.minimalCompletionStage();
    }

    public void closeConnection() {
        if (!closeRequested.compareAndSet(false, true)) {
            return;
        }

        var platform = cultConnection.platform();
        if (platform != null) platform.disconnect(Component.text("Disconnected"));
        else cultConnection.channel().close();
    }

    public void sendMessage(Component component) {
        var platform = cultConnection.platform();
        if (platform != null) platform.sendMessage(component);
    }

    public void bind(PlatformPlayer player) {
        this.player = Objects.requireNonNull(player);
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
