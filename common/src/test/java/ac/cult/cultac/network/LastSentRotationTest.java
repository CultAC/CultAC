package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LocalPlayer#sendPosition sends a rotation only when it differs from yRotLast and xRotLast, the last one it sent. A
 * teleport acceptance turns the client without changing them, so a position-only move after it is back at the last one.
 */
class LastSentRotationTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final Vec3 STANDING = new Vec3(0.5, FLOOR_Y, 0.5);
    // Facing -x, one walking step from rest on stone.
    private static final float WEST = 90;
    private static final Vec3 STEP_WEST = new Vec3(0.5 - 0.1F * 0.98F, FLOOR_Y, 0.5);
    // The same step off ground, as the first tick after a dismount.
    private static final Vec3 AIR_STEP_WEST = new Vec3(0.5 - 0.02F * 0.98F, FLOOR_Y, 0.5);
    // The yaw the client sent a tick before WEST, one mouse count of 0.15 degrees short.
    private static final float WEST_BEFORE = WEST - 0.15F;
    // No movement input at this yaw walks due west.
    private static final float FAR_YAW = 20;
    private static final int VEHICLE = 100;
    private static final Vec3 SEAT = new Vec3(3.5, FLOOR_Y, 3.5);

    @Test
    void aPositionOnlyStepWalksAlongTheLastSentYaw() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(STANDING, Vec3.ZERO);
            turn(f, WEST);

            f.input(FORWARD);
            PredictionResult step = step(f, STEP_WEST);
            assertTrue(exact(step), describe(step));
        }
    }

    /** The teleport's look is a tick behind; the mouse turn after the acceptance lands back on the last sent yaw. */
    @Test
    void aPositionOnlyStepAfterATeleportAcceptanceWalksAlongTheLastSentYaw() throws Exception {
        stepAfterAnAcceptanceAt(WEST_BEFORE);
    }

    @Test
    void aPositionOnlyStepAfterATeleportAcceptanceFarFromTheLastSentYawWalksAlongIt() throws Exception {
        stepAfterAnAcceptanceAt(FAR_YAW);
    }

    /** LocalPlayer#sendChanges sends passenger Rot without writing yRotLast, which still holds the pre-mount yaw. */
    @Test
    void aPositionOnlyStepAfterADismountTeleportToThePreMountYawWalksAlongIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(STANDING, Vec3.ZERO);
            turn(f, WEST);
            f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.ARMOR_STAND, SEAT, 0, 0, 0);
            passengers(f, List.of(f.player.entityID));
            turn(f, FAR_YAW);
            passengers(f, List.of());
            f.teleport(f.player.sendTrailingTransaction(), STANDING, new RelativeFlag(0), FAR_YAW, 0, WEST, 0);

            f.input(FORWARD);
            PredictionResult step = step(f, AIR_STEP_WEST, false);
            assertTrue(exact(step), describe(step));
            assertEquals(WEST, f.player.xRot);
        }
    }

    private static void stepAfterAnAcceptanceAt(float teleportYaw) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(STANDING, Vec3.ZERO);
            turn(f, WEST);
            f.teleport(f.player.sendTrailingTransaction(), STANDING, new RelativeFlag(0), WEST, 0, teleportYaw, 0);
            assertEquals(teleportYaw, f.player.xRot, "the acceptance did not apply its look");

            f.input(FORWARD);
            PredictionResult step = step(f, STEP_WEST);
            assertTrue(exact(step), describe(step));
            assertEquals(WEST, f.player.xRot);
        }
    }

    /** set_passengers for {@link #VEHICLE}, processed once its ping returns. */
    private static void passengers(JavaTickFixture f, List<Integer> passengers) {
        var packet = new ClientboundSetPassengers(VEHICLE, passengers);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
        f.player.packetEntityReplication.onSetPassengers(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** A rotation-only tick: Rot. */
    private static void turn(JavaTickFixture f, float yaw) {
        move(f, new ServerboundMovePlayer(0, 0, 0, yaw, 0, true, false, false, true));
        f.tickEnd();
        assertEquals(yaw, f.player.xRot);
    }

    /** A moving tick without a rotation: LocalPlayer#sendPosition sends Pos. */
    private static PredictionResult step(JavaTickFixture f, Vec3 to) {
        return step(f, to, true);
    }

    private static PredictionResult step(JavaTickFixture f, Vec3 to, boolean onGround) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        move(f, new ServerboundMovePlayer(to.x, to.y, to.z, 0, 0, onGround, false, true, false));
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != before, "the tick committed no prediction");
        return result;
    }

    private static void move(JavaTickFixture f, ServerboundMovePlayer packet) {
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, packet), f.player, packet);
    }
}
