package ac.grim.grimac.utils.latency;

import ac.grim.grimac.checks.impl.movement.GhostBlockMitigator;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.ClientBlockShapes;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.*;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import ac.grim.grimac.utils.nmsutil.NativeBlockCollisionHelper;
import ac.grim.grimac.utils.nmsutil.NmsBlockTags;
import ac.grim.grimac.utils.nmsutil.NmsIdentifierUtil;
import ac.grim.grimac.utils.nmsutil.NmsPalettedContainerUtil;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import org.bukkit.block.BlockFace;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.bukkit.util.Vector;

import java.util.*;

// Inspired by https://github.com/GeyserMC/Geyser/blob/master/connector/src/main/java/org/geysermc/connector/network/session/cache/ChunkCache.java
public class CompensatedWorld implements BlockGetter {
    private static final BlockState AIR_STATE = Block.stateById(0);
    public static final BlockData airData = ac.grim.grimac.network.protocol.util.SpigotConversionUtil.fromNmsBlockState(AIR_STATE).clone();
    private static final int RECENT_CLIENT_COLLISION_CHANGE_TICKS = 3;
    private static final int RECENT_CLIENT_FLUID_CHANGE_TICKS = 3;
    private static final int MAX_REINTERN_CONTENT_COMPARISONS_PER_TICK = 10;
    private static final int MAX_REINTERN_HASH_CALCULATIONS_PER_TICK = 50;
    private static final int MAX_REINTERN_ATTEMPTED_COMPARISONS_PER_TICK = 100;
    private static final byte RECENT_FLUID_WATER = 1;
    private static final byte RECENT_FLUID_LAVA = 1 << 1;
    public final GrimPlayer player;
    public final Map<Long, CachedChunk> chunks;
    private final Long2ObjectOpenHashMap<String> chunkDimensions = new Long2ObjectOpenHashMap<>();
    // Packet locations for blocks
    public Set<ShulkerData> openShulkerBoxes = new HashSet<>();
    public final CompensatedWorldPistons pistons;
    private final Long2IntOpenHashMap recentClientCollisionChanges = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap recentClientFluidChanges = new Long2IntOpenHashMap();
    private final Long2ByteOpenHashMap recentClientFluidChangeKinds = new Long2ByteOpenHashMap();
    private final Map<PendingReinternSection, PendingReinternState> pendingReinternSections = new LinkedHashMap<>();
    private final SectionPoolLeaseTracker sectionPoolLeases = new SectionPoolLeaseTracker();
    // 1.17 with datapacks, and 1.18, have negative world offset values
    private int minHeight = 0;
    private int maxHeight = 256;
    private String visibleDimension = "minecraft:overworld";
    private ClientboundDimensionData lastClientboundDimension = new ClientboundDimensionData(
            "minecraft:overworld",
            0,
            256
    );

    // When the player changes the blocks, they track what the server thinks the blocks are
    //
    // Pair of the block position and the owning list TO the actual block
    // The owning list is so that this info can be removed when the final list is processed
    private final Long2ObjectOpenHashMap<BlockPrediction> originalServerBlocks = new Long2ObjectOpenHashMap<>();
    // Blocks the client changed while placing or breaking blocks
    private List<BlockPos> currentlyChangedBlocks = new LinkedList<>();
    private final Map<Integer, List<BlockPos>> serverIsCurrentlyProcessingThesePredictions = new HashMap<>();
    private final Object2ObjectLinkedOpenHashMap<Pair<BlockPos, Action>, Vec3> unackedActions = new Object2ObjectLinkedOpenHashMap<>();
    private int clientPredictionSequence;
    private boolean isCurrentlyPredicting = false;
    public boolean isRaining = false;

    public CompensatedWorld(GrimPlayer player) {
        this.player = player;
        this.pistons = new CompensatedWorldPistons(player, this);
        chunks = new Long2ObjectOpenHashMap<>(81, 0.5f);
    }

    public static final class CachedChunk {
        private final CachedSection[] sections;
        private final int transaction;

        public CachedChunk(CachedSection[] sections, int transaction) {
            this.sections = sections;
            this.transaction = transaction;
        }

