package ac.cult.cultac.events.packets.worldreader;

import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.WorldPackets.BlockEntityUpdate;
import ac.cult.cultac.network.packet.WorldPackets.BlockUpdate;
import ac.cult.cultac.network.packet.WorldPackets.Chunk;
import ac.cult.cultac.network.packet.WorldPackets.ChunkBiomes;
import ac.cult.cultac.network.packet.WorldPackets.Difficulty;
import ac.cult.cultac.network.packet.WorldPackets.RecipeInputs;
import ac.cult.cultac.network.packet.WorldPackets.SectionBlocksUpdate;
import ac.cult.cultac.network.packet.WorldPackets.TimeUpdate;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockChangedAck;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundForgetLevelChunk;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundGameEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetChunkCacheCenter;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetChunkCacheRadius;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.ViaClientBlockShapeMappings;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import java.util.List;

public class BasePacketWorldReader {

    @CultPacketHandler
    public void onChunkCacheCenter(
            PacketSendEvent<ClientboundSetChunkCacheCenter> event,
            CultPlayer player,
            ClientboundSetChunkCacheCenter packet) {
        int transaction = appendFlushProofTransaction(event, player);
        player.latencyUtils.addRealTimeTask(
                transaction, () -> player.compensatedWorld.clientChunkCacheCenter(packet.x(), packet.z()));
    }

    @CultPacketHandler
    public void onChunkCacheRadius(
            PacketSendEvent<ClientboundSetChunkCacheRadius> event,
            CultPlayer player,
            ClientboundSetChunkCacheRadius packet) {
        int transaction = appendFlushProofTransaction(event, player);
        player.latencyUtils.addRealTimeTask(
                transaction, () -> player.compensatedWorld.clientChunkCacheRadius(packet.radius()));
    }

    @CultPacketHandler
    public void onForgetLevelChunk(
            PacketSendEvent<ClientboundForgetLevelChunk> event, CultPlayer player, ClientboundForgetLevelChunk packet) {
        unloadChunk(event, player, packet.x(), packet.z());
    }

    @CultPacketHandler
    public void onLevelChunkWithLight(PacketSendEvent<Chunk> event, CultPlayer player, Chunk packet) {
        if (player.compensatedWorld.isClientboundUnresolved()) return;
        handleMapChunk(player, event, packet);
    }

    @CultPacketHandler
    public void onBlockUpdate(PacketSendEvent<BlockUpdate> event, CultPlayer player, BlockUpdate packet) {
        if (player.compensatedWorld.isClientboundUnresolved()) return;
        handleBlockChange(player, event, packet);
    }

    @CultPacketHandler
    public void onBlockEntityData(
            PacketSendEvent<BlockEntityUpdate> event, CultPlayer player, BlockEntityUpdate packet) {
        String dimension = packetDimension(player);
        int transaction = appendFlushProofTransaction(event, player);
        player.latencyUtils.addRealTimeTask(
                transaction, () -> player.compensatedWorld.applyClientBlockEntityData(dimension, packet));
    }

    @CultPacketHandler
    public void onTimeUpdate(PacketSendEvent<TimeUpdate> event, CultPlayer player, TimeUpdate packet) {
        var backendTicks = player.backendTicks();
        if (backendTicks != null) backendTicks.onGameTime(packet.gameTime(), System.nanoTime());
        // A time update in the same flush as a piston event is processed in that same client task.
        // Deferring it to another ping gives Entity#limitPistonMovement the wrong game time: a server
        // time correction can repeat the previous tick's time, preserving its 0.51 piston limit.
        int transaction = appendFlushProofTransaction(event, player);
        player.latencyUtils.addRealTimeTask(transaction, () -> player.compensatedWorld.applyClientTime(packet));
    }

    @CultPacketHandler
    public void onDifficulty(PacketSendEvent<Difficulty> event, CultPlayer player, Difficulty packet) {
        player.latencyUtils.addRealTimeTaskNext(() -> player.compensatedWorld.applyClientDifficulty(packet));
    }

