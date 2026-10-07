package ac.cult.cultac.network;

import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
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
    default ac.cult.cultac.protocol.data.RegistryNames registryNames() {
        var values = modelValues();
        return values == null
                ? ac.cult.cultac.network.codec.ModelRegistryNamesState.defaults()
                : values.registryNames();
    }

    /** Owned initial world facts; received configuration packets replace them. */
    default ac.cult.cultac.utils.latency.ClientWorldRegistries.Data initialWorldData() {
        return ac.cult.cultac.utils.latency.ClientWorldRegistries.modelDefaults();
    }

    /** Encoding at Cult's actual pipeline position; null selects the native dispatcher encoding. */
    @Nullable
    default ac.cult.cultac.protocol.ProtocolVersion getObservedProtocol() {
        return null;
    }

    /** Original authenticated client protocol, independent of any translation before Cult. */
    @Nullable
    default ac.cult.cultac.protocol.ProtocolVersion getClientProtocol() {
        return null;
    }

    /** Present when the observed wire can differ from the 26.3 model; shared by every platform. */
    @Nullable
    default ac.cult.cultac.network.codec.ConnectionModelValues modelValues() {
        return null;
    }

    default ac.cult.cultac.protocol.PacketValueAdapter packetValues(CultConnection connection) {
        var values = modelValues();
        return values == null ? null : values.packetValues(getObservedProtocol(), connection);
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
