package ac.cult.cultac.events.packets.worldreader;

import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.WorldPackets.Chunk;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.latency.CompensatedGeysers;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import ac.cult.cultac.utils.latency.PalettedSection;
import io.netty.buffer.Unpooled;

/** Model sections decoded into owned palettes; packet and transaction order are unchanged. */
public class PacketWorldReaderTwentySix extends BasePacketWorldReader {

    @Override
    public void handleMapChunk(CultPlayer player, PacketSendEvent<Chunk> event, Chunk packet) {
        var dimension = player.compensatedWorld.getLastClientboundDimension();
        CachedSection[] chunks = new CachedSection[dimension.sectionCount()];
        int[][] biomes = new int[chunks.length][];
        int biomeSize = biomeRegistrySize(player);
        var bytes = Unpooled.wrappedBuffer(packet.sections());
        try {
            for (int i = 0; i < chunks.length; i++) {
                bytes.readShort(); // Existing cache recalculates block/fluid counts from the decoded states.
                bytes.readShort();
                chunks[i] = new CachedSection(PalettedSection.readBlocks(bytes));
                biomes[i] = PalettedSection.readBiomes(bytes, biomeSize);
            }
        } finally {
            bytes.release();
        }
        var tickers = packet.tickerCandidates().stream()
                .filter(position -> hasGeyserTicker(chunks, dimension.minHeight(), position))
                .toList();
        addChunkToCache(
                event,
                player,
                chunks,
                true,
                dimension.dimension(),
                packet.x(),
                packet.z(),
                tickers,
                packet.blockEntities(),
                biomes,
                packet.motionBlocking());
        if (packet.light() != null) {
            player.latencyUtils.addRealTimeTask(
                    player.lastTransactionSent.get(),
                    () -> player.compensatedWorld.applyLight(
                            dimension.dimension(), packet.x(), packet.z(), packet.light()));
        }
    }

    private static int biomeRegistrySize(CultPlayer player) {
        return player.user
                .getCultConnection()
                .require(ac.cult.cultac.protocol.data.RegistryNames.class)
                .size("minecraft:worldgen/biome");
    }

    @Override
    public void handleBiomes(
            CultPlayer player,
            PacketSendEvent<?> event,
            ac.cult.cultac.network.packet.WorldPackets.ChunkBiomes packet) {
        var dimension = player.compensatedWorld.getLastClientboundDimension();
        var decoded = new java.util.ArrayList<int[][]>(packet.chunks().size());
        int biomeSize = biomeRegistrySize(player);
        for (var chunk : packet.chunks()) {
            int[][] values = new int[dimension.sectionCount()][];
            var bytes = Unpooled.wrappedBuffer(chunk.sections());
            try {
                for (int index = 0; index < values.length; index++) {
                    values[index] = PalettedSection.readBiomes(bytes, biomeSize);
                }
            } finally {
                bytes.release();
            }
            decoded.add(values);
        }
        int transaction = appendTrailingProofTransaction(event, player);
        player.latencyUtils.addRealTimeTask(transaction, () -> {
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
