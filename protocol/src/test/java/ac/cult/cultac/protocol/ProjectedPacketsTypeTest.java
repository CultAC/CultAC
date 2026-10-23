package ac.cult.cultac.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.protocol.data.ProtocolData;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ProjectedPacketsTypeTest {
    @Test
    void everyObservedIdResolvesToTheSameModelTypeOnEveryCall() {
        int unbound = 0;
        for (ProtocolVersion modelVersion : ProtocolVersion.values()) {
            var model = ProtocolRuntime.create(ProtocolData.load(modelVersion));
            for (ProtocolVersion version : ProtocolVersion.values()) {
                var observed = ProtocolRuntime.create(ProtocolData.load(version));
                try (var packets = new ProjectedPackets(observed, model, () -> null)) {
                    for (int pass = 0; pass < 2; pass++) {
                        for (ConnectionPhase phase : ConnectionPhase.values()) {
                            for (PacketDirection direction : PacketDirection.values()) {
                                int size = observed.data().packets(phase, direction).size();
                                for (int id = -2; id <= size + 1; id++) {
                                    String label = modelVersion + "<-" + version + " " + phase + " " + direction
                                            + " " + id + " pass " + pass;
                                    assertEquals(
                                            expected(observed, model, phase, direction, id),
                                            outcome(packets, phase, direction, id),
                                            label);
                                    if (observed.binding(phase, direction, id) == null) unbound++;
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(true, unbound > 0);
    }

    private static String expected(
            ProtocolRuntime observed, ProtocolRuntime model, ConnectionPhase phase, PacketDirection direction, int id) {
        try {
            var bound = observed.binding(phase, direction, id);
            PacketType<?> type = bound != null
                    ? bound.type()
                    : model.typeForKey(direction.name().toLowerCase(Locale.ROOT) + "."
                            + observed.data().packets(phase, direction).name(id).replace("minecraft:", ""));
            return describe(type);
        } catch (RuntimeException failure) {
            return "!" + failure.getClass().getName() + ": " + failure.getMessage();
        }
    }

    private static String outcome(ProjectedPackets packets, ConnectionPhase phase, PacketDirection direction, int id) {
        try {
            return describe(packets.type(phase, direction, id));
        } catch (RuntimeException failure) {
            return "!" + failure.getClass().getName() + ": " + failure.getMessage();
        }
    }

    private static String describe(PacketType<?> type) {
        return type == null ? "null" : type.key() + "@" + System.identityHashCode(type);
    }
}
