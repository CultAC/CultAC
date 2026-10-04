package ac.cult.cultac.events.packets;

import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;

/** Tracks the tags a client received; PLAY updates apply at their transaction boundary. */
public class PacketServerRegistries {
    @CultPacketHandler
    public void onTags(
            PacketSendEvent<ac.cult.cultac.network.packet.RegistryTags> event,
            CultPlayer player,
            ac.cult.cultac.network.packet.RegistryTags packet) {
        if (player.registryState == null)
            player.registryState = new ac.cult.cultac.utils.latency.ClientComponentRegistries();
        var state = player.registryState;
        if (event.getPhase() == ac.cult.cultac.protocol.ConnectionPhase.CONFIGURATION) {
            if (player.isBedrockMovement()) state.appendTags(packet);
            else state.appendConfigurationTags(packet);
        } else {
            player.sendTransaction();
            player.latencyUtils.addRealTimeTaskNext(() -> state.appendTags(packet));
            event.getTasksAfterSend().add(player::sendTransaction);
        }
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
    }

    @CultPacketHandler("clientbound.finish_configuration")
    public void onFinishConfiguration(PacketSendEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (!player.isBedrockMovement() && player.registryState != null) {
            var state = player.registryState;
            var connection = player.user.getCultConnection();
            var model = connection.dispatcher().runtime().data().version();
            var client = ac.cult.cultac.protocol.ProtocolVersion.of(
                    player.getClientVersion().getProtocolVersion());
            state.finishConfiguration(model, connection.getObservedProtocol(), client);
        }
    }

    @CultPacketHandler
    public void onRegistryData(
            PacketSendEvent<ac.cult.cultac.network.packet.RegistryData> event,
            CultPlayer player,
            ac.cult.cultac.network.packet.RegistryData packet) {
        if (player.isBedrockMovement()) return;
        if (player.registryState == null)
            player.registryState = new ac.cult.cultac.utils.latency.ClientComponentRegistries();
        player.registryState.observeRegistryData();
    }
}
