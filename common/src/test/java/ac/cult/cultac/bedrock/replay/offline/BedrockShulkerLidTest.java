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
import ac.cult.cultac.protocol.value.Direction;
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
 * whose peek steps 0.05 towards the last handled peek data at the end of each client tick. A shulker on any other face
 * opens the same way away from that face.
 */
public class BedrockShulkerLidTest {
    private static final int SHULKER = 5797;
    private static final double X = 61.5;
    private static final double Z = 21.5;
    private static final float GRAVITY_TICK = -0.0784F;

    /**
     * Fully open shulkers on each face, and the faces a player walking or jumping into them stops at: the face opposite
     * the attach face stands the physical peek out, the box 0.98 tall and each X and Z face 0.0001 in.
     */
    @Test
    public void eachAttachFaceOpensTheBoxAwayFromIt() {
        // attach face, peek, feet X/Y/Z, then min X/Y/Z and max X/Y/Z
        assertOpenBox(Direction.NORTH, 100, 109.5, 120, -14.5, 109.0001, 120, -14.9999, 109.9999, 120.98, -13.0001);
        assertOpenBox(Direction.NORTH, 0, 109.5, 120, -14.5, 109.0001, 120, -14.9999, 109.9999, 120.98, -14.0001);
        assertOpenBox(Direction.SOUTH, 100, 109.5, 120, -16.5, 109.0001, 120, -17.9999, 109.9999, 120.98, -16.0001);
        assertOpenBox(Direction.WEST, 100, 115.5, 120, -27.5, 115.0001, 120, -27.9999, 116.9999, 120.98, -27.0001);
        assertOpenBox(Direction.EAST, 100, 114.5, 120, -27.5, 113.0001, 120, -27.9999, 114.9999, 120.98, -27.0001);
        assertOpenBox(Direction.UP, 100, 108.5, 123, -27.5, 108.0001, 122, -27.9999, 108.9999, 123.98, -27.0001);
        assertOpenBox(Direction.UP, 50, 108.5, 123, -27.5, 108.0001, 122.5, -27.9999, 108.9999, 123.98, -27.0001);
    }

    private static void assertOpenBox(Direction face, int peek, double... feetAndBounds) {
        var feet = new Vec3(feetAndBounds[0], feetAndBounds[1], feetAndBounds[2]);
        withOpenShulker(face, peek, feet, (player, shulker) -> {
            SimpleCollisionBox box = shulker.bedrockShownBox(player, new SimpleCollisionBox());
            double[] actual = {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ};
            for (int i = 0; i < 6; i++)
                assertEquals(face + " " + peek + " bound " + i, feetAndBounds[3 + i], actual[i], 2.0E-6);
        });
    }

    /** A move may leave a shulker along the axis its lid opens on only, by up to 1.1 times the lid. */
    @Test
    public void aShulkerLimitsTheMoveAlongTheAxisItsLidOpensOnOnly() {
        var feet = new Vec3(109.5, 120, -14.5);
        assertLimit(Direction.NORTH, 0, feet, 0.0F, 0.0F, 0.0F);
        assertLimit(Direction.NORTH, 100, feet, 0.0F, 0.0F, 1.1F);
        assertLimit(Direction.WEST, 100, feet, 1.1F, 0.0F, 0.0F);
        assertLimit(Direction.DOWN, 100, feet, 0.0F, 1.1F, 0.0F);
        assertLimit(Direction.UP, 50, feet, 0.0F, 0.55F, 0.0F);
    }

    private static void assertLimit(Direction face, int peek, Vec3 feet, float x, float y, float z) {
        withOpenShulker(face, peek, feet, (player, shulker) -> {
            var limit = shulker.bedrockDepenetrationLimit();
            assertEquals(face + " " + peek, x, limit.x(), 1.0E-6F);
            assertEquals(face + " " + peek, y, limit.y(), 1.0E-6F);
            assertEquals(face + " " + peek, z, limit.z(), 1.0E-6F);
        });
    }

    /** A shulker at {@code feet} on {@code face} whose lid has stepped to {@code peek} data. */
    private static void withOpenShulker(
            Direction face, int peek, Vec3 feet, java.util.function.BiConsumer<CultPlayer, PacketEntityShulker> test) {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.compensatedWorld.ensureValidationChunkLoaded(
                    (int) Math.floor(feet.x) >> 4, (int) Math.floor(feet.z) >> 4);
            player.compensatedEntities.addEntity(SHULKER, EntityTypeIds.SHULKER, feet, 0, 0, 0);
            var none = ByteBuffer.allocate(0);
            player.compensatedEntities.updateEntityMetadata(
                    SHULKER,
                    List.of(
                            new EntityMetadata.Entry(WatchableIndexUtil.SHULKER_ATTACH_FACE, face, none),
                            new EntityMetadata.Entry(WatchableIndexUtil.SHULKER_PEEK, (byte) peek, none)));
            for (int tick = 0; tick < 25; tick++) player.checkManager.endBedrockClientTick();
            test.accept(player, shulker(player));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void theBoxIsAUnitWideAndItsLidRaisesTheTop() {
        withShulker(player -> {
            assertBox(player, 120.98);
            peek(player, 100);
            assertBox(player, 120.98);
            double[] tops = {120.98615, 121.00446, 121.03449, 121.07548, 121.12645, 121.18610, 121.25298, 121.32549};
            for (double top : tops) {
                player.checkManager.endBedrockClientTick();
                assertBox(player, top);
            }
            for (int tick = 8; tick < 25; tick++) player.checkManager.endBedrockClientTick();
            assertBox(player, 121.98);
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

    /** An opening lid carries the player standing on it up with every step, the largest 0.078 in a tick. */
    @Test
    public void anOpeningLidLiftsThePlayerStandingOnIt() {
        withShulker(player -> {
            standAt(player, 120.98);
            clientTick(player, 25216, 120.98);
            peek(player, 100);
            clientTick(player, 25217, 120.98);
            double[] feet = {
                120.98615, 121.00446, 121.03449, 121.07548, 121.12645, 121.18610, 121.25298, 121.32549, 121.40176,
                121.47996, 121.55821, 121.63448, 121.70699, 121.77387, 121.83356, 121.88450, 121.92549, 121.95553,
                121.97385, 121.98000, 121.98000
            };
            for (int i = 0; i < feet.length; i++) clientTick(player, 25218 + i, feet[i]);
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
            shulker(player).bedrockCollidable = true;
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

    private static void assertBox(CultPlayer player, double top) {
        SimpleCollisionBox box = shulker(player).bedrockShownBox(player, new SimpleCollisionBox());
        assertEquals(61.0001, box.minX, 1.0E-7);
        assertEquals(61.9999, box.maxX, 1.0E-7);
        assertEquals(21.0001, box.minZ, 1.0E-7);
        assertEquals(21.9999, box.maxZ, 1.0E-7);
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