        public int sectionCount() {
            return sections.length;
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
        public static final java.util.concurrent.atomic.AtomicLong mutableCopyCounter = new java.util.concurrent.atomic.AtomicLong(0);

        private static final BlockState AIR_SECTION_STATE = Block.stateById(0);

        private final PalettedContainer<BlockState> states;
        private int nonEmptyBlockCount;
        private int fluidCount;
        private volatile boolean shared;
        private long lastMutatedNanos;
        private int poolReferences;
        private int cachedContentHash;
        private volatile boolean cachedContentHashValid;

        public CachedSection(PalettedContainer<BlockState> states) {
            this(states, false);
        }

        public CachedSection(PalettedContainer<BlockState> states, boolean shared) {
            this.states = states;
            this.shared = shared;
            this.lastMutatedNanos = System.nanoTime();
            recalcCounts();
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
            return new CachedSection(NmsPalettedContainerUtil.createBlockStates(AIR_SECTION_STATE));
        }

        public BlockState getState(int index) {
            BlockState state = states.get(index & 0xF, (index >> 8) & 0xF, (index >> 4) & 0xF);
            return state == null ? AIR_SECTION_STATE : state;
        }

        public BlockState setState(int index, BlockState state) {
            BlockState replacement = state == null ? AIR_SECTION_STATE : state;
            BlockState previous = states.getAndSet(index & 0xF, (index >> 8) & 0xF, (index >> 4) & 0xF, replacement);
            decrementCounts(previous);
            incrementCounts(replacement);
            this.lastMutatedNanos = System.nanoTime();
            this.cachedContentHashValid = false;
            return previous == null ? AIR_SECTION_STATE : previous;
        }

        public boolean isEmpty() {
            return nonEmptyBlockCount == 0;
        }

        public boolean hasFluid() {
            return fluidCount > 0;
        }

        public boolean maybeHas(java.util.function.Predicate<BlockState> predicate) {
            return states.maybeHas(predicate);
        }

        public long getLastMutatedNanos() {
            return lastMutatedNanos;
        }

        public void setLastMutatedNanos(long nanos) {
            this.lastMutatedNanos = nanos;
        }

        PalettedContainer<BlockState> states() {
            return states;
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

        private void recalcCounts() {
            nonEmptyBlockCount = 0;
            fluidCount = 0;
            states.count((state, count) -> {
                if (state != null && !state.isAir()) {
                    nonEmptyBlockCount += count;
                    if (!state.getFluidState().isEmpty()) {
                        fluidCount += count;
                    }
                }
            });
        }

        private void decrementCounts(BlockState state) {
            if (state != null && !state.isAir()) {
                nonEmptyBlockCount--;
                if (!state.getFluidState().isEmpty()) {
                    fluidCount--;
                }
            }
        }

        private void incrementCounts(BlockState state) {
            if (state != null && !state.isAir()) {
                nonEmptyBlockCount++;
                if (!state.getFluidState().isEmpty()) {
                    fluidCount++;
                }
            }
        }
    }

    public void startPredicting() {
        this.isCurrentlyPredicting = true;
    }

    public void advanceClientPredictionSequence() {
        clientPredictionSequence++;
    }

    public void resetClientPredictions() {
        clientPredictionSequence = 0;
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
        for (Iterator<Map.Entry<Integer, List<BlockPos>>> it = serverIsCurrentlyProcessingThesePredictions.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, List<BlockPos>> iter = it.next();
            if (iter.getKey() <= prediction) {
                applyBlockChanges(iter.getValue(), transaction);
                it.remove();
            }
        }
    }

    public void handlePredictionConfirmation(int prediction, GrimPlayer.TrackedTransaction transaction) {
        for (Iterator<Map.Entry<Integer, List<BlockPos>>> it = serverIsCurrentlyProcessingThesePredictions.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, List<BlockPos>> iter = it.next();
            if (iter.getKey() <= prediction) {
                applyBlockChanges(iter.getValue(), transaction);
                it.remove();
            }
        }
    }

    public void handleBlockBreakAck(BlockPos blockPos, int blockState, Action action, boolean accepted) {
        if (!accepted || action != Action.START_DESTROY_BLOCK || !unackedActions.containsKey(new Pair<>(blockPos, action))) {
            player.sendTransaction(); // This packet actually matters
            player.latencyUtils.addRealTimeTaskNow(() -> { if (unackedActions.containsKey(new Pair<>(blockPos, action))) {
                    Vec3 playerPos = unackedActions.remove(new Pair<>(blockPos, action));
                    handleAck(blockPos, blockState, playerPos);
                }
            });
        } else {
            unackedActions.remove(new Pair<>(blockPos, action));
        }

        player.latencyUtils.addRealTimeTaskNow(() -> { while (unackedActions.size() >= 50) {
                this.unackedActions.removeFirst();
            }
        });
    }

    public void handleServerBlockUpdate(BlockPos pos, BlockState state, GrimPlayer.TrackedTransaction transaction) {
        player.latencyUtils.addRealTimeTask(transaction.transaction(), () -> handleServerBlockUpdate(pos, state, transaction.transaction()));
    }

    public void handleServerBlockUpdate(BlockPos pos, BlockState state, int transaction) {
        updateBlock(pos.getX(), pos.getY(), pos.getZ(), state);
    }

    private void applyBlockChanges(List<BlockPos> toApplyBlocks, int transaction) {
        // The transaction is sent after the block-ack packet in the same clientbound bundle.
        // Vanilla processes bundle sub-packets in order on the client thread, then replies to
        // the ping, so this marker means the ack and any bundled block updates are applied.
        player.latencyUtils.addRealTimeTask(transaction, () -> toApplyBlocks.forEach(vector3i -> {
            BlockPrediction predictionData = originalServerBlocks.get(vector3i.asLong());

            // We are the last to care about this prediction, remove it to stop memory leak
            // Block changes are allowed to execute out of order, because it actually doesn't matter
            if (predictionData != null && predictionData.getForBlockUpdate() == toApplyBlocks) {
                originalServerBlocks.remove(vector3i.asLong());
                handleAck(vector3i, predictionData.getOriginalBlockId(), predictionData.getPlayerPosition());
            }
        }));
    }

    private void applyBlockChanges(List<BlockPos> toApplyBlocks, GrimPlayer.TrackedTransaction transaction) {
        // The transaction is sent after the block-ack packet in the same clientbound bundle.
        // Vanilla processes bundle sub-packets in order on the client thread, then replies to
        // the ping, so the tracked transaction is the marker for applying the ack here.
        player.latencyUtils.addRealTimeTask(transaction.transaction(), () -> toApplyBlocks.forEach(vector3i -> {
            BlockPrediction predictionData = originalServerBlocks.get(vector3i.asLong());

            // We are the last to care about this prediction, remove it to stop memory leak
            // Block changes are allowed to execute out of order, because it actually doesn't matter
            if (predictionData != null && predictionData.getForBlockUpdate() == toApplyBlocks) {
                originalServerBlocks.remove(vector3i.asLong());
                handleAck(vector3i, predictionData.getOriginalBlockId(), predictionData.getPlayerPosition());
            }
        }));
    }

    private void handleAck(BlockPos vector3i, int originalBlockId, Vec3 playerPosition) {
        // make sure that this "delayed" block update is always known to the ghost block mitigator
        final GhostBlockMitigator mitigator = player.getGhostBlockMitigator();
        mitigator.handleNewBlock(vector3i);

        // If we need to change the world block state
        BlockState state = Block.stateById(originalBlockId);
        if (!getBlockStateAt(vector3i).equals(state)) {
            updateBlock(vector3i.getX(), vector3i.getY(), vector3i.getZ(), state);

            if (playerPosition == null) {
                // Fuck you player. You tried teleporting and then causing an illegal block change to try to validate your illegal teleport.
                final ac.grim.grimac.manager.player.SetbackTeleportUtil setbackUtil = player.getSetbackTeleportUtil();
                setbackUtil.executeForceResync("block ack");
            } else if (ClientBlockShapes.movement(
                    player,
                    ac.grim.grimac.network.protocol.util.SpigotConversionUtil.fromNmsBlockState(state),
                    vector3i.getX(),
                    vector3i.getY(),
                    vector3i.getZ())
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

    public void handleBlockBreakPrediction(NmsPacketUtil.PlayerActionData digging) {
        // Current runtime does not use the legacy delayed block-break ack path.
    }

    public void stopPredicting(ServerboundUseItemOnPacket packet) {
        stopPredicting(NmsPacketUtil.readUseItemOn(packet).sequence());
    }

    public void stopPredicting(ServerboundUseItemPacket packet) {
        stopPredicting(NmsPacketUtil.readUseItem(packet).sequence());
    }

    public void stopPredicting(ServerboundPlayerActionPacket packet) {
        stopPredicting(NmsPacketUtil.readPlayerAction(packet).sequence());
    }

    public void stopPredicting(int ignoredWireSequence) {
        this.isCurrentlyPredicting = false; // We aren't in a block place or use item

        if (this.currentlyChangedBlocks.isEmpty()) return; // Nothing to change

        List<BlockPos> toApplyBlocks = this.currentlyChangedBlocks; // We must now track the client applying the server predicted blocks
        this.currentlyChangedBlocks = new LinkedList<>(); // Reset variable without changing original

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

    public void updateBlock(BlockPos pos, BlockData state) {
        updateBlock(pos.getX(), pos.getY(), pos.getZ(), toNmsState(state));
    }

    public void ensureValidationChunkLoaded(int chunkX, int chunkZ) {
        long chunkPosition = chunkPositionToLong(chunkX, chunkZ);
        if (chunks.containsKey(chunkPosition)) {
            return;
        }

        int sectionCount = lastClientboundDimension.sectionCount();
        sectionPoolLeases.retain(visibleDimension, chunkX, chunkZ, sectionCount);
        chunks.put(chunkPosition, new CachedChunk(new CachedSection[sectionCount], player.lastTransactionSent.get()));
        chunkDimensions.put(chunkPosition, visibleDimension);
    }

    public void markForBlockPrediction(BlockPos pos) {
        currentlyChangedBlocks.add(pos);
    }

    public boolean hasPendingBlockPrediction(BlockPos pos) {
        return originalServerBlocks.containsKey(pos.asLong());
    }

    public BlockData updateBlock(int x, int y, int z, int combinedID) {
        return updateBlock(x, y, z, Block.stateById(combinedID));
    }

    public boolean wasClientCollisionChangedRecently(BlockPos pos) {
        return recentClientCollisionChanges.containsKey(pos.asLong());
    }

    public boolean hasRecentClientCollisionChanges(SimpleCollisionBox queryBox) {
        int minX = GrimMath.floor(queryBox.minX);
        int minY = GrimMath.floor(queryBox.minY);
        int minZ = GrimMath.floor(queryBox.minZ);
        int maxX = GrimMath.ceil(queryBox.maxX) - 1;
        int maxY = GrimMath.ceil(queryBox.maxY) - 1;
        int maxZ = GrimMath.ceil(queryBox.maxZ) - 1;
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
        int minX = GrimMath.floor(queryBox.minX);
        int minY = GrimMath.floor(queryBox.minY);
        int minZ = GrimMath.floor(queryBox.minZ);
        int maxX = GrimMath.ceil(queryBox.maxX) - 1;
        int maxY = GrimMath.ceil(queryBox.maxY) - 1;
        int maxZ = GrimMath.ceil(queryBox.maxZ) - 1;
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

    public List<SimpleCollisionBox> getDynamicBlockCollisionUncertaintyBoxes(SimpleCollisionBox queryBox) {
        List<SimpleCollisionBox> boxes = new ArrayList<>(pistons.getDynamicBlockCollisionUncertaintyBoxes(queryBox));

        for (ShulkerData data : openShulkerBoxes) {
            if (!data.isAnimationActive()) {
                continue;
            }

            SimpleCollisionBox box = openShulkerCollisionBox(data);
            if (box.isCollided(queryBox)) {
                boxes.add(box);
            }
        }

        return boxes;
    }

    public boolean hasOpenShulkerCollision(SimpleCollisionBox queryBox) {
        for (ShulkerData data : openShulkerBoxes) {
            if (data.isAnimationActive() && openShulkerCollisionBox(data).isCollided(queryBox)) {
                return true;
            }
        }

        return false;
    }

    private SimpleCollisionBox openShulkerCollisionBox(ShulkerData data) {
        return data.getCollision().copy().expand(data.getFacing(player));
    }

    void markRecentClientCollisionChange(BlockPos pos) {
        recentClientCollisionChanges.put(pos.asLong(), RECENT_CLIENT_COLLISION_CHANGE_TICKS);
    }

    private void markRecentClientFluidChange(BlockPos pos, BlockState oldState, BlockState newState) {
        byte kinds = recentFluidChangeKinds(oldState, newState);
        if (kinds == 0) {
            return;
        }

        long key = pos.asLong();
        recentClientFluidChanges.put(key, RECENT_CLIENT_FLUID_CHANGE_TICKS);
        recentClientFluidChangeKinds.put(key, kinds);
    }

    private void markRecentClientCollisionChange(BlockPos pos, BlockState oldState, BlockState newState) {
        if (oldState == null || newState == null) {
            markRecentClientCollisionChange(pos);
            return;
        }

        if (oldState.getBlock() == Blocks.MOVING_PISTON || newState.getBlock() == Blocks.MOVING_PISTON || !collisionShapesMatch(pos, oldState, newState)) {
            markRecentClientCollisionChange(pos);
        }
    }

    private boolean collisionShapesMatch(BlockPos pos, BlockState oldState, BlockState newState) {
        List<AABB> oldBoxes = NativeBlockCollisionHelper.getCollisionShape(player, oldState, pos.getX(), pos.getY(), pos.getZ()).toAabbs();
        List<AABB> newBoxes = NativeBlockCollisionHelper.getCollisionShape(player, newState, pos.getX(), pos.getY(), pos.getZ()).toAabbs();
        if (oldBoxes.size() != newBoxes.size()) {
            return false;
        }

        for (int i = 0; i < oldBoxes.size(); i++) {
            AABB oldBox = oldBoxes.get(i);
            AABB newBox = newBoxes.get(i);
            if (Double.compare(oldBox.minX, newBox.minX) != 0
                    || Double.compare(oldBox.minY, newBox.minY) != 0
                    || Double.compare(oldBox.minZ, newBox.minZ) != 0
                    || Double.compare(oldBox.maxX, newBox.maxX) != 0
                    || Double.compare(oldBox.maxY, newBox.maxY) != 0
                    || Double.compare(oldBox.maxZ, newBox.maxZ) != 0) {
                return false;
            }
        }

        return true;
    }

    private byte recentFluidChangeKinds(BlockState oldState, BlockState newState) {
        if (oldState == null || newState == null) {
            return (byte) (RECENT_FLUID_WATER | RECENT_FLUID_LAVA);
        }

        FluidState oldFluid = oldState.getFluidState();
        FluidState newFluid = newState.getFluidState();
        if (oldFluid.equals(newFluid)) {
            return 0;
        }

        byte result = 0;
        if (oldFluid.is(FluidTags.WATER) || newFluid.is(FluidTags.WATER)) {
            result |= RECENT_FLUID_WATER;
        }
        if (oldFluid.is(FluidTags.LAVA) || newFluid.is(FluidTags.LAVA)) {
            result |= RECENT_FLUID_LAVA;
        }
        return result;
    }

    private void tickRecentClientCollisionChanges() {
        recentClientCollisionChanges.long2IntEntrySet().removeIf(entry -> {
            int ticks = entry.getIntValue() - 1;
            if (ticks <= 0) {
                return true;
            }
            entry.setValue(ticks);
            return false;
        });
    }

    private void tickRecentClientFluidChanges() {
        recentClientFluidChanges.long2IntEntrySet().removeIf(entry -> {
            int ticks = entry.getIntValue() - 1;
            if (ticks <= 0) {
                recentClientFluidChangeKinds.remove(entry.getLongKey());
                return true;
            }
            entry.setValue(ticks);
            return false;
        });
    }

    public BlockData updateBlock(int x, int y, int z, BlockState newState) {
        BlockPos asVector = new BlockPos(x, y, z);
        BlockPrediction prediction = originalServerBlocks.get(asVector.asLong());
        BlockData original = getBlockDataAt(asVector);

        if (isCurrentlyPredicting) {
            if (prediction == null) {
                boolean isPlayerTryingToDisableGrim = player.getSetbackTeleportUtil().shouldBlockMovement();
                int serverState = Block.getId(getBlockStateAt(asVector));
                originalServerBlocks.put(asVector.asLong(), new BlockPrediction(currentlyChangedBlocks, asVector, serverState, Block.getId(newState), isPlayerTryingToDisableGrim ? null : new Vec3(player.x, player.y, player.z))); // Remember server controlled block type
            } else {
                prediction.setForBlockUpdate(currentlyChangedBlocks); // Block existing there was placed by client, mark block to have a new prediction
                prediction.setPredictedBlockId(Block.getId(newState));
            }
            currentlyChangedBlocks.add(asVector);
        }

        final GhostBlockMitigator ghostBlockMitigator = player.getGhostBlockMitigator();

        if (!isCurrentlyPredicting && prediction != null) {
            // Reconciliation must not overwrite the local predicted state while the prediction is still pending.
            // Only update the stored rollback target; the actual local block stays client-predicted until
            // confirmation handling decides whether to keep it or restore the original server state.
            prediction.setOriginalBlockId(Block.getId(newState));
            ghostBlockMitigator.handleUpdateServerBlockState(asVector, ac.grim.grimac.network.protocol.util.SpigotConversionUtil.fromNmsBlockState(newState).clone());
            return original;
        }

        ghostBlockMitigator.handleNewBlock(asVector);

        // This works because of how we optimized wrappedblockstate (avoid messing with inner tick predictions)
        if (getBlockStateAt(asVector).equals(newState)) return original;

        player.checkManager.getSimulationProcessor().handleBlockChange(asVector, original.clone());
        player.checkManager.getSimulationProcessor().handleBlockChange(asVector, ac.grim.grimac.network.protocol.util.SpigotConversionUtil.fromNmsBlockState(newState).clone());
        markRecentClientCollisionChange(asVector, toNmsState(original), newState);
        markRecentClientFluidChange(asVector, toNmsState(original), newState);

        applyBlockChangeRawDANGER(x, y, z, newState);
        return original;
    }

    public void applyBlockChangeRawDANGER(int x, int y, int z, int combinedID) {
        applyBlockChangeRawDANGER(x, y, z, Block.stateById(combinedID));
    }

    public void applyBlockChangeRawDANGER(int x, int y, int z, BlockState combinedState) {
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

            section.setState(CachedChunk.index(x & 0xF, offsetY & 0xF, z & 0xF), combinedState);
            queuePendingReintern(chunkX, sectionIndex, chunkZ, section);
            pistons.handleBlockStateApplied(new BlockPos(x, y, z), combinedState);
        }
    }

    public void removeInvalidPistonLikeStuff() {
        removeInvalidPistonLikeStuff(0);
    }

    public void onClientTickEnd() {
        removeInvalidPistonLikeStuff();
        processPendingReinterns();
    }

    private void processPendingReinterns() {
        if (pendingReinternSections.isEmpty()) return;

        long now = System.nanoTime();
        SectionPool.ComparisonBudget comparisonBudget = SectionPool.ComparisonBudget.limited(
                MAX_REINTERN_CONTENT_COMPARISONS_PER_TICK,
                MAX_REINTERN_HASH_CALCULATIONS_PER_TICK,
                MAX_REINTERN_ATTEMPTED_COMPARISONS_PER_TICK);
        List<PendingReinternCandidate> candidates = new ArrayList<>(pendingReinternSections.size());
        Iterator<Map.Entry<PendingReinternSection, PendingReinternState>> iterator = pendingReinternSections.entrySet().iterator();
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

            candidates.add(new PendingReinternCandidate(key, state, column, section, section.getLastMutatedNanos()));
        }

        candidates.sort(Comparator.comparingLong(PendingReinternCandidate::lastMutatedNanos));
        for (PendingReinternCandidate candidate : candidates) {
            CachedSection section = candidate.section();
            if (!SectionPool.isStableForReintern(section, now)) {
                break;
            }

            String dimension = dimensionForChunk(candidate.key().chunkX, candidate.key().chunkZ);
            SectionPool pool = SectionPool.forChunk(dimension, candidate.key().chunkX, candidate.key().sectionIndex, candidate.key().chunkZ);
            SectionPool.ReinternResult result = pool.tryReinternRetained(
                    section, candidate.state().nextComparisonIndex(), comparisonBudget);
            if (!result.complete()) {
                pendingReinternSections.put(candidate.key(), new PendingReinternState(section, result.nextComparisonIndex()));
                break;
            }

            CachedSection reinterned = result.section();
            if (reinterned != section) {
                candidate.column().setSection(candidate.key().sectionIndex, reinterned);
            }
            pendingReinternSections.remove(candidate.key());
        }
    }

    private void queuePendingReintern(int chunkX, int sectionIndex, int chunkZ, CachedSection section) {
        pendingReinternSections.put(new PendingReinternSection(chunkX, sectionIndex, chunkZ), new PendingReinternState(section, 0));
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

    private String dimensionForChunk(int chunkX, int chunkZ) {
        return chunkDimensions.getOrDefault(chunkPositionToLong(chunkX, chunkZ), visibleDimension);
    }

    private void removePendingReinternSections(int chunkX, int chunkZ) {
        pendingReinternSections.keySet().removeIf(key -> key.chunkX == chunkX && key.chunkZ == chunkZ);
    }

    private void removePendingReinternSection(int chunkX, int sectionIndex, int chunkZ) {
        pendingReinternSections.remove(new PendingReinternSection(chunkX, sectionIndex, chunkZ));
    }

    private record PendingReinternSection(int chunkX, int sectionIndex, int chunkZ) {
    }

    private record PendingReinternState(CachedSection section, int nextComparisonIndex) {
    }

    private record PendingReinternCandidate(PendingReinternSection key,
                                            PendingReinternState state,
                                            CachedChunk column,
                                            CachedSection section,
                                            long lastMutatedNanos) {
    }

    public void removeInvalidPistonLikeStuff(int transactionId) {
        if (transactionId == 0) {
            // End-of-client-tick path: advance time-based state instead of
            // pruning by transaction watermark.
            tickRecentClientCollisionChanges();
            tickRecentClientFluidChanges();
            pistons.tickClientTickEnd();
            openShulkerBoxes.removeIf(ShulkerData::tickIfGuaranteedFinished);
        } else {
            // Transaction path: drop entries the client has provably seen.
            pistons.removeSentBefore(transactionId);
            openShulkerBoxes.removeIf(box -> box.isClosing() && box.lastTransactionSent < transactionId);
        }
        pruneOrphanedShulkerBoxes();
    }

    // Drop shulker boxes whose backing block or entity no longer exists.
    private void pruneOrphanedShulkerBoxes() {
        openShulkerBoxes.removeIf(box -> box.blockPos != null
                ? !NmsBlockTags.isShulkerBox(player.compensatedWorld.getBlockDataAt(box.blockPos).getMaterial())
                : !player.compensatedEntities.entityMap.containsValue(box.entity));
    }

    public void clearPistonLikeState() {
        pistons.clear();
        openShulkerBoxes.clear();
        recentClientCollisionChanges.clear();
        recentClientFluidChanges.clear();
        recentClientFluidChangeKinds.clear();
    }

    public PistonPushes tickPlayerInPistonPushingArea(SimulationContext context) {
        // Occurs on player login
        if (player.boundingBox == null) return new PistonPushes(new SimpleCollisionBox(), Collections.emptySet());

        SimpleCollisionBox queryBox = pistons.createPistonQueryBox(context);
        PistonPushes pistonResult = pistons.tickPlayerInPistonPushingArea(queryBox);
        SimpleCollisionBox shulkerPushes = accumulateShulkerPushes(queryBox);

        SimpleCollisionBox pistonPush = pistonResult.getPistonPush();
        SimpleCollisionBox combinedPushes = pistonPush.copy().union(shulkerPushes);
        return new PistonPushes(combinedPushes, pistonPush, shulkerPushes, pistonResult.getSlimeBlockLaunches());
    }

    // Expands the query box by every colliding open shulker box lid and
    // accumulates the resulting entity push direction.
    private SimpleCollisionBox accumulateShulkerPushes(SimpleCollisionBox queryBox) {
        SimpleCollisionBox shulkerPushes = new SimpleCollisionBox();
        for (ShulkerData data : openShulkerBoxes) {
            if (!data.canPushEntities()) {
                continue;
            }

            BlockFace pushDirection = data.getFacing(player);
            if (queryBox.isCollided(openShulkerCollisionBox(data))) {
                queryBox.expand(Math.abs(pushDirection.getModX()), Math.abs(pushDirection.getModY()), Math.abs(pushDirection.getModZ()));
                shulkerPushes.expandToCoordinate(pushDirection.getModX(), pushDirection.getModY(), pushDirection.getModZ());
            }
        }
        return shulkerPushes;
    }

    public BlockData getBlockDataAt(BlockPos vector3i) {
        return getBlockDataAt(vector3i.getX(), vector3i.getY(), vector3i.getZ());
    }

    public BlockData getBlockDataAt(int x, int y, int z) {
        BlockState state = getBlockStateAt(x, y, z);
        return state == null ? airData.clone() : ac.grim.grimac.network.protocol.util.SpigotConversionUtil.fromNmsBlockState(state).clone();
    }

    public BlockState getBlockStateAt(BlockPos vector3i) {
        return getBlockStateAt(vector3i.getX(), vector3i.getY(), vector3i.getZ());
    }

    public BlockState getBlockStateAt(int x, int y, int z) {
        try {
            CachedChunk column = getChunk(x >> 4, z >> 4);

            y -= minHeight;
            if (column == null || y < 0 || (y >> 4) >= column.sectionCount()) return AIR_STATE;

            CachedSection section = column.getSection(y >> 4);
            if (section != null) return section.getState(CachedChunk.index(x & 0xF, y & 0xF, z & 0xF));
        } catch (Exception ignored) {
        }

        return AIR_STATE;
    }

    public FluidState getFluidStateAt(BlockPos vector3i) {
        return getFluidStateAt(vector3i.getX(), vector3i.getY(), vector3i.getZ());
    }

    public FluidState getFluidStateAt(int x, int y, int z) {
        return getBlockStateAt(x, y, z).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos blockPos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos blockPos) {
        return getBlockStateAt(blockPos);
    }

    @Override
    public BlockState getBlockStateIfLoaded(BlockPos blockPos) {
        return getBlockStateAt(blockPos);
    }

    @Override
    public FluidState getFluidIfLoaded(BlockPos blockPos) {
        return isChunkLoaded(blockPos.getX() >> 4, blockPos.getZ() >> 4) ? getFluidStateAt(blockPos) : Fluids.EMPTY.defaultFluidState();
    }

    @Override
    public FluidState getFluidState(BlockPos blockPos) {
        return getFluidStateAt(blockPos);
    }

    @Override
    public int getHeight() {
        return maxHeight - minHeight;
    }

    @Override
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

    public void addToCache(CachedChunk chunk, int chunkX, int chunkZ) {
        addToCache(chunk, visibleDimension, chunkX, chunkZ);
    }

    public void addToCache(CachedChunk chunk, String dimension, int chunkX, int chunkZ) {
        addToCache(chunk, dimension, player.lastTransactionSent.get(), chunkX, chunkZ);
    }

    public void addToCache(CachedChunk chunk, String dimension, int transaction, int chunkX, int chunkZ) {
        long chunkPosition = chunkPositionToLong(chunkX, chunkZ);
        player.latencyUtils.addRealTimeTask(transaction, () -> {
            CachedChunk previous = chunks.get(chunkPosition);
            String previousDimension = chunkDimensions.get(chunkPosition);
            if (previous != null && dimension.equals(previousDimension) && previous.getTransaction() > transaction) {
                return;
            }

            sectionPoolLeases.retain(dimension, chunkX, chunkZ, chunk.sectionCount());
            internChunkSections(chunk, dimension, chunkX, chunkZ);
            removePendingReinternSections(chunkX, chunkZ);
            previous = chunks.put(chunkPosition, chunk);
            previousDimension = chunkDimensions.put(chunkPosition, dimension);
            if (previous != null) {
                String releaseDimension = previousDimension == null ? dimension : previousDimension;
                releaseChunkSections(previous, releaseDimension, chunkX, chunkZ);
                if (previous.sectionCount() != chunk.sectionCount() || !releaseDimension.equals(dimension)) {
                    sectionPoolLeases.release(releaseDimension, chunkX, chunkZ, previous.sectionCount());
                }
            }
            preservePendingPredictions(chunk, chunkX, chunkZ);
        });
    }

    public void mergeIntoCache(CachedChunk existingColumn, CachedSection[] toMerge, int chunkX, int chunkZ) {
        mergeIntoCache(existingColumn, toMerge, visibleDimension, player.lastTransactionSent.get(), chunkX, chunkZ);
    }

    public void mergeIntoCache(CachedChunk existingColumn, CachedSection[] toMerge, String dimension, int transaction, int chunkX, int chunkZ) {
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
                sections[sectionIndex] = SectionPool.forChunk(dimension, chunkX, sectionIndex, chunkZ).internRetained(section);
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

    private void releaseMergedChunkSections(CachedChunk existingColumn, CachedSection[] toMerge, String dimension, int chunkX, int chunkZ) {
        for (int sectionIndex = 0; sectionIndex < Math.min(existingColumn.sectionCount(), toMerge.length); sectionIndex++) {
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

            BlockState serverState = getCachedChunkState(chunk, position);
            if (serverState != null) {
                prediction.setOriginalBlockId(Block.getId(serverState));
            }

            setCachedChunkState(chunk, position, Block.stateById(prediction.getPredictedBlockId()));
        }
    }

    private BlockState getCachedChunkState(CachedChunk chunk, BlockPos position) {
        int offsetY = position.getY() - minHeight;
        int sectionIndex = offsetY >> 4;
        if (sectionIndex < 0 || sectionIndex >= chunk.sectionCount()) {
            return null;
        }

        CachedSection section = chunk.getSection(sectionIndex);
        if (section == null) {
            return AIR_STATE;
        }
        return section.getState(CachedChunk.index(position.getX() & 0xF, offsetY & 0xF, position.getZ() & 0xF));
    }

    private void setCachedChunkState(CachedChunk chunk, BlockPos position, BlockState state) {
        int offsetY = position.getY() - minHeight;
        int sectionIndex = offsetY >> 4;
        CachedSection section = chunk.getOrCreateSection(sectionIndex);
        if (section == null) return;
        if (section.isShared()) {
            section = prepareSectionForWrite(position.getX() >> 4, sectionIndex, position.getZ() >> 4, section);
            chunk.setSection(sectionIndex, section);
        }
        section.setState(CachedChunk.index(position.getX() & 0xF, offsetY & 0xF, position.getZ() & 0xF), state);
        queuePendingReintern(position.getX() >> 4, sectionIndex, position.getZ() >> 4, section);
    }

    public Material getMaterialAt(double x, double y, double z) {
        return getBlockDataAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)).getMaterial();
    }

    public Material getMaterialAt(int x, int y, int z) {
        return getBlockDataAt(x, y, z).getMaterial();
    }

    public Material getMaterialAt(BlockPos position) {
        return getBlockDataAt(position).getMaterial();
    }

    public BlockData getBlockDataAt(double x, double y, double z) {
        return getBlockDataAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public double getFluidLevelAt(int x, int y, int z) {
        return Math.max(getWaterFluidLevelAt(x, y, z), getLavaFluidLevelAt(x, y, z));
    }

    public boolean isWaterSourceBlock(int x, int y, int z) {
        return getFluidStateAt(x, y, z).isSourceOfType(Fluids.WATER);
    }

    public double getLavaFluidLevelAt(net.minecraft.core.BlockPos pos) {
        return getLavaFluidLevelAt(pos.getX(), pos.getY(), pos.getZ());
    }

    public double getLavaFluidLevelAt(int x, int y, int z) {
        FluidState fluidState = getFluidStateAt(x, y, z);
        if (!fluidState.is(FluidTags.LAVA)) return 0;
        if (getFluidStateAt(x, y + 1, z).is(FluidTags.LAVA)) return 1;
        return fluidState.getAmount() / 9f;
    }

    public double getWaterFluidLevelAt(net.minecraft.core.BlockPos position) {
        return getWaterFluidLevelAt(position.getX(), position.getY(), position.getZ());
    }

    public double getWaterFluidLevelAt(double x, double y, double z) {
        return getWaterFluidLevelAt(GrimMath.floor(x), GrimMath.floor(y), GrimMath.floor(z));
    }

    public double getWaterFluidLevelAt(int x, int y, int z) {
        FluidState fluidState = getFluidStateAt(x, y, z);
        if (!fluidState.is(FluidTags.WATER)) return 0;

        // If water has water above it, it's block height is 1, even if it's waterlogged
        if (getFluidStateAt(x, y + 1, z).is(FluidTags.WATER)) {
            return 1;
        }

        return fluidState.getAmount() / 9f;
    }

    public void removeChunkLater(int chunkX, int chunkZ) {
        removeChunkLater(visibleDimension, chunkX, chunkZ, player.lastTransactionSent.get());
    }

    public void removeChunkLater(int chunkX, int chunkZ, int transaction) {
        removeChunkLater(visibleDimension, chunkX, chunkZ, transaction);
    }

    public void removeChunkLater(String dimension, int chunkX, int chunkZ, int transaction) {
        long chunkPosition = chunkPositionToLong(chunkX, chunkZ);
        player.latencyUtils.addRealTimeTask(transaction, () -> {
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
            chunkDimensions.remove(chunkPosition);
            removePendingReinternSections(chunkX, chunkZ);
            if (removed != null) {
                releaseChunkSections(removed, dimension, chunkX, chunkZ);
                sectionPoolLeases.release(dimension, chunkX, chunkZ, removed.sectionCount());
            } else {
                sectionPoolLeases.release(dimension, chunkX, chunkZ);
            }
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

    public boolean isLastClientboundDimensionChange(CommonPlayerSpawnInfo spawnInfo) {
        return !lastClientboundDimension.dimension().equals(NmsIdentifierUtil.resourceKey(spawnInfo.dimension()));
    }

    public void setLastClientboundDimension(CommonPlayerSpawnInfo spawnInfo) {
        int minY = spawnInfo.dimensionType().value().minY();
        lastClientboundDimension = new ClientboundDimensionData(
                NmsIdentifierUtil.resourceKey(spawnInfo.dimension()),
                minY,
                minY + spawnInfo.dimensionType().value().height()
        );
    }

    public void setDimension(CommonPlayerSpawnInfo spawnInfo) {
        visibleDimension = NmsIdentifierUtil.resourceKey(spawnInfo.dimension());
        minHeight = spawnInfo.dimensionType().value().minY();
        maxHeight = minHeight + spawnInfo.dimensionType().value().height();
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
            sectionPoolLeases.release(chunkDimension, chunkX, chunkZ, entry.getValue().sectionCount());
            removePendingReinternSections(chunkX, chunkZ);
            iterator.remove();
        }
    }

    public void clearChunks() {
        for (Map.Entry<Long, CachedChunk> entry : chunks.entrySet()) {
            long chunkPosition = entry.getKey();
            String dimension = chunkDimensions.getOrDefault(chunkPosition, visibleDimension);
            releaseChunkSections(entry.getValue(), dimension, chunkXFromPosition(chunkPosition), chunkZFromPosition(chunkPosition));
        }
        sectionPoolLeases.clear();
        chunks.clear();
        chunkDimensions.clear();
        pendingReinternSections.clear();
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

    public BlockData getBlockDataAt(Vector aboveCCWPos) {
        return getBlockDataAt(aboveCCWPos.getX(), aboveCCWPos.getY(), aboveCCWPos.getZ());
    }

    private static BlockState toNmsState(BlockData data) {
        return NmsBlockTags.toNmsState(data);
    }
}
