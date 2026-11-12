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
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityShulker;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Consumer;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

/** A boat's shulker passenger remains a solid actor for the player walking beside it. */
public class BedrockMountedShulkerTest {
    private static final int BOAT = 5801;
    private static final int SHULKER = 5802;
    private static final float BOAT_X = 316.3F;
    private static final float BOAT_Y = 120.0F;
    private static final float Z = -34.5F;
    // Geyser's shulker seat: 0.1875 + half shulker height - half boat height.
    private static final Vec3 SEAT = new Vec3(0, 0.3875F, 0);

    @Test
    public void anIdlePlayerLeavesTheMountedShulkerWithoutInventingInput() {
        withBoat(player -> {
            // The closed shell's west face ends the player's centre at this float coordinate. The initial overlap
            // is 0.001617431640625, the sideways displacement that a stationary prediction used to reject.
            float stopped = 315.5001F;
            standAt(player, stopped + 0.001617431640625F);
            assertTrue(player.compensatedEntities.isUntrackedRider(shulker(player)));
            tick(player, 100, stopped);
            tick(player, 101, stopped);
        });
    }

    @Test
    public void walkingIntoTheMountedShellStopsAtItsFace() {
        withBoat(player -> {
            standAt(player, 315.45F);
            tick(player, 100, 315.45F);
            tick(player, 101, 315.5001F, -90, 0, 1);
            tick(player, 102, 315.5001F, -90, 0, 1);
        });
    }

    @Test
    public void anActorWithoutTheCollidableFlagStillDoesNotStopMovement() {
        withBoat(player -> {
            shulker(player).bedrockCollidable = false;
            float x = 315.5017F;
            standAt(player, x);
            tick(player, 100, x);
        });
    }

    @Test
    public void aPassengerFollowsTheShownBoatInsteadOfItsOldSpawnOrNewLerpTarget() {
        withBoat(player -> {
            PacketEntity boat = boat(player);
            assertSeat(player, BOAT_X, 120.7625F, Z);
            boat.handleBedrockMove(player, new Vec3(BOAT_X + 0.3F, BOAT_Y, Z), 0, 0, false, false, true);
            assertSeat(player, BOAT_X, 120.7625F, Z);
            player.checkManager.endBedrockClientTick();
            assertSeat(player, BOAT_X + 0.1F, 120.7625F, Z);
            player.checkManager.endBedrockClientTick();
            assertSeat(player, BOAT_X + 0.2F, 120.7625F, Z);
        });
    }

    @Test
    public void seatRotationUsesTheBedrockBoatYawAndThePassengerOffset() {
        withBoat(player -> {
            shulker(player).bedrockSeatOffset = new Vec3(0.2F, SEAT.y, 0);
            boat(player).clientPhysicalYaw = 0; // Bedrock boat yaw is 90 degrees above the tracked Java yaw.
            assertSeat(player, BOAT_X, 120.7625F, Z + 0.2F);
            boat(player).clientPhysicalYaw = 90;
            assertSeat(player, BOAT_X - 0.2F, 120.7625F, Z);
        });
    }

    @Test
    public void aMountedShulkersLidStillStepsOncePerClientTick() {
        withBoat(player -> {
            var shulker = shulker(player);
            double closed = shulker.bedrockShownBox(player, new SimpleCollisionBox()).maxY;
            player.compensatedEntities.updateEntityMetadata(
                    SHULKER,
                    List.of(new EntityMetadata.Entry(
                            WatchableIndexUtil.SHULKER_PEEK, (byte) 100, ByteBuffer.allocate(0))));
            assertEquals(closed, shulker.bedrockShownBox(player, new SimpleCollisionBox()).maxY, 0);
            player.checkManager.endBedrockClientTick();
            assertEquals(closed + 0.00615, shulker.bedrockShownBox(player, new SimpleCollisionBox()).maxY, 4.0E-6);
            for (int i = 1; i < 20; i++) player.checkManager.endBedrockClientTick();
            assertEquals(closed + 1, shulker.bedrockShownBox(player, new SimpleCollisionBox()).maxY, 2.0E-6);
        });
    }

