package ac.grim.grimac.utils.collisions;

import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.network.protocol.util.viaversion.ViaVersionUtil;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.nmsutil.NativeBlockCollisionHelper;
import com.viaversion.viaversion.api.Via;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class ViaClientBlockShapeMappings {
    private static final ClientVersion MINIMUM_SUPPORTED_VERSION = ClientVersion.V_1_19_4;
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

    static Optional<CollisionBox> movement(GrimPlayer player, BlockData state, int x, int y, int z, double entityBottom) {
        Replacement replacement = replacement(player, state);
        if (replacement == null || !replacement.movementChanged()) {
            return Optional.empty();
        }

        CollisionBox latest = NativeBlockCollisionHelper.getCollisionBox(player, replacement.blockData(), x, y, z, entityBottom);
        Optional<CollisionBox> versioned = VersionedJavaBlockShapes.movement(player, replacement.blockData(), x, y, z);
        return versioned.filter(shape -> !ClientBlockShapes.sameShape(latest, shape)).or(() -> Optional.of(latest));
    }

    static Optional<CollisionBox> visual(GrimPlayer player, BlockData state, int x, int y, int z) {
        Replacement replacement = replacement(player, state);
        if (replacement == null || !replacement.visualChanged()) {
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
    public static BlockState clientBlockState(GrimPlayer player, BlockState state) {
        if (player == null || player.bedrockState != null || state == null) {
            return state;
        }
        Replacement replacement = SNAPSHOT.get().replacement(player.getClientVersion(), Block.getId(state));
        return replacement == null ? state : replacement.blockState();
    }

    private static Replacement replacement(GrimPlayer player, BlockData state) {
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
        int serverProtocol = ClientVersion.V_26_2.getProtocolVersion();

        for (ClientVersion version : ClientVersion.values()) {
            if (version.isOlderThan(MINIMUM_SUPPORTED_VERSION) || !version.isOlderThan(ClientVersion.V_26_2)) {
                continue;
            }

            List<ProtocolPathEntry> toServer = Via.getManager().getProtocolManager()
                    .getProtocolPath(version.getProtocolVersion(), serverProtocol);
            List<ProtocolPathEntry> toClient = Via.getManager().getProtocolManager()
                    .getProtocolPath(serverProtocol, version.getProtocolVersion());
            if (toServer == null || toClient == null) {
                continue;
            }

            VersionMappings versionMappings = buildVersionMappings(toServer, toClient);
            if (!versionMappings.isEmpty()) {
                mappings.put(version, versionMappings);
            }
        }

        return new Snapshot(mappings);
    }

    private static VersionMappings buildVersionMappings(List<ProtocolPathEntry> toServer, List<ProtocolPathEntry> toClient) {
        BitSet representedCurrentStates = representedCurrentStates(toServer);
        Map<Integer, Replacement> replacements = new HashMap<>();
        Set<Material> needsReplacementByMaterial = new HashSet<>();

        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            int currentId = Block.getId(state);
            if (representedCurrentStates.get(currentId)) {
                continue;
            }

            needsReplacementByMaterial.add(ac.grim.grimac.network.protocol.util.SpigotConversionUtil.fromNmsBlockState(state).getMaterial());

            int clientState = mapStateId(currentId, toClient);
            if (clientState < 0) {
                continue;
            }

            int normalizedReplacement = mapStateId(clientState, toServer);
            if (normalizedReplacement < 0 || normalizedReplacement == currentId) {
                continue;
            }

            BlockState replacementState = Block.stateById(normalizedReplacement);
            ShapeDifference difference = shapeDifference(state, replacementState);
            replacements.put(currentId, new Replacement(
                    replacementState,
                    ac.grim.grimac.network.protocol.util.SpigotConversionUtil.fromNmsBlockState(replacementState),
                    difference.movementChanged(),
                    difference.visualChanged()));
        }

        return new VersionMappings(replacements, Set.copyOf(needsReplacementByMaterial));
    }

    private static BitSet representedCurrentStates(List<ProtocolPathEntry> toServer) {
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

    private static int mapStateId(int stateId, List<ProtocolPathEntry> path) {
        int mapped = stateId;
        for (ProtocolPathEntry entry : path) {
            MappingData mappingData = entry.protocol().getMappingData();
            if (mappingData == null || blockStateMappings(entry.protocol()) == null) {
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

    private static Mappings blockStateMappings(Protocol<?, ?, ?, ?> protocol) {
        MappingData mappingData = protocol.getMappingData();
        if (mappingData == null) {
            return null;
        }
        try {
            return mappingData.getBlockStateMappings();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static ShapeDifference shapeDifference(BlockState state, BlockState replacementState) {
        BlockData blockData = ac.grim.grimac.network.protocol.util.SpigotConversionUtil.fromNmsBlockState(state);
        BlockData replacementData = ac.grim.grimac.network.protocol.util.SpigotConversionUtil.fromNmsBlockState(replacementState);

        boolean movementChanged = false;
        boolean visualChanged = false;
        try {
            CollisionBox originalMovement = NativeBlockCollisionHelper.getCollisionBox(null, blockData, 0, 0, 0, Double.NaN);
            CollisionBox replacementMovement = NativeBlockCollisionHelper.getCollisionBox(null, replacementData, 0, 0, 0, Double.NaN);
            movementChanged = !ClientBlockShapes.sameShape(originalMovement, replacementMovement);
        } catch (RuntimeException ignored) {
        }

        try {
            CollisionBox originalVisual = NativeBlockCollisionHelper.getSelectionBox(null, state, 0, 0, 0);
            CollisionBox replacementVisual = NativeBlockCollisionHelper.getSelectionBox(null, replacementState, 0, 0, 0);
            visualChanged = !ClientBlockShapes.sameShape(originalVisual, replacementVisual);
        } catch (RuntimeException ignored) {
        }

        return new ShapeDifference(movementChanged, visualChanged);
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

    private record VersionMappings(Map<Integer, Replacement> replacements, Set<Material> needsReplacementMaterials) {
        boolean isEmpty() {
            return replacements.isEmpty() && needsReplacementMaterials.isEmpty();
        }
    }

    private record Replacement(BlockState blockState, BlockData blockData, boolean movementChanged, boolean visualChanged) {
    }

    private record ShapeDifference(boolean movementChanged, boolean visualChanged) {
        boolean changed() {
            return movementChanged || visualChanged;
        }
    }
}
