package ac.cult.cultac.events.packets.worldreader;

import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.WorldPackets.BlockEntityUpdate;
import ac.cult.cultac.network.packet.WorldPackets.BlockUpdate;
import ac.cult.cultac.network.packet.WorldPackets.Chunk;
import ac.cult.cultac.network.packet.WorldPackets.SectionBlocksUpdate;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockChangedAck;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundForgetLevelChunk;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundGameEvent;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.ViaClientBlockShapeMappings;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import java.util.List;

public class BasePacketWorldReader {

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
        int transaction = appendTrailingProofTransaction(event, player);
        player.latencyUtils.addRealTimeTask(
                transaction, () -> player.compensatedWorld.applyClientBlockEntityData(dimension, packet));
    }

    @CultPacketHandler
    public void onTimeUpdate(
            PacketSendEvent<ac.cult.cultac.network.packet.WorldPackets.TimeUpdate> event,
            CultPlayer player,
            ac.cult.cultac.network.packet.WorldPackets.TimeUpdate packet) {
        var backendTicks = player.backendTicks();
        if (backendTicks != null) backendTicks.onGameTime(packet.gameTime(), System.nanoTime());
        player.latencyUtils.addRealTimeTaskNext(() -> player.compensatedWorld.applyClientTime(packet));
    }

    @CultPacketHandler
    public void onDifficulty(
            PacketSendEvent<ac.cult.cultac.network.packet.WorldPackets.Difficulty> event,
            CultPlayer player,
            ac.cult.cultac.network.packet.WorldPackets.Difficulty packet) {
        player.latencyUtils.addRealTimeTaskNext(() -> player.compensatedWorld.applyClientDifficulty(packet));
    }

    @CultPacketHandler
    public void onRecipeInputs(
            PacketSendEvent<ac.cult.cultac.network.packet.WorldPackets.RecipeInputs> event,
            CultPlayer player,
            ac.cult.cultac.network.packet.WorldPackets.RecipeInputs packet) {
        int transaction = appendTrailingProofTransaction(event, player);
        player.latencyUtils.addRealTimeTask(transaction, () -> player.compensatedWorld.applyClientRecipeInputs(packet));
    }

    @CultPacketHandler
    public void onChunkBiomes(
            PacketSendEvent<ac.cult.cultac.network.packet.WorldPackets.ChunkBiomes> event,
            CultPlayer player,
            ac.cult.cultac.network.packet.WorldPackets.ChunkBiomes packet) {
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

    public void handleBiomes(
            CultPlayer player,
            PacketSendEvent<?> event,
            ac.cult.cultac.network.packet.WorldPackets.ChunkBiomes packet) {
        // Subclasses decode the active biome palette format.
    }

    public void addChunkToCache(
            PacketSendEvent<?> event,
            CultPlayer player,
            CachedSection[] chunks,
            boolean isGroundUp,
            int chunkX,
            int chunkZ) {
        addChunkToCache(event, player, chunks, isGroundUp, packetDimension(player), chunkX, chunkZ);
    }

    public void addChunkToCache(
            PacketSendEvent<?> event,
            CultPlayer player,
            CachedSection[] chunks,
            boolean isGroundUp,
            String dimension,
            int chunkX,
            int chunkZ) {
        addChunkToCache(event, player, chunks, isGroundUp, dimension, chunkX, chunkZ, List.of());
    }

    public void addChunkToCache(
            PacketSendEvent<?> event,
            CultPlayer player,
            CachedSection[] chunks,
            boolean isGroundUp,
            String dimension,
            int chunkX,
            int chunkZ,
            List<BlockPos> geyserTickers) {
        addChunkToCache(
                event, player, chunks, isGroundUp, dimension, chunkX, chunkZ, geyserTickers, List.of(), null, null);
    }

    public void addChunkToCache(
            PacketSendEvent<?> event,
            CultPlayer player,
            CachedSection[] chunks,
            boolean isGroundUp,
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
        int applyTransaction = appendTrailingProofTransaction(event, player);
        if (isGroundUp) {
            if (player.chunkDebug) {
                player.sendMessage("Chunk " + chunkX + " " + chunkZ + " was added.");
            }
            CachedChunk column = new CachedChunk(chunks, applyTransaction, blockEntities, biomes);
            player.compensatedWorld.addToCache(
                    column, dimension, applyTransaction, chunkX, chunkZ, geyserTickers, motionBlocking);
        } else {
            if (player.chunkDebug) {
                player.sendMessage("Chunk " + chunkX + " " + chunkZ + " was merged.");
            }
            int level = player.compensatedWorld.level();
            player.latencyUtils.addRealTimeTask(applyTransaction, () -> {
                if (level != player.compensatedWorld.level()) return;
                CachedChunk existingColumn = player.compensatedWorld.getChunk(chunkX, chunkZ);
                if (existingColumn == null) {
                    // Corrupting the player's empty chunk is actually quite meaningless
                    // You are able to set blocks inside it, and they do apply, it just always returns air despite what
                    // its data says
                    // So go ahead, corrupt the player's empty chunk and make it no longer all air, it doesn't matter
                    //
                    // LogUtil.warn("Invalid non-ground up continuous sent for empty chunk " + chunkX + " " + chunkZ + "
                    // for " + player.user.getProfile().getName() + "! This corrupts the player's empty chunk!");
                    return;
                }
                player.compensatedWorld.mergeIntoCache(
                        existingColumn, chunks, dimension, applyTransaction, chunkX, chunkZ);
            });
        }
    }

    private String packetDimension(CultPlayer player) {
        return player.compensatedWorld.getLastClientboundDimension().dimension();
    }

    public void unloadChunk(PacketSendEvent<?> event, CultPlayer player, int x, int z) {
        if (player == null) return;
        if (player.chunkDebug) {
            player.sendMessage("Chunk " + x + " " + z + " queued for unload.");
        }
        int applyTransaction = appendTrailingProofTransaction(event, player);
        player.compensatedWorld.removeChunkLater(packetDimension(player), x, z, applyTransaction);
    }

    public void unloadChunk(CultPlayer player, int x, int z) {
        if (player == null) return;
        if (player.chunkDebug) {
            player.sendMessage("Chunk " + x + " " + z + " queued for unload.");
        }
        player.compensatedWorld.removeChunkLater(x, z);
    }

    protected int appendTrailingProofTransaction(PacketSendEvent<?> event, CultPlayer player) {
        CultPlayer.TrackedTransaction transaction = player.appendTrailingProofTransaction(event);
        return transaction == null ? player.lastTransactionSent.get() : transaction.transaction();
    }

    public void handleBlockChange(CultPlayer player, PacketSendEvent<BlockUpdate> event, BlockUpdate blockChange) {
        int range = 16;

        BlockPos blockPosition = blockChange.position();
        int state = blockChange.state();
        // MCP-Reborn ClientPacketListener handles packets in bundle order on the client
        // thread. Put Cult's ping after the block update so the pong marks the point
        // where the client has processed the new block state.
        if (isNearPlayer(player, blockPosition, range)) {
            CultPlayer.TrackedTransaction transaction = player.appendTrailingProofTransaction(event);
            if (transaction != null) {
                if (blockChange != event.getOriginalPacket()) event.replace(blockChange);
                player.compensatedWorld.handleServerBlockUpdate(blockPosition, state, transaction);
                return;
            }
        }

        player.compensatedWorld.queueServerBlockUpdate(
                blockPosition.getX(),
                blockPosition.getY(),
                blockPosition.getZ(),
                state,
                player.lastTransactionSent.get());
    }

    public void handleBlockEvent(
            CultPlayer player, PacketSendEvent<ClientboundBlockEvent> event, ClientboundBlockEvent blockEvent) {
        int range = 16;
        BlockPos blockPosition = new BlockPos(
                blockEvent.position().x(),
                blockEvent.position().y(),
                blockEvent.position().z());
        String dimension = packetDimension(player);
        if (isNearPlayer(player, blockPosition, range)) {
            CultPlayer.TrackedTransaction transaction = player.appendTrailingProofTransaction(event);
            if (transaction != null) {
                if (blockEvent != event.getOriginalPacket()) event.replace(blockEvent);
                event.getTasksAfterSend().add(() -> {
                    Runnable applyEvent = () -> {
                        player.compensatedWorld.applyClientBlockEvent(dimension, blockEvent);
                        player.compensatedWorld.pistons.handleBlockEvent(
                                blockPosition,
                                blockEvent.blockId(),
                                blockEvent.action(),
                                blockEvent.parameter(),
                                transaction.transaction());
                    };
                    player.latencyUtils.addRealTimeTask(transaction.transaction(), applyEvent);
                });
                return;
            }
        }

        player.latencyUtils.addRealTimeTaskNow(() -> {
            player.compensatedWorld.applyClientBlockEvent(dimension, blockEvent);
            player.compensatedWorld.pistons.handleBlockEvent(
                    blockPosition,
                    blockEvent.blockId(),
                    blockEvent.action(),
                    blockEvent.parameter(),
                    player.lastTransactionSent.get());
        });
    }

    public void handleMultiBlockChange(
            CultPlayer player, PacketSendEvent<SectionBlocksUpdate> event, SectionBlocksUpdate multiBlockChange) {
        int range = 16;
        if (multiBlockChange.size() == 0) {
            return;
        }

        boolean nearPlayer = false;
        for (int i = 0; i < multiBlockChange.size(); i++) {
            if (Math.abs(multiBlockChange.x(i) - player.x) < range
                    && Math.abs(multiBlockChange.y(i) - player.y) < range
                    && Math.abs(multiBlockChange.z(i) - player.z) < range) {
                nearPlayer = true;
                break;
            }
        }

        if (nearPlayer) {
            CultPlayer.TrackedTransaction transaction = player.appendTrailingProofTransaction(event);
            if (transaction != null) {
                multiBlockChange.runUpdates((x, y, z, state) ->
                        player.compensatedWorld.queueServerBlockUpdate(x, y, z, state, transaction.transaction()));
                return;
            }
        }

        int transaction = player.lastTransactionSent.get();
        multiBlockChange.runUpdates(
                (x, y, z, state) -> player.compensatedWorld.queueServerBlockUpdate(x, y, z, state, transaction));
    }

    private boolean isNearPlayer(CultPlayer player, BlockPos pos, int range) {
        return Math.abs(pos.getX() - player.x) < range
                && Math.abs(pos.getY() - player.y) < range
                && Math.abs(pos.getZ() - player.z) < range;
    }
}
