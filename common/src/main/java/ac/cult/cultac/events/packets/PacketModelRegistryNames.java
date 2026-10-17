package ac.cult.cultac.events.packets;

import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.codec.ConnectionModelValues;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.RegistryData;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;

/**
 * Collects received registry IDs on connections that own model values; {@link PacketServerRegistries} publishes
 * them with the rest of the generation at finish_configuration.
 */
public final class PacketModelRegistryNames {
    private static ConnectionModelValues values(PacketSendEvent<?> event) {
        var platform = event.getUser().getCultConnection().platform();
        return platform == null ? null : platform.modelValues();
    }

    @CultPacketHandler
    public void registry(PacketSendEvent<RegistryData> event, CultPlayer player, RegistryData packet) {
        var values = values(event);
        if (values != null) values.append(packet.registry(), packet.entries());
    }

    @CultPacketHandler("clientbound.start_configuration")
    public void start(PacketSendEvent<Opaque> event, CultPlayer player, Opaque packet) {
        var values = values(event);
        if (values != null) values.beginConfiguration();
    }
}