    @Test
    public void aDismountAnchorsAtTheBedrockSeat() {
        withBoat(player -> {
            var rider = shulker(player);
            player.compensatedEntities.settleIfUntrackedRider(rider);
            rider.eject();
            assertSeat(player, BOAT_X, 120.7625F, Z);
        });
    }

    private static void assertSeat(CultPlayer player, double x, double y, double z) {
        SimpleCollisionBox box = shulker(player).bedrockShownBox(player, new SimpleCollisionBox());
        assertEquals(x, (box.minX + box.maxX) * 0.5, 3.1E-5);
        assertEquals(y, box.minY, 2.0E-6);
        assertEquals(z, (box.minZ + box.maxZ) * 0.5, 4.0E-6);
    }

    private static PacketEntity boat(CultPlayer player) {
        return player.compensatedEntities.getEntity(BOAT);
    }

    private static PacketEntityShulker shulker(CultPlayer player) {
        return (PacketEntityShulker) player.compensatedEntities.getEntity(SHULKER);
    }

    private static void withBoat(Consumer<CultPlayer> test) {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            int stone =
                    DataTables.defaults().registry().block("minecraft:stone").defaultState();
            for (int x = 313; x <= 319; x++)
                for (int z = -37; z <= -32; z++) {
                    player.compensatedWorld.ensureValidationChunkLoaded(x >> 4, z >> 4);
                    player.compensatedWorld.updateBlock(x, 119, z, stone);
                }
            player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, new Vec3(BOAT_X, BOAT_Y, Z), -90, 0, 0);
            PacketEntity boat = boat(player);
            boat.bedrockRuntimeId = 6001;
            boat.bedrockPositionOffset = 0.375F;
            boat.clientPhysicalYaw = -90;
            boat.setBedrockSize(1.6F, 0.6F, 1.0F);
            boat.refreshDimensions(player);
            boat.bedrockCollidable = true;
            // A different spawn position proves that collision does not read a rider's stale packet position.
            player.compensatedEntities.addEntity(SHULKER, EntityTypeIds.SHULKER, new Vec3(320.5, 120, Z), 0, 0, 0);
            var rider = shulker(player);
            rider.bedrockCollidable = true;
            rider.bedrockSeatOffset = SEAT;
            rider.mount(boat);
            test.accept(player);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static void standAt(CultPlayer player, float x) {
        player.x = player.lastX = x;
        player.y = player.lastY = 120;
        player.z = player.lastZ = Z;
        player.onGround = player.lastOnGround = true;
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, x, 120, Z);
    }

    private static void tick(CultPlayer player, long tick, float x) {
        tick(player, tick, x, 0, 0, 0);
    }

    private static void tick(CultPlayer player, long tick, float x, float yaw, float inputX, float inputZ) {
        Vec3 velocity = new Vec3(0, -0.0784F, 0);
        long flags = (1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal())
                | (1L << PlayerAuthInputData.HORIZONTAL_COLLISION.ordinal());
        var frame = BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2225)
                .clientTick(tick)
                .inputMode(1)
                .playMode(2)
                .deviceId(3)
                .position(new Vec3(x, 120, Z))
                .packetPosition(new Vec3(x, 121.62001F, Z))
                .rotation(yaw, 0, yaw)
                .moveVector(inputX, inputZ)
                .delta(velocity)
                .reportedEndOfTickVelocity(velocity)
                .rawInputFlags(flags)
                .build();
        player.bedrockState.offerAuthInputFrame(frame);
        var result = player.checkManager
                .getSimulationProcessor()
                .processBedrockAuthInputFrame(frame, BedrockPredictionTrigger.OFFLINE_REPLAY);
        assertNotNull(result);
        var prediction = result.getProfileResult(BedrockPredictionResult.class);
        assertNotNull(prediction);
        var state = prediction.nextTickBaseState();
        double positionOffset =
                state.physicalFeetPosition().subtract(new Vec3d(x, 120, Z)).length();
        double velocityOffset =
                state.velocity().subtract(new Vec3d(0, -0.0784F, 0)).length();
        assertTrue("position offset " + positionOffset, positionOffset <= 0.001);
        assertTrue("velocity offset " + velocityOffset, velocityOffset <= 0.001);
        assertEquals(0, player.checkManager.getListener(BedrockMovement.class).violations, 0);
        player.checkManager.endBedrockClientTick();
    }
}