    @CultPacketHandler
    public void onRecipeInputs(PacketSendEvent<RecipeInputs> event, CultPlayer player, RecipeInputs packet) {
        int transaction = appendFlushProofTransaction(event, player);
        player.latencyUtils.addRealTimeTask(transaction, () -> player.compensatedWorld.applyClientRecipeInputs(packet));
    }

    @CultPacketHandler
    public void onChunkBiomes(PacketSendEvent<ChunkBiomes> event, CultPlayer player, ChunkBiomes packet) {
        if (player.compensatedWorld.isClientboundUnresolved()) return;
        handleBiomes(player, event, packet);
    }

    @CultPacketHandler
    public void onBlockEvent(
            PacketSendEvent<ClientboundBlockEvent> event, CultPlayer player, ClientboundBlockEvent packet) {
        handleBlockEvent(player, event, packet);
    }

    @CultPacketHandler
    public void onSectionBlocksUpdate(
            PacketSendEvent<SectionBlocksUpdate> event, CultPlayer player, SectionBlocksUpdate packet) {
        if (player.compensatedWorld.isClientboundUnresolved()) return;
        handleMultiBlockChange(player, event, packet);
    }

    @CultPacketHandler
    public void onBlockChangedAck(
            PacketSendEvent<ClientboundBlockChangedAck> event, CultPlayer player, ClientboundBlockChangedAck packet) {
        player.getGhostBlockMitigator().onServerAckSent(packet.sequence());
        player.itemUseBurst.serverAck(packet.sequence());
        CultPlayer.TrackedTransaction transaction = player.appendTrailingProofTransaction(event);
        if (transaction != null) {
            player.compensatedWorld.handlePredictionConfirmation(packet.sequence(), transaction);
            player.latencyUtils.addRealTimeTask(
                    transaction.transaction(), () -> player.blockActionReconciler.confirmServerAck(packet.sequence()));
        } else {
            player.compensatedWorld.handlePredictionConfirmation(packet.sequence());
            player.latencyUtils.addRealTimeTask(
                    player.lastTransactionSent.get(),
                    () -> player.blockActionReconciler.confirmServerAck(packet.sequence()));
        }
    }

    @CultPacketHandler
    public void onGameEvent(
            PacketSendEvent<ClientboundGameEvent> event, CultPlayer player, ClientboundGameEvent packet) {
        player.latencyUtils.addRealTimeTaskNow(
                () -> player.compensatedWorld.applyClientWeather(packet.event(), packet.param()));
    }

    public void handleMapChunk(CultPlayer player, PacketSendEvent<Chunk> event, Chunk packet) {
        // Subclasses decode the active chunk format.
    }

    public void handleBiomes(CultPlayer player, PacketSendEvent<?> event, ChunkBiomes packet) {
        // Subclasses decode the active biome palette format.
    }

    public void addChunkToCache(
            PacketSendEvent<?> event,
            CultPlayer player,
            CachedSection[] chunks,
            String dimension,
            int chunkX,
            int chunkZ,
            List<BlockPos> geyserTickers,
            List<BlockEntityUpdate> blockEntities,
            int[][] biomes,
            long[] motionBlocking) {
        // The compensated world stores what this connection sees. Translate
        // before pooling so clients with different palettes cannot share wrong states.
        boolean replacesStates = ViaClientBlockShapeMappings.replacesBlockStates(player);
        for (int i = 0; i < chunks.length; i++) {
            if (chunks[i] != null && (replacesStates || !chunks[i].hasOnlyKnownStateIds()))
                chunks[i] = chunks[i].translatedStateIds(
                        state -> ViaClientBlockShapeMappings.clientBlockStateId(player, state));
        }
        double chunkCenterX = (chunkX << 4) + 8;
        double chunkCenterZ = (chunkZ << 4) + 8;
        boolean playerLoadingIntoChunk =
                Math.abs(player.x - chunkCenterX) < 16 && Math.abs(player.z - chunkCenterZ) < 16;

        for (TeleportData teleports : player.getSetbackTeleportUtil().pendingTeleports) {
            if (teleports.getFlags().getMask() != 0) {
                continue; // idk how to handle this... relative teleports SUCK for anticheats.
            }
            playerLoadingIntoChunk = playerLoadingIntoChunk
                    || (Math.abs(teleports.getLocation().x - chunkCenterX) < 16
                            && Math.abs(teleports.getLocation().z - chunkCenterZ) < 16);
        }

        if (playerLoadingIntoChunk) {
            // Wrap this between bread.
            player.sendTransaction();
        }
        int applyTransaction = appendFlushProofTransaction(event, player);
        if (player.chunkDebug) {
            player.sendMessage("Chunk " + chunkX + " " + chunkZ + " was added.");
        }
        CachedChunk column = new CachedChunk(chunks, applyTransaction, blockEntities, biomes);
        player.compensatedWorld.addToCache(
                column, dimension, applyTransaction, chunkX, chunkZ, geyserTickers, motionBlocking);
    }

