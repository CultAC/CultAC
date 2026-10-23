package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.FluidTags;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.cultac.checks.impl.movement.GhostBlockMitigator;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.packet.WorldPackets.SectionBlocksUpdate;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.ClientBlockShapes;
import ac.cult.cultac.utils.collisions.ViaClientBlockShapeMappings;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.*;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientBlockGeometry;
import ac.cult.cultac.utils.nmsutil.ClientBlockProperties;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.ClientShapeBoxes;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

// Inspired by
// https://github.com/GeyserMC/Geyser/blob/master/connector/src/main/java/org/geysermc/connector/network/session/cache/ChunkCache.java
public class CompensatedWorld {
    private static final int RECENT_CLIENT_COLLISION_CHANGE_TICKS = 3;
    private static final int RECENT_CLIENT_FLUID_CHANGE_TICKS = 3;
    private static final int MAX_PENDING_PREDICTIONS = 1024;
    private static final int MAX_REINTERN_CONTENT_COMPARISONS_PER_TICK = 10;
    private static final int MAX_REINTERN_HASH_CALCULATIONS_PER_TICK = 50;
    private static final int MAX_REINTERN_ATTEMPTED_COMPARISONS_PER_TICK = 100;
    private static final Comparator<PendingReinternCandidate> BY_LAST_MUTATED =
            Comparator.comparingLong(PendingReinternCandidate::lastMutatedNanos);
    private static final byte RECENT_FLUID_WATER = 1;
    private static final byte RECENT_FLUID_LAVA = 1 << 1;
    public final CultPlayer player;
    private final Long2ObjectMap<CachedChunk> chunks;
    private final LongSet chunkPositions;
    // Changes whenever a chunk is added to or removed from chunks; every write to chunks bumps it.
    private long chunkEpoch;
    private final Long2ObjectOpenHashMap<String> chunkDimensions = new Long2ObjectOpenHashMap<>();
    // Packet locations for blocks
    public Set<ShulkerData> openShulkerBoxes = new HashSet<>();
    public final CompensatedWorldPistons pistons;
    private final CompensatedGeysers geysers = new CompensatedGeysers();
    private final CompensatedBlockEntities clientBlockEntities;
    private final ClientBlockGeometry geometry;
    private final ac.cult.blocksim.engine.ClientClocks clientClocks = new ac.cult.blocksim.engine.ClientClocks();
    private final ClientEnvironment clientEnvironment = new ClientEnvironment();
    private long clientGameTime;
    private long endedTickGameTime;
    private Map<String, Set<String>> clientRecipeInputs = Map.of();
    // Initial ClientListenerCookie uses DEFAULT_FLAGS; ClientLevelData begins at NORMAL.
    private Set<String> clientEnabledFeatures = Set.of("minecraft:vanilla");
    private boolean clientPeaceful;
    private final Long2IntOpenHashMap recentClientCollisionChanges = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap recentClientFluidChanges = new Long2IntOpenHashMap();
    private final Long2ByteOpenHashMap recentClientFluidChangeKinds = new Long2ByteOpenHashMap();
    private final Map<PendingReinternSection, PendingReinternState> pendingReinternSections = new LinkedHashMap<>();
    /** One tick end's reintern candidates, empty outside processPendingReinterns; owned by the packet thread. */
    private final List<PendingReinternCandidate> reinternCandidates = new ArrayList<>();

    private final SectionPoolLeaseTracker sectionPoolLeases = new SectionPoolLeaseTracker();
    // 1.17 with datapacks, and 1.18, have negative world offset values
    private int minHeight = 0;
    private int maxHeight = 256;
    private String visibleDimension = "minecraft:overworld";
    private ac.cult.blocksim.environment.DimensionData visibleDimensionType;
    private int clientSeaLevel = 63;
    private long clientBiomeZoomSeed;
    private boolean fastLava;
    private boolean hasSkyLight = true;
    private ClientboundDimensionData lastClientboundDimension =
            new ClientboundDimensionData("minecraft:overworld", 0, 256);

    // When the player changes the blocks, they track what the server thinks the blocks are
    //
    // Pair of the block position and the owning list TO the actual block
    // The owning list is so that this info can be removed when the final list is processed
    private final Long2ObjectOpenHashMap<BlockPrediction> originalServerBlocks = new Long2ObjectOpenHashMap<>();
    // Server block states sent to the client but not yet acknowledged, for resynchronization.
    private final ServerBlockView serverBlockView = new ServerBlockView();
    // Blocks the client changed while placing or breaking blocks
    private List<BlockPos> currentlyChangedBlocks = new LinkedList<>();
    private final Map<Integer, List<BlockPos>> serverIsCurrentlyProcessingThesePredictions = new LinkedHashMap<>();
    private int clientPredictionSequence;
    /** BlockStatePredictionHandler#lastTeleportSequence: the prediction sequence when the client last took a teleport. */
    private int lastTeleportPredictionSequence = -1;

    private int level;
    private boolean clientboundUnresolved;
    private boolean levelUnresolved;
    private boolean isCurrentlyPredicting = false;
    private final Long2IntOpenHashMap selectionOriginalStates = new Long2IntOpenHashMap();
    private boolean selectionSnapshotActive;
    private boolean readingSelectionSnapshot;
    private String selectionHeldItemKey;
    // Read and toggled only on this player's packet executor.
    private boolean debugBlockChanges;
    public boolean isRaining = false;
    private float clientRainLevel;
    /** Hears the columns and blocks this world applies; null when nothing listens. */
    private TerrainListener terrainListener;

    /** What this world applies to its columns and blocks, heard on this player's packet executor. */
    public interface TerrainListener {
        /** A column was loaded, loaded again or merged into. */
        void columnLoaded(int chunkX, int chunkZ);

        void columnUnloaded(int chunkX, int chunkZ);

        /** A block changed to the model block-state id {@code state}. */
        void blockChanged(int x, int y, int z, int state);
    }

    public CompensatedWorld(CultPlayer player) {
        this.player = player;
        this.geometry = new ClientBlockGeometry(this);
        this.clientBlockEntities = new CompensatedBlockEntities(player);
        this.pistons = new CompensatedWorldPistons(player, this);
        chunks = new Long2ObjectOpenHashMap<>(81, 0.5f);
        chunkPositions = LongSets.unmodifiable(chunks.keySet());
    }

    public void terrainListener(TerrainListener listener) {
        terrainListener = listener;
    }

    public CompensatedGeysers getGeysers() {
        return geysers;
    }

    public ClientBlockGeometry geometry() {
        return geometry;
    }

    public static final class CachedChunk {
        private final CachedSection[] sections;
        private final int transaction;
        private MotionHeightmap motionHeightmap;
        private final List<ac.cult.cultac.network.packet.WorldPackets.BlockEntityUpdate> blockEntities;
        private static final AtomicReferenceArray<int[]> UNIFORM_BIOMES = new AtomicReferenceArray<>(1024);
        private int[][] biomes;

        public CachedChunk(CachedSection[] sections, int transaction) {
            this(sections, transaction, List.of(), null);
        }

        public CachedChunk(
                CachedSection[] sections,
                int transaction,
                List<ac.cult.cultac.network.packet.WorldPackets.BlockEntityUpdate> blockEntities,
                int[][] biomes) {
            this.sections = sections;
            this.transaction = transaction;
            this.blockEntities = List.copyOf(blockEntities);
            replaceBiomes(biomes);
        }

        public int biomeAt(int section, int quartX, int quartY, int quartZ) {
            if (biomes == null) throw new IllegalStateException("Chunk has no received biome palette");
            return biomes[Math.clamp(section, 0, sections.length - 1)][(quartY << 4) | (quartZ << 2) | quartX];
        }

        public void replaceBiomes(int[][] biomes) {
            if (biomes == null) {
                this.biomes = null;
                return;
            }
            if (biomes.length != sections.length) throw new IllegalArgumentException("Biome section count mismatch");
            int[][] retained = new int[biomes.length][];
            for (int i = 0; i < biomes.length; i++) {
                int[] values = biomes[i];
                if (values.length != 64) throw new IllegalArgumentException("Biome section must contain 64 values");
                retained[i] = isUniform(values) ? uniformBiomes(values[0]) : values.clone();
            }
            this.biomes = retained;
        }

        /** 64 copies of {@code biome}; arrays are shared between chunks, so no caller may write to them. */
        public static int[] uniformBiomes(int biome) {
            if (biome < 0 || biome >= UNIFORM_BIOMES.length()) {
                int[] values = new int[64];
                Arrays.fill(values, biome);
                return values;
            }
            int[] shared = UNIFORM_BIOMES.get(biome);
            if (shared != null) return shared;
            int[] values = new int[64];
            Arrays.fill(values, biome);
            return UNIFORM_BIOMES.compareAndSet(biome, null, values) ? values : UNIFORM_BIOMES.get(biome);
        }

        private static boolean isUniform(int[] values) {
            for (int i = 1; i < values.length; i++) if (values[i] != values[0]) return false;
            return true;
        }

        public int sectionCount() {
            return sections.length;
        }

        private int stateAtIndex(int index) {
            CachedSection section = getSection(index >>> 12);
            return section == null || section.isEmpty() ? 0 : section.getStateId(index);
        }

        private int heightmapScanHeight() {
            for (int i = sections.length - 1; i >= 0; i--)
                if (sections[i] != null && !sections[i].isEmpty()) return (i + 1) << 4;
            return 16;
        }

        private MotionHeightmap motionHeightmap() {
            if (motionHeightmap == null) motionHeightmap = new MotionHeightmap(sections.length << 4);
            return motionHeightmap;
        }

        public CachedSection getSection(int sectionIndex) {
            if (sectionIndex < 0 || sectionIndex >= sections.length) {
                return null;
            }
            return sections[sectionIndex];
        }

        public CachedSection getOrCreateSection(int sectionIndex) {
            if (sectionIndex < 0 || sectionIndex >= sections.length) {
                return null;
            }
            CachedSection section = sections[sectionIndex];
            if (section == null) {
                section = CachedSection.createAirSection();
                sections[sectionIndex] = section;
            }
            return section;
        }

        public int getTransaction() {
            return transaction;
        }

        public void setSection(int sectionIndex, CachedSection section) {
            if (sectionIndex < 0 || sectionIndex >= sections.length) {
                return;
            }
            sections[sectionIndex] = section;
        }

        public void mergeSections(CachedSection[] toMerge) {
            for (int i = 0; i < Math.min(sections.length, toMerge.length); i++) {
                if (toMerge[i] != null) sections[i] = toMerge[i];
            }
        }

        public static int index(int x, int y, int z) {
            return (y << 8) | (z << 4) | x;
        }
    }

    public record ClientboundDimensionData(String dimension, int minHeight, int maxHeight) {
        public int sectionCount() {
            return (maxHeight - minHeight) >> 4;
        }
    }

    public static final class CachedSection {
        public static final java.util.concurrent.atomic.AtomicLong mutableCopyCounter =
                new java.util.concurrent.atomic.AtomicLong(0);
        private static final long[] COUNT_WEIGHTS = countWeights();

