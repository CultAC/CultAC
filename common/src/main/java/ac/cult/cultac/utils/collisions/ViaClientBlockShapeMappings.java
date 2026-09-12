package ac.cult.cultac.utils.collisions;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.util.viaversion.ViaVersionUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.protocols.v1_12_2to1_13.Protocol1_12_2To1_13;
import com.viaversion.viaversion.api.data.MappingData;
import com.viaversion.viaversion.api.data.Mappings;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolPathEntry;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.block.data.CraftBlockData;

import java.util.BitSet;
import java.lang.reflect.Method;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class ViaClientBlockShapeMappings {
    // Material#isSolid from the vanilla 1.8 block registry (IDs 0..197).
    // This is material metadata, not collision-shape fullness. E.g. slabs are
    // solid material and ladders are not, regardless of their collision boxes.
    private static final Set<Integer> LEGACY_NON_SOLID_MATERIAL_IDS = Set.of(
            0, 6, 8, 9, 10, 11, 27, 28, 31, 32, 37, 38, 39, 40, 50, 51, 55, 59,
            65, 66, 69, 75, 76, 77, 78, 83, 90, 93, 94, 104, 105, 106, 111,
            115, 119, 127, 131, 132, 140, 141, 142, 143, 144, 149, 150, 157, 171, 175);
    private static final ClientVersion MINIMUM_SUPPORTED_VERSION = ClientVersion.V_1_8;
    private static final ClassValue<Optional<Method>> LEGACY_BLOCK_REWRITERS = new ClassValue<>() {
        @Override protected Optional<Method> computeValue(Class<?> type) {
            try { return Optional.of(type.getMethod("handleBlockId", int.class)); }
            catch (NoSuchMethodException ignored) { return Optional.empty(); }
        }
    };
    private static final AtomicReference<Snapshot> SNAPSHOT = new AtomicReference<>(Snapshot.empty());

    private ViaClientBlockShapeMappings() {
    }

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

    static Optional<CollisionBox> movement(CultPlayer player, BlockData state, int x, int y, int z, double entityBottom) {
        Replacement replacement = replacement(player, state);
        if (replacement == null) {
            return Optional.empty();
        }

        CollisionBox latest = NativeBlockCollisionHelper.getCollisionBox(player, replacement.blockData(), x, y, z, entityBottom);
        Optional<CollisionBox> versioned = VersionedJavaBlockShapes.movement(player, replacement.blockData(), x, y, z);
        return versioned.filter(shape -> !ClientBlockShapes.sameShape(latest, shape)).or(() -> Optional.of(latest));
    }

    static Optional<CollisionBox> visual(CultPlayer player, BlockData state, int x, int y, int z) {
        Replacement replacement = replacement(player, state);
        if (replacement == null) {
            return Optional.empty();
        }

        CollisionBox latest = NativeBlockCollisionHelper.getSelectionBox(player, replacement.blockState(), x, y, z);
        Optional<CollisionBox> versioned = VersionedJavaBlockShapes.visual(player, replacement.blockData(), x, y, z);
        return versioned.filter(shape -> !ClientBlockShapes.sameShape(latest, shape)).or(() -> Optional.of(latest));
    }

    /**
     * Returns the current-server state that ViaVersion maps back from the state
     * visible to this client. This is the same round trip used by the baseline
     * FastBreak check and is needed even when the replacement has an identical
     * collision shape but different mining properties.
     */
    public static BlockState clientBlockState(CultPlayer player, BlockState state) {
        if (player == null || player.bedrockState != null || state == null) {
            return state;
        }
        Replacement replacement = SNAPSHOT.get().replacement(player.getClientVersion(), Block.getId(state));
        return replacement == null ? state : replacement.blockState();
    }

    public static boolean legacyMaterialIsSolid(CultPlayer player, BlockState state) {
        VersionMappings mapping = SNAPSHOT.get().versions().get(player.getClientVersion());
        return mapping == null ? state.isSolid() : mapping.legacyMaterialSolidity().getOrDefault(state.getBlock(), state.isSolid());
    }

    private static Replacement replacement(CultPlayer player, BlockData state) {
        if (player == null || player.bedrockState != null || state == null) {
            return null;
        }
        BlockState blockState = toBlockState(state);
        if (blockState == null) {
            return null;
        }
        return SNAPSHOT.get().replacement(player.getClientVersion(), Block.getId(blockState));
    }

    private static Snapshot build() {
        EnumMap<ClientVersion, VersionMappings> mappings = new EnumMap<>(ClientVersion.class);
        int serverProtocol = net.minecraft.SharedConstants.getProtocolVersion();

        for (ClientVersion version : ClientVersion.values()) {
            if (version.isOlderThan(MINIMUM_SUPPORTED_VERSION) || version.getProtocolVersion() >= serverProtocol) {
                continue;
            }

            // Connection paths run client -> server, but block-state mappings
            // translate clientbound packets, in the opposite direction/order.
            List<ProtocolPathEntry> clientConnection = Via.getManager().getProtocolManager()
                    .getProtocolPath(version.getProtocolVersion(), serverProtocol);
            List<ProtocolPathEntry> serverConnection = Via.getManager().getProtocolManager()
                    .getProtocolPath(serverProtocol, version.getProtocolVersion());
            if (clientConnection == null || serverConnection == null) {
                continue;
            }

            VersionMappings versionMappings = buildVersionMappings(version, serverConnection.reversed(), clientConnection.reversed());
            if (!versionMappings.isEmpty()) {
                mappings.put(version, versionMappings);
            }
        }

        return new Snapshot(mappings);
    }

    private static VersionMappings buildVersionMappings(ClientVersion version, List<ProtocolPathEntry> toServer, List<ProtocolPathEntry> toClient) {
        // Via loads mapping data asynchronously; a registered path does not mean
        // its mappings are ready. Finish both directions before caching states.
        for (ProtocolPathEntry entry : toServer) {
            Via.getManager().getProtocolManager().completeMappingDataLoading(entry.protocol().getClass());
        }
        for (ProtocolPathEntry entry : toClient) {
            Via.getManager().getProtocolManager().completeMappingDataLoading(entry.protocol().getClass());
        }
        BitSet representedCurrentStates = representedCurrentStates(version, toServer, toClient);
        Map<Integer, Replacement> replacements = new HashMap<>();
        Set<Material> needsReplacementByMaterial = new HashSet<>();

        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            int currentId = Block.getId(state);
            if (representedCurrentStates.get(currentId)) {
                continue;
            }

            needsReplacementByMaterial.add(ac.cult.cultac.network.protocol.util.SpigotConversionUtil.fromNmsBlockState(state).getMaterial());

            int clientState = mapStateId(currentId, toClient);
            if (clientState < 0) {
                continue;
            }

            int normalizedReplacement = mapStateId(clientState, toServer);
            if (normalizedReplacement < 0 || normalizedReplacement == currentId) {
                continue;
            }

            BlockState replacementState = Block.stateById(normalizedReplacement);
            // Neighbour-derived properties (stairs, fences, panes) are not in
            // pre-flattening metadata. Preserve them for an existing block; the
            // versioned shape layer supplies the historical geometry.
            if (version.isOlderThan(ClientVersion.V_1_13) && replacementState.getBlock() == state.getBlock()) {
                replacementState = state;
            }
            if (version.isOlderThan(ClientVersion.V_1_13) && replacementState.hasProperty(BlockStateProperties.WATERLOGGED)) {
                replacementState = replacementState.setValue(BlockStateProperties.WATERLOGGED, false);
            }
            if (replacementState == state) continue;
            replacements.put(currentId, new Replacement(
                    replacementState,
                    ac.cult.cultac.network.protocol.util.SpigotConversionUtil.fromNmsBlockState(replacementState)));
        }

        Map<Block, Boolean> legacyMaterialSolidity = new HashMap<>();
        if (version == ClientVersion.V_1_8) {
            for (int legacyState = 0; legacyState < 198 * 16; legacyState++) {
                int currentState = mapStateId(legacyState, toServer);
                if (currentState >= 0) {
                    legacyMaterialSolidity.putIfAbsent(Block.stateById(currentState).getBlock(),
                            !LEGACY_NON_SOLID_MATERIAL_IDS.contains(legacyState >> 4));
                }
            }
        }
        return new VersionMappings(replacements, Set.copyOf(needsReplacementByMaterial), Map.copyOf(legacyMaterialSolidity));
    }

    static BitSet representedCurrentStates(ClientVersion version, List<ProtocolPathEntry> toServer, List<ProtocolPathEntry> toClient) {
        BitSet represented = new BitSet();
        int initialStateCount = initialStateCount(toServer);
        if (initialStateCount <= 0) {
            return represented;
        }

        for (int stateId = 0; stateId < initialStateCount; stateId++) {
            int currentState = mapStateId(stateId, toServer);
            // Pre-flattening forward protocols share numeric IDs and may have no
            // mapping table before 1.12.2 -> 1.13. That table includes blocks an
            // older client cannot represent. Require the clientbound translation
            // to preserve the original ID/data before treating it as native.
            if (currentState >= 0 && (version.isNewerThanOrEquals(ClientVersion.V_1_13)
                    || mapStateId(currentState, toClient) == stateId)) {
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
            throw new IllegalStateException("Cannot translate legacy block state with " + protocol.getClass().getName(), exception);
        }
    }

    private static Mappings blockStateMappings(Protocol<?, ?, ?, ?> protocol) {
        MappingData mappingData = protocol.getMappingData();
        if (mappingData == null) {
            return null;
        }
        try {
            return protocol instanceof Protocol1_12_2To1_13 ? mappingData.getBlockMappings() : mappingData.getBlockStateMappings();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static BlockState toBlockState(BlockData state) {
        if (state instanceof CraftBlockData craftBlockData) {
            return craftBlockData.getState();
        }
        return null;
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

    private record VersionMappings(Map<Integer, Replacement> replacements, Set<Material> needsReplacementMaterials, Map<Block, Boolean> legacyMaterialSolidity) {
        boolean isEmpty() {
            return replacements.isEmpty() && needsReplacementMaterials.isEmpty();
        }
    }

    private record Replacement(BlockState blockState, BlockData blockData) {
    }

}
