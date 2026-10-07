package ac.cult.cultac.network.protocol.player;

import ac.cult.cultac.platform.api.player.PlatformPlayer;
import io.netty.util.concurrent.EventExecutor;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

public final class User {
    @Nullable
    private volatile PlatformPlayer player;

    private final Profile profile;
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final ac.cult.cultac.network.CultConnection cultConnection;

    public User(Profile profile, ac.cult.cultac.network.CultConnection connection) {
        this.profile = java.util.Objects.requireNonNull(profile);
        this.cultConnection = java.util.Objects.requireNonNull(connection);
        connection.bind(this);
    }

    public ac.cult.cultac.network.CultConnection getCultConnection() {
        return cultConnection;
    }

    public ac.cult.cultac.protocol.ProtocolVersion getObservedProtocol() {
        return cultConnection.getObservedProtocol();
    }

    @Nullable
    public ac.cult.cultac.protocol.ProtocolVersion getClientProtocol() {
        var cultPlayer = getCultPlayer();
        return cultPlayer == null ? cultConnection.getClientProtocol() : cultPlayer.getClientProtocol();
    }

    public ac.cult.cultac.player.CultPlayer getCultPlayer() {
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

    public boolean isPreparingWrites() {
        return cultConnection.isPreparingWrites();
    }

    @Nullable
    public Object getBedrockBridgeConnection() {
        return cultConnection.bedrockBridge();
    }

    public ac.cult.cultac.protocol.ConnectionPhase getConnectionState() {
        return cultConnection.phase(ac.cult.cultac.protocol.PacketDirection.SERVERBOUND);
    }

    public ac.cult.cultac.protocol.ConnectionPhase getEncoderState() {
        return cultConnection.phase(ac.cult.cultac.protocol.PacketDirection.CLIENTBOUND);
    }

    public CompletionStage<Void> write(Object packet) {
        return completion(getCultConnection()
                .write(
                        packet instanceof ac.cult.cultac.network.CultWrite write
                                ? write
                                : new ac.cult.cultac.network.CultWrite(packet, false)));
    }

    public CompletionStage<Void> writeSilently(Object packet) {
        return completion(getCultConnection().write(new ac.cult.cultac.network.CultWrite(packet, true)));
    }

    public CompletionStage<Void> write(java.util.List<ac.cult.cultac.network.CultWrite> packets, boolean bundle) {
        return completion(getCultConnection().write(packets, bundle));
    }

    private static CompletionStage<Void> completion(io.netty.channel.ChannelFuture future) {
        var result = new java.util.concurrent.CompletableFuture<Void>();
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
        this.player = java.util.Objects.requireNonNull(player);
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
