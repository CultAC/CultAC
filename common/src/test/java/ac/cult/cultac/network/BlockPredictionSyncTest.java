package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedSection;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ClientLevel and BlockStatePredictionHandler packet orderings around a pending block prediction. */
class BlockPredictionSyncTest {
    private static final BlockPos POS = new BlockPos(8, 64, 8);

    @Test
    void aChunkResendReplacesThePredictedBlockAndTheAckRestoresTheRetainedState() throws Exception {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            var world = world(player);
            predict(world, state("stone"));
            // ClientLevel#setServerVerifiedBlockState only updates the retained state of a pending prediction.
            world.updateBlock(POS, state("dirt"));
            assertEquals(state("stone"), world.getBlockStateIdAt(POS));

            // LevelChunk#replaceWithPacketData reads the resent sections raw.
            int transaction = player.lastTransactionReceived.get() + 1;
            var resend = new CachedChunk(new CachedSection[world.getLastClientboundSectionCount()], transaction);
            world.addToCache(resend, "minecraft:overworld", transaction, 0, 0, List.of());
            player.latencyUtils.handleNettySyncTransaction(transaction);
            assertEquals(state("air"), world.getBlockStateIdAt(POS));
            assertEquals(state("air"), world.getServerSentBlockStateIdAt(POS));
            assertEquals(
                    state("dirt"), world.pendingPredictionSnapshot().getFirst().retained());

            world.handlePredictionConfirmation(1, player.lastTransactionReceived.get());
            assertEquals(state("dirt"), world.getBlockStateIdAt(POS));
            assertTrue(world.pendingPredictionSnapshot().isEmpty());
        }
    }

    @Test
    void aTeleportHandledAfterThePredictionSuppressesTheAckSnap() throws Exception {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            var world = world(player);
            world.applyBlockChangeRawDANGER(POS.getX(), POS.getY(), POS.getZ(), state("stone"));
            moveTo(player, 8.5, 66, 8.5);
            predict(world, state("air"));
            moveTo(player, 8.5, 64, 8.5);
            world.onClientTeleport();

            world.handlePredictionConfirmation(1, player.lastTransactionReceived.get());
            assertEquals(state("stone"), world.getBlockStateIdAt(POS));
            assertEquals(64, player.y);
        }
    }

    @Test
    void anAckWithoutAnInterveningTeleportSnapsBackToThePredictionPosition() throws Exception {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            var world = world(player);
            world.applyBlockChangeRawDANGER(POS.getX(), POS.getY(), POS.getZ(), state("stone"));
            world.onClientTeleport();
            moveTo(player, 8.5, 66, 8.5);
            predict(world, state("air"));
            moveTo(player, 8.5, 64, 8.5);

            world.handlePredictionConfirmation(1, player.lastTransactionReceived.get());
            assertEquals(state("stone"), world.getBlockStateIdAt(POS));
            assertEquals(66, player.y);
        }
    }

    @Test
    void preTeleportGuardClientsAlwaysSnapBack() throws Exception {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            var resolved = CultPlayer.class.getDeclaredField("resolvedClientVersion");
            resolved.setAccessible(true);
            resolved.set(player, ClientVersion.V_1_21_11);
            var world = world(player);
            world.applyBlockChangeRawDANGER(POS.getX(), POS.getY(), POS.getZ(), state("stone"));
            moveTo(player, 8.5, 66, 8.5);
            predict(world, state("air"));
            moveTo(player, 8.5, 64, 8.5);
            world.onClientTeleport();

            world.handlePredictionConfirmation(1, player.lastTransactionReceived.get());
            assertEquals(66, player.y);
        }
    }

    private static CompensatedWorld world(CultPlayer player) {
        var world = player.compensatedWorld;
        var dimension = player.getWorldRegistries().dimension("minecraft:overworld");
        world.setLastClientboundDimension("minecraft:overworld", dimension.dimension());
        world.setDimension("minecraft:overworld", dimension);
        world.ensureValidationChunkLoaded(0, 0);
        return world;
    }

    /** MultiPlayerGameMode#startPrediction for one block write. */
    private static void predict(CompensatedWorld world, int state) {
        world.advanceClientPredictionSequence();
        world.startPredicting();
        world.updateBlock(POS, state);
        world.stopPredicting(1);
        assertEquals(state, world.getBlockStateIdAt(POS));
    }

    private static void moveTo(CultPlayer player, double x, double y, double z) {
        player.x = x;
        player.y = y;
        player.z = z;
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, x, y, z);
    }

    private static int state(String block) {
        return DataTables.defaults().registry().block("minecraft:" + block).defaultState();
    }
}
