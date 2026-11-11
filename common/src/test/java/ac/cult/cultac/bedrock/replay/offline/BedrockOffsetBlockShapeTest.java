package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.api.BedrockBlockShapeContext;
import ac.cult.cultac.bedrock.prediction.api.BedrockCollisionShapeQuery;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionWorldBuilder;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.BedrockClientBlockShapeMappings;
import ac.cult.cultac.utils.collisions.ClientBlockShapes;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Bamboo and pointed dripstone are shifted on X and Z by a hash of the position the session's coordinate frame gives
 * them: one of sixteen steps across 0.25 either way for bamboo and 0.125 either way for pointed dripstone. A tip at
 * (20113, 120, 20030) so reaches x 20113.6458 in a session whose frame starts at (20080, 19984) and x 20113.6125 in
 * one that starts at (20032, 20000): a player whose box begins at x 20113.63895 stands on the first tip and falls
 * past the second.
 */
public class BedrockOffsetBlockShapeTest {
    private static final int TIP = DataTables.defaults()
            .registry()
            .with(
                    DataTables.defaults()
                            .registry()
                            .block("minecraft:pointed_dripstone")
                            .defaultState(),
                    "thickness",
                    "tip");
    private static final int BAMBOO =
            DataTables.defaults().registry().block("minecraft:bamboo").defaultState();
    private static final double TIP_TOP = 120.6875;
    private static final float GRAVITY_TICK = -0.0784F;
    private static final long FIRST_TICK = 7947;

    @Before
    public void loadShapes() {
        OfflineCultTestBootstrap.installConfig();
        BedrockClientBlockShapeMappings.initialize();
    }

    @After
    public void unloadShapes() {
        BedrockClientBlockShapeMappings.clear();
    }

    @Test
    public void pointedDripstoneTakesItsStepOfAnEighthEitherWay() {
        // (20113, 20030) hashes to steps 9 and 15 of 0..15: 0.025 and 0.125.
        WorldCollisionBox tip = box(TIP, new BlockPosition(20113, 120, 20030), BedrockCoordinateFrame.IDENTITY);
        assertEquals(20113.3375, tip.minX(), 1.0E-5);
        assertEquals(20113.7125, tip.maxX(), 1.0E-5);
        assertEquals(20030.4375, tip.minZ(), 1.0E-5);
        assertEquals(20030.8125, tip.maxZ(), 1.0E-5);
        assertEquals(120.0, tip.minY(), 0.0);
        assertEquals(120.6875, tip.maxY(), 1.0E-6);
    }

    @Test
    public void everyPointedDripstoneStateTakesTheOffsetOfItsPosition() {
        var position = new BlockPosition(20113, 120, 20030);
        for (String direction : List.of("up", "down")) {
            for (String thickness : List.of("tip_merge", "tip", "frustum", "middle", "base")) {
                WorldCollisionBox box = box(dripstone(thickness, direction), position, BedrockCoordinateFrame.IDENTITY);
                String state = thickness + " " + direction;
                assertEquals(state, 20113.5 + 0.025, (box.minX() + box.maxX()) / 2.0, 1.0E-5);
                assertEquals(state, 20030.5 + 0.125, (box.minZ() + box.maxZ()) / 2.0, 1.0E-5);
            }
        }
    }

    @Test
    public void bambooTakesItsStepOfAQuarterEitherWay() {
        // (20107, 20032) hashes to steps 0 and 4: -0.25 and -0.116667.
        WorldCollisionBox bamboo = box(BAMBOO, new BlockPosition(20107, 120, 20032), BedrockCoordinateFrame.IDENTITY);
        assertEquals(20107.25, bamboo.minX(), 1.0E-5);
        assertEquals(20107.375, bamboo.maxX(), 1.0E-5);
        assertEquals(20032.383333, bamboo.minZ(), 1.0E-5);
        assertEquals(20032.508333, bamboo.maxZ(), 1.0E-5);
    }

    @Test
    public void theHashReadsThePositionTheCoordinateFrameGivesTheBlock() {
        var frame = new BedrockCoordinateFrame(20080, 19984, 1);
        for (int state : new int[] {TIP, BAMBOO}) {
            WorldCollisionBox local = box(state, new BlockPosition(33, 120, 46), BedrockCoordinateFrame.IDENTITY);
            WorldCollisionBox world = box(state, new BlockPosition(20113, 120, 20030), frame);
            assertEquals(local.minX() + 20080, world.minX(), 1.0E-9);
            assertEquals(local.maxX() + 20080, world.maxX(), 1.0E-9);
            assertEquals(local.minZ() + 19984, world.minZ(), 1.0E-9);
            assertEquals(local.maxZ() + 19984, world.maxZ(), 1.0E-9);
        }
        // (33, 46) hashes to step 5 on X where (20113, 20030) hashes to step 9.
        assertEquals(20113.645833, box(TIP, new BlockPosition(20113, 120, 20030), frame).maxX(), 1.0E-5);
    }

