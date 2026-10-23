package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsO;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Minecraft#tick runs ClientLevel#tickEntities before LocalPlayer#sendChanges: AbstractBoat#tick decays and steers the
 * driven boat's deltaRotation and turns the boat by it, the rider's AbstractBoat#positionRider turns its yaw by the
 * same deltaRotation and clampRotation holds the yaw within 105 degrees of the boat, and only then does the client send
 * the rider's Rot and the boat's move. A boat keeps turning its rider while deltaRotation decays after the keys are
 * released, with an inventory open or not.
 */
class BoatRiderInventoryLookTest {
    private static final int BOAT = 100;
    private static final int OTHER_RIDER = 101;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final ServerboundPlayerInput LEFT =
            new ServerboundPlayerInput(false, false, true, false, false, false, false);
    private static final ServerboundPlayerInput RELEASED =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final int RELEASE_TICK = 8;
    // The client opens the inventory in Minecraft#handleKeybinds, before the tick's entities.
    private static final int OPEN_TICK = RELEASE_TICK + 1;
    private static final int TICKS = 40;
    private static final float LOOK_YAW = 30;
    private static final float LOOK_PITCH = 10;
    private static final int TELEPORT_TICK = OPEN_TICK + 2;
    // A boat that moves 0.01 blocks a tick from the teleport on.
    private static final Vec3 DRIFT = new Vec3(0.01, 0.0, 0.0);

    @Test
    void aRiderTheReleasedBoatKeepsTurningInItsInventoryIsNotRotating() throws Exception {
        Run run = drive(true, -1, 0, 0, 0);

        assertTrue(run.turnedLooks > 0, "the boat turned no judged look");
        assertEquals(0, run.violations);
    }

    @Test
    void aMouseLookOnTopOfTheBoatsTurnInAnInventoryIsStillFlagged() throws Exception {
        Run run = drive(true, OPEN_TICK + 2, 20, 0, 0);

        assertEquals(1, run.violations);
    }

    @Test
    void aPitchChangeWhileTheBoatTurnsInAnInventoryIsStillFlagged() throws Exception {
        Run run = drive(true, OPEN_TICK + 2, 0, 5, 0);

        assertEquals(1, run.violations);
    }

    @Test
    void aRiderBehindTheBoatsControllerIsNotTurnedByItsDeltaRotation() throws Exception {
        Run run = drive(false, -1, 0, 0, 0);

        assertTrue(run.turnedLooks > 0, "the rider turned no judged look");
        assertEquals(run.turnedLooks, run.violations);
    }

    @Test
    void aBoatYawThatIsNotTheBoatsTurnCannotExplainTheRidersLook() throws Exception {
        // The boat reports a yaw that clampRotation turns into a look the keys gave no boat to make.
        Run run = drive(true, OPEN_TICK + 2, 0, 0, -120);

        assertEquals(1, run.violations);
    }

    /**
     * ClientPacketListener#handleTeleportEntity interpolates a ticking boat within 64 blocks: for three ticks
     * Entity#commonTick runs LinearInterpolationHandler#interpolate before AbstractBoat#controlBoat turns the boat, and
     * positionRider turns the rider by the same deltaRotation.
     */
    @Test
    void aRiderTheInterpolatingTeleportedBoatTurnsInItsInventoryIsNotRotating() throws Exception {
        assertEquals(0, teleportInAnInventory(DRIFT, false));
    }

    /**
     * The third LinearInterpolationHandler step puts a boat that does not move itself exactly on the teleport target,
     * and LocalPlayer#sendChanges sends that tick's ServerboundMoveVehiclePacket after the rider's Rot.
     */
    @Test
    void aRiderTheStillTeleportedBoatTurnsInItsInventoryIsNotRotating() throws Exception {
        assertEquals(0, teleportInAnInventory(Vec3.ZERO, false));
    }

    /** The ping after a teleport bundle can reach the client after its next tick. */
    @Test
    void aRiderTheStillTeleportedBoatTurnsInItsInventoryBeforeTheNextPingIsNotRotating() throws Exception {
        assertEquals(0, teleportInAnInventory(Vec3.ZERO, true));
    }

