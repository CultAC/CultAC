package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntityShulker;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

/**
 * A Bedrock player on a floor shulker at (61.5, 120, 21.5) stands 0.98 above the shulker's feet and follows its lid,
 * whose peek steps 0.05 towards the last handled peek data at the end of each client tick.
 */
public class BedrockShulkerLidTest {
    private static final int SHULKER = 5797;
    private static final double X = 61.5;
    private static final double Z = 21.5;
    private static final float GRAVITY_TICK = -0.0784F;

    @Test
    public void theBoxIsAUnitWideAndItsLidRaisesTheTop() {
        withShulker(player -> {
            PacketEntityShulker shulker = shulker(player);
            assertBox(shulker, 120.98);
            peek(player, 100);
            assertBox(shulker, 120.98);
            double[] tops = {120.98615, 121.00446, 121.03449, 121.07548, 121.12645, 121.18610, 121.25298, 121.32549};
            for (double top : tops) {
                player.checkManager.endBedrockClientTick();
                assertBox(shulker, top);
            }
            for (int tick = 8; tick < 25; tick++) player.checkManager.endBedrockClientTick();
            assertBox(shulker, 121.98);
        });
    }

    /** Peek data handled between two ticks leaves the first of them unmoved and raises the second by one step. */
    @Test
    public void theFirstLidStepReachesThePlayerTwoTicksAfterTheDataIsAcknowledged() {
        withShulker(player -> {
            standAt(player, 120.98);
            for (long tick = 25213; tick <= 25216; tick++) clientTick(player, tick, 120.98);
            peek(player, 100);
            clientTick(player, 25217, 120.98);
            clientTick(player, 25218, 120.98615);
        });
    }

    /**
     * Peek 0 handled while the lid is one step from fully open: the next tick takes that last opening step and the
     * one after it the first closing step.
     */
    @Test
    public void aLidTurnsAroundOneTickAfterTheDataAndLowersThePlayer() {
        withShulker(player -> {
            peek(player, 100);
            for (int tick = 0; tick < 19; tick++) player.checkManager.endBedrockClientTick();
            standAt(player, 121.97385);
            clientTick(player, 25236, 121.97385);
            peek(player, 0);
            double[] feet = {
                121.98000, 121.97385, 121.95553, 121.92549, 121.88450, 121.83356, 121.77387, 121.70699, 121.63448,
                121.55821, 121.47996, 121.40176, 121.32549
            };
            for (int i = 0; i < feet.length; i++) clientTick(player, 25237 + i, feet[i]);
        });
    }

    /** Peek 30 lowers a fully open lid to 121.1861, where it holds. */
    @Test
    public void aLidStopsAtAPartialPeek() {
        withShulker(player -> {
            peek(player, 100);
            for (int tick = 0; tick < 22; tick++) player.checkManager.endBedrockClientTick();
            standAt(player, 121.98);
            peek(player, 30);
            double[] feet = {
                121.98000, 121.97385, 121.95553, 121.92549, 121.88450, 121.83356, 121.77387, 121.70699, 121.63448,
                121.55821, 121.47996, 121.40176, 121.32549, 121.25298, 121.18610, 121.18610, 121.18610, 121.18610,
                121.18610
            };
            for (int i = 0; i < feet.length; i++) clientTick(player, 25317 + i, feet[i]);
        });
    }

    private static void withShulker(java.util.function.Consumer<CultPlayer> test) {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            int stone = DataTables.defaults().registry().block("minecraft:stone").defaultState();
            for (int x = 59; x <= 63; x++)
                for (int z = 19; z <= 23; z++) {
                    player.compensatedWorld.ensureValidationChunkLoaded(x >> 4, z >> 4);
                    player.compensatedWorld.updateBlock(x, 119, z, stone);
                }
            player.compensatedEntities.addEntity(SHULKER, EntityTypeIds.SHULKER, new Vec3(X, 120.0, Z), 0, 0, 0);
            test.accept(player);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static PacketEntityShulker shulker(CultPlayer player) {
        return (PacketEntityShulker) player.compensatedEntities.getEntity(SHULKER);
    }

    /** The peek data the client has handled. */
    private static void peek(CultPlayer player, int peek) {
        player.compensatedEntities.updateEntityMetadata(
                SHULKER,
                List.of(new EntityMetadata.Entry(
                        WatchableIndexUtil.SHULKER_PEEK, (byte) peek, ByteBuffer.allocate(0))));
    }

    private static void assertBox(PacketEntityShulker shulker, double top) {
        SimpleCollisionBox box = shulker.bedrockCollisionBox(new SimpleCollisionBox(), new SimpleCollisionBox());
        assertNotNull(box);
        assertEquals(61.0, box.minX, 0.0);
        assertEquals(62.0, box.maxX, 0.0);
        assertEquals(21.0, box.minZ, 0.0);
        assertEquals(22.0, box.maxZ, 0.0);
        assertEquals(120.0, box.minY, 0.0);
        assertEquals(top, box.maxY, 5.0E-5);
    }

    private static void standAt(CultPlayer player, double y) {
        player.x = player.lastX = X;
        player.y = player.lastY = y;
        player.z = player.lastZ = Z;
        player.onGround = player.lastOnGround = true;
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, X, y, Z);
    }

    /** One client tick that ends standing with the feet at {@code y}, which the engine must predict without a flag. */
    private static void clientTick(CultPlayer player, long tick, double y) {
        var feet = new Vec3(X, (float) y, Z);
        var velocity = new Vec3(0, GRAVITY_TICK, 0);
        var frame = BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2193)
                .clientTick(tick)
                .inputMode(1)
                .playMode(2)
                .deviceId(3)
                .position(feet)
                .packetPosition(new Vec3(X, (float) (y + 1.62001), Z))
                .rotation(0, 0, 0)
                .moveVector(0, 0)
                .delta(velocity)
                .reportedEndOfTickVelocity(velocity)
                .rawInputFlags(1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal())
                .build();
        player.bedrockState.offerAuthInputFrame(frame);
        var result = player.checkManager
                .getSimulationProcessor()
                .processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.OFFLINE_REPLAY);
        assertNotNull("tick " + tick, result);
        var state = result.getProfileResult(BedrockPredictionResult.class).nextTickBaseState();
        assertNotNull("tick " + tick, state);
        assertEquals(
                "tick " + tick, 0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
        double offset = state.physicalFeetPosition()
                .subtract(new Vec3d(feet.x, feet.y, feet.z))
                .length();
        assertTrue("tick " + tick + " position offset " + offset, offset <= 0.001);
        double velocityOffset =
                state.velocity().subtract(new Vec3d(0, GRAVITY_TICK, 0)).length();
        assertTrue("tick " + tick + " velocity offset " + velocityOffset, velocityOffset <= 0.001);
        player.checkManager.endBedrockClientTick();
    }
}