    @Test
    public void aPlayerOverTheTipOfItsOwnSessionStandsOnIt() {
        withTip(player -> {
            var frame = new BedrockCoordinateFrame(20080, 19984, 1);
            clientTick(player, frame, FIRST_TICK, TIP_TOP, GRAVITY_TICK, true);
            clientTick(player, frame, FIRST_TICK + 1, TIP_TOP, GRAVITY_TICK, true);
            clientTick(player, frame, FIRST_TICK + 2, TIP_TOP, GRAVITY_TICK, true);
        });
    }

    @Test
    public void aPlayerBesideTheTipOfItsOwnSessionFallsPastIt() {
        withTip(player -> {
            var frame = new BedrockCoordinateFrame(20032, 20000, 1);
            clientTick(player, frame, FIRST_TICK, TIP_TOP, GRAVITY_TICK, false);
            clientTick(player, frame, FIRST_TICK + 1, TIP_TOP - 0.0784, -0.155232F, false);
            clientTick(player, frame, FIRST_TICK + 2, TIP_TOP - 0.0784 - 0.155232, -0.23052737F, false);
        });
    }

    @Test
    public void theBoxOtherChecksReadIsTheOneOfTheSession() {
        withTip(player -> {
            clientTick(player, new BedrockCoordinateFrame(20080, 19984, 1), FIRST_TICK, TIP_TOP, GRAVITY_TICK, true);
            List<SimpleCollisionBox> boxes = new ArrayList<>();
            ClientBlockShapes.movement(player, TIP, 20113, 120, 20030).downCast(boxes);
            assertEquals(1, boxes.size());
            assertEquals(20113.270833, boxes.getFirst().minX, 1.0E-5);
            assertEquals(20113.645833, boxes.getFirst().maxX, 1.0E-5);
            assertEquals(TIP_TOP, boxes.getFirst().maxY, 1.0E-6);
        });
    }

    private static int dripstone(String thickness, String direction) {
        var registry = DataTables.defaults().registry();
        int state = registry.block("minecraft:pointed_dripstone").defaultState();
        return registry.with(registry.with(state, "thickness", thickness), "vertical_direction", direction);
    }

    private static WorldCollisionBox box(int state, BlockPosition position, BedrockCoordinateFrame frame) {
        var context = new BedrockBlockShapeContext(
                at -> 0.0F,
                BedrockBlockShapeContext.NONE.movingBlockBoxes(),
                BedrockBlockShapeContext.NONE.pistonProgress(),
                frame);
        var boxes = new BedrockCollisionWorldBuilder(BedrockClientBlockShapeMappings.catalog(), context)
                .block(position, state, BedrockCollisionShapeQuery.NONE)
                .collisionBoxes();
        assertEquals(1, boxes.size());
        return boxes.getFirst();
    }

    private static void withTip(Consumer<CultPlayer> test) {
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            int stone = DataTables.defaults().registry().block("minecraft:stone").defaultState();
            for (int x = 20111; x <= 20116; x++)
                for (int z = 20028; z <= 20032; z++) {
                    player.compensatedWorld.ensureValidationChunkLoaded(x >> 4, z >> 4);
                    player.compensatedWorld.updateBlock(x, 119, z, stone);
                }
            player.compensatedWorld.updateBlock(20113, 120, 20030, TIP);
            test.accept(player);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    /** One client tick ending with the feet at {@code y} and the box starting at x 20113.63895, predicted exactly. */
    private static void clientTick(
            CultPlayer player,
            BedrockCoordinateFrame coordinates,
            long tick,
            double y,
            float velocityY,
            boolean verticalCollision) {
        var local = new Vec3((float) (20113.93895 - coordinates.originX()), (float) y, (float)
                (20030.5 - coordinates.originZ()));
        var feet = coordinates.toWorld(local);
        if (tick == FIRST_TICK) {
            player.x = player.lastX = feet.x;
            player.y = player.lastY = feet.y;
            player.z = player.lastZ = feet.z;
            player.onGround = player.lastOnGround = verticalCollision;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, feet.x, feet.y, feet.z);
        }
        var velocity = new Vec3(0, velocityY, 0);
        var frame = BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2193)
                .clientTick(tick)
                .inputMode(1)
                .playMode(2)
                .deviceId(3)
                .coordinateFrame(coordinates)
                .position(feet)
                .packetPosition(new Vec3(local.x, (float) (y + 1.62001), local.z))
                .rotation(0, 0, 0)
                .moveVector(0, 0)
                .delta(velocity)
                .reportedEndOfTickVelocity(velocity)
                .rawInputFlags(verticalCollision ? 1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal() : 0L)
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
                state.velocity().subtract(new Vec3d(0, velocityY, 0)).length();
        assertTrue("tick " + tick + " velocity offset " + velocityOffset, velocityOffset <= 0.001);
    }
}