        private final PalettedSection states;
        private int nonEmptyBlockCount;
        private int fluidCount;
        private volatile boolean shared;
        private long lastMutatedNanos;
        private int poolReferences;
        private int cachedContentHash;
        private volatile boolean cachedContentHashValid;

        public CachedSection(PalettedSection states) {
            this(states, false);
        }

        public CachedSection(PalettedSection states, boolean shared) {
            this.states = states;
            this.shared = shared;
            this.lastMutatedNanos = System.nanoTime();
            recalcCounts();
        }

        public CachedSection translatedStateIds(java.util.function.IntUnaryOperator mapper) {
            var translated = states.translated(id -> stateIdOrAir(mapper.applyAsInt(id)));
            return translated == states ? this : new CachedSection(translated);
        }

        public boolean hasOnlyKnownStateIds() {
            return states.localPaletteWithin(DataTables.defaults().registry().stateCount());
        }

        public boolean isShared() {
            return shared;
        }

        public void markShared() {
            this.shared = true;
        }

        void markPrivate() {
            this.shared = false;
        }

        int poolReferences() {
            return poolReferences;
        }

        void retainPoolReference() {
            poolReferences++;
        }

        int releasePoolReference() {
            if (poolReferences > 0) {
                poolReferences--;
            }
            return poolReferences;
        }

        void clearPoolReferences() {
            poolReferences = 0;
        }

        public CachedSection mutableCopy() {
            mutableCopyCounter.incrementAndGet();
            return new CachedSection(states.copy(), false);
        }

        public static CachedSection createAirSection() {
            return new CachedSection(PalettedSection.blocks());
        }

        public int getStateId(int index) {
            return states.get(index & 4095);
        }

        public int setStateId(int index, int state) {
            int replacement = stateIdOrAir(state);
            int previous = states.getAndSet(index & 4095, replacement);
            decrementCounts(previous);
            incrementCounts(replacement);
            this.lastMutatedNanos = System.nanoTime();
            this.cachedContentHashValid = false;
            return previous;
        }

        public boolean isEmpty() {
            return nonEmptyBlockCount == 0;
        }

        public boolean hasFluid() {
            return fluidCount > 0;
        }

        public long getLastMutatedNanos() {
            return lastMutatedNanos;
        }

        public void setLastMutatedNanos(long nanos) {
            this.lastMutatedNanos = nanos;
        }

        PalettedSection states() {
            return states;
        }

        /** Writes the block states as the pinned 26.3 chunk packet section does, in model ids. */
        public void writeStates(ByteBuf output) {
            states.write(output);
        }

        boolean hasCachedContentHash() {
            return cachedContentHashValid;
        }

        int cachedContentHash() {
            return cachedContentHash;
        }

        void cacheContentHash(int hash) {
            this.cachedContentHash = hash;
            this.cachedContentHashValid = true;
        }

        private static ac.cult.blocksim.data.StateFacts facts(int state) {
            return ac.cult.blocksim.data.DataTables.defaults().registry().facts(state);
        }

        private static long[] countWeights() {
            int states = DataTables.defaults().registry().stateCount();
            long[] weights = new long[states];
            for (int state = 0; state < states; state++) {
                StateFacts facts = facts(state);
                if (!facts.has(StateFacts.AIR))
                    weights[state] = facts.fluid().equals("minecraft:empty") ? 1 : 1 | 1L << 32;
            }
            return weights;
        }

        private void recalcCounts() {
            long counts = states.weightSumWithin(COUNT_WEIGHTS);
            if (counts >= 0) {
                nonEmptyBlockCount = (int) counts;
                fluidCount = (int) (counts >>> 32);
                return;
            }
            nonEmptyBlockCount = 0;
            fluidCount = 0;
            states.forEachCountWithin(DataTables.defaults().registry().stateCount(), (state, count) -> {
                var facts = facts(state);
                if (!facts.has(ac.cult.blocksim.data.StateFacts.AIR)) {
                    nonEmptyBlockCount += count;
                    if (!facts.fluid().equals("minecraft:empty")) fluidCount += count;
                }
            });
        }

        private void decrementCounts(int state) {
            var facts = facts(state);
            if (!facts.has(ac.cult.blocksim.data.StateFacts.AIR)) {
                nonEmptyBlockCount--;
                if (!facts.fluid().equals("minecraft:empty")) fluidCount--;
            }
        }

        private void incrementCounts(int state) {
            var facts = facts(state);
            if (!facts.has(ac.cult.blocksim.data.StateFacts.AIR)) {
                nonEmptyBlockCount++;
                if (!facts.fluid().equals("minecraft:empty")) fluidCount++;
            }
        }
    }

    public void startPredicting() {
        this.isCurrentlyPredicting = true;
    }

    /** Minecraft#pick precedes all local block predictions in handleKeybinds. */
    public void beginSelectionSnapshot() {
        if (!selectionSnapshotActive) {
            var held = player.getInventory().getClientSelectedHeldItem();
            selectionHeldItemKey = held == null || held.isEmpty() ? "minecraft:air" : held.itemKey();
        }
        selectionSnapshotActive = true;
    }

    public String selectionHeldItemKey() {
        return readingSelectionSnapshot ? selectionHeldItemKey : null;
    }

    public boolean isReadingSelectionSnapshot() {
        return readingSelectionSnapshot;
    }

    public <T> T withSelectionHeldItemKey(String key, java.util.function.Supplier<T> query) {
        String previous = selectionHeldItemKey;
        selectionHeldItemKey = key;
        try {
            return query.get();
        } finally {
            selectionHeldItemKey = previous;
        }
    }

    public <T> T withSelectionSnapshot(java.util.function.Supplier<T> query) {
        boolean previous = readingSelectionSnapshot;
        readingSelectionSnapshot = true;
        try {
            return query.get();
        } finally {
            readingSelectionSnapshot = previous;
        }
    }

    public void endSelectionSnapshot() {
        selectionOriginalStates.clear();
        selectionSnapshotActive = false;
        selectionHeldItemKey = null;
    }

    public void toggleBlockChangeDebug() {
        debugBlockChanges = !debugBlockChanges;
        player.sendMessage(Component.text("[places] ", NamedTextColor.AQUA)
                .append(Component.text(
                        "Block changes " + (debugBlockChanges ? "enabled" : "disabled") + ".", NamedTextColor.GRAY)));
    }

    public void advanceClientPredictionSequence() {
        if (player.isBedrockMovement()) return;
        clientPredictionSequence++;
    }

    /** ClientPacketListener#handleMovePlayer -> BlockStatePredictionHandler#onTeleport. */
    public void onClientTeleport() {
        if (!tracksTeleportSequence()) return;
        lastTeleportPredictionSequence = clientPredictionSequence;
    }

