package ac.cult.cultac.network.packet;

import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owned configuration tag data for platforms that receive the model over the wire. */
public record RegistryTags(Map<String, Payload> tags) implements ClientboundPacket {
    public RegistryTags {
        tags = Collections.unmodifiableMap(new LinkedHashMap<>(tags));
    }
    /** Last occurrences retain their wire order for version-specific tag renames. */
    public record Payload(Map<String, List<Integer>> entries) {
        public static final Payload EMPTY = new Payload(Map.of());

        public Payload {
            var copy = new LinkedHashMap<String, List<Integer>>();
            entries.forEach((name, ids) -> copy.put(name, List.copyOf(ids)));
            entries = Collections.unmodifiableMap(copy);
        }

        public boolean isEmpty() {
            return entries.isEmpty();
        }
    }
}
