package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.minecraft.MinecraftRegistries;
import ac.cult.cultac.vanilla.VanillaRegistryState;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.util.concurrent.EventExecutor;
import net.kyori.adventure.text.Component;

final class VelocityConnectionAdapter implements PlatformConnection {
    private final ProxyServer proxy;
    private final Player nativePlayer;
    private final VelocityPlayer player;
    private final VanillaRegistryState state;
    private final EventExecutor owner;
    private final Object bedrockBridge;
    private final ac.cult.cultac.protocol.WireValueDecoder codecs;
    private VelocityProtocols.Versions protocols;
    private ac.cult.cultac.network.codec.ObservedPacketValues values;
    private CultConnection connection;

    VelocityConnectionAdapter(
            ProxyServer proxy,
            Player nativePlayer,
            VelocityPlayer player,
            VanillaRegistryState state,
            EventExecutor eventLoop) {
        this(proxy, nativePlayer, player, state, eventLoop, null, null);
    }

    VelocityConnectionAdapter(
            ProxyServer proxy,
            Player nativePlayer,
            VelocityPlayer player,
            VanillaRegistryState state,
            EventExecutor eventLoop,
            ac.cult.cultac.protocol.WireValueDecoder codecs,
            ac.cult.cultac.network.PacketOwner bedrock) {
        this.proxy = proxy;
        this.nativePlayer = nativePlayer;
        this.player = player;
        this.state = state;
        // A Bedrock session stays on Geyser's tick loop, which orders its Bedrock and Java packets.
        this.owner = bedrock == null ? eventLoop : bedrock.executor();
        this.bedrockBridge = bedrock == null ? null : bedrock.bedrockBridge();
        this.codecs = codecs;
    }

    EventExecutor owner() {
        return owner;
    }

    Object bedrockBridge() {
        return bedrockBridge;
    }

    @Override
    public void runInModel(Runnable task) {
        state.execute(task);
    }

    VanillaRegistryState state() {
        return state;
    }

    void attach(CultConnection connection) {
        this.connection = connection;
        player.attach(connection);
    }

    void bindProtocols(VelocityProtocols.Versions protocols) {
        if (this.protocols != null) throw new IllegalStateException("Velocity client protocols already bound");
        this.protocols = java.util.Objects.requireNonNull(protocols);
    }

    @Override
    public MinecraftRegistries registries() {
        return state.registries();
    }

    @Override
    public ac.cult.cultac.protocol.ProtocolVersion getObservedProtocol() {
        return protocols == null ? null : protocols.observed();
    }

    @Override
    public ac.cult.cultac.protocol.ProtocolVersion getClientProtocol() {
        return protocols == null ? null : protocols.client();
    }

    @Override
    public ac.cult.cultac.protocol.PacketValueAdapter packetValues() {
        if (values == null)
            values = new ac.cult.cultac.network.codec.ObservedPacketValues(
                    codecs, getObservedProtocol(), state.registries(), () -> {
                        var player = connection.player();
                        if (player == null) throw new IllegalStateException("Chunk before player initialization");
                        var dimension = player.compensatedWorld.getLastClientboundDimension();
                        return new int[] {dimension.minHeight(), dimension.sectionCount() * 16};
                    });
        return values;
    }

    void beginConfiguration() {
        if (values != null) values.beginConfiguration();
    }

    @Override
    public User.Profile authenticatedProfile() {
        return new User.Profile(nativePlayer.getUniqueId(), nativePlayer.getUsername());
    }

    @Override
    public void disconnect(Component reason) {
        nativePlayer.disconnect(reason);
    }

    @Override
    public void sendMessage(Component message) {
        nativePlayer.sendMessage(message);
    }

    @Override
    public PlayerBinding playerBinding() {
        return new PlayerBinding() {
            @Override
            public VelocityPlayer player() {
                return player;
            }

            @Override
            public boolean isCurrent() {
                return nativePlayer.isActive()
                        && proxy.getPlayer(nativePlayer.getUniqueId()).orElse(null) == nativePlayer;
            }

            @Override
            public boolean matches(Object candidate) {
                return candidate == nativePlayer;
            }

            @Override
            public void initialize(CultPlayer target) {
                // Join/respawn packets supply client-visible entity, dimension and game mode.
                // Velocity has no authoritative world state to copy during configuration.
            }
        };
    }
}