    private String packetDimension(CultPlayer player) {
        return player.compensatedWorld.getLastClientboundDimension().dimension();
    }

    public void unloadChunk(PacketSendEvent<?> event, CultPlayer player, int x, int z) {
        if (player.chunkDebug) {
            player.sendMessage("Chunk " + x + " " + z + " queued for unload.");
        }
        int applyTransaction = appendFlushProofTransaction(event, player);
        player.compensatedWorld.removeChunkLater(packetDimension(player), x, z, applyTransaction);
    }

    /** The flush ping that proves this world update; see {@link CultPlayer#appendFlushProofTransaction}. */
    protected int appendFlushProofTransaction(PacketSendEvent<?> event, CultPlayer player) {
        int transaction = player.appendFlushProofTransaction(event);
        return transaction > 0 ? transaction : player.lastTransactionSent.get();
    }

    public void handleBlockChange(CultPlayer player, PacketSendEvent<BlockUpdate> event, BlockUpdate blockChange) {
        BlockPos blockPosition = blockChange.position();
        // ClientPacketListener#handleBundlePacket handles the flush bundle in one task, so the
        // pong of a ping in it marks the point where the client has the new block state, however far it is.
        int transaction = appendFlushProofTransaction(event, player);
        if (blockChange != event.getOriginalPacket()) event.replace(blockChange);
        player.compensatedWorld.queueServerBlockUpdate(
                blockPosition.getX(), blockPosition.getY(), blockPosition.getZ(), blockChange.state(), transaction);
    }

    public void handleBlockEvent(
            CultPlayer player, PacketSendEvent<ClientboundBlockEvent> event, ClientboundBlockEvent blockEvent) {
        BlockPos blockPosition = new BlockPos(
                blockEvent.position().x(),
                blockEvent.position().y(),
                blockEvent.position().z());
        String dimension = packetDimension(player);
        // ClientPacketListener#handleBlockEvent runs Level#blockEvent in the bundle's task, so
        // the flush proof proves the event exactly as it does the block updates around it.
        int transaction = appendFlushProofTransaction(event, player);
        if (blockEvent != event.getOriginalPacket()) event.replace(blockEvent);
        // Queue in packet order, like block updates. Deferring insertion until tasksAfterSend
        // can put this event behind a later block update expanded in the same output batch.
        player.latencyUtils.addRealTimeTask(transaction, () -> {
            player.compensatedWorld.applyClientBlockEvent(dimension, blockEvent);
            player.compensatedWorld.pistons.handleBlockEvent(
                    blockPosition, blockEvent.blockId(), blockEvent.action(), blockEvent.parameter(), transaction);
        });
    }

    public void handleMultiBlockChange(
            CultPlayer player, PacketSendEvent<SectionBlocksUpdate> event, SectionBlocksUpdate multiBlockChange) {
        if (multiBlockChange.size() == 0) {
            return;
        }
        player.compensatedWorld.queueServerBlockUpdates(multiBlockChange, appendFlushProofTransaction(event, player));
    }
}