    /**
     * Steers left until {@link #RELEASE_TICK}, opens the inventory at {@link #OPEN_TICK} and has the server teleport the
     * boat 5 blocks and 150 degrees round at {@link #TELEPORT_TICK}; the boat moves {@code drift} a tick from then on.
     * The ping the server sends after the teleport is answered after the next tick when {@code trailingPong}. Returns
     * MultiActionsO's violations.
     */
    private static double teleportInAnInventory(Vec3 drift, boolean trailingPong) throws Exception {
        float[] turns = deltaRotations();
        try (var f = board(true)) {
            MultiActionsO check = f.player.checkManager.getCheck(MultiActionsO.class);
            f.player.setExperimentalChecks(true);
            check.setEnabled(true);
            check.reload();
            check.violations = 0;
            float boatYaw = 0;
            float yaw = clampRotation(LOOK_YAW, boatYaw);
            Vec3 boat = SEAT;
            Vec3 target = SEAT;
            float targetYaw = 0;
            Vec3 lastStep = SEAT;
            float lastStepYaw = 0;
            int steps = 0;
            int turnedLooks = 0;
            int nextPing = -1;
            for (int tick = 0; tick <= TELEPORT_TICK + 20; tick++) {
                if (tick == OPEN_TICK) f.player.openWindow.onClientOpenOrCloseWindow(true);
                if (tick == TELEPORT_TICK) {
                    target = new Vec3(SEAT.x, SEAT.y, SEAT.z + 5.0);
                    targetYaw = boatYaw + 150.0F;
                    teleportBoat(f, target, targetYaw);
                    int bundlePing = f.player.lastTransactionSent.get();
                    nextPing = f.player.sendTransactionAndGetId();
                    f.pongThrough(trailingPong ? bundlePing : nextPing);
                    lastStep = boat;
                    lastStepYaw = boatYaw;
                    steps = 3;
                }
                steer(f, tick);
                if (steps > 0) {
                    // AbstractInterpolationHandler#interpolate carries the boat's own move and turn into the target.
                    Vec3 moved = boat.subtract(lastStep);
                    if (moved.lengthSqr() > 1.0E-5F) target = target.add(moved);
                    targetYaw += boatYaw - lastStepYaw;
                    boat = boat.lerp(target, 1.0 / steps);
                    boatYaw = rotLerp(1.0 / steps, boatYaw, targetYaw);
                    lastStep = boat;
                    lastStepYaw = boatYaw;
                    steps--;
                }
                boatYaw += turns[tick];
                float previousYaw = yaw;
                yaw = clampRotation(yaw + turns[tick], boatYaw);
                if (tick >= TELEPORT_TICK) {
                    if (yaw != previousYaw) turnedLooks++;
                    boat = boat.add(drift);
                }
                rot(f, yaw, LOOK_PITCH);
                moveBoat(f, boat, boatYaw);
                f.tickEnd();
                if (tick == TELEPORT_TICK) f.pongThrough(nextPing);
            }
            assertTrue(turnedLooks > 0, "the boat turned no judged look");
            assertEquals(boatYaw, f.player.compensatedEntities.getSelf().getRiding().clientPhysicalYaw);
            return check.violations;
        }
    }

    private record Run(double violations, int turnedLooks) {}

    /**
     * Steers left until {@link #RELEASE_TICK}, opens the inventory at {@link #OPEN_TICK} and sends the rider's look the
     * way vanilla turns it with a boat it drives, plus {@code extraYaw} and {@code extraPitch} at tick {@code at}, with
     * the boat reporting a yaw {@code boatShift} off its turn then. A rider behind the controller sends the same look
     * while the boat, which it cannot move, stands still.
     */
    private static Run drive(boolean controls, int at, float extraYaw, float extraPitch, float boatShift)
            throws Exception {
        float[] turns = deltaRotations();
        try (var f = board(controls)) {
            MultiActionsO check = f.player.checkManager.getCheck(MultiActionsO.class);
            f.player.setExperimentalChecks(true);
            check.setEnabled(true);
            check.reload();
            check.violations = 0;
            float boatYaw = 0;
            // Entity#turn, then AbstractBoat#onPassengerTurned, before the first tick.
            float yaw = clampRotation(LOOK_YAW, boatYaw);
            float pitch = LOOK_PITCH;
            int turnedLooks = 0;
            for (int tick = 0; tick < TICKS; tick++) {
                if (tick == OPEN_TICK) f.player.openWindow.onClientOpenOrCloseWindow(true);
                steer(f, tick);
                if (controls) boatYaw += turns[tick];
                if (tick == at) boatYaw += boatShift;
                float previousYaw = yaw;
                yaw = clampRotation(yaw + turns[tick], boatYaw);
                if (tick == at) {
                    yaw += extraYaw;
                    pitch += extraPitch;
                }
                if (tick > OPEN_TICK && yaw != previousYaw) turnedLooks++;
                rot(f, yaw, pitch);
                if (controls) moveBoat(f, boatYaw);
                f.tickEnd();
            }
            return new Run(check.violations, turnedLooks);
        }
    }

