package ac.cult.cultac.events.packets;

import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.RegistryData;
import ac.cult.cultac.player.CultPlayer;

/**
 * Collects received registry IDs on connections that own model values; {@link PacketServerRegistries} publishes
 * them with the rest of the generation at finish_configuration. Each configuration starts afresh in
 * {@link ac.cult.cultac.network.CultConnection#forwarded}.
 */
public final class PacketModelRegistryNames {
    @CultPacketHandler
    public void registry(PacketSendEvent<RegistryData> event, CultPlayer player, RegistryData packet) {
        var platform = event.getUser().getCultConnection().platform();
        var values = platform == null ? null : platform.modelValues();
        if (values != null) values.append(packet.registry(), packet.entries());
    }
}
