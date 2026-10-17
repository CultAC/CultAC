package ac.cult.cultac.events.packets;

import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.codec.ConnectionModelValues;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;

/** Tracks the tags a client received; PLAY updates apply at their transaction boundary. */
public class PacketServerRegistries {
    @CultPacketHandler
    public void onEnabledFeatures(
            PacketSendEvent<ac.cult.cultac.network.packet.WorldPackets.EnabledFeatures> event,
            CultPlayer player,
            ac.cult.cultac.network.packet.WorldPackets.EnabledFeatures packet) {
        player.compensatedWorld.applyClientFeatures(packet);
    }

    @CultPacketHandler
    public void onTags(
            PacketSendEvent<ac.cult.cultac.network.packet.RegistryTags> event,
            CultPlayer player,
            ac.cult.cultac.network.packet.RegistryTags packet) {
        if (player.registryState == null)
            player.registryState = new ac.cult.cultac.utils.latency.ClientComponentRegistries();
        var state = player.registryState;
        if (event.getPhase() == ac.cult.cultac.protocol.ConnectionPhase.CONFIGURATION) {
            configurationTags(player, packet);
        } else {
            player.sendTransaction();
            player.latencyUtils.addRealTimeTaskNext(() -> state.appendTags(packet));
            event.getTasksAfterSend().add(player::sendTrailingTransaction);
        }
    }

    /** Configuration tags collect until finish_configuration publishes them with the received contents. */
    static void configurationTags(CultPlayer player, RegistryTags packet) {
        if (player.registryState == null) player.registryState = new ClientComponentRegistries();
        if (player.isBedrockMovement()) player.registryState.appendTags(packet);
        else player.registryState.appendConfigurationTags(packet);
    }

    @CultPacketHandler("clientbound.start_configuration")
    public void onStartConfiguration(PacketSendEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (player.isBedrockMovement()) {
            player.registryState = null;
            return;
        }
        if (player.registryState == null)
            player.registryState = new ac.cult.cultac.utils.latency.ClientComponentRegistries();
        player.registryState.beginConfiguration();
        // Start of configuration removes the server player, ending the use the burst balance tracks.
        player.itemUseBurst.reset();
    }

    /**
     * Stages the whole generation (model names, configuration tags, world definitions) before publishing any of
     * it, and publishes model defaults when the generation fails to stage.
     */
    @CultPacketHandler("clientbound.finish_configuration")
    public void onFinishConfiguration(PacketSendEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (!player.isBedrockMovement()
                && !event.isCancelled()
                && event.getPhase() == ac.cult.cultac.protocol.ConnectionPhase.CONFIGURATION) {
            // Invalidate only after the actual server terminal was written. A client
            // acknowledgement cannot authorize a new player or discard its state.
            event.getTasksAfterSend().add(() -> player.packetStateData.javaLocalPlayerPresent = false);
        }
        boolean java = !player.isBedrockMovement();
        if (java) {
            player.compensatedWorld.resetClientClocks();
            player.compensatedEntities.clientPlayerModes.clear();
        }
        var connection = event.getUser().getCultConnection();
        var platform = connection.platform();
        var values = platform == null ? null : platform.modelValues();
        var observed = connection.getObservedProtocol();
        ConnectionModelValues.Staged names = null;
        ClientComponentRegistries.StagedConfiguration world = null;
        try {
            if (values != null) names = values.stage(event.getDecodedProtocol());
            var bound = names == null ? null : names.tags();
            if (java && player.registryState == null && bound != null)
                player.registryState = new ClientComponentRegistries();
            if (java && player.registryState != null) {
                var model = connection.dispatcher().runtime().data().version();
                var client = ProtocolVersion.of(player.getClientVersion().getProtocolVersion());
                world = player.registryState.stageConfiguration(model, observed, client, bound);
            }
        } catch (RuntimeException failure) {
            if (values != null) values.fallback();
            if (java && player.registryState != null) player.registryState.fallback();
            RegistryGenerationAlerts.report(player, failure);
            return;
        }
        if (values != null) values.publish(names);
        if (world != null) player.registryState.publish(world, observed);
        else if (!java && names != null && names.tags() != null) configurationTags(player, names.tags());
    }

    @CultPacketHandler
    public void onRegistryData(
            PacketSendEvent<ac.cult.cultac.network.packet.RegistryData> event,
            CultPlayer player,
            ac.cult.cultac.network.packet.RegistryData packet) {
        if (player.isBedrockMovement()) return;
        if (player.registryState == null)
            player.registryState = new ac.cult.cultac.utils.latency.ClientComponentRegistries();
        player.getWorldRegistries();
        player.registryState.appendConfigurationRegistryData(packet);
    }
}
