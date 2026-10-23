package ac.cult.cultac.utils.collisions;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.util.viaversion.ViaVersionUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.data.MappingData;
import com.viaversion.viaversion.api.data.Mappings;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolPathEntry;
import com.viaversion.viaversion.protocols.v1_12_2to1_13.Protocol1_12_2To1_13;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class ViaClientBlockShapeMappings {
    private static final ClientVersion MINIMUM_SUPPORTED_VERSION = ClientVersion.V_1_21_2;
    private static final ClassValue<Optional<Method>> LEGACY_BLOCK_REWRITERS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            try {
                return Optional.of(type.getMethod("handleBlockId", int.class));
            } catch (NoSuchMethodException ignored) {
                return Optional.empty();
            }
        }
    };
    private static final AtomicReference<Snapshot> SNAPSHOT = new AtomicReference<>(Snapshot.empty());

    private ViaClientBlockShapeMappings() {}

    public static void initialize() {
        if (!ViaVersionUtil.isAvailable()) {
            SNAPSHOT.set(Snapshot.empty());
            return;
        }

        try {
            Snapshot snapshot = build();
            SNAPSHOT.set(snapshot);
            LogUtil.info("[ClientBlockShapes] Via replacement shape cache: "
                    + snapshot.replacementCount() + " translated state mappings, "
                    + snapshot.replacementCandidateMaterialCount() + " candidate materials across "
                    + snapshot.versionCount() + " client versions.");
        } catch (Throwable throwable) {
            SNAPSHOT.set(Snapshot.empty());
            LogUtil.warn("[ClientBlockShapes] Failed to build Via replacement shape cache: " + throwable.getMessage());
        }
    }

    static Optional<CollisionBox> movement(CultPlayer player, int state, int x, int y, int z, double entityBottom) {
        Replacement replacement = replacement(player, state);
        if (replacement == null) {
            return Optional.empty();
        }

        CollisionBox latest =
                NativeBlockCollisionHelper.getCollisionBox(player, replacement.stateId(), x, y, z, entityBottom);
        Optional<CollisionBox> versioned = VersionedJavaBlockShapes.movement(player, replacement.stateId(), x, y, z);
        return versioned
                .filter(shape -> !ClientBlockShapes.sameShape(latest, shape))
                .or(() -> Optional.of(latest));
    }

    static Optional<CollisionBox> visual(CultPlayer player, int state, int x, int y, int z) {
        Replacement replacement = replacement(player, state);
        if (replacement == null) {
            return Optional.empty();
        }

        CollisionBox latest = NativeBlockCollisionHelper.getSelectionBox(player, replacement.stateId(), x, y, z);
        Optional<CollisionBox> versioned = VersionedJavaBlockShapes.visual(player, replacement.stateId(), x, y, z);
        return versioned
                .filter(shape -> !ClientBlockShapes.sameShape(latest, shape))
                .or(() -> Optional.of(latest));
    }

    /**
     * Returns the current-server state that ViaVersion maps back from the state
     * visible to this client. This is the same round trip used by the baseline
     * FastBreak check and is needed even when the replacement has an identical
     * collision shape but different mining properties.
     */
    public static int clientBlockStateId(CultPlayer player, int state) {
        if (player == null || player.bedrockState != null) return state;
        Replacement replacement = SNAPSHOT.get().replacement(player.getClientVersion(), state);
        return replacement == null ? state : replacement.stateId();
    }

    public static boolean replacesBlockStates(CultPlayer player) {
        if (player == null || player.bedrockState != null) return false;
        VersionMappings mapping = SNAPSHOT.get().versions().get(player.getClientVersion());
        return mapping != null && !mapping.replacements().isEmpty();
    }

    private static Replacement replacement(CultPlayer player, int state) {
        if (player == null || player.bedrockState != null || state < 0) return null;
        return SNAPSHOT.get().replacement(player.getClientVersion(), state);
    }

    private static Snapshot build() {
        EnumMap<ClientVersion, VersionMappings> mappings = new EnumMap<>(ClientVersion.class);
        int nativeModelProtocol = ProtocolVersion.V26_3.protocol();

        for (ClientVersion version : ClientVersion.values()) {
            if (version.isOlderThan(MINIMUM_SUPPORTED_VERSION) || version.getProtocolVersion() >= nativeModelProtocol) {
                continue;
            }

            // Connection paths run client -> server, but block-state mappings
            // translate clientbound packets, in the opposite direction/order.
            List<ProtocolPathEntry> clientConnection = Via.getManager()
                    .getProtocolManager()
                    .getProtocolPath(version.getProtocolVersion(), nativeModelProtocol);
            List<ProtocolPathEntry> serverConnection = Via.getManager()
                    .getProtocolManager()
                    .getProtocolPath(nativeModelProtocol, version.getProtocolVersion());
            if (clientConnection == null || serverConnection == null) {
                continue;
            }

            VersionMappings versionMappings =
                    buildVersionMappings(serverConnection.reversed(), clientConnection.reversed());
            if (!versionMappings.isEmpty()) {
                mappings.put(version, versionMappings);
            }
        }

        return new Snapshot(mappings);
    }

    private static VersionMappings buildVersionMappings(
            List<ProtocolPathEntry> toServer, List<ProtocolPathEntry> toClient) {
        // Via loads mapping data asynchronously; a registered path does not mean
        // its mappings are ready. Finish both directions before caching states.
        for (ProtocolPathEntry entry : toServer) {
            Via.getManager()
                    .getProtocolManager()
                    .completeMappingDataLoading(entry.protocol().getClass());
        }
        for (ProtocolPathEntry entry : toClient) {
            Via.getManager()
                    .getProtocolManager()
                    .completeMappingDataLoading(entry.protocol().getClass());
        }
        BitSet representedCurrentStates = representedCurrentStates(toServer);
        var replacements = new Int2ObjectOpenHashMap<Replacement>();
        Set<Integer> needsReplacementByMaterial = new HashSet<>();

        for (int currentId = 0; currentId < DataTables.defaults().registry().stateCount(); currentId++) {
            if (representedCurrentStates.get(currentId)) {
                continue;
            }

            needsReplacementByMaterial.add(DataTables.defaults().registry().blockIndex(currentId));

            int clientState = mapStateId(currentId, toClient);
            if (clientState < 0) {
                continue;
            }

            int normalizedReplacement = mapStateId(clientState, toServer);
            if (normalizedReplacement < 0 || normalizedReplacement == currentId) {
                continue;
            }

            int replacementState =
                    normalizedReplacement < DataTables.defaults().registry().stateCount() ? normalizedReplacement : 0;
            if (replacementState == currentId) continue;
            replacements.put(currentId, new Replacement(replacementState));
        }

        return new VersionMappings(Int2ObjectMaps.unmodifiable(replacements), Set.copyOf(needsReplacementByMaterial));
    }

    static BitSet representedCurrentStates(List<ProtocolPathEntry> toServer) {
        BitSet represented = new BitSet();
        int initialStateCount = initialStateCount(toServer);
        if (initialStateCount <= 0) {
            return represented;
        }

        for (int stateId = 0; stateId < initialStateCount; stateId++) {
            int currentState = mapStateId(stateId, toServer);
            if (currentState >= 0) {
                represented.set(currentState);
            }
        }
        return represented;
    }

    private static int initialStateCount(List<ProtocolPathEntry> path) {
        for (ProtocolPathEntry entry : path) {
            Mappings mappings = blockStateMappings(entry.protocol());
            if (mappings != null) {
                return mappings.size();
            }
        }
        return 0;
    }

    static int mapStateId(int stateId, List<ProtocolPathEntry> path) {
        int mapped = stateId;
        for (ProtocolPathEntry entry : path) {
            MappingData mappingData = entry.protocol().getMappingData();
            if (entry.protocol() instanceof Protocol1_12_2To1_13) {
                // Flattening is stored as BLOCK mappings in this forward
                // protocol. WorldPacketRewriter1_13#toNewId uses this table,
                // then retries without metadata, then falls back to air.
                Mappings flattening = mappingData.getBlockMappings();
                int next = flattening.getNewId(Math.max(0, mapped));
                if (next < 0) next = flattening.getNewId(mapped & ~15);
                mapped = next < 0 ? 0 : next;
                continue;
            }
            if (mappingData == null || blockStateMappings(entry.protocol()) == null) {
                // Pre-flattening ViaBackwards and ViaRewind translate numeric
                // id/data through LegacyBlockItemRewriter#handleBlockId, not
                // MappingData#getNewBlockStateId. Use that packet rewriter.
                mapped = mapLegacyBlock(entry.protocol(), mapped);
                continue;
            }

            int next;
            try {
                next = mappingData.getNewBlockStateId(mapped);
            } catch (RuntimeException exception) {
                return -1;
            }
            if (next < 0) {
                return -1;
            }
            mapped = next;
        }
        return mapped;
    }

    private static int mapLegacyBlock(Protocol<?, ?, ?, ?> protocol, int stateId) {
        Object rewriter = protocol.getItemRewriter();
        if (rewriter == null) return stateId;
        Optional<Method> method = LEGACY_BLOCK_REWRITERS.get(rewriter.getClass());
        if (method.isEmpty()) return stateId;
        try {
            return ((Number) method.get().invoke(rewriter, stateId)).intValue();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Cannot translate legacy block state with "
                            + protocol.getClass().getName(),
                    exception);
        }
    }

    private static Mappings blockStateMappings(Protocol<?, ?, ?, ?> protocol) {
        MappingData mappingData = protocol.getMappingData();
        if (mappingData == null) {
            return null;
        }
        try {
            return protocol instanceof Protocol1_12_2To1_13
                    ? mappingData.getBlockMappings()
                    : mappingData.getBlockStateMappings();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private record Snapshot(EnumMap<ClientVersion, VersionMappings> versions) {
        static Snapshot empty() {
            return new Snapshot(new EnumMap<>(ClientVersion.class));
        }

        Replacement replacement(ClientVersion version, int currentStateId) {
            VersionMappings mappings = versions.get(version);
            return mappings == null ? null : mappings.replacements().get(currentStateId);
        }

        int replacementCount() {
            int count = 0;
            for (VersionMappings mappings : versions.values()) {
                count += mappings.replacements().size();
            }
            return count;
        }

        int versionCount() {
            return versions.size();
        }

        int replacementCandidateMaterialCount() {
            int count = 0;
            for (VersionMappings mappings : versions.values()) {
                count += mappings.needsReplacementMaterials().size();
            }
            return count;
        }
    }

    private record VersionMappings(Int2ObjectMap<Replacement> replacements, Set<Integer> needsReplacementMaterials) {
        boolean isEmpty() {
            return replacements.isEmpty() && needsReplacementMaterials.isEmpty();
        }
    }

    private record Replacement(int stateId) {}
}
