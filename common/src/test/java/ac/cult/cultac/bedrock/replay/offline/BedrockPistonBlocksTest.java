package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import org.junit.Test;

/**
 * A Bedrock player's piston blocks are the ones Geyser sends, not the ones a Java client's piston would move.
 *
 * <p>Geyser answers a sticky piston's extension with block updates of its own: the pushed block's cell becomes air
 * at once, the arm follows two of its ticks later, and the server's own final blocks arrive after that.
 */
public class BedrockPistonBlocksTest {
    private static final BlockPos PISTON = new BlockPos(20083, 121, 20060);
    private static final BlockPos PUSHED = new BlockPos(20084, 121, 20060);
    private static final BlockPos AHEAD = new BlockPos(20085, 121, 20060);
    private static final int EAST = Direction.EAST.get3DDataValue();
    private static final int STICKY = BlockProps.FACING.with(state("minecraft:sticky_piston"), EAST);
    private static final int SLIME = state("minecraft:slime_block");
    private static final int ARM = BlockProps.FACING.with(state("minecraft:piston_head"), EAST);
    private static final int AIR = BlockIds.AIR.defaultState();

    @Test
    public void theServersPistonEventMovesNoBlocksAndPushesNothing() {
        run(player -> {
            var world = player.compensatedWorld;
            var packet = new ClientboundBlockEvent(
                    PISTON, 0, EAST, DataTables.defaults().registry().blockIndex(STICKY));
            var event = new PacketSendEvent<>(
                    player.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
            new PacketWorldReaderTwentySix().handleBlockEvent(player, event, packet);
            event.getTasksAfterSend().forEach(Runnable::run);
            acknowledge(player);
            assertEquals(STICKY, world.getBlockStateIdAt(PISTON));
            assertEquals(SLIME, world.getBlockStateIdAt(PUSHED));
            assertEquals(AIR, world.getBlockStateIdAt(AHEAD));
        });
    }

    @Test
    public void theWorldFollowsGeysersPistonBlocksUntilTheServersArrive() {
        run(player -> {
            var world = player.compensatedWorld;
            // The pushed block leaves its cell when the client has Geyser's air there.
            world.handleBedrockClientBlockUpdate(PUSHED, AIR, false);
            assertEquals(AIR, world.getBlockStateIdAt(PUSHED));
            // A moving piston block the server sends never reaches the client.
            world.handleServerBlockUpdate(AHEAD, state("minecraft:moving_piston"), 0);
            assertEquals(AIR, world.getBlockStateIdAt(AHEAD));
            // Geyser's arm, then the server's final blocks as Geyser forwards them.
            world.handleBedrockClientBlockUpdate(PUSHED, ARM, false);
            assertEquals(ARM, world.getBlockStateIdAt(PUSHED));
            int stickyArm = DataTables.defaults().registry().with(ARM, "type", "sticky");
            world.handleServerBlockUpdate(AHEAD, SLIME, 0);
            world.handleBedrockClientBlockUpdate(PUSHED, stickyArm, true);
            assertEquals(SLIME, world.getBlockStateIdAt(AHEAD));
            assertEquals(stickyArm, world.getBlockStateIdAt(PUSHED));
            assertEquals(stickyArm, world.getServerSentBlockStateIdAt(PUSHED));
        });
    }

    private static void run(java.util.function.Consumer<CultPlayer> body) {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.compensatedWorld.ensureValidationChunkLoaded(PISTON.getX() >> 4, PISTON.getZ() >> 4);
            player.compensatedWorld.updateBlock(PISTON, STICKY);
            player.compensatedWorld.updateBlock(PUSHED, SLIME);
            body.accept(player);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static void acknowledge(CultPlayer player) {
        int transaction = player.lastTransactionSent.get();
        player.lastTransactionReceived.set(Math.max(player.lastTransactionReceived.get(), transaction));
        player.latencyUtils.handleNettySyncTransaction(transaction);
    }

    private static int state(String block) {
        return DataTables.defaults().registry().block(block).defaultState();
    }
}
