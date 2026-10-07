package ac.cult.cultac.codec;

import ac.cult.shaded.vialib.ViaManagerImpl;
import ac.cult.shaded.vialib.api.Via;
import ac.cult.shaded.vialib.api.data.MappingDataLoader;
import ac.cult.shaded.vialib.api.platform.ViaPlatformLoader;
import ac.cult.shaded.vialib.api.protocol.packet.Direction;
import ac.cult.shaded.vialib.api.protocol.version.ProtocolVersion;
import ac.cult.shaded.vialib.backwards.ViaBackwards;
import ac.cult.shaded.vialib.backwards.ViaBackwardsConfig;
import ac.cult.shaded.vialib.backwards.api.ViaBackwardsPlatform;
import ac.cult.shaded.vialib.backwards.api.data.TranslatableMappings;
import ac.cult.shaded.vialib.backwards.protocol.registration.BackwardsRegistrations;
import ac.cult.shaded.vialib.commands.ViaCommandHandler;
import ac.cult.shaded.vialib.platform.NoopInjector;
import ac.cult.shaded.vialib.protocols.base.v1_16.ClientboundBaseProtocol1_16;
import ac.cult.shaded.vialib.protocols.base.v1_7.ServerboundBaseProtocol1_7;
import com.google.common.collect.Range;
import java.io.File;
import java.util.logging.Logger;

/** Only the configuration/PLAY conversion paths between 1.21.3 and 26.3 are registered. */
final class ModernProtocols {
    // Loaded mappings and types belong to the normal plugin loader's lifetime.
    // Closing a value-decoder facade does not reinitialize Via's private singleton.
    private static ViaManagerImpl shared;

    static synchronized ViaManagerImpl open(File directory) {
        if (shared == null) shared = initialize(directory);
        return shared;
    }

