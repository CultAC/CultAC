package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.protocol.BedrockTeleportProvenance;
import java.util.ArrayList;
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
    private BedrockTeleportProvenance provenance = BedrockTeleportProvenance.GEYSER;
    private Integer javaTeleportId;

    void begin() { depth++; }

    void enqueue(BedrockPacket packet, Runnable write) {
        if (depth != 0) {
            pending.add(new Pending(packet, write));
        } else {
            emit(packet, write, new Emission(frame, provenance, javaTeleportId));
        }
    }

    void withContext(BedrockTeleportProvenance source, Integer teleportId, Runnable action) {
        BedrockTeleportProvenance previousSource = provenance;
        Integer previousId = javaTeleportId;
        provenance = source;
        javaTeleportId = teleportId;
        try { action.run(); }
        finally { provenance = previousSource; javaTeleportId = previousId; }
    }

    void finish(int originX, int originZ, BedrockTeleportProvenance provenance, Integer teleportId) {
        if (--depth != 0) return;
        if (originX != frame.originX() || originZ != frame.originZ()) {
            frame = new BedrockCoordinateFrame(originX, originZ, Math.incrementExact(frame.revision()));
        }
        List<Pending> writes = List.copyOf(pending);
        pending.clear();
        Emission emission = new Emission(frame, provenance, teleportId);
        for (Pending write : writes) emit(write.packet(), write.write(), emission);
    }

    private void emit(BedrockPacket packet, Runnable write, Emission emission) {
        emissions.put(packet, emission);
        write.run();
    }

    Emission take(BedrockPacket packet) { return emissions.remove(packet); }
    BedrockCoordinateFrame frame() { return frame; }
    void clear() { pending.clear(); emissions.clear(); depth = 0; }

    record Emission(BedrockCoordinateFrame frame, BedrockTeleportProvenance provenance, Integer javaTeleportId) { }
    private record Pending(BedrockPacket packet, Runnable write) { }
}
