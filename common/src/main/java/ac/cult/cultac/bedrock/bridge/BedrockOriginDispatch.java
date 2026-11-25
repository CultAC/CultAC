package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.protocol.BedrockTeleportOperation;
import ac.cult.cultac.bedrock.protocol.BedrockTeleportProvenance;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

/** Brackets a rewrite before crossing Cloudburst's asynchronous enqueue boundary. Owned by the adapter lock. */
final class BedrockOriginDispatch {
    private BedrockCoordinateFrame frame = BedrockCoordinateFrame.IDENTITY;
    private final Map<BedrockPacket, Emission> emissions = new IdentityHashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    private int depth;
    private BedrockTeleportOperation operation;

    void begin() {
        depth++;
    }

    void enqueue(BedrockPacket packet, Runnable write) {
        if (depth != 0) {
            pending.add(new Pending(packet, write));
        } else {
            emit(packet, write, new Emission(frame, operation));
        }
    }

    void withOperation(BedrockTeleportOperation next, Runnable action) {
        BedrockTeleportOperation previous = operation;
        operation = next;
        try {
            action.run();
        } finally {
            operation = previous;
        }
    }

    void finish(int originX, int originZ, BedrockTeleportOperation operation) {
        if (--depth != 0) return;
        if (originX != frame.originX() || originZ != frame.originZ()) {
            frame = new BedrockCoordinateFrame(originX, originZ, Math.incrementExact(frame.revision()));
        }
        List<Pending> writes = List.copyOf(pending);
        pending.clear();
        Emission emission = new Emission(frame, operation);
        for (Pending write : writes) emit(write.packet(), write.write(), emission);
    }

    private void emit(BedrockPacket packet, Runnable write, Emission emission) {
        emissions.put(packet, emission);
        write.run();
    }

    Emission take(BedrockPacket packet) {
        return emissions.remove(packet);
    }

    BedrockCoordinateFrame frame() {
        return frame;
    }

    /** Only JDK values and native packet identities cross the generation boundary. */
    Map<String, Object> continuation() {
        if (depth != 0 || !pending.isEmpty()) throw new IllegalStateException("GFP rewrite is still in progress");
        var queued = new IdentityHashMap<BedrockPacket, Map<String, Object>>();
        emissions.forEach((packet, emission) -> {
            var fields = new HashMap<String, Object>(coordinates(emission.frame()));
            if (emission.operation() != null) {
                fields.put("operation-sequence", emission.operation().id());
                fields.put("operation-source", emission.operation().provenance().name());
            }
            queued.put(packet, Map.copyOf(fields));
        });
        return Map.of("frame", coordinates(frame), "emissions", queued);
    }

    void restore(Map<?, ?> state) {
        clear();
        frame = coordinates((Map<?, ?>) state.get("frame"));
        ((Map<?, ?>) state.get("emissions")).forEach((packet, value) -> {
            var fields = (Map<?, ?>) value;
            var operation = fields.get("operation-sequence") instanceof Long sequence
                    ? new BedrockTeleportOperation(
                            sequence, BedrockTeleportProvenance.valueOf((String) fields.get("operation-source")), null)
                    : null;
            emissions.put((BedrockPacket) packet, new Emission(coordinates(fields), operation));
        });
    }

    private static Map<String, Object> coordinates(BedrockCoordinateFrame frame) {
        return Map.of("origin-x", frame.originX(), "origin-z", frame.originZ(), "revision", frame.revision());
    }

    private static BedrockCoordinateFrame coordinates(Map<?, ?> fields) {
        return new BedrockCoordinateFrame(
                (Integer) fields.get("origin-x"), (Integer) fields.get("origin-z"), (Long) fields.get("revision"));
    }

    void clear() {
        pending.clear();
        emissions.clear();
        depth = 0;
    }

    record Emission(BedrockCoordinateFrame frame, BedrockTeleportOperation operation) {
        BedrockTeleportProvenance provenance() {
            return operation == null ? BedrockTeleportProvenance.GEYSER : operation.provenance();
        }
    }

    private record Pending(BedrockPacket packet, Runnable write) {}
}
