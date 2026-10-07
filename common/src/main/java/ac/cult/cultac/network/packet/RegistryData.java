package ac.cult.cultac.network.packet;

import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import java.util.List;

/** Client-visible configuration entries, including custom data and known-pack references. */
public record RegistryData(String registry, List<WireValueDecoder.RegistryEntry> entries) implements ClientboundPacket {
    public RegistryData {
        entries = List.copyOf(entries);
    }
}