    private static ViaManagerImpl initialize(File directory) {
        var platform = new CodecPlatform(directory);
        var manager =
                new ViaManagerImpl(platform, new NoopInjector(), new ViaCommandHandler(false), ViaPlatformLoader.NOOP);
        Via.init(manager);
        try {
            manager.getConfigurationProvider().register(platform.getConf());
            MappingDataLoader.loadGlobalIdentifiers();
            var protocols = manager.getProtocolManager();
            var base = protocols.getBaseProtocol();
            base.initialize();
            base.register(manager.getProviders());
            protocols.registerBaseProtocol(
                    Direction.CLIENTBOUND, new ClientboundBaseProtocol1_16(), Range.atLeast(ProtocolVersion.v1_21_2));
            protocols.registerBaseProtocol(
                    Direction.SERVERBOUND, new ServerboundBaseProtocol1_7(), Range.atLeast(ProtocolVersion.v1_21_2));
            // Upstream's StructuredDataKey/VersionedTypes initializers are circular.
            // Its normal full registration initializes keys first; retain that order.
            java.util.Objects.requireNonNull(ac.cult.shaded.vialib.api.minecraft.data.StructuredDataKey.CUSTOM_DATA);
            // The preceding edge supplies the 1.21.2 component/particle codec fillers.
            protocols.registerProtocol(
                    new ac.cult.shaded.vialib.protocols.v1_21to1_21_2.Protocol1_21To1_21_2(),
                    ProtocolVersion.v1_21_2,
                    ProtocolVersion.v1_21);
            protocols.registerProtocol(
                    new ac.cult.shaded.vialib.protocols.v1_21_2to1_21_4.Protocol1_21_2To1_21_4(),
                    ProtocolVersion.getProtocol(769),
                    ProtocolVersion.getProtocol(768));
            protocols.registerProtocol(
                    new ac.cult.shaded.vialib.protocols.v1_21_4to1_21_5.Protocol1_21_4To1_21_5(),
                    ProtocolVersion.getProtocol(770),
                    ProtocolVersion.getProtocol(769));
            protocols.registerProtocol(
                    new ac.cult.shaded.vialib.protocols.v1_21_5to1_21_6.Protocol1_21_5To1_21_6(),
                    ProtocolVersion.getProtocol(771),
                    ProtocolVersion.getProtocol(770));
            protocols.registerProtocol(
                    new ac.cult.shaded.vialib.protocols.v1_21_6to1_21_7.Protocol1_21_6To1_21_7(),
                    ProtocolVersion.getProtocol(772),
                    ProtocolVersion.getProtocol(771));
            protocols.registerProtocol(
                    new ac.cult.shaded.vialib.protocols.v1_21_7to1_21_9.Protocol1_21_7To1_21_9(),
                    ProtocolVersion.getProtocol(773),
                    ProtocolVersion.getProtocol(772));
            protocols.registerProtocol(
                    new ac.cult.shaded.vialib.protocols.v1_21_9to1_21_11.Protocol1_21_9To1_21_11(),
                    ProtocolVersion.getProtocol(774),
                    ProtocolVersion.getProtocol(773));
            protocols.registerProtocol(
                    new ac.cult.shaded.vialib.protocols.v1_21_11to26_1.Protocol1_21_11To26_1(),
                    ProtocolVersion.getProtocol(775),
                    ProtocolVersion.getProtocol(774));
            protocols.registerProtocol(
                    new ac.cult.shaded.vialib.protocols.v26_1to26_2.Protocol26_1To26_2(),
                    ProtocolVersion.getProtocol(776),
                    ProtocolVersion.getProtocol(775));
            protocols.registerProtocol(
                    new ac.cult.shaded.vialib.protocols.v26_2to26_3.Protocol26_2To26_3(),
                    ProtocolVersion.getProtocol(777),
                    ProtocolVersion.getProtocol(776));
            var backwards = new ViaBackwardsPlatform() {
                @Override
                public Logger getLogger() {
                    return platform.getLogger();
                }

                @Override
                public void disable() {
                    throw new IllegalStateException("Private codecs unavailable");
                }

                @Override
                public File getDataFolder() {
                    return directory;
                }
            };
            var config = new ViaBackwardsConfig(new File(directory, "backwards.yml"), platform.getLogger());
            config.reload();
            manager.getConfigurationProvider().register(config);
            ViaBackwards.init(backwards, config);
            TranslatableMappings.loadTranslatables();
            BackwardsRegistrations.apply();
            protocols.registerProtocol(
                    new ac.cult.shaded.vialib.backwards.protocol.v1_21_4to1_21_2.Protocol1_21_4To1_21_2(),
                    ProtocolVersion.getProtocol(768),
                    ProtocolVersion.getProtocol(769));
            protocols.registerProtocol(
                    new ac.cult.shaded.vialib.backwards.protocol.v1_21_5to1_21_4.Protocol1_21_5To1_21_4(),
                    ProtocolVersion.getProtocol(769),
                    ProtocolVersion.getProtocol(770));
            protocols.registerProtocol(
                    new ac.cult.shaded.vialib.backwards.protocol.v1_21_6to1_21_5.Protocol1_21_6To1_21_5(),
                    ProtocolVersion.getProtocol(770),
                    ProtocolVersion.getProtocol(771));
            protocols.registerProtocol(
                    new ac.cult.shaded.vialib.backwards.protocol.v1_21_7to1_21_6.Protocol1_21_7To1_21_6(),
                    ProtocolVersion.getProtocol(771),
                    ProtocolVersion.getProtocol(772));
            protocols.registerProtocol(
                    new ac.cult.shaded.vialib.backwards.protocol.v1_21_9to1_21_7.Protocol1_21_9To1_21_7(),
                    ProtocolVersion.getProtocol(772),
                    ProtocolVersion.getProtocol(773));
            protocols.registerProtocol(
                    new ac.cult.shaded.vialib.backwards.protocol.v1_21_11to1_21_9.Protocol1_21_11To1_21_9(),
                    ProtocolVersion.getProtocol(773),
                    ProtocolVersion.getProtocol(774));
            protocols.registerProtocol(
                    new ac.cult.shaded.vialib.backwards.protocol.v26_1to1_21_11.Protocol26_1To1_21_11(),
                    ProtocolVersion.getProtocol(774),
                    ProtocolVersion.getProtocol(775));
            protocols.registerProtocol(
                    new ac.cult.shaded.vialib.backwards.protocol.v26_2to26_1.Protocol26_2To26_1(),
                    ProtocolVersion.getProtocol(775),
                    ProtocolVersion.getProtocol(776));
            protocols.registerProtocol(
                    new ac.cult.shaded.vialib.backwards.protocol.v26_3to26_2.Protocol26_3To26_2(),
                    ProtocolVersion.getProtocol(776),
                    ProtocolVersion.getProtocol(777));
            // Wait through the registered futures rather than running plugin startup tasks.
            for (var protocol : protocols.getProtocols()) {
                protocols.completeMappingDataLoading(protocol.getClass());
            }
            protocols.checkForMappingCompletion(true);
            // This library never injects channels or runs platform jobs. Mapping loading
            // has also shut down its executor, so the cached read-only manager owns no workers.
            manager.getScheduler().shutdown();
            return manager;
        } catch (RuntimeException | Error failure) {
            manager.destroy();
            throw failure;
        }
    }
}
