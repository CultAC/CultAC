package ac.grim.grimac.events.packets.worldreader;

import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.latency.CompensatedWorld.CachedChunk;
import ac.grim.grimac.utils.latency.CompensatedWorld.CachedSection;
import ac.grim.grimac.utils.data.TeleportData;
import ac.grim.grimac.network.event.PacketSendEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public class BasePacketWorldReader {

    @GrimPacketHandler
    public void onForgetLevelChunk(PacketSendEvent event, GrimPlayer player, ClientboundForgetLevelChunkPacket packet) {
        Object chunkPos = packet.pos();
        unloadChunk(event, player,
                NmsPacketUtil.intMethodOrFieldValue(chunkPos, "x"),
                NmsPacketUtil.intMethodOrFieldValue(chunkPos, "z"));
    }

    @GrimPacketHandler
    public void onLevelChunkWithLight(PacketSendEvent event, GrimPlayer player, ClientboundLevelChunkWithLightPacket packet) {
        handleMapChunk(player, event, packet);
    }

    @GrimPacketHandler
    public void onBlockUpdate(PacketSendEvent event, GrimPlayer player, ClientboundBlockUpdatePacket packet) {
        handleBlockChange(player, event, packet);
    }

    @GrimPacketHandler
    public void onBlockEvent(PacketSendEvent event, GrimPlayer player, ClientboundBlockEventPacket packet) {
        handleBlockEvent(player, event, packet);
    }

    @GrimPacketHandler
    public void onSectionBlocksUpdate(PacketSendEvent event, GrimPlayer player, ClientboundSectionBlocksUpdatePacket packet) {
        handleMultiBlockChange(player, event, packet);
    }

    @GrimPacketHandler
    public void onBlockChangedAck(PacketSendEvent event, GrimPlayer player, ClientboundBlockChangedAckPacket packet) {
        GrimPlayer.TrackedTransaction transaction = player.createTrackedTransactionPacketForBundle();
        if (transaction != null) {
            List<Packet<? super ClientGamePacketListener>> packets = List.of(packet, transaction.packet());
            event.setNmsPacket(new ClientboundBundlePacket(packets));
            event.getTasksAfterSend().add(() -> player.markTrackedTransactionPacketSent(transaction));
            player.compensatedWorld.handlePredictionConfirmation(packet.sequence(), transaction);
        } else {
            player.compensatedWorld.handlePredictionConfirmation(packet.sequence());
        }
    }

    @GrimPacketHandler
    public void onGameEvent(PacketSendEvent event, GrimPlayer player, ClientboundGameEventPacket packet) {
        player.latencyUtils.addRealTimeTaskNow(() -> { if (packet.getEvent() == ClientboundGameEventPacket.START_RAINING) {
                player.compensatedWorld.isRaining = true;
            } else if (packet.getEvent() == ClientboundGameEventPacket.STOP_RAINING) {
                player.compensatedWorld.isRaining = false;
            } else if (packet.getEvent() == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE) {
                player.compensatedWorld.isRaining = packet.getParam() > 0.2f;
            }
        });
    }

    public void handleMapChunk(GrimPlayer player, PacketSendEvent event, ClientboundLevelChunkWithLightPacket packet) {
        // Subclasses decode the active chunk format.
    }

    public void addChunkToCache(PacketSendEvent event, GrimPlayer player, CachedSection[] chunks, boolean isGroundUp, int chunkX, int chunkZ) {
        addChunkToCache(event, player, chunks, isGroundUp, packetDimension(player), chunkX, chunkZ);
    }

    public void addChunkToCache(PacketSendEvent event, GrimPlayer player, CachedSection[] chunks, boolean isGroundUp, String dimension, int chunkX, int chunkZ) {
        addChunkToCache(event, player, chunks, isGroundUp, dimension, chunkX, chunkZ, List.of());
    }

    public void addChunkToCache(PacketSendEvent event, GrimPlayer player, CachedSection[] chunks, boolean isGroundUp, String dimension, int chunkX, int chunkZ, List<BlockPos> geyserTickers) {
        double chunkCenterX = (chunkX << 4) + 8;
        double chunkCenterZ = (chunkZ << 4) + 8;
        boolean playerLoadingIntoChunk = Math.abs(player.x - chunkCenterX) < 16 && Math.abs(player.z - chunkCenterZ) < 16;

        for (TeleportData teleports : player.getSetbackTeleportUtil().pendingTeleports) {
            if (teleports.getFlags().getMask() != 0) {
                continue; // idk how to handle this... relative teleports SUCK for anticheats.
            }
            playerLoadingIntoChunk = playerLoadingIntoChunk || (Math.abs(teleports.getLocation().x - chunkCenterX) < 16 && Math.abs(teleports.getLocation().z - chunkCenterZ) < 16);
        }

        if (playerLoadingIntoChunk) {
            // Wrap this between bread.
            player.sendTransaction();
        }
        int applyTransaction = appendTrailingProofTransaction(event, player);
        if (isGroundUp) {
            if (player.chunkDebug) { player.sendMessage("Chunk " + chunkX + " " + chunkZ + " was added."); }
            CachedChunk column = new CachedChunk(chunks, applyTransaction);
            player.compensatedWorld.addToCache(column, dimension, applyTransaction, chunkX, chunkZ, geyserTickers);
        } else {
            if (player.chunkDebug) { player.sendMessage("Chunk " + chunkX + " " + chunkZ + " was merged."); }
            player.latencyUtils.addRealTimeTask(applyTransaction, () -> {
                CachedChunk existingColumn = player.compensatedWorld.getChunk(chunkX, chunkZ);
                if (existingColumn == null) {
                    // Corrupting the player's empty chunk is actually quite meaningless
                    // You are able to set blocks inside it, and they do apply, it just always returns air despite what its data says
                    // So go ahead, corrupt the player's empty chunk and make it no longer all air, it doesn't matter
                    //
                    // LogUtil.warn("Invalid non-ground up continuous sent for empty chunk " + chunkX + " " + chunkZ + " for " + player.user.getProfile().getName() + "! This corrupts the player's empty chunk!");
                    return;
                }
                player.compensatedWorld.mergeIntoCache(existingColumn, chunks, dimension, applyTransaction, chunkX, chunkZ);
            });
        }
    }

    private String packetDimension(GrimPlayer player) {
        return player.compensatedWorld.getLastClientboundDimension().dimension();
    }

    public void unloadChunk(PacketSendEvent event, GrimPlayer player, int x, int z) {
        if (player == null) return;
        if (player.chunkDebug) { player.sendMessage("Chunk " + x + " " + z + " queued for unload."); }
        int applyTransaction = appendTrailingProofTransaction(event, player);
        player.compensatedWorld.removeChunkLater(packetDimension(player), x, z, applyTransaction);
    }

    public void unloadChunk(GrimPlayer player, int x, int z) {
        if (player == null) return;
        if (player.chunkDebug) { player.sendMessage("Chunk " + x + " " + z + " queued for unload."); }
        player.compensatedWorld.removeChunkLater(x, z);
    }

    private int appendTrailingProofTransaction(PacketSendEvent event, GrimPlayer player) {
        GrimPlayer.TrackedTransaction transaction = player.createTrackedTransactionPacketForDeferredSend();
        if (transaction == null) {
            return player.lastTransactionSent.get();
        }

        event.getPacketsAfterSend().add(transaction.packet());
        event.getTasksAfterSend().add(() -> player.markTrackedTransactionPacketSent(transaction));
        return transaction.transaction();
    }

    public void handleBlockChange(GrimPlayer player, PacketSendEvent event, ClientboundBlockUpdatePacket blockChange) {
        int range = 16;

        BlockPos blockPosition = blockChange.getPos();
        BlockState state = blockChange.getBlockState();
        // MCP-Reborn ClientPacketListener handles packets in bundle order on the client
        // thread. Put Grim's ping after the block update so the pong marks the point
        // where the client has processed the new block state.
        if (isNearPlayer(player, blockPosition, range)) {
            GrimPlayer.TrackedTransaction transaction = player.createTrackedTransactionPacketForBundle();
            if (transaction != null) {
                List<Packet<? super ClientGamePacketListener>> packets = List.of(blockChange, transaction.packet());
                event.setNmsPacket(new ClientboundBundlePacket(packets));
                event.getTasksAfterSend().add(() -> player.markTrackedTransactionPacketSent(transaction));
                player.compensatedWorld.handleServerBlockUpdate(blockPosition, state, transaction);
                return;
            }
        }

        player.latencyUtils.addRealTimeTaskNow(() -> player.compensatedWorld.handleServerBlockUpdate(blockPosition, state, player.lastTransactionSent.get()));
    }

    public void handleBlockEvent(GrimPlayer player, PacketSendEvent event, ClientboundBlockEventPacket blockEvent) {
        int range = 16;
        BlockPos blockPosition = blockEvent.getPos();
        if (isNearPlayer(player, blockPosition, range)) {
            GrimPlayer.TrackedTransaction transaction = player.createTrackedTransactionPacketForBundle();
            if (transaction != null) {
                List<Packet<? super ClientGamePacketListener>> packets = List.of(blockEvent, transaction.packet());
                event.setNmsPacket(new ClientboundBundlePacket(packets));
                event.getTasksAfterSend().add(() -> {
                    player.markTrackedTransactionPacketSent(transaction);
                    player.compensatedWorld.pistons.handleBlockEvent(
                        blockPosition,
                        blockEvent.getBlock(),
                        blockEvent.getB0(),
                        blockEvent.getB1(),
                        transaction.transaction());
                });
                return;
            }
        }

        player.latencyUtils.addRealTimeTaskNow(() -> player.compensatedWorld.pistons.handleBlockEvent(
                blockPosition,
                blockEvent.getBlock(),
                blockEvent.getB0(),
                blockEvent.getB1(),
                player.lastTransactionSent.get()));
    }

    public void handleMultiBlockChange(GrimPlayer player, PacketSendEvent event, ClientboundSectionBlocksUpdatePacket multiBlockChange) {
        int range = 16;
        List<ServerBlockUpdate> updates = new ArrayList<>();
        boolean[] nearPlayer = {false};

        multiBlockChange.runUpdates((pos, state) -> {
            BlockPos immutablePos = pos.immutable();
            updates.add(new ServerBlockUpdate(immutablePos, state));
            nearPlayer[0] |= isNearPlayer(player, immutablePos, range);
        });

        if (updates.isEmpty()) {
            return;
        }

        if (nearPlayer[0]) {
            GrimPlayer.TrackedTransaction transaction = player.createTrackedTransactionPacketForBundle();
            if (transaction != null) {
                List<Packet<? super ClientGamePacketListener>> packets = List.of(multiBlockChange, transaction.packet());
                event.setNmsPacket(new ClientboundBundlePacket(packets));
                event.getTasksAfterSend().add(() -> player.markTrackedTransactionPacketSent(transaction));
                player.latencyUtils.addRealTimeTask(transaction.transaction(), () -> updates.forEach(update ->
                        player.compensatedWorld.handleServerBlockUpdate(update.pos(), update.state(), transaction.transaction())));
                return;
            }
        }

        player.latencyUtils.addRealTimeTaskNow(() -> updates.forEach(update ->
                player.compensatedWorld.handleServerBlockUpdate(update.pos(), update.state(), player.lastTransactionSent.get())));
    }

    private boolean isNearPlayer(GrimPlayer player, BlockPos pos, int range) {
        return Math.abs(pos.getX() - player.x) < range
                && Math.abs(pos.getY() - player.y) < range
                && Math.abs(pos.getZ() - player.z) < range;
    }

    private record ServerBlockUpdate(BlockPos pos, BlockState state) {
    }
}
