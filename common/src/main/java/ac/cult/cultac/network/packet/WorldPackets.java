package ac.cult.cultac.network.packet;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.ClientClocks;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import ac.cult.cultac.protocol.value.BlockPos;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;

/** Consumed world data; block states use the bundled model's numeric IDs. */
public final class WorldPackets {
    private WorldPackets() {}

    public record BlockUpdate(BlockPos position, int state) implements ClientboundPacket {
        private static final int STATE_COUNT = DataTables.defaults().registry().stateCount();

        public BlockUpdate {
            position = position.immutable();
            // Section updates use nullable IdMap.byId; Block.getId(null) is air.
            // Single-block decoding retains its separate byIdOrThrow contract.
            state = normalizeState(state);
        }

        static int normalizeState(int state) {
            return state < 0 || state >= STATE_COUNT ? 0 : state;
        }

        static boolean hasCompactStateIds() {
            return STATE_COUNT <= (1 << 20);
        }
    }

    public record SectionBlocksUpdate(List<BlockUpdate> updates) implements ClientboundPacket {
        public SectionBlocksUpdate {
            updates = updates instanceof PackedSectionUpdates ? updates : List.copyOf(updates);
        }

        /** ClientboundSectionBlocksUpdatePacket retains local coordinates and states until application. */
        public static SectionBlocksUpdate readSection(
                long section, int count, LongSupplier input, IntUnaryOperator states) {
            return new SectionBlocksUpdate(PackedSectionUpdates.read(section, count, input, states));
        }

        public int size() {
            return updates.size();
        }

        public int x(int index) {
            return updates instanceof PackedSectionUpdates packed
                    ? packed.x(index)
                    : updates.get(index).position().getX();
        }

        public int y(int index) {
            return updates instanceof PackedSectionUpdates packed
                    ? packed.y(index)
                    : updates.get(index).position().getY();
        }

        public int z(int index) {
            return updates instanceof PackedSectionUpdates packed
                    ? packed.z(index)
                    : updates.get(index).position().getZ();
        }

        public void runUpdates(BlockUpdateConsumer consumer) {
            Objects.requireNonNull(consumer);
            if (updates instanceof PackedSectionUpdates packed) packed.runUpdates(consumer);
            else
                for (var update : updates)
                    consumer.accept(
                            update.position().getX(),
                            update.position().getY(),
                            update.position().getZ(),
                            update.state());
        }
    }

    @FunctionalInterface
    public interface BlockUpdateConsumer {
        void accept(int x, int y, int z, int state);
    }

    public record TimeUpdate(long gameTime, Map<String, ClientClocks.State> clocks) implements ClientboundPacket {
        public TimeUpdate {
            clocks = Map.copyOf(clocks);
        }
    }

    public record EnabledFeatures(Set<String> features) implements ClientboundPacket {
        public EnabledFeatures {
            features = Set.copyOf(features);
        }
    }

    /** Only peaceful difficulty affects the action contract; the lock is a settings/UI field. */
    public record Difficulty(boolean peaceful) implements ClientboundPacket {}

    /** Only recipe input membership affects client block interactions. */
    public record RecipeInputs(Map<String, Set<String>> itemSets) implements ClientboundPacket {
        public RecipeInputs {
            var copy = new HashMap<String, Set<String>>();
            itemSets.forEach((key, items) -> copy.put(key, Set.copyOf(items)));
            itemSets = Map.copyOf(copy);
        }
    }

    public record BiomeChunk(int x, int z, ByteBuffer sections, List<SectionSnapshot> decodedSections) {
        public BiomeChunk(int x, int z, ByteBuffer sections) {
            this(x, z, sections, null);
        }

        public BiomeChunk {
            sections = sections == null ? null : sections.asReadOnlyBuffer();
            decodedSections = decodedSections == null ? null : List.copyOf(decodedSections);
            if ((sections == null) == (decodedSections == null))
                throw new IllegalArgumentException("One section representation required");
        }

        @Override
        public ByteBuffer sections() {
            return sections == null ? SectionSnapshot.encode(decodedSections) : sections.asReadOnlyBuffer();
        }
    }

    public record ChunkBiomes(List<BiomeChunk> chunks) implements ClientboundPacket {
        public ChunkBiomes {
            chunks = List.copyOf(chunks);
        }
    }

    /** Owned client-visible block entity data, decoded at the platform boundary. */
    public record BlockEntityUpdate(BlockPos position, String type, NbtValue.Compound tag)
            implements ClientboundPacket {
        public BlockEntityUpdate {
            position = position.immutable();
            Objects.requireNonNull(type);
        }
    }

    /**
     * The decoder supplies an owned section byte array. Read-only views never retain the frame,
     * and each caller gets its own index. The consumer supplies its existing dimension's section count.
     */
    public record Chunk(
            int x,
            int z,
            ByteBuffer sections,
            List<BlockPos> tickerCandidates,
            List<BlockEntityUpdate> blockEntities,
            long[] motionBlocking,
            List<SectionSnapshot> decodedSections)
            implements ClientboundPacket {
        public Chunk(
                int x,
                int z,
                ByteBuffer sections,
                List<BlockPos> tickerCandidates,
                List<BlockEntityUpdate> blockEntities,
                long[] motionBlocking) {
            this(x, z, sections, tickerCandidates, blockEntities, motionBlocking, null);
        }

        public Chunk(int x, int z, ByteBuffer sections, List<BlockPos> tickerCandidates) {
            this(x, z, sections, tickerCandidates, List.of(), null);
        }

        public Chunk {
            sections = sections == null ? null : sections.asReadOnlyBuffer();
            decodedSections = decodedSections == null ? null : List.copyOf(decodedSections);
            if ((sections == null) == (decodedSections == null))
                throw new IllegalArgumentException("One section representation required");
            tickerCandidates = List.copyOf(tickerCandidates);
            blockEntities = List.copyOf(blockEntities);
            motionBlocking = motionBlocking == null ? null : motionBlocking.clone();
        }

        @Override
        public ByteBuffer sections() {
            return sections == null ? SectionSnapshot.encode(decodedSections) : sections.asReadOnlyBuffer();
        }

        @Override
        public long[] motionBlocking() {
            return motionBlocking == null ? null : motionBlocking.clone();
        }
    }
}
