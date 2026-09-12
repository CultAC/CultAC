package ac.cult.cultac.bedrock.prediction.geometry;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.data.MappingData;
import com.viaversion.viaversion.api.protocol.ProtocolPathEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/** The clientbound mapping order used by GeyserSpigotLegacyNativeWorldManager. */
public final class BedrockServerStateMappings {
    private BedrockServerStateMappings() {
    }

    public static int[] create(int serverProtocol, int serverStateCount, int catalogProtocol, int catalogStateCount) {
        if (serverProtocol == catalogProtocol) {
            if (serverStateCount != catalogStateCount) {
                throw new IllegalStateException("Server and collision catalog registry sizes differ for protocol " + serverProtocol);
            }
            return translate(serverStateCount, catalogStateCount, List.of());
        }
        return ViaMappings.create(serverProtocol, serverStateCount, catalogProtocol, catalogStateCount);
    }

    static int[] translate(int serverStateCount, int catalogStateCount, List<IntUnaryOperator> clientboundSteps) {
        if (serverStateCount <= 0 || catalogStateCount <= 0) {
            throw new IllegalArgumentException("Block state registries must not be empty");
        }
        int[] result = new int[serverStateCount];
        for (int serverId = 0; serverId < serverStateCount; serverId++) {
            int mapped = serverId;
            for (IntUnaryOperator step : clientboundSteps) {
                mapped = step.applyAsInt(mapped);
                if (mapped < 0) {
                    throw new IllegalStateException("ViaVersion cannot translate server block state " + serverId);
                }
            }
            if (mapped >= catalogStateCount) {
                throw new IllegalStateException("Translated block state " + mapped + " is outside the collision catalog for server state " + serverId);
            }
            result[serverId] = mapped;
        }
        return result;
    }

    // Isolate ViaVersion linkage so the identity path works without the plugin.
    private static final class ViaMappings {
        static int[] create(int serverProtocol, int serverStateCount, int catalogProtocol, int catalogStateCount) {
            var manager = Via.getManager().getProtocolManager();
            List<ProtocolPathEntry> path = manager.getProtocolPath(catalogProtocol, serverProtocol);
            if (path == null) {
                throw new IllegalStateException("ViaVersion has no path from Java protocol " + serverProtocol + " to catalog protocol " + catalogProtocol);
            }
            List<IntUnaryOperator> steps = new ArrayList<>();
            for (int i = path.size() - 1; i >= 0; i--) {
                var protocol = path.get(i).protocol();
                manager.completeMappingDataLoading(protocol.getClass());
                MappingData data = protocol.getMappingData();
                if (data != null && data.getBlockStateMappings() != null) {
                    var stateMappings = data.getBlockStateMappings();
                    steps.add(id -> {
                        // MappingDataBase substitutes air for missing mappings; reject before calling it.
                        if (id < 0 || id >= stateMappings.size() || stateMappings.getNewId(id) < 0) {
                            throw new IllegalStateException("Missing ViaVersion block state mapping for " + id);
                        }
                        int mapped = data.getNewBlockStateId(id);
                        if (mapped >= stateMappings.mappedSize()) {
                            throw new IllegalStateException("Out-of-range ViaVersion block state mapping for " + id);
                        }
                        return mapped;
                    });
                }
            }
            return translate(serverStateCount, catalogStateCount, steps);
        }
    }
}