    /** BlockStatePredictionHandler#lastTeleportSequence exists from 26.1; older clients always snap back. */
    private boolean tracksTeleportSequence() {
        return !player.isBedrockMovement() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_1);
    }

    public void resetClientPredictions() {
        player.blockActionReconciler.reset();
        endSelectionSnapshot();
        clientPredictionSequence = 0;
        lastTeleportPredictionSequence = -1;
        isCurrentlyPredicting = false;
        currentlyChangedBlocks.clear();
        originalServerBlocks.clear();
        serverIsCurrentlyProcessingThesePredictions.clear();
    }

    public void handlePredictionConfirmation(int prediction) {
        player.sendTransaction();
        handlePredictionConfirmation(prediction, player.lastTransactionSent.get());
    }

    public void handlePredictionConfirmation(int prediction, int transaction) {
        for (Iterator<Map.Entry<Integer, List<BlockPos>>> it =
                        serverIsCurrentlyProcessingThesePredictions.entrySet().iterator();
                it.hasNext(); ) {
            Map.Entry<Integer, List<BlockPos>> iter = it.next();
            if (iter.getKey() <= prediction) {
                applyBlockChanges(iter.getValue(), transaction, prediction);
                it.remove();
            }
        }
        endGhostPositionsUpTo(prediction, transaction);
    }

    public void handlePredictionConfirmation(int prediction, CultPlayer.TrackedTransaction transaction) {
        for (Iterator<Map.Entry<Integer, List<BlockPos>>> it =
                        serverIsCurrentlyProcessingThesePredictions.entrySet().iterator();
                it.hasNext(); ) {
            Map.Entry<Integer, List<BlockPos>> iter = it.next();
            if (iter.getKey() <= prediction) {
                applyBlockChanges(iter.getValue(), transaction, prediction);
                it.remove();
            }
        }
        endGhostPositionsUpTo(prediction, transaction.transaction());
    }

    /** ClientLevel#handleBlockChangedAck: the client has ended every prediction up to this sequence. */
    private void endGhostPositionsUpTo(int prediction, int transaction) {
        if (player.isBedrockMovement()) return;
        player.latencyUtils.addRealTimeTask(
                transaction, () -> player.getGhostBlockMitigator().onClientAck(prediction));
    }

    /** BlockStatePredictionHandler#currentSequence: the client's local block prediction counter. */
    public int clientPredictionSequence() {
        return clientPredictionSequence;
    }

    public void handleServerBlockUpdate(BlockPos pos, int state, CultPlayer.TrackedTransaction transaction) {
        queueServerBlockUpdate(pos.getX(), pos.getY(), pos.getZ(), state, transaction.transaction());
    }

    /** Records a block update at send time and applies it once the client has processed it. */
    public void queueServerBlockUpdate(int x, int y, int z, int state, int transaction) {
        long position = BlockPos.asLong(x, y, z);
        long id = serverBlockView.sent(
                position, ViaClientBlockShapeMappings.clientBlockStateId(player, stateIdOrAir(state)));
        int queuedLevel = level;
        player.latencyUtils.addRealTimeTask(transaction, () -> {
            if (queuedLevel == level) handleServerBlockUpdate(x, y, z, state, transaction);
            serverBlockView.applied(position, id);
        });
    }

    public void queueServerBlockUpdates(SectionBlocksUpdate update, int transaction) {
        long[] ids = new long[update.size()];
        int[] sent = {0};
        update.runUpdates((x, y, z, state) -> ids[sent[0]++] = serverBlockView.sent(
                BlockPos.asLong(x, y, z), ViaClientBlockShapeMappings.clientBlockStateId(player, stateIdOrAir(state))));
        int queuedLevel = level;
        player.latencyUtils.addRealTimeTask(transaction, () -> {
            int[] applied = {0};
            update.runUpdates((x, y, z, state) -> {
                if (queuedLevel == level) handleServerBlockUpdate(x, y, z, state, transaction);
                serverBlockView.applied(BlockPos.asLong(x, y, z), ids[applied[0]++]);
            });
        });
    }

    /** A login or dimension change was sent; earlier server blocks no longer describe the client's world. */
    public void serverWorldChangeSent() {
        serverBlockView.worldChangeSent();
        player.latencyUtils.addRealTimeTaskNext(serverBlockView::worldChangeApplied);
    }

    /**
     * The newest block state the server has sent for this position, excluding client predictions, or
     * {@link #UNKNOWN_SERVER_STATE} while the column or world is unloaded or being replaced.
     * Resynchronization packets are written after everything already sent, so they must use this.
     */
    public int getServerSentBlockStateIdAt(BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        if (serverBlockView.isUnknown(chunkX, chunkZ) || !isChunkLoaded(chunkX, chunkZ)) return UNKNOWN_SERVER_STATE;
        int sent = serverBlockView.sentState(pos.asLong());
        return sent != ServerBlockView.UNKNOWN ? sent : getServerBlockStateIdAt(pos);
    }

    public static final int UNKNOWN_SERVER_STATE = ServerBlockView.UNKNOWN;

    public void handleServerBlockUpdate(BlockPos pos, int state, int transaction) {
        handleServerBlockUpdate(pos.getX(), pos.getY(), pos.getZ(), state, transaction);
    }

    public void handleServerBlockUpdate(int x, int y, int z, int state, int transaction) {
        updateBlock(x, y, z, state);
    }

    private void applyBlockChanges(List<BlockPos> toApplyBlocks, int transaction, int sequence) {
        // The transaction is sent after the block-ack packet in the same clientbound bundle.
        // Vanilla processes bundle sub-packets in order on the client thread, then replies to
        // the ping, so this marker means the ack and any bundled block updates are applied.
        player.latencyUtils.addRealTimeTask(transaction, () -> {
            // BlockStatePredictionHandler#endPredictionsUpTo: no snap back after a teleport since the prediction.
            boolean snapsBack = !tracksTeleportSequence() || lastTeleportPredictionSequence < sequence;
            toApplyBlocks.forEach(vector3i -> {
                BlockPrediction predictionData = originalServerBlocks.get(vector3i.asLong());

                // We are the last to care about this prediction, remove it to stop memory leak
                // Block changes are allowed to execute out of order, because it actually doesn't matter
                if (predictionData != null && predictionData.getForBlockUpdate() == toApplyBlocks) {
                    originalServerBlocks.remove(vector3i.asLong());
                    handleAck(
                            vector3i,
                            predictionData.getOriginalBlockId(),
                            predictionData.getPlayerPosition(),
                            snapsBack);
                }
            });
        });
    }

    private void applyBlockChanges(
            List<BlockPos> toApplyBlocks, CultPlayer.TrackedTransaction transaction, int sequence) {
        applyBlockChanges(toApplyBlocks, transaction.transaction(), sequence);
    }

    private void handleAck(BlockPos vector3i, int originalBlockId, Vec3 playerPosition, boolean snapsBack) {
        // make sure that this "delayed" block update is always known to the ghost block mitigator
        final GhostBlockMitigator mitigator = player.getGhostBlockMitigator();
        mitigator.handleNewBlock(vector3i);

        // If we need to change the world block state
        int state = stateIdOrAir(originalBlockId);
        if (getBlockStateIdAt(vector3i) != state) {
            updateBlock(vector3i.getX(), vector3i.getY(), vector3i.getZ(), state);

            if (playerPosition == null) {
                // Fuck you player. You tried teleporting and then causing an illegal block change to try to validate
                // your illegal teleport.
                final ac.cult.cultac.manager.player.SetbackTeleportUtil setbackUtil = player.getSetbackTeleportUtil();
                setbackUtil.executeForceResync("block ack");
            } else if (snapsBack
                    && ClientBlockShapes.movement(player, state, vector3i.getX(), vector3i.getY(), vector3i.getZ())
                            .isIntersected(player.boundingBox)) {
                // The player will teleport themselves if they get stuck in the reverted block
                player.lastX = player.x;
                player.lastY = player.y;
                player.lastZ = player.z;
                player.x = playerPosition.x;
                player.y = playerPosition.y;
                player.z = playerPosition.z;
                player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, player.x, player.y, player.z);
            }
        }
    }

    public void stopPredicting(int ignoredWireSequence) {
        this.isCurrentlyPredicting = false; // We aren't in a block place or use item

        if (this.currentlyChangedBlocks.isEmpty()) return; // Nothing to change

        List<BlockPos> toApplyBlocks =
                this.currentlyChangedBlocks; // We must now track the client applying the server predicted blocks
        this.currentlyChangedBlocks = new LinkedList<>(); // Reset variable without changing original

        // Only a client whose wire sequence stops advancing reaches this; its oldest prediction reverts to the server.
        if (serverIsCurrentlyProcessingThesePredictions.size() >= MAX_PENDING_PREDICTIONS) {
            Iterator<Map.Entry<Integer, List<BlockPos>>> oldest =
                    serverIsCurrentlyProcessingThesePredictions.entrySet().iterator();
            Map.Entry<Integer, List<BlockPos>> entry = oldest.next();
            applyBlockChanges(entry.getValue(), player.lastTransactionSent.get() + 1, entry.getKey());
            oldest.remove();
        }
        // Vanilla stores predictions under its local monotonic counter, not the untrusted packet sequence.
        serverIsCurrentlyProcessingThesePredictions.put(clientPredictionSequence, toApplyBlocks);
    }

    public static long chunkPositionToLong(int x, int z) {
        return ((x & 0xFFFFFFFFL) << 32L) | (z & 0xFFFFFFFFL);
    }

    private static int chunkXFromPosition(long chunkPosition) {
        return (int) (chunkPosition >> 32);
    }

    private static int chunkZFromPosition(long chunkPosition) {
        return (int) chunkPosition;
    }

    public void updateBlock(BlockPos pos, int state) {
        updateBlock(pos.getX(), pos.getY(), pos.getZ(), state);
    }

    public void ensureValidationChunkLoaded(int chunkX, int chunkZ) {
        long chunkPosition = chunkPositionToLong(chunkX, chunkZ);
        if (chunks.containsKey(chunkPosition)) {
            return;
        }

        int sectionCount = lastClientboundDimension.sectionCount();
        sectionPoolLeases.retain(visibleDimension, chunkX, chunkZ, sectionCount);
        chunks.put(chunkPosition, new CachedChunk(new CachedSection[sectionCount], player.lastTransactionSent.get()));
        chunkEpoch++;
        chunkDimensions.put(chunkPosition, visibleDimension);
    }

    public void markForBlockPrediction(BlockPos pos) {
        if (player.isBedrockMovement()) return;
        currentlyChangedBlocks.add(pos);
    }

    public boolean hasPendingBlockPrediction(BlockPos pos) {
        return originalServerBlocks.containsKey(pos.asLong());
    }

    /** Retained server state, excluding a locally predicted placement or destruction. */
    public int getServerBlockStateIdAt(BlockPos pos) {
        BlockPrediction pending = originalServerBlocks.get(pos.asLong());
        return pending == null ? getBlockStateIdAt(pos) : pending.getOriginalBlockId();
    }

    /** ClientPacketListener.handleGameEvent and Level.setRainLevel (26.3). */
    public void applyClientWeather(ac.cult.cultac.protocol.value.GameEventType event, float level) {
        switch (event) {
            case START_RAINING -> {
                clientRainLevel = 0.0F;
                isRaining = true;
            }
            case STOP_RAINING -> {
                clientRainLevel = 1.0F;
                isRaining = false;
            }
            case RAIN_LEVEL_CHANGE -> {
                clientRainLevel = Math.clamp(level, 0.0F, 1.0F);
                isRaining = level > 0.2F;
            }
            default -> {}
        }
    }

    private boolean clientIsRaining() {
        // Keep the existing coarse isRaining view for movement; actions use the
        // native received level and dimension weather restrictions.
        return hasSkyLight
                && visibleDimensionType != null
                && !visibleDimensionType.hasCeiling()
                && !visibleDimension.equals("minecraft:the_end")
                && clientRainLevel > 0.2;
    }

    /** Level.precipitationAt (26.3), retaining only the inputs needed by trident use. */
    public boolean clientIsRainingAt(BlockPos pos) {
        if (!clientIsRaining() || clientMotionBlockingHeightAt(pos.getX(), pos.getZ()) > pos.getY()) return false;
        return player.getWorldRegistries()
                .canRain(
                        clientBiomeKeyAt(pos),
                        new ac.cult.blocksim.engine.BlockPos(pos.getX(), pos.getY(), pos.getZ()),
                        clientSeaLevel);
    }

    /** Level.getHeight(MOTION_BLOCKING): first available Y, including missing/out-of-world fallbacks. */
    public int clientMotionBlockingHeightAt(int x, int z) {
        if (x < -30000000 || x >= 30000000 || z < -30000000 || z >= 30000000) return clientSeaLevel + 1;
        var chunk = getChunk(x >> 4, z >> 4);
        return minHeight
                + (chunk == null || chunk.motionHeightmap == null
                        ? 0
                        : chunk.motionHeightmap.get((x & 15) | ((z & 15) << 4)));
    }

    public void applyClientMotionHeightmap(String dimension, int x, int z, long[] received) {
        if (!dimension.equals(visibleDimension) || !dimension.equals(dimensionForChunk(x, z))) return;
        var chunk = getChunk(x, z);
        if (chunk != null && !chunk.motionHeightmap().load(received))
            chunk.motionHeightmap.prime(chunk.heightmapScanHeight(), chunk::stateAtIndex, motionBlockingPredicate());
    }

    private java.util.function.IntPredicate motionBlockingPredicate() {
        var data = ac.cult.blocksim.data.DataTables.defaults();
        var source =
                player.user.getCultConnection().dispatcher().runtime().data().version();
        var client = player.isBedrockMovement()
                ? source
                : ac.cult.cultac.protocol.ProtocolVersion.of(
                        player.getClientVersion().getProtocolVersion());
        if (!client.atLeast(ac.cult.cultac.protocol.ProtocolVersion.V26_3)) {
            // Through 26.2, BlockStateBase.blocksMotion uses legacy solidity with these two exceptions.
            return state -> !data.registry().facts(state).fluid().equals("minecraft:empty")
                    || data.registry().facts(state).has(ac.cult.blocksim.data.StateFacts.SOLID)
                            && !data.registry().block(state).key().equals("minecraft:cobweb")
                            && !data.registry().block(state).key().equals("minecraft:bamboo_sapling");
        }
        String key = "block:minecraft:blocks_motion_in_heightmap";
        Set<String> blocks = data.tags().getOrDefault(key, Set.of());
        if (player.registryState != null) {
            blocks = player.registryState
                    .blockSimulatorTags(source, client, data)
                    .tags()
                    .getOrDefault(key, blocks);
        }
        Set<String> opaqueBlocks = blocks;
        return state -> opaqueBlocks.contains(data.registry().block(state).key())
                || !data.registry().facts(state).fluid().equals("minecraft:empty");
    }

    public record PendingPrediction(BlockPos position, int sequence, int retained, int predicted) {}

    /** Immutable observations for the opt-in client snapshot comparison. */
    public List<PendingPrediction> pendingPredictionSnapshot() {
        return originalServerBlocks.values().stream()
                .map(prediction -> new PendingPrediction(
                        prediction.getBlockPosition(),
                        prediction.getSequence(),
                        prediction.getOriginalBlockId(),
                        prediction.getPredictedBlockId()))
                .toList();
    }

    public boolean wasClientCollisionChangedRecently(BlockPos pos) {
        return recentClientCollisionChanges.containsKey(pos.asLong());
    }

    public boolean hasRecentClientCollisionChanges(SimpleCollisionBox queryBox) {
        int minX = CultMath.floor(queryBox.minX);
        int minY = CultMath.floor(queryBox.minY);
        int minZ = CultMath.floor(queryBox.minZ);
        int maxX = CultMath.ceil(queryBox.maxX) - 1;
        int maxY = CultMath.ceil(queryBox.maxY) - 1;
        int maxZ = CultMath.ceil(queryBox.maxZ) - 1;
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    mutablePos.set(x, y, z);
                    if (wasClientCollisionChangedRecently(mutablePos)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public boolean hasRecentClientFluidChanges(SimpleCollisionBox queryBox) {
        return hasRecentClientFluidChanges(queryBox, (byte) (RECENT_FLUID_WATER | RECENT_FLUID_LAVA));
    }

    public boolean hasRecentClientWaterChanges(SimpleCollisionBox queryBox) {
        return hasRecentClientFluidChanges(queryBox, RECENT_FLUID_WATER);
    }

    public boolean hasRecentClientLavaChanges(SimpleCollisionBox queryBox) {
        return hasRecentClientFluidChanges(queryBox, RECENT_FLUID_LAVA);
    }

    private boolean hasRecentClientFluidChanges(SimpleCollisionBox queryBox, byte mask) {
        int minX = CultMath.floor(queryBox.minX);
        int minY = CultMath.floor(queryBox.minY);
        int minZ = CultMath.floor(queryBox.minZ);
        int maxX = CultMath.ceil(queryBox.maxX) - 1;
        int maxY = CultMath.ceil(queryBox.maxY) - 1;
        int maxZ = CultMath.ceil(queryBox.maxZ) - 1;
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    long key = mutablePos.set(x, y, z).asLong();
                    if (recentClientFluidChanges.containsKey(key)
                            && (recentClientFluidChangeKinds.get(key) & mask) != 0) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public void addDynamicBlockCollisionUncertaintyBoxes(SimpleCollisionBox queryBox, List<SimpleCollisionBox> boxes) {
        pistons.addDynamicBlockCollisionUncertaintyBoxes(queryBox, boxes);
        if (openShulkerBoxes.isEmpty()) return;

        for (ShulkerData data : openShulkerBoxes) {
            if (!data.isAnimationActive() || ridesUntracked(data)) {
                continue;
            }

            SimpleCollisionBox box = openShulkerCollisionBox(data);
            if (box.isCollided(queryBox)) {
                boxes.add(box);
            }
        }
    }

    public boolean hasOpenShulkerCollision(SimpleCollisionBox queryBox) {
        for (ShulkerData data : openShulkerBoxes) {
            if (data.isAnimationActive()
                    && !ridesUntracked(data)
                    && openShulkerCollisionBox(data).isCollided(queryBox)) {
                return true;
            }
        }

        return false;
    }

    // A shulker entity riding outside the own riding tree has no simulated position.
    private boolean ridesUntracked(ShulkerData data) {
        return data.entity != null && player.compensatedEntities.isUntrackedRider(data.entity);
    }

    private SimpleCollisionBox openShulkerCollisionBox(ShulkerData data) {
        Direction facing = data.getFacing(player);
        return data.getCollision().copy().expandToCoordinate(facing.getModX(), facing.getModY(), facing.getModZ());
    }

    void markRecentClientCollisionChange(BlockPos pos) {
        recentClientCollisionChanges.put(pos.asLong(), RECENT_CLIENT_COLLISION_CHANGE_TICKS);
    }

    private void markRecentClientFluidChange(BlockPos pos, int oldState, int newState) {
        byte kinds = recentFluidChangeKinds(oldState, newState);
        if (kinds == 0) {
            return;
        }

        long key = pos.asLong();
        recentClientFluidChanges.put(key, RECENT_CLIENT_FLUID_CHANGE_TICKS);
        recentClientFluidChangeKinds.put(key, kinds);
    }

    private void markRecentClientCollisionChange(BlockPos pos, int oldState, int newState) {
        if (BlockIds.is(oldState, BlockIds.MOVING_PISTON)
                || BlockIds.is(newState, BlockIds.MOVING_PISTON)
                || !collisionShapesMatch(pos, oldState, newState)) {
            markRecentClientCollisionChange(pos);
        }
    }

    private boolean collisionShapesMatch(BlockPos pos, int oldState, int newState) {
        if (oldState == newState) return true;
        var oldFixed = geometry().staticCollision(oldState);
        var newFixed = geometry().staticCollision(newState);
        if (oldFixed != null && newFixed != null) return ClientShapeBoxes.sameCachedShape(oldFixed, newFixed);
        var point = new ac.cult.blocksim.engine.BlockPos(pos.getX(), pos.getY(), pos.getZ());
        var actor = BlockIds.is(oldState, BlockIds.POWDER_SNOW) || BlockIds.is(newState, BlockIds.POWDER_SNOW)
                ? JavaCollisionState.current(player)
                : null;
        var context = NativeBlockCollisionHelper.entityContext(player, player.y);
        var comparison = ClientShapeBoxes.comparison(
                NativeBlockCollisionHelper.getCollisionShape(player, oldState, point, player.y, actor, context));
        return comparison.matches(
                NativeBlockCollisionHelper.getCollisionShape(player, newState, point, player.y, actor, context));
    }

    private byte recentFluidChangeKinds(int oldState, int newState) {
        var oldFluid = ClientFluidQueries.modelFluid(oldState);
        var newFluid = ClientFluidQueries.modelFluid(newState);
        if (oldFluid.equals(newFluid)) {
            return 0;
        }

        byte result = 0;
        if (FluidTags.WATER.test(oldFluid) || FluidTags.WATER.test(newFluid)) {
            result |= RECENT_FLUID_WATER;
        }
        if (FluidTags.LAVA.test(oldFluid) || FluidTags.LAVA.test(newFluid)) {
            result |= RECENT_FLUID_LAVA;
        }
        return result;
    }

    private void tickRecentClientCollisionChanges() {
        var iterator = recentClientCollisionChanges.long2IntEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            int ticks = entry.getIntValue() - 1;
            if (ticks <= 0) iterator.remove();
            else entry.setValue(ticks);
        }
    }

    private void tickRecentClientFluidChanges() {
        var iterator = recentClientFluidChanges.long2IntEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            int ticks = entry.getIntValue() - 1;
            if (ticks <= 0) {
                recentClientFluidChangeKinds.remove(entry.getLongKey());
                iterator.remove();
            } else entry.setValue(ticks);
        }
    }

    public int updateBlock(int x, int y, int z, int newState) {
        newState = ViaClientBlockShapeMappings.clientBlockStateId(player, stateIdOrAir(newState));
        BlockPos asVector = new BlockPos(x, y, z);
        BlockPrediction prediction = originalServerBlocks.get(asVector.asLong());
        int original = getBlockStateIdAt(asVector);
        if (isCurrentlyPredicting && selectionSnapshotActive) {
            selectionOriginalStates.putIfAbsent(asVector.asLong(), original);
        }

        if (isCurrentlyPredicting && !player.isBedrockMovement()) {
            if (prediction == null) {
                boolean isPlayerTryingToDisableCult =
                        player.getSetbackTeleportUtil().shouldBlockMovement();
                int serverState = getBlockStateIdAt(asVector);
                originalServerBlocks.put(
                        asVector.asLong(),
                        new BlockPrediction(
                                currentlyChangedBlocks,
                                asVector,
                                serverState,
                                newState,
                                isPlayerTryingToDisableCult ? null : new Vec3(player.x, player.y, player.z),
                                clientPredictionSequence)); // Remember server controlled block type
            } else {
                prediction.setForBlockUpdate(
                        currentlyChangedBlocks); // Block existing there was placed by client, mark block to have a new
                // prediction
                prediction.setPredictedBlockId(newState);
                prediction.setSequence(clientPredictionSequence);
            }
            currentlyChangedBlocks.add(asVector);
        }

        final GhostBlockMitigator ghostBlockMitigator = player.getGhostBlockMitigator();

        if (!isCurrentlyPredicting && prediction != null) {
            // Reconciliation must not overwrite the local predicted state while the prediction is still pending.
            // Only update the stored rollback target; the actual local block stays client-predicted until
            // confirmation handling decides whether to keep it or restore the original server state.
            prediction.setOriginalBlockId(newState);
            ghostBlockMitigator.handleUpdateServerBlockState(asVector, newState);
            return original;
        }

        ghostBlockMitigator.handleNewBlock(asVector);

        // This works because of how we optimized wrappedblockstate (avoid messing with inner tick predictions)
        if (getBlockStateIdAt(asVector) == newState) return original;

        player.checkManager.getSimulationProcessor().handleBlockChange(asVector, original);
        player.checkManager.getSimulationProcessor().handleBlockChange(asVector, newState);
        markRecentClientCollisionChange(asVector, original, newState);
        markRecentClientFluidChange(asVector, original, newState);

        applyBlockChangeRawDANGER(x, y, z, newState, asVector);
        geysers.updateBlock(asVector, original, newState);
        if (debugBlockChanges && isCurrentlyPredicting && getBlockStateIdAt(asVector) == newState) {
            String state =
                    ac.cult.blocksim.data.DataTables.defaults().registry().serialize(newState);
            if (state.startsWith("minecraft:")) state = state.substring("minecraft:".length());
            player.sendMessage(Component.text("[places] ", NamedTextColor.AQUA)
                    .append(Component.text(x + ", " + y + ", " + z, NamedTextColor.GRAY))
                    .append(Component.text(" → ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(state, NamedTextColor.GREEN)));
        }
        return original;
    }

    public void applyBlockChangeRawDANGER(int x, int y, int z, int combinedState) {
        applyBlockChangeRawDANGER(x, y, z, combinedState, null);
    }

    /** Reuse updateBlock's owned immutable position; standalone writes still create their own callback input. */
    private void applyBlockChangeRawDANGER(int x, int y, int z, int combinedState, BlockPos position) {
        combinedState = ViaClientBlockShapeMappings.clientBlockStateId(player, stateIdOrAir(combinedState));
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        CachedChunk column = getChunk(chunkX, chunkZ);

        // Apply 1.17 expanded world offset
        int offsetY = y - minHeight;

        if (column != null) {
            int sectionIndex = offsetY >> 4;
            CachedSection section = column.getOrCreateSection(sectionIndex);
            if (section == null) return;
            if (section.isShared()) {
                section = prepareSectionForWrite(chunkX, sectionIndex, chunkZ, section);
                column.setSection(sectionIndex, section);
            }

            int index = CachedChunk.index(x & 0xF, offsetY & 0xF, z & 0xF);
            int previous = section.getStateId(index);
            boolean heightmapChange = previous != combinedState
                    && !(section.isEmpty()
                            && ac.cult.blocksim.data.DataTables.defaults()
                                    .registry()
                                    .facts(combinedState)
                                    .has(ac.cult.blocksim.data.StateFacts.AIR));
            if (position == null) position = new BlockPos(x, y, z);
            clientBlockEntities.stateChanged(position, previous, combinedState);
            section.setStateId(index, combinedState);
            if (heightmapChange)
                column.motionHeightmap()
                        .update(
                                (x & 15) | ((z & 15) << 4),
                                offsetY,
                                combinedState,
                                column::stateAtIndex,
                                motionBlockingPredicate());
            queuePendingReintern(chunkX, sectionIndex, chunkZ, section);
            pistons.handleBlockStateApplied(position, combinedState);
            if (terrainListener != null) terrainListener.blockChanged(x, y, z, combinedState);
        }
    }

    public void removeInvalidPistonLikeStuff() {
        removeInvalidPistonLikeStuff(0);
    }

    public void onClientTickEnd() {
        endedTickGameTime = clientGameTime;
        if (!player.isBedrockMovement() && clientRunsNormally()) {
            clientClocks.tick(++clientGameTime);
            clientBlockEntities.tick();
        } else {
            clientBlockEntities.skipTick();
        }
        clientEnvironment.tick();
        removeInvalidPistonLikeStuff();
        processPendingReinterns();
    }

    /**
     * TickRateManager#runsNormally for the client tick in progress: Minecraft#tick sets it from the frozen state and
     * the remaining steps before taking a step.
     */
    private boolean clientRunsNormally() {
        return !player.packetStateData.serverTicksFrozen || player.packetStateData.serverFrozenTickStepsRemaining > 0;
    }

    private void processPendingReinterns() {
        if (pendingReinternSections.isEmpty()) return;

        long now = System.nanoTime();
        CachedSection oldest = null;
        Iterator<Map.Entry<PendingReinternSection, PendingReinternState>> iterator =
                pendingReinternSections.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<PendingReinternSection, PendingReinternState> entry = iterator.next();
            PendingReinternSection key = entry.getKey();
            PendingReinternState state = entry.getValue();
            CachedChunk column = getChunk(key.chunkX, key.chunkZ);
            if (column == null) {
                iterator.remove();
                continue;
            }

            CachedSection section = column.getSection(key.sectionIndex);
            if (section == null || section.isShared() || section != state.section()) {
                iterator.remove();
                continue;
            }

            if (oldest == null || section.getLastMutatedNanos() < oldest.getLastMutatedNanos()) oldest = section;
        }
        // Candidates run oldest write first and stop at the first unstable one.
        if (oldest == null || !SectionPool.isStableForReintern(oldest, now)) return;

        SectionPool.ComparisonBudget comparisonBudget = SectionPool.ComparisonBudget.limited(
                MAX_REINTERN_CONTENT_COMPARISONS_PER_TICK,
                MAX_REINTERN_HASH_CALCULATIONS_PER_TICK,
                MAX_REINTERN_ATTEMPTED_COMPARISONS_PER_TICK);
        List<PendingReinternCandidate> candidates = reinternCandidates;
        try {
            for (Map.Entry<PendingReinternSection, PendingReinternState> entry : pendingReinternSections.entrySet()) {
                PendingReinternSection key = entry.getKey();
                PendingReinternState state = entry.getValue();
                CachedSection section = state.section();
                candidates.add(new PendingReinternCandidate(
                        key, state, getChunk(key.chunkX, key.chunkZ), section, section.getLastMutatedNanos()));
            }

            candidates.sort(BY_LAST_MUTATED);
            for (PendingReinternCandidate candidate : candidates) {
                CachedSection section = candidate.section();
                if (!SectionPool.isStableForReintern(section, now)) {
                    break;
                }

                String dimension = dimensionForChunk(candidate.key().chunkX, candidate.key().chunkZ);
                SectionPool pool = SectionPool.forChunk(
                        dimension, candidate.key().chunkX, candidate.key().sectionIndex, candidate.key().chunkZ);
                SectionPool.ReinternResult result =
                        pool.tryReinternRetained(section, candidate.state().nextComparisonIndex(), comparisonBudget);
                if (!result.complete()) {
                    candidate.state().nextComparisonIndex = result.nextComparisonIndex();
                    break;
                }

                CachedSection reinterned = result.section();
                if (reinterned != section) {
                    candidate.column().setSection(candidate.key().sectionIndex, reinterned);
                }
                pendingReinternSections.remove(candidate.key());
            }
        } finally {
            candidates.clear();
        }
    }

    private void queuePendingReintern(int chunkX, int sectionIndex, int chunkZ, CachedSection section) {
        var key = new PendingReinternSection(chunkX, sectionIndex, chunkZ);
        var pending = pendingReinternSections.get(key);
        if (pending == null) pendingReinternSections.put(key, new PendingReinternState(section, 0));
        else {
            pending.section = section;
            pending.nextComparisonIndex = 0;
        }
    }

    private CachedSection prepareSectionForWrite(int chunkX, int sectionIndex, int chunkZ, CachedSection section) {
        if (section.isEmpty() && !section.hasFluid()) {
            return CachedSection.createAirSection();
        }

        String dimension = dimensionForChunk(chunkX, chunkZ);
        if (SectionPool.tryMakeExclusiveForMutation(dimension, chunkX, sectionIndex, chunkZ, section)) {
            return section;
        }
        SectionPool.releaseSectionReference(dimension, chunkX, sectionIndex, chunkZ, section);
        return section.mutableCopy();
    }

    public String dimensionForChunk(int chunkX, int chunkZ) {
        return chunkDimensions.getOrDefault(chunkPositionToLong(chunkX, chunkZ), visibleDimension);
    }

    private void removePendingReinternSections(int chunkX, int chunkZ) {
        pendingReinternSections.keySet().removeIf(key -> key.chunkX == chunkX && key.chunkZ == chunkZ);
    }

    private void removePendingReinternSection(int chunkX, int sectionIndex, int chunkZ) {
        pendingReinternSections.remove(new PendingReinternSection(chunkX, sectionIndex, chunkZ));
    }

    private record PendingReinternSection(int chunkX, int sectionIndex, int chunkZ) {}

    private static final class PendingReinternState {
        private CachedSection section;
        private int nextComparisonIndex;

        PendingReinternState(CachedSection section, int nextComparisonIndex) {
            this.section = section;
            this.nextComparisonIndex = nextComparisonIndex;
        }

        CachedSection section() {
            return section;
        }

        int nextComparisonIndex() {
            return nextComparisonIndex;
        }
    }

    private record PendingReinternCandidate(
            PendingReinternSection key,
            PendingReinternState state,
            CachedChunk column,
            CachedSection section,
            long lastMutatedNanos) {}

    public void removeInvalidPistonLikeStuff(int transactionId) {
        if (transactionId == 0) {
            // End-of-client-tick path: advance time-based state instead of
            // pruning by transaction watermark.
            tickClientWorldState();
        } else {
            // Transaction path: drop entries the client has provably seen.
            pistons.removeSentBefore(transactionId);
            openShulkerBoxes.removeIf(box -> box.isClosing() && box.lastTransactionSent < transactionId);
        }
        pruneOrphanedShulkerBoxes();
    }

    private void tickClientWorldState() {
        tickRecentClientCollisionChanges();
        tickRecentClientFluidChanges();
        pistons.tickClientTickEnd();
        openShulkerBoxes.removeIf(ShulkerData::tickIfGuaranteedFinished);
    }

    // Drop shulker boxes whose backing block or entity no longer exists.
    private void pruneOrphanedShulkerBoxes() {
        openShulkerBoxes.removeIf(box -> box.blockPos != null
                ? !ClientBlockProperties.isShulkerBox(player.compensatedWorld.getBlockStateIdAt(box.blockPos))
                : !player.compensatedEntities.entityMap.containsValue(box.entity));
    }

    public void clearPistonLikeState() {
        pistons.clear();
        openShulkerBoxes.clear();
        recentClientCollisionChanges.clear();
        recentClientFluidChanges.clear();
        recentClientFluidChangeKinds.clear();
    }

    /**
     * {@code onGround}: Entity#onGround as the packet's start reports it. {@code lastPushes}: the last packet's, or
     * null.
     */
    public PistonPushes tickPlayerInPistonPushingArea(
            SimulationContext context, boolean onGround, PistonPushes lastPushes) {
        // Occurs on player login
        if (player.boundingBox == null) return new PistonPushes(new SimpleCollisionBox(), Collections.emptySet());

        List<CompensatedBlockEntities.ShulkerShove> shulkerBoxShoves = shulkerBoxShoves();
        if (openShulkerBoxes.isEmpty() && pistons.hasNoPushSources() && shulkerBoxShoves.isEmpty()) {
            // Push consumers only read these boxes, so the three empty pushes share one.
            SimpleCollisionBox empty = new SimpleCollisionBox();
            return new PistonPushes(empty, empty, empty, Collections.emptySet());
        }

        SimpleCollisionBox queryBox = pistons.createPistonQueryBox(context);
        PistonPushes pistonResult = pistons.tickPlayerInPistonPushingArea(queryBox);
        SimpleCollisionBox shulkerPushes = accumulateShulkerPushes(queryBox);

        SimpleCollisionBox pistonPush = pistonResult.getPistonPush();
        SimpleCollisionBox combinedPushes = pistonPush.copy().union(shulkerPushes);
        PistonPushes pushes =
                new PistonPushes(combinedPushes, pistonPush, shulkerPushes, pistonResult.getSlimeBlockLaunches());
        if (!shulkerBoxShoves.isEmpty()) shoveByShulkerBoxes(context, onGround, lastPushes, shulkerBoxShoves, pushes);
        return pushes;
    }

    /**
     * ShulkerBoxBlockEntity#moveCollidedEntities calls this packet carries. Before 26.3, LocalPlayer#sendPosition
     * runs before ClientLevel#tickBlockEntities, so the packet carries the last client tick's calls; from 26.3,
     * Minecraft#tick sends the position after them, so it carries the next client tick's calls.
     */
    private List<CompensatedBlockEntities.ShulkerShove> shulkerBoxShoves() {
        if (player.isBedrockMovement()) return List.of();
        List<CompensatedBlockEntities.ShulkerShove> shoves = new ArrayList<>(1);
        clientBlockEntities.shulkerShoves(pistons.reportsAfterBlockEntities(), clientRunsNormally(), shoves);
        return shoves;
    }

    /**
     * The shulker box block shoves this packet carries. Before 26.3 they move the player from the packet's start; from
     * 26.3 from its travel's end. PistonMovingBlockEntity#moveCollidedEntities and Shulker#onPeekAmountChange calls of
     * this packet or the last also move the player; without them, each call's result follows from the one before.
     */
    private void shoveByShulkerBoxes(
            SimulationContext context,
            boolean onGround,
            PistonPushes lastPushes,
            List<CompensatedBlockEntities.ShulkerShove> shoves,
            PistonPushes pushes) {
        // PistonShulkerPush replays the last packet's piston and shulker entity moves in this one.
        boolean exact = pushes.getPistonPush().isEmpty()
                && pushes.getShulkerPush().isEmpty()
                && (lastPushes == null
                        || lastPushes.getPistonPush().isEmpty()
                                && lastPushes.getShulkerPush().isEmpty());
        SimpleCollisionBox envelope = new SimpleCollisionBox();
        if (!pistons.reportsAfterBlockEntities()) {
            ShulkerBoxShoveResult result =
                    shoveByShulkerBoxes(context, shoves, context.getStart(), context.getPose(), onGround, false);
            if (result == null) return;
            envelope.unionX(result.movement().x);
            envelope.unionY(result.movement().y);
            envelope.unionZ(result.movement().z);
            pushes.setShulkerBoxPush(envelope);
            pushes.setShulkerBoxShoveBeforeTravel(true);
            if (exact) pushes.setShulkerBoxShoveResult(result);
            else pushes.getPush().union(envelope);
            return;
        }
        SimpleCollisionBox reach = exact
                ? context.getFromMaximumExtent()
                        .union(context.getToMaximumExtent())
                        .expand(1.0D)
                : shoveReach(context, shoves, pushes, lastPushes);
        List<CompensatedBlockEntities.ShulkerShove> reaching = new ArrayList<>(shoves.size());
        double x = 0.0D, y = 0.0D, z = 0.0D;
        for (CompensatedBlockEntities.ShulkerShove shove : shoves) {
            if (!intersects(reach, shove.area())) continue;
            reaching.add(shove);
            x += shove.x();
            y += shove.y();
            z += shove.z();
            envelope.unionX(x);
            envelope.unionY(y);
            envelope.unionZ(z);
        }
        if (reaching.isEmpty()) return;
        pushes.setShulkerBoxPush(envelope);
        if (exact) pushes.setShulkerBoxShovesAfterTravel(List.copyOf(reaching));
        else pushes.getPush().union(envelope);
    }

    /**
     * Where the local player's box can be when a call of {@code shoves} tests it: the packet's end, before the call's
     * own and every later move of the pass, Entity#move(MoverType.SHULKER_BOX),
     * PistonMovingBlockEntity#moveCollidedEntities and Shulker#onPeekAmountChange (26.3 :256). Entity#collide can stop
     * each of those moves anywhere from zero to its whole movement.
     */
    private static SimpleCollisionBox shoveReach(
            SimulationContext context,
            List<CompensatedBlockEntities.ShulkerShove> shoves,
            PistonPushes pushes,
            PistonPushes lastPushes) {
        double[] low = new double[3];
        double[] high = new double[3];
        for (SimpleCollisionBox push : new SimpleCollisionBox[] {
            pushes.getPistonPush(),
            pushes.getShulkerPush(),
            lastPushes == null ? null : lastPushes.getPistonPush(),
            lastPushes == null ? null : lastPushes.getShulkerPush()
        }) {
            if (push == null || push.isEmpty()) continue;
            low[0] = Math.min(low[0], push.minX);
            low[1] = Math.min(low[1], push.minY);
            low[2] = Math.min(low[2], push.minZ);
            high[0] = Math.max(high[0], push.maxX);
            high[1] = Math.max(high[1], push.maxY);
            high[2] = Math.max(high[2], push.maxZ);
        }
        for (CompensatedBlockEntities.ShulkerShove shove : shoves) {
            double[] movement = {shove.x(), shove.y(), shove.z()};
            for (int axis = 0; axis < 3; axis++) {
                low[axis] += Math.min(0.0D, movement[axis]);
                high[axis] += Math.max(0.0D, movement[axis]);
            }
        }
        SimpleCollisionBox end = context.getToMaximumExtent();
        return new SimpleCollisionBox(
                end.minX - high[0],
                end.minY - high[1],
                end.minZ - high[2],
                end.maxX - low[0],
                end.maxY - low[1],
                end.maxZ - low[2]);
    }

    /**
     * ShulkerBoxBlockEntity#moveCollidedEntities for each of {@code shoves} in Level#tickBlockEntities order: the
     * local player's Entity#move(MoverType.SHULKER_BOX) when its box of {@code pose}, from {@code position} after the
     * calls before, intersects the call's area. Each move starts from the Entity#onGround the last left,
     * {@code onGround} first. Null when no call reaches the player. From 26.3 ({@code restitution})
     * Entity#restituteMovementAfterCollisions zeroes the vertical velocity only after a vertical move or a horizontal
     * collision (Entity#move, 26.1 :800-808, 26.3 :820-821).
     */
    public ShulkerBoxShoveResult shoveByShulkerBoxes(
            SimulationContext context,
            List<CompensatedBlockEntities.ShulkerShove> shoves,
            Vec3 position,
            Pose pose,
            boolean onGround,
            boolean restitution) {
        boolean vehicle = context.getVehicle() != null;
        float width = vehicle ? context.getMaxWidth() : pose.width * context.getScale();
        float height = vehicle ? context.getMaxHeight() : pose.height * context.getScale();
        Vec3 start = position;
        boolean reached = false, zeroX = false, zeroY = false, zeroZ = false;
        for (CompensatedBlockEntities.ShulkerShove shove : shoves) {
            SimpleCollisionBox box =
                    GetBoundingBox.getBoundingBoxFromPosAndSize(position.x, position.y, position.z, width, height);
            if (!intersects(box, shove.area())) continue;
            reached = true;
            Vec3 movement = Collisions.collide(player, box, shove.x(), shove.y(), shove.z(), onGround);
            double movementLength = movement.lengthSqr();
            double deltaLength = shove.x() * shove.x() + shove.y() * shove.y() + shove.z() * shove.z();
            if (movementLength > 1.0E-7D || deltaLength - movementLength < 1.0E-7D) position = position.add(movement);
            // Mth#equal
            boolean xCollision = !(Math.abs(movement.x - shove.x()) < 1.0E-5F);
            boolean zCollision = !(Math.abs(movement.z - shove.z()) < 1.0E-5F);
            boolean verticalCollision = shove.y() != movement.y;
            onGround = verticalCollision && shove.y() < 0.0D;
            zeroX |= xCollision;
            zeroZ |= zCollision;
            if (verticalCollision && (!restitution || shove.y() != 0.0D || xCollision || zCollision)) zeroY = true;
        }
        return reached ? new ShulkerBoxShoveResult(position.subtract(start), zeroX, zeroY, zeroZ, onGround) : null;
    }

    private static boolean intersects(SimpleCollisionBox box, SimpleCollisionBox area) {
        return box.minX < area.maxX
                && box.maxX > area.minX
                && box.minY < area.maxY
                && box.maxY > area.minY
                && box.minZ < area.maxZ
                && box.maxZ > area.minZ;
    }

    // Expands the query box by every colliding open shulker box lid and
    // accumulates the resulting entity push direction.
    private SimpleCollisionBox accumulateShulkerPushes(SimpleCollisionBox queryBox) {
        SimpleCollisionBox shulkerPushes = new SimpleCollisionBox();
        for (ShulkerData data : openShulkerBoxes) {
            if (!data.canPushEntities() || ridesUntracked(data)) {
                continue;
            }

            Direction pushDirection = data.getFacing(player);
            if (queryBox.isCollided(openShulkerCollisionBox(data))) {
                queryBox.expand(
                        Math.abs(pushDirection.getModX()),
                        Math.abs(pushDirection.getModY()),
                        Math.abs(pushDirection.getModZ()));
                shulkerPushes.expandToCoordinate(
                        pushDirection.getModX(), pushDirection.getModY(), pushDirection.getModZ());
            }
        }
        return shulkerPushes;
    }

    public int getBlockStateIdAt(BlockPos pos) {
        return getBlockStateIdAt(pos.getX(), pos.getY(), pos.getZ());
    }

    public int getBlockStateIdAt(int x, int y, int z) {
        if (readingSelectionSnapshot) {
            long position = new BlockPos(x, y, z).asLong();
            if (selectionOriginalStates.containsKey(position)) return selectionOriginalStates.get(position);
        }
        try {
            CachedChunk column = getChunk(x >> 4, z >> 4);

            y -= minHeight;
            if (column == null || y < 0 || (y >> 4) >= column.sectionCount()) return 0;

            CachedSection section = column.getSection(y >> 4);
            if (section != null) return section.getStateId(CachedChunk.index(x & 0xF, y & 0xF, z & 0xF));
        } catch (Exception ignored) {
        }

        return 0;
    }

    public int getHeight() {
        return maxHeight - minHeight;
    }

    public int getMinY() {
        return minHeight;
    }

    public CachedChunk getChunk(int chunkX, int chunkZ) {
        long chunkPosition = chunkPositionToLong(chunkX, chunkZ);
        return chunks.get(chunkPosition);
    }

    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        long chunkPosition = chunkPositionToLong(chunkX, chunkZ);
        return chunks.containsKey(chunkPosition);
    }

    public void putChunk(int chunkX, int chunkZ, CachedChunk chunk) {
        chunks.put(chunkPositionToLong(chunkX, chunkZ), chunk);
        chunkEpoch++;
    }

    /** The positions of the received chunks, read-only, in the map's iteration order. */
    public LongSet chunkPositions() {
        return chunkPositions;
    }

    public long chunkEpoch() {
        return chunkEpoch;
    }

    public ac.cult.blocksim.engine.BlockEntityData getClientBlockEntityData(BlockPos pos) {
        return clientBlockEntities.snapshot(pos, getBlockStateIdAt(pos));
    }

    public void applyPredictedBlockEntityData(BlockPos pos, ac.cult.blocksim.engine.BlockEntityData data) {
        clientBlockEntities.predict(pos, getBlockStateIdAt(pos), data);
    }

    /** ClientLevel#getGameTime after the last client tick end. */
    public long clientGameTime() {
        return clientGameTime;
    }

    /** ClientLevel#getGameTime during the client tick whose tick end was processed last. */
    public long endedTickGameTime() {
        return endedTickGameTime;
    }

    /** ClientPacketListener.handleSetTime updates clocks before invalidating attributes. */
    public void applyClientTime(ac.cult.cultac.network.packet.WorldPackets.TimeUpdate packet) {
        clientGameTime = packet.gameTime();
        clientClocks.handleUpdates(clientGameTime, packet.clocks());
        clientEnvironment.tick();
    }

    /** Configuration creates a new ClientPacketListener and its clock manager. */
    public void resetClientClocks() {
        clientClocks.reset();
    }

    /**
     * Counts client levels. A level-specific task captures this when queued and does nothing once a login has
     * replaced its level.
     */
    public int level() {
        return level;
    }

    /** Login creates fresh level data even when the dimension key is unchanged. */
    public void onClientLogin() {
        level++;
        clientGameTime = 0;
        clientPeaceful = false;
        clientRainLevel = 0.0F;
    }

    public void applyClientRecipeInputs(ac.cult.cultac.network.packet.WorldPackets.RecipeInputs packet) {
        clientRecipeInputs = packet.itemSets();
    }

    public void applyClientFeatures(ac.cult.cultac.network.packet.WorldPackets.EnabledFeatures packet) {
        clientEnabledFeatures = packet.features();
    }

    public void applyClientDifficulty(ac.cult.cultac.network.packet.WorldPackets.Difficulty packet) {
        clientPeaceful = packet.peaceful();
    }

    public boolean clientCanSpawn(ac.cult.blocksim.entity.EntityTypes.Type type) {
        return type.canSpawn(clientEnabledFeatures, clientPeaceful);
    }

    public boolean clientHasFeature(String key) {
        return clientEnabledFeatures.contains(key);
    }

    public Set<String> clientFeatures() {
        return clientEnabledFeatures;
    }

    public Set<String> clientRecipeInputs(String key) {
        return clientRecipeInputs.getOrDefault(key, Set.of());
    }

    public ac.cult.blocksim.environment.BooleanEnvironment.Values clientEnvironmentAt(BlockPos pos) {
        var chunk = getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) throw new IllegalStateException("Missing client chunk " + pos);
        int y = Math.clamp(pos.getY(), minHeight, maxHeight - 1);
        return clientEnvironment.at(
                chunk.biomeAt((y - minHeight) >> 4, (pos.getX() >> 2) & 3, (y >> 2) & 3, (pos.getZ() >> 2) & 3));
    }

    public String clientBiomeKeyAt(BlockPos pos) {
        var quart =
                ac.cult.blocksim.engine.BiomeLookup.quartAt(clientBiomeZoomSeed, pos.getX(), pos.getY(), pos.getZ());
        var names = player.user.getCultConnection().require(ac.cult.cultac.protocol.data.RegistryNames.class);
        var chunk = getChunk(quart.x() >> 2, quart.z() >> 2);
        // LevelReader.getNoiseBiome delegates missing chunks to ClientLevel.getUncachedNoiseBiome.
        if (chunk == null)
            return names.name("minecraft:worldgen/biome", names.id("minecraft:worldgen/biome", "minecraft:plains"));
        int quartY = Math.clamp(quart.y(), minHeight >> 2, (maxHeight - 1) >> 2);
        int biomeId = chunk.biomeAt(((quartY << 2) - minHeight) >> 4, quart.x() & 3, quartY & 3, quart.z() & 3);
        return names.name("minecraft:worldgen/biome", biomeId);
    }

    public void applyClientBiomes(String dimension, int x, int z, int[][] biomes) {
        if (!dimension.equals(visibleDimension) || !dimension.equals(dimensionForChunk(x, z))) return;
        var chunk = getChunk(x, z);
        if (chunk != null) chunk.replaceBiomes(biomes);
    }

    public void applyClientBlockEntityData(
            String dimension, ac.cult.cultac.network.packet.WorldPackets.BlockEntityUpdate packet) {
        int x = packet.position().getX() >> 4, z = packet.position().getZ() >> 4;
        if (isChunkLoaded(x, z) && dimension.equals(visibleDimension) && dimension.equals(dimensionForChunk(x, z))) {
            pistons.receiveBlockEntityData(packet);
            clientBlockEntities.receive(packet, getBlockStateIdAt(packet.position()));
        }
    }

    public void applyClientBlockEvent(
            String dimension, ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent packet) {
        var pos = new BlockPos(
                packet.position().x(), packet.position().y(), packet.position().z());
        if (!isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)
                || !dimension.equals(visibleDimension)
                || !dimension.equals(dimensionForChunk(pos.getX() >> 4, pos.getZ() >> 4))) return;
        int state = getBlockStateIdAt(pos);
        // Preserve the current-block check using the generated model block IDs.
        if (ac.cult.blocksim.data.DataTables.defaults().registry().blockIndex(state) == packet.blockId())
            clientBlockEntities.blockEvent(pos, state, packet.action(), packet.parameter());
    }

    public void addToCache(CachedChunk chunk, int chunkX, int chunkZ) {
        addToCache(chunk, visibleDimension, chunkX, chunkZ);
    }

    public void addToCache(CachedChunk chunk, String dimension, int chunkX, int chunkZ) {
        addToCache(chunk, dimension, player.lastTransactionSent.get(), chunkX, chunkZ);
    }

    public void addToCache(CachedChunk chunk, String dimension, int transaction, int chunkX, int chunkZ) {
        addToCache(chunk, dimension, transaction, chunkX, chunkZ, List.of());
    }

    public void addToCache(
            CachedChunk chunk,
            String dimension,
            int transaction,
            int chunkX,
            int chunkZ,
            List<BlockPos> geyserTickers) {
        addToCache(chunk, dimension, transaction, chunkX, chunkZ, geyserTickers, null);
    }

    public void addToCache(
            CachedChunk chunk,
            String dimension,
            int transaction,
            int chunkX,
            int chunkZ,
            List<BlockPos> geyserTickers,
            long[] motionBlocking) {
        long chunkPosition = chunkPositionToLong(chunkX, chunkZ);
        serverBlockView.columnSent(chunkX, chunkZ);
        int queuedLevel = level;
        player.latencyUtils.addRealTimeTask(transaction, () -> {
            serverBlockView.columnApplied(chunkX, chunkZ);
            if (queuedLevel != level) return;
            CachedChunk previous = chunks.get(chunkPosition);
            String previousDimension = chunkDimensions.get(chunkPosition);
            if (previous != null && dimension.equals(previousDimension) && previous.getTransaction() > transaction) {
                return;
            }

            // ClientChunkCache reuses a LevelChunk. A packet that omits this heightmap
            // leaves its previous values intact even when the section data is replaced.
            if (previous != null
                    && dimension.equals(previousDimension)
                    && previous.sectionCount() == chunk.sectionCount())
                chunk.motionHeightmap = previous.motionHeightmap;

            sectionPoolLeases.retain(dimension, chunkX, chunkZ, chunk.sectionCount());
            internChunkSections(chunk, dimension, chunkX, chunkZ);
            removePendingReinternSections(chunkX, chunkZ);
            previous = chunks.put(chunkPosition, chunk);
            chunkEpoch++;
            previousDimension = chunkDimensions.put(chunkPosition, dimension);
            if (previous != null) {
                String releaseDimension = previousDimension == null ? dimension : previousDimension;
                releaseChunkSections(previous, releaseDimension, chunkX, chunkZ);
                if (previous.sectionCount() != chunk.sectionCount() || !releaseDimension.equals(dimension)) {
                    sectionPoolLeases.release(releaseDimension, chunkX, chunkZ, previous.sectionCount());
                }
            }
            if (motionBlocking != null) applyClientMotionHeightmap(dimension, chunkX, chunkZ, motionBlocking);
            preservePendingPredictions(chunk, chunkX, chunkZ);
            if (dimension.equals(visibleDimension)) {
                geysers.replaceChunk(chunkX, chunkZ, geyserTickers);
                pistons.forgetChunk(chunkX, chunkZ);
                clientBlockEntities.forgetChunk(chunkX, chunkZ);
                for (var entity : chunk.blockEntities) applyClientBlockEntityData(dimension, entity);
            }
            if (terrainListener != null) terrainListener.columnLoaded(chunkX, chunkZ);
        });
    }

    public void mergeIntoCache(CachedChunk existingColumn, CachedSection[] toMerge, int chunkX, int chunkZ) {
        mergeIntoCache(existingColumn, toMerge, visibleDimension, player.lastTransactionSent.get(), chunkX, chunkZ);
    }

    public void mergeIntoCache(
            CachedChunk existingColumn,
            CachedSection[] toMerge,
            String dimension,
            int transaction,
            int chunkX,
            int chunkZ) {
        long chunkPosition = chunkPositionToLong(chunkX, chunkZ);
        String previousDimension = chunkDimensions.get(chunkPosition);
        if (dimension.equals(previousDimension) && existingColumn.getTransaction() > transaction) {
            return;
        }

        chunkDimensions.putIfAbsent(chunkPosition, dimension);
        internChunkSections(toMerge, dimension, chunkX, chunkZ);
        removePendingReinternSections(toMerge, chunkX, chunkZ);
        releaseMergedChunkSections(existingColumn, toMerge, dimension, chunkX, chunkZ);
        existingColumn.mergeSections(toMerge);
        preservePendingPredictions(existingColumn, chunkX, chunkZ);
        if (terrainListener != null) terrainListener.columnLoaded(chunkX, chunkZ);
    }

    private void removePendingReinternSections(CachedSection[] sections, int chunkX, int chunkZ) {
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            if (sections[sectionIndex] != null) {
                removePendingReinternSection(chunkX, sectionIndex, chunkZ);
            }
        }
    }

    private void internChunkSections(CachedChunk chunk, String dimension, int chunkX, int chunkZ) {
        internChunkSections(chunk.sections, dimension, chunkX, chunkZ);
    }

    private void internChunkSections(CachedSection[] sections, String dimension, int chunkX, int chunkZ) {
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            CachedSection section = sections[sectionIndex];
            if (section != null) {
                sections[sectionIndex] = SectionPool.forChunk(dimension, chunkX, sectionIndex, chunkZ)
                        .internRetained(section);
            }
        }
    }

    private void releaseChunkSections(CachedChunk chunk, String dimension, int chunkX, int chunkZ) {
        releaseChunkSections(chunk.sections, dimension, chunkX, chunkZ);
    }

    private void releaseChunkSections(CachedSection[] sections, String dimension, int chunkX, int chunkZ) {
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            CachedSection section = sections[sectionIndex];
            if (section != null) {
                SectionPool.releaseSectionReference(dimension, chunkX, sectionIndex, chunkZ, section);
            }
        }
    }

    private void releaseMergedChunkSections(
            CachedChunk existingColumn, CachedSection[] toMerge, String dimension, int chunkX, int chunkZ) {
        for (int sectionIndex = 0;
                sectionIndex < Math.min(existingColumn.sectionCount(), toMerge.length);
                sectionIndex++) {
            if (toMerge[sectionIndex] != null) {
                CachedSection previous = existingColumn.getSection(sectionIndex);
                if (previous != null) {
                    SectionPool.releaseSectionReference(dimension, chunkX, sectionIndex, chunkZ, previous);
                }
            }
        }
    }

    private void preservePendingPredictions(CachedChunk chunk, int chunkX, int chunkZ) {
        if (originalServerBlocks.isEmpty()) {
            return;
        }

        for (BlockPrediction prediction : originalServerBlocks.values()) {
            BlockPos position = prediction.getBlockPosition();
            if ((position.getX() >> 4) != chunkX || (position.getZ() >> 4) != chunkZ) {
                continue;
            }

            int serverState = getCachedChunkStateId(chunk, position);
            if (serverState != -1) {
                prediction.setOriginalBlockId(serverState);
            }

            setCachedChunkState(chunk, position, prediction.getPredictedBlockId());
        }
    }

    private int getCachedChunkStateId(CachedChunk chunk, BlockPos position) {
        int offsetY = position.getY() - minHeight;
        int sectionIndex = offsetY >> 4;
        if (sectionIndex < 0 || sectionIndex >= chunk.sectionCount()) {
            return -1;
        }

        CachedSection section = chunk.getSection(sectionIndex);
        if (section == null) {
            return 0;
        }
        return section.getStateId(CachedChunk.index(position.getX() & 0xF, offsetY & 0xF, position.getZ() & 0xF));
    }

    private void setCachedChunkState(CachedChunk chunk, BlockPos position, int state) {
        int offsetY = position.getY() - minHeight;
        int sectionIndex = offsetY >> 4;
        CachedSection section = chunk.getOrCreateSection(sectionIndex);
        if (section == null) return;
        if (section.isShared()) {
            section = prepareSectionForWrite(position.getX() >> 4, sectionIndex, position.getZ() >> 4, section);
            chunk.setSection(sectionIndex, section);
        }
        section.setStateId(CachedChunk.index(position.getX() & 0xF, offsetY & 0xF, position.getZ() & 0xF), state);
        queuePendingReintern(position.getX() >> 4, sectionIndex, position.getZ() >> 4, section);
    }

    public double getFluidLevelAt(int x, int y, int z) {
        return Math.max(getWaterFluidLevelAt(x, y, z), getLavaFluidLevelAt(x, y, z));
    }

    public boolean isWaterSourceBlock(int x, int y, int z) {
        return ClientFluidQueries.modelFluid(getBlockStateIdAt(x, y, z)).isSourceOfType("minecraft:water");
    }

    public double getLavaFluidLevelAt(ac.cult.cultac.protocol.value.BlockPos pos) {
        return getLavaFluidLevelAt(pos.getX(), pos.getY(), pos.getZ());
    }

    public double getLavaFluidLevelAt(int x, int y, int z) {
        var fluidState = ClientFluidQueries.modelFluid(getBlockStateIdAt(x, y, z));
        if (!FluidTags.LAVA.test(fluidState)) return 0;
        if (FluidTags.LAVA.test(ClientFluidQueries.modelFluid(getBlockStateIdAt(x, y + 1, z)))) return 1;
        return fluidState.amount() / 9f;
    }

    public double getWaterFluidLevelAt(ac.cult.cultac.protocol.value.BlockPos position) {
        return getWaterFluidLevelAt(position.getX(), position.getY(), position.getZ());
    }

    public double getWaterFluidLevelAt(double x, double y, double z) {
        return getWaterFluidLevelAt(CultMath.floor(x), CultMath.floor(y), CultMath.floor(z));
    }

    public double getWaterFluidLevelAt(int x, int y, int z) {
        var fluidState = ClientFluidQueries.modelFluid(getBlockStateIdAt(x, y, z));
        if (!FluidTags.WATER.test(fluidState)) return 0;

        // If water has water above it, it's block height is 1, even if it's waterlogged
        if (FluidTags.WATER.test(ClientFluidQueries.modelFluid(getBlockStateIdAt(x, y + 1, z)))) {
            return 1;
        }

        return fluidState.amount() / 9f;
    }

    public void removeChunkLater(int chunkX, int chunkZ) {
        removeChunkLater(visibleDimension, chunkX, chunkZ, player.lastTransactionSent.get());
    }

    public void removeChunkLater(int chunkX, int chunkZ, int transaction) {
        removeChunkLater(visibleDimension, chunkX, chunkZ, transaction);
    }

    public void removeChunkLater(String dimension, int chunkX, int chunkZ, int transaction) {
        long chunkPosition = chunkPositionToLong(chunkX, chunkZ);
        serverBlockView.columnSent(chunkX, chunkZ);
        int queuedLevel = level;
        player.latencyUtils.addRealTimeTask(transaction, () -> {
            serverBlockView.columnApplied(chunkX, chunkZ);
            if (queuedLevel != level) return;
            String currentDimension = chunkDimensions.get(chunkPosition);
            if (currentDimension != null && !dimension.equals(currentDimension)) {
                sectionPoolLeases.release(dimension, chunkX, chunkZ);
                return;
            }

            CachedChunk current = chunks.get(chunkPosition);
            if (current != null && current.getTransaction() > transaction) {
                return;
            }

            CachedChunk removed = chunks.remove(chunkPosition);
            chunkEpoch++;
            chunkDimensions.remove(chunkPosition);
            removePendingReinternSections(chunkX, chunkZ);
            if (removed != null) {
                releaseChunkSections(removed, dimension, chunkX, chunkZ);
                sectionPoolLeases.release(dimension, chunkX, chunkZ, removed.sectionCount());
            } else {
                sectionPoolLeases.release(dimension, chunkX, chunkZ);
            }
            if (dimension.equals(visibleDimension)) {
                geysers.removeChunk(chunkX, chunkZ);
                pistons.forgetChunk(chunkX, chunkZ);
                clientBlockEntities.forgetChunk(chunkX, chunkZ);
            }
            if (removed != null && terrainListener != null) terrainListener.columnUnloaded(chunkX, chunkZ);
        });
    }

    public int getMinHeight() {
        return minHeight;
    }

    public int getLastClientboundSectionCount() {
        return lastClientboundDimension.sectionCount();
    }

    public ClientboundDimensionData getLastClientboundDimension() {
        return lastClientboundDimension;
    }

    public boolean isLastClientboundDimensionChange(String dimension) {
        return !lastClientboundDimension.dimension().equals(dimension);
    }

    public void setLastClientboundDimension(String dimension, ac.cult.blocksim.environment.DimensionData type) {
        int minY = type.minY();
        lastClientboundDimension = new ClientboundDimensionData(dimension, minY, minY + type.height());
        clientboundUnresolved = false;
    }

    /**
     * Records a sent level whose dimension type the published generation cannot name. Its chunks have no known
     * geometry, so they stay out of the model until a later login or respawn resolves its type.
     */
    public void setLastClientboundUnresolved(String dimension) {
        lastClientboundDimension = new ClientboundDimensionData(
                dimension, lastClientboundDimension.minHeight(), lastClientboundDimension.maxHeight());
        clientboundUnresolved = true;
    }

    /** True while the last sent level's dimension type is unknown; its chunk geometry must not be used. */
    public boolean isClientboundUnresolved() {
        return clientboundUnresolved;
    }

    /** The client's current level, whose dimension type is unknown, holds nothing in the model. */
    public void setUnresolvedDimension(String dimension) {
        if (!visibleDimension.equals(dimension)) clientBlockEntities.clear();
        visibleDimension = dimension;
        levelUnresolved = true;
    }

    /** True while the client's current level has an unknown dimension type. */
    public boolean isLevelUnresolved() {
        return levelUnresolved;
    }

    public void setDimension(String dimension, ac.cult.blocksim.environment.DimensionData.Binding binding) {
        var type = binding.dimension();
        if (!visibleDimension.equals(dimension)) {
            clientBlockEntities.clear();
            clientGameTime = 0;
            clientRainLevel = 0.0F;
        }
        visibleDimension = dimension;
        visibleDimensionType = type;
        levelUnresolved = false;
        minHeight = type.minY();
        maxHeight = minHeight + type.height();
        fastLava = type.hasFastLava();
        hasSkyLight = type.hasSkyLight();
        clientEnvironment.configure(binding.environment().get(), clientClocks);
    }

    public String getVisibleDimension() {
        return visibleDimension;
    }

    public int clientSeaLevel() {
        return clientSeaLevel;
    }

    public void clientSeaLevel(int seaLevel) {
        clientSeaLevel = seaLevel;
    }

    public long clientBiomeZoomSeed() {
        return clientBiomeZoomSeed;
    }

    public void clientBiomeZoomSeed(long seed) {
        clientBiomeZoomSeed = seed;
    }

    public ac.cult.blocksim.environment.DimensionData getVisibleDimensionType() {
        return visibleDimensionType;
    }

    public boolean hasFastLava() {
        return fastLava;
    }

    public void clearChunksForDimension(String dimension) {
        Iterator<Map.Entry<Long, CachedChunk>> iterator = chunks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, CachedChunk> entry = iterator.next();
            long chunkPosition = entry.getKey();
            String chunkDimension = chunkDimensions.get(chunkPosition);
            if (!dimension.equals(chunkDimension)) {
                continue;
            }

            int chunkX = chunkXFromPosition(chunkPosition);
            int chunkZ = chunkZFromPosition(chunkPosition);
            chunkDimensions.remove(chunkPosition);

            releaseChunkSections(entry.getValue(), chunkDimension, chunkX, chunkZ);
            sectionPoolLeases.release(
                    chunkDimension, chunkX, chunkZ, entry.getValue().sectionCount());
            removePendingReinternSections(chunkX, chunkZ);
            if (dimension.equals(visibleDimension)) {
                geysers.removeChunk(chunkX, chunkZ);
                pistons.forgetChunk(chunkX, chunkZ);
                clientBlockEntities.forgetChunk(chunkX, chunkZ);
            }
            iterator.remove();
            chunkEpoch++;
            if (terrainListener != null) terrainListener.columnUnloaded(chunkX, chunkZ);
        }
    }

    public void clearChunks() {
        TerrainListener listener = terrainListener;
        long[] cleared = listener == null ? null : new long[chunks.size()];
        int count = 0;
        for (Map.Entry<Long, CachedChunk> entry : chunks.entrySet()) {
            long chunkPosition = entry.getKey();
            if (cleared != null) cleared[count++] = chunkPosition;
            String dimension = chunkDimensions.getOrDefault(chunkPosition, visibleDimension);
            releaseChunkSections(
                    entry.getValue(), dimension, chunkXFromPosition(chunkPosition), chunkZFromPosition(chunkPosition));
            pistons.forgetChunk(chunkXFromPosition(chunkPosition), chunkZFromPosition(chunkPosition));
        }
        sectionPoolLeases.clear();
        chunks.clear();
        chunkEpoch++;
        chunkDimensions.clear();
        pendingReinternSections.clear();
        geysers.clear();
        clientBlockEntities.clear();
        if (cleared != null) {
            for (long position : cleared)
                listener.columnUnloaded(chunkXFromPosition(position), chunkZFromPosition(position));
        }
    }

    public boolean hasRetainedSectionPoolChunk(int chunkX, int chunkZ) {
        return sectionPoolLeases.hasRetainedChunk(visibleDimension, chunkX, chunkZ);
    }

    public boolean hasRetainedChunk(int chunkX, int chunkZ) {
        return hasRetainedSectionPoolChunk(chunkX, chunkZ);
    }

    public int cachedChunkCount() {
        return chunks.size();
    }

    public SectionPoolLeaseTracker sectionPoolLeases() {
        return sectionPoolLeases;
    }

    public int getMaxHeight() {
        return maxHeight;
    }

    private static int stateIdOrAir(int state) {
        return state >= 0
                        && state
                                < ac.cult.blocksim.data.DataTables.defaults()
                                        .registry()
                                        .stateCount()
                ? state
                : 0;
    }
}
