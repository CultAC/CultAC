package ac.cult.cultac.network;

import ac.cult.cultac.manager.tick.BackendTickEstimator;
import ac.cult.cultac.network.codec.ConnectionModelValues;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.PacketValueAdapter;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.RegistryNames;
import ac.cult.cultac.utils.latency.ClientWorldRegistries;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

/** Platform operations for one exact transport connection, never an account lookup. */
public interface PlatformConnection {
    /** Available once the platform has authenticated this connection. */
    @Nullable
    User.Profile authenticatedProfile();

    /** Available once the platform's player has been attached to this connection. */
    @Nullable
    PlayerBinding playerBinding();

    /** Current model IDs for packet conversion: received registries on connections with model values. */
    default RegistryNames registryNames() {
        var values = modelValues();
        return values == null ? ModelRegistryNamesState.defaults() : values.registryNames();
    }

    /** Owned initial world facts; received configuration packets replace them. */
    default ClientWorldRegistries.Data initialWorldData() {
        return ClientWorldRegistries.modelDefaults();
    }

    /** Encoding at Cult's actual pipeline position; null selects the native dispatcher encoding. */
    @Nullable
    default ProtocolVersion getObservedProtocol() {
        return null;
    }

    /** Original authenticated client protocol, independent of any translation before Cult. */
    @Nullable
    default ProtocolVersion getClientProtocol() {
        return null;
    }

    /** Disconnects a client older than 1.21.2 (in observe-only, leaves it untracked); true when it is not to be tracked. */
    default boolean disconnectOutdatedClient() {
        return false;
    }

    /**
     * The brand the platform already received on this connection before Cult attached; null when the platform does not
     * keep it or none was sent yet.
     */
    @Nullable
    default String clientBrand() {
        return null;
    }

    /** The backend server this connection is in, where the platform has several; null otherwise. */
    @Nullable
    default String backendName() {
        return null;
    }

    /** Present when the observed wire can differ from the 26.3 model; shared by every platform. */
    @Nullable
    default ConnectionModelValues modelValues() {
        return null;
    }

    default PacketValueAdapter packetValues(CultConnection connection) {
        var values = modelValues();
        return values == null ? null : values.packetValues(getObservedProtocol(), connection);
    }

    /**
     * Server tick boundaries estimated from the backend stream, for platforms that cannot observe the
     * server's tick loop. Null where the platform delivers tick ends itself.
     */
    @Nullable
    default BackendTickEstimator backendTicks() {
        return null;
    }

    /**
     * Runs connection work that started on a bridge's own thread, such as Geyser's tick loop.
     */
    default void runInModel(Runnable task) {
        task.run();
    }

    void disconnect(Component reason);

    /** Direct platform delivery; must not call back through User.sendMessage. */
    void sendMessage(Component message);

    interface PlayerBinding {
        PlatformPlayer player();

        /** Rechecked on the packet owner before binding; a reconnect must not steal this session. */
        boolean isCurrent();

        boolean matches(Object nativePlayer);

        /** Initializes platform state when joining or attaching during a reload. */
        void initialize(CultPlayer player);
    }
}
