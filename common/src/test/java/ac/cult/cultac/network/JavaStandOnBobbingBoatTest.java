package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A player standing on a boat that floats without a controlling passenger. AbstractBoat#tick leaves the client's boat
 * to LinearInterpolationHandler#doInterpolate, which lerps its position a third, a half and all of the way to each
 * position sync, and Entity#collide lands the player on the top of the box at that position, up to 1e-7 above its
 * feet (VoxelShape#collideX).
 */
class JavaStandOnBobbingBoatTest {
    private static final int BOAT = 4901;
    private static final double X = 281.32534227595613;
    private static final double Z = 232.54583552834512;
    private static final double PLAYER_X = 281.02305419829855;
    private static final double PLAYER_Z = 232.53352726801702;
    private static final double BOAT_HEIGHT = 0.5625;
    private static final double GROUNDED_Y = -0.0784000015258789;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    /**
     * The boat's height and its position sync heights, each followed by four client ticks: the capture's, and one
     * block higher, where the box's top crosses 64.
     */
    static Stream<Arguments> bobbing() {
        return Stream.of(
                Arguments.of(62.523264312743876, new double[] {62.52326431274301, 62.52326431274325, 62.523264312743876}),
                Arguments.of(63.52326431274295, new double[] {63.52326431274335, 63.52326431274239, 63.52326431274321}));
    }

    @ParameterizedTest
    @MethodSource("bobbing")
    void standingOnABoatInterpolatingUpAndDownIsExact(double boatY, double[] syncs) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.ensureValidationChunkLoaded(17, 14);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, new Vec3(X, boatY, Z), 0, 0, 0);
            double playerY = boatY + BOAT_HEIGHT;
            Vec3 start = new Vec3(PLAYER_X, playerY, PLAYER_Z);
            f.place(start, new Vec3(0.0, GROUNDED_Y, 0.0));
            f.player.onGround = f.player.lastOnGround = true;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.fromBoolean(true));
            f.player.xRot = f.player.lastTickXRot = 0.0F;
            f.input(NONE);
            int tick = 0;
            for (double target : syncs) {
                positionSync(f, target);
                for (int step = 0; step < 4; step++) {
                    // The player ticks before the boat, so it collides with the boat's box of the previous tick.
                    double top = boatY + BOAT_HEIGHT;
                    playerY = playerY + (top - playerY);
                    PredictionResult result = f.tick(new Vec3(PLAYER_X, playerY, PLAYER_Z), true);
                    assertTrue(exact(result), "tick " + tick + " boat " + boatY + ": " + describe(result));
                    tick++;
                    // LinearInterpolationHandler#doInterpolate over three steps.
                    if (step < 3) boatY = boatY + (target - boatY) * (1.0 / (3 - step));
                }
            }
        }
    }

    private static void positionSync(JavaTickFixture f, double y) {
        var sync = new ClientboundEntityPositionSync(
                BOAT, new PositionPath.Linear(new Vec3d(X, y, Z)), 81.71743F, 0.0F, false);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_POSITION_SYNC, sync, false);
        f.player.packetEntityReplication.onEntityPositionSync(event, f.player, sync);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }
}
