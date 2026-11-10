package ac.cult.cultac.events.packets.worldreader;

import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.SectionSnapshot;
import ac.cult.cultac.network.packet.WorldPackets.Chunk;
import ac.cult.cultac.network.packet.WorldPackets.ChunkBiomes;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.data.RegistryNames;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.latency.CompensatedGeysers;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import ac.cult.cultac.utils.latency.PalettedSection;
import ac.cult.cultac.utils.latency.SectionPool;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;

/** Model sections decoded into owned palettes; packet and transaction order are unchanged. */
public class PacketWorldReaderTwentySix extends BasePacketWorldReader {

    @Override
    public void handleMapChunk(CultPlayer player, PacketSendEvent<Chunk> event, Chunk packet) {
        var dimension = player.compensatedWorld.getLastClientboundDimension();
        CachedSection[] chunks = new CachedSection[dimension.sectionCount()];
        int[][] biomes = new int[chunks.length][];
        int biomeSize = biomeRegistrySize(player);
        if (packet.decodedSections() != null) {
            if (packet.decodedSections().size() != chunks.length)
                throw new IllegalArgumentException("Chunk section count differs from the received dimension");
            for (int i = 0; i < chunks.length; i++) {
                var section = packet.decodedSections().get(i);
                chunks[i] = CachedSection.received(
                        section.takeBlocks(), section.nonEmptyBlockCount(), section.knownFluidCount());
                biomes[i] = biomeValues(section);
            }
        } else {
            var bytes = Unpooled.wrappedBuffer(packet.sections());
            try {
                for (int i = 0; i < chunks.length; i++) {
                    // Pinned 26.3 framing: both counts are the server's, kept as LevelChunkSection.read keeps them.
                    int nonEmpty = bytes.readShort();
                    int fluids = bytes.readShort();
                    var pool = SectionPool.existing(dimension.dimension(), packet.x(), i, packet.z());
                    chunks[i] = CachedSection.received(PalettedSection.readBlocks(bytes, pool), nonEmpty, fluids);
                    biomes[i] = PalettedSection.readBiomeValues(bytes, biomeSize);
                }
            } finally {
                bytes.release();
            }
        }
        // JavaLevelChunkWithLightTranslator sends a Bedrock client only the sections within its dimension's heights.
        var blockEntities = packet.blockEntities();
        boolean clipped = false;
        for (int i = 0; i < chunks.length; i++) {
            if (dimension.clientHoldsSection(i)) continue;
            chunks[i] = null;
            clipped = true;
        }
        if (clipped)
            blockEntities = blockEntities.stream()
                    .filter(entity ->
                            dimension.clientHoldsSection((entity.position().getY() - dimension.minHeight()) >> 4))
                    .toList();
        var tickers = packet.tickerCandidates().stream()
                .filter(position -> hasGeyserTicker(chunks, dimension.minHeight(), position))
                .toList();
        addChunkToCache(
                event,
                player,
                chunks,
                dimension.dimension(),
                packet.x(),
                packet.z(),
                tickers,
                blockEntities,
                biomes,
                packet.motionBlocking());
    }

    private static int[] biomeValues(SectionSnapshot section) {
        int biome = section.uniformBiome();
        return biome < 0 ? section.copyBiomes() : CachedChunk.uniformBiomes(biome);
    }

    private static int biomeRegistrySize(CultPlayer player) {
        return player.user.getCultConnection().require(RegistryNames.class).size("minecraft:worldgen/biome");
    }

    @Override
    public void handleBiomes(CultPlayer player, PacketSendEvent<?> event, ChunkBiomes packet) {
        var dimension = player.compensatedWorld.getLastClientboundDimension();
        var decoded = new ArrayList<int[][]>(packet.chunks().size());
        int biomeSize = biomeRegistrySize(player);
        for (var chunk : packet.chunks()) {
            int[][] values = new int[dimension.sectionCount()][];
            if (chunk.decodedSections() != null) {
                if (chunk.decodedSections().size() != values.length)
                    throw new IllegalArgumentException("Biome section count differs from the received dimension");
                for (int i = 0; i < values.length; i++)
                    values[i] = biomeValues(chunk.decodedSections().get(i));
            } else {
                var bytes = Unpooled.wrappedBuffer(chunk.sections());
                try {
                    for (int index = 0; index < values.length; index++) {
                        values[index] = PalettedSection.readBiomes(bytes, biomeSize);
                    }
                } finally {
                    bytes.release();
                }
            }
            decoded.add(values);
        }
        int transaction = appendFlushProofTransaction(event, player);
        int level = player.compensatedWorld.level();
        player.latencyUtils.addRealTimeTask(transaction, () -> {
            if (level != player.compensatedWorld.level()) return;
            for (int index = 0; index < decoded.size(); index++) {
                var chunk = packet.chunks().get(index);
                player.compensatedWorld.applyClientBiomes(
                        dimension.dimension(), chunk.x(), chunk.z(), decoded.get(index));
            }
        });
    }

    private static boolean hasGeyserTicker(CachedSection[] sections, int minHeight, BlockPos position) {
        int offsetY = position.getY() - minHeight;
        int sectionIndex = offsetY >> 4;
        if (offsetY < 0 || sectionIndex >= sections.length || sections[sectionIndex] == null) return false;
        return CompensatedGeysers.hasTicker(sections[sectionIndex].getStateId(
                CachedChunk.index(position.getX() & 0xF, offsetY & 0xF, position.getZ() & 0xF)));
    }
}
