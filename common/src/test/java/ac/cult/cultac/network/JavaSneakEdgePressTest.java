package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A 26.3 client walking diagonally across a one-block-wide hole presses shift on the tick its box leaves the floor on
 * one axis. LocalPlayer#aiStep sets crouching from the previous keys, so that tick's input is unslowed, while
 * Player#maybeBackOffFromEdge already reads the new shift key and holds the axis over the hole. deltaMovement keeps the
 * unslowed delta on that axis, and the next, crouched tick moves with it. The moves are a capture's, translated onto
 * the fixture floor; the hole is the floor's blocks at x = 0, z = 0 and z = 1.
 */
class JavaSneakEdgePressTest {
    private static final ServerboundPlayerInput WALK =
            new ServerboundPlayerInput(true, false, true, false, false, false, false);
    private static final ServerboundPlayerInput WALK_SNEAK =
            new ServerboundPlayerInput(true, false, true, false, false, true, false);
    private static final double GROUND_FRICTION = 0.6F * 0.91F;

    @Test
    void theTickAfterAPressMovesWithTheUnslowedDeltaTheEdgeHid() throws Exception {
        try (var f = start()) {
            assertExact(f, 9.105514526367188F, 67.8520736694336F, 0.782098951135, 0.317263363135);
            assertExact(f, 32.15744400024414F, 72.5227279663086F, 0.91825391302, 0.481571041728);
            assertExact(f, 83.38395690917969F, 72.37206268310547F, 0.956453456398, 0.61691323819);
        }
    }

    @Test
    void aTickAfterAPressPastTheHiddenDeltaFlags() throws Exception {
        try (var f = start()) {
            assertExact(f, 9.105514526367188F, 67.8520736694336F, 0.782098951135, 0.317263363135);
            assertFlagged(f, 32.15744400024414F, 72.5227279663086F, 0.91825391302, 0.481571041728 + 0.01);
        }
    }

    @Test
    void aTickAfterAPressThatSkipsTheSneakSlowFlags() throws Exception {
        try (var f = start()) {
            assertExact(f, 9.105514526367188F, 67.8520736694336F, 0.782098951135, 0.317263363135);
            // The hidden z carried at ground friction plus the uncrouched input of the capture's tick.
            assertFlagged(f, 32.15744400024414F, 72.5227279663086F, 0.91825391302 + 0.1, 0.481571041728 + 0.1);
        }
    }

    /** The fixture at the capture's tick before the press, walking with Speed II. */
    private static JavaTickFixture start() throws Exception {
        var f = new JavaTickFixture();
        for (int z = 0; z <= 1; z++) {
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y - 1, z), BlockIds.AIR.defaultState());
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        var speed =
                new AttributeModifier("minecraft:effect.speed", 0.4, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        var attributes = new ClientboundUpdateAttributes(
                f.player.entityID, List.of(new AttributeSnapshot("minecraft:movement_speed", 0.1F, List.of(speed))));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_ATTRIBUTES, attributes, false);
        f.player.packetEntityReplication.onUpdateAttributes(event, f.player, attributes);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());

        f.place(
                at(0.293455940144, 0.164190136471),
                new Vec3(0.2666280375815404 * GROUND_FRICTION, 0.0, 0.1406542453369184 * GROUND_FRICTION));
        // The tick's ground state lives in the simulation context.
        f.player.lastOnGround = false;
        f.input(WALK);
        assertExact(f, -11.987753868103027F, 61.37342834472656F, 0.556428238542, 0.317263363135);
        f.input(WALK_SNEAK);
        return f;
    }

    private static void assertExact(JavaTickFixture f, float yaw, float pitch, double x, double z) {
        PredictionResult result = tick(f, yaw, pitch, x, z);
        assertTrue(exact(result), describe(result));
    }

    private static void assertFlagged(JavaTickFixture f, float yaw, float pitch, double x, double z) {
        PredictionResult result = tick(f, yaw, pitch, x, z);
        assertFalse(exact(result), describe(result));
    }

    private static PredictionResult tick(JavaTickFixture f, float yaw, float pitch, double x, double z) {
        f.player.xRot = yaw;
        f.player.yRot = pitch;
        return f.tick(at(x, z), true);
    }

    /** A capture position, translated onto the fixture floor. */
    private static Vec3 at(double x, double z) {
        return new Vec3(x, FLOOR_Y, z);
    }
}
