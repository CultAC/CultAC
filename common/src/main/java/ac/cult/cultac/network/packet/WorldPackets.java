package ac.cult.cultac.network.packet;

import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import ac.cult.cultac.protocol.value.BlockPos;
import java.nio.ByteBuffer;
import java.util.List;

/** Consumed world data; block states use the bundled model's numeric IDs. */
public final class WorldPackets {
    private WorldPackets() {}

    public record BlockUpdate(BlockPos position, int state) implements ClientboundPacket {
        private static final int STATE_COUNT =
                ac.cult.blocksim.data.DataTables.defaults().registry().stateCount();

        public BlockUpdate {
            position = position.immutable();
            // Section updates use nullable IdMap.byId; Block.getId(null) is air.
            // Single-block decoding retains its separate byIdOrThrow contract.
            if (state < 0 || state >= STATE_COUNT) state = 0;
        }
    }

    public record SectionBlocksUpdate(List<BlockUpdate> updates) implements ClientboundPacket {
        public SectionBlocksUpdate {
            updates = List.copyOf(updates);
        }
    }

    public record TimeUpdate(long gameTime, java.util.Map<String, ac.cult.blocksim.engine.ClientClocks.State> clocks)
            implements ClientboundPacket {
        public TimeUpdate {
            clocks = java.util.Map.copyOf(clocks);
        }
    }

    public record EnabledFeatures(java.util.Set<String> features) implements ClientboundPacket {
        public EnabledFeatures {
            features = java.util.Set.copyOf(features);
        }
    }

    /** Only peaceful difficulty affects the action contract; the lock is a settings/UI field. */
    public record Difficulty(boolean peaceful) implements ClientboundPacket {}

    /** Only recipe input membership affects client block interactions. */
    public record RecipeInputs(java.util.Map<String, java.util.Set<String>> itemSets) implements ClientboundPacket {
        public RecipeInputs {
            var copy = new java.util.HashMap<String, java.util.Set<String>>();
            itemSets.forEach((key, items) -> copy.put(key, java.util.Set.copyOf(items)));
            itemSets = java.util.Map.copyOf(copy);
        }
    }

    public record BiomeChunk(int x, int z, ByteBuffer sections) {
        public BiomeChunk {
            sections = sections.asReadOnlyBuffer();
        }

        @Override
        public ByteBuffer sections() {
            return sections.asReadOnlyBuffer();
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
            java.util.Objects.requireNonNull(type);
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
            LightValues light,
            List<BlockEntityUpdate> blockEntities,
            long[] motionBlocking)
            implements ClientboundPacket {
        public Chunk(int x, int z, ByteBuffer sections, List<BlockPos> tickerCandidates) {
            this(x, z, sections, tickerCandidates, null);
        }

        public Chunk(int x, int z, ByteBuffer sections, List<BlockPos> tickerCandidates, LightValues light) {
            this(x, z, sections, tickerCandidates, light, List.of());
        }

        public Chunk(
                int x,
                int z,
                ByteBuffer sections,
                List<BlockPos> tickerCandidates,
                LightValues light,
                List<BlockEntityUpdate> blockEntities) {
            this(x, z, sections, tickerCandidates, light, blockEntities, null);
        }

        public Chunk {
            sections = sections.asReadOnlyBuffer();
            tickerCandidates = List.copyOf(tickerCandidates);
            blockEntities = List.copyOf(blockEntities);
            motionBlocking = motionBlocking == null ? null : motionBlocking.clone();
        }

        @Override
        public ByteBuffer sections() {
            return sections.asReadOnlyBuffer();
        }

        @Override
        public long[] motionBlocking() {
            return motionBlocking == null ? null : motionBlocking.clone();
        }
    }

    public record LightUpdate(int x, int z, LightValues light) implements ClientboundPacket {}
}
