package ac.cult.cultac.platform.bukkit;

import ac.cult.cultac.network.CultNetworkManager;
import ac.cult.cultac.network.DormancyController;
import ac.cult.cultac.network.codec.ClientPacketCodecs;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolRuntime;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.data.RegistryNames;
import ac.cult.cultac.utils.latency.ClientWorldRegistries;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

/** Resolves the codec catalog once before registering Paper's channel initializer. */
final class BukkitProtocolTransport {
    private BukkitProtocolTransport() {}

    static PaperInjector initialize(CultNetworkManager manager, JavaPlugin plugin) {
        var version = ProtocolVersion.of(SharedConstants.getProtocolVersion());
        // The dispatcher speaks the 26.3 model; an older host's wire is projected per connection.
        // Every connection's value codecs load here rather than on the first event loop.
        ProtocolCodecs.decoder();
        var runtime = ProtocolRuntime.create(
                ProtocolData.load(ProtocolVersion.V26_3),
                ClientPacketCodecs.catalog(
                        context -> context.state().require(RegistryNames.class),
                        context -> context.state().require(ClientWorldRegistries.class)),
                commandInputLimit(version));
        var dormancy = new PaperDormancy();
        DormancyController.platform(dormancy);
        var injector = new PaperInjector(manager, dormancy);
        var listener = new BukkitConnectionListener(manager, dormancy);
        manager.configureTransport(
                runtime,
                injector::enable,
                () -> {
                    BukkitRuntimeRegistrations.registerEvents(listener, plugin);
                },
                () -> {
                    HandlerList.unregisterAll(listener);
                    return injector.unregister().thenCompose(ignored -> dormancy.remove());
                });
        return injector;
    }

    private static int commandInputLimit(ProtocolVersion version) {
        if (!version.atLeast(ProtocolVersion.V1_21_11)) return 32767;
        try {
            return (Integer) ServerboundChatCommandPacket.class
                    .getField("MAX_CHAT_PACKET_INPUT_SIZE")
                    .get(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Native command input limit is unavailable", failure);
        }
    }
}