    /** The boat's deltaRotation at each tick of {@link #drive}, which neither the rider's nor the boat's yaw changes. */
    private static float[] deltaRotations() throws Exception {
        float[] turns = new float[TICKS];
        try (var f = board(true)) {
            for (int tick = 0; tick < TICKS; tick++) {
                steer(f, tick);
                rot(f, f.player.xRot, f.player.yRot);
                moveBoat(f, 0);
                f.tickEnd();
                turns[tick] = f.player.boatData.deltaRotation;
            }
        }
        return turns;
    }

    /** A 26.3 client on a boat at yaw 0 on stone, its first passenger when it {@code controls} the boat. */
    private static JavaTickFixture board(boolean controls) throws Exception {
        var f = new JavaTickFixture();
        f.place(SEAT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, SEAT, 0, 0, 0);
        if (controls) {
            f.setPassengers(BOAT, List.of(f.player.entityID));
        } else {
            f.player.compensatedEntities.addEntity(OTHER_RIDER, EntityTypeIds.ZOMBIE, SEAT, 0, 0, 0);
            f.setPassengers(BOAT, List.of(OTHER_RIDER, f.player.entityID));
        }
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
        return f;
    }

    // LocalPlayer#sendChanges sends the keys that changed before the Rot.
    private static void steer(JavaTickFixture f, int tick) {
        if (tick == 0) f.input(LEFT);
        if (tick == RELEASE_TICK) f.input(RELEASED);
    }

    private static void rot(JavaTickFixture f, float yaw, float pitch) {
        var packet = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, f.player.onGround, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, packet), f.player, packet);
    }

    private static void moveBoat(JavaTickFixture f, float boatYaw) {
        var packet = new ServerboundMoveVehicle(new Vec3d(SEAT.x, SEAT.y, SEAT.z), boatYaw, 0, true, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, packet), f.player, packet);
    }

    private static void moveBoat(JavaTickFixture f, Vec3 position, float boatYaw) {
        var packet = new ServerboundMoveVehicle(new Vec3d(position.x, position.y, position.z), boatYaw, 0, true, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, packet), f.player, packet);
    }

    private static void teleportBoat(JavaTickFixture f, Vec3 target, float yaw) {
        var packet = new ClientboundTeleportEntity(
                BOAT, new Vec3d(target.x, target.y, target.z), Vec3d.ZERO, yaw, 0, Set.of(), true);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.TELEPORT_ENTITY, packet, false);
        f.player.packetEntityReplication.onTeleportEntity(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    // Mth#rotLerp in double, as LinearInterpolationHandler#doInterpolate calls it.
    private static float rotLerp(double alpha, float from, float to) {
        double delta = ((double) to - from) % 360.0;
        if (delta >= 180.0) delta -= 360.0;
        if (delta < -180.0) delta += 360.0;
        return (float) (from + alpha * delta);
    }

    // AbstractBoat#clampRotation's yaw for a player passenger, in Mth's float operations.
    private static float clampRotation(float yaw, float boatYaw) {
        float delta = (yaw - boatYaw) % 360.0F;
        if (delta >= 180.0F) delta -= 360.0F;
        if (delta < -180.0F) delta += 360.0F;
        float target = delta < -105.0F ? -105.0F : Math.min(delta, 105.0F);
        return yaw + target - delta;
    }
}
