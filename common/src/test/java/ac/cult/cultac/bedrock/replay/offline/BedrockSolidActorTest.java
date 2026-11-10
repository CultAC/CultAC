package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.impl.bedrock.BedrockMovement;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

/**
 * An entity the client holds with the collidable flag is a solid shape of the player's move, at the box its last
 * lerp step left it: a boat whose side is 0.05 inside a standing player moves it out by that much, with no speed.
 */
public class BedrockSolidActorTest {
    private static final int BOAT = 5801;
    private static final double X = 61.5;
    private static final double Z = 21.5;
    // The boat's west side lies 0.05 inside the player's east side at x 61.8.
    private static final double OVERLAPPING = 61.8 - 0.05 + 0.8;
    private static final float GRAVITY_TICK = -0.0784F;
    private static final long VERTICAL = 1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal();
    private static final long HORIZONTAL = 1L << PlayerAuthInputData.HORIZONTAL_COLLISION.ordinal();

    @Test
    public void aCollidableBoatMovesTheStandingPlayerOutOfItsBox() {
        withBoat(OVERLAPPING, true, player -> {
            Vec3d moved = clientTick(player, 100, X - 0.05, VERTICAL | HORIZONTAL);
            assertEquals(X - 0.05, moved.x(), 1.0E-5);
            assertEquals(Z, moved.z(), 0);
            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
            // Flush with the boat, the next tick stays.
            assertEquals(X - 0.05, clientTick(player, 101, X - 0.05, VERTICAL).x(), 1.0E-5);
            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
        });
    }

    @Test
    public void aBoatWithoutTheFlagIsNoShapeOfTheMove() {
        withBoat(OVERLAPPING, false, player -> assertEquals(X, clientTick(player, 100, X, VERTICAL).x(), 0));
    }

    @Test
    public void aBoatDeeperThanTheLimitIsNoShapeOfTheMove() {
        withBoat(OVERLAPPING - 0.15, true, player -> assertEquals(X, clientTick(player, 100, X, VERTICAL).x(), 0));
    }

    /** A move the client handled changes where the boat lerps to; the player's move meets the last step's box. */
    @Test
    public void theMoveMeetsTheBoxTheLastLerpStepLeft() {
        withBoat(OVERLAPPING + 0.3, true, player -> {
            PacketEntity boat = player.compensatedEntities.getEntity(BOAT);
            boat.handleBedrockMove(player, new Vec3(OVERLAPPING, 120.0, Z), 0, 0, false, false, true);
            // The boat still stands 0.25 clear of the player in this tick's move, and steps 0.1 closer after it.
            assertEquals(X, clientTick(player, 100, X, VERTICAL).x(), 0);
            assertEquals(X, clientTick(player, 101, X, VERTICAL).x(), 0);
            assertEquals(X, clientTick(player, 102, X, VERTICAL).x(), 0);
            // The third step put its side 0.05 inside the player.
            assertEquals(X - 0.05, clientTick(player, 103, X - 0.05, VERTICAL | HORIZONTAL).x(), 1.0E-5);
            assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
        });
    }

    /** A boat moved 0.3 further on each of five ticks takes one step of the newest move's three every tick. */
    @Test
    public void aBoatMovedEveryTickStepsTowardsTheNewestMove() {
        withBoat(60.0, true, player -> {
            PacketEntity boat = player.compensatedEntities.getEntity(BOAT);
            double[] shown = {60.0, 60.1, 60.26667, 60.47778, 60.71852, 60.97902, 61.23951, 61.5, 61.5};
            for (int tick = 0; tick < shown.length; tick++) {
                if (tick < 5) {
                    boat.handleBedrockMove(player, new Vec3(60.3 + 0.3 * tick, 120.0, Z), 0, 0, false, true, false);
                }
                SimpleCollisionBox box = boat.bedrockShownBox(player, new SimpleCollisionBox());
                assertEquals("tick " + tick, shown[tick], (box.minX + box.maxX) / 2, 2.0E-5);
                assertEquals("tick " + tick, 1.6, box.maxX - box.minX, 1.0E-6);
                assertNull(boat.oldPacketLocation);
                assertTrue(boat.clientPhysicalPositionExact);
                player.checkManager.endBedrockClientTick();
            }
        });
    }

    private static void withBoat(double x, boolean collidable, java.util.function.Consumer<CultPlayer> test) {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            int stone = DataTables.defaults().registry().block("minecraft:stone").defaultState();
            for (int bx = 59; bx <= 64; bx++)
                for (int bz = 19; bz <= 23; bz++) {
                    player.compensatedWorld.ensureValidationChunkLoaded(bx >> 4, bz >> 4);
                    player.compensatedWorld.updateBlock(bx, 119, bz, stone);
                }
            player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, new Vec3(x, 120.0, Z), 0, 0, 0);
            PacketEntity boat = player.compensatedEntities.getEntity(BOAT);
            boat.setBedrockSize(1.6F, 0.6F, 1.0F);
            boat.refreshDimensions(player);
            boat.bedrockCollidable = collidable;
            player.x = player.lastX = X;
            player.y = player.lastY = 120.0;
            player.z = player.lastZ = Z;
            player.onGround = player.lastOnGround = true;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, X, 120.0, Z);
            test.accept(player);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    /** One client tick reported standing at {@code x}; the feet the engine predicts for it. */
    private static Vec3d clientTick(CultPlayer player, long tick, double x, long flags) {
        var feet = new Vec3((float) x, 120.0, Z);
        var velocity = new Vec3(0, GRAVITY_TICK, 0);
        var frame = BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2193)
                .clientTick(tick)
                .inputMode(1)
                .playMode(2)
                .deviceId(3)
                .position(feet)
                .packetPosition(new Vec3((float) x, 121.62001F, Z))
                .rotation(0, 0, 0)
                .moveVector(0, 0)
                .delta(velocity)
                .reportedEndOfTickVelocity(velocity)
                .rawInputFlags(flags)
                .build();
        player.bedrockState.offerAuthInputFrame(frame);
        var result = player.checkManager
                .getSimulationProcessor()
                .processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.OFFLINE_REPLAY);
        assertNotNull("tick " + tick, result);
        var state = result.getProfileResult(BedrockPredictionResult.class).nextTickBaseState();
        assertNotNull("tick " + tick, state);
        assertEquals("tick " + tick, GRAVITY_TICK, (float) state.velocity().y(), 1.0E-4F);
        assertEquals("tick " + tick, 0.0, state.velocity().x(), 0);
        player.checkManager.endBedrockClientTick();
        return state.physicalFeetPosition();
    }
}
