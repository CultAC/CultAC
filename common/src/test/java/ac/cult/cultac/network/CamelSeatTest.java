package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.combat.PlayerReach;
import ac.cult.cultac.checks.impl.combat.ReachCheck.MeasuredAttack;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.PlayerObserver;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Camel#getPassengerAttachmentPoint seats the driver {@code 0.5 * scale} ahead of the camel along its yRot, at the
 * standing body anchor height. Positions and rotations come from a vanilla 26.3 client, moved onto the fixture floor.
 */
class CamelSeatTest {
    private static final int CAMEL = 100;
    private static final int RIDER = 101;
    private static final int TARGET = 102;
    // Camel#isInPoseTransition ends 52 ticks after a stand.
    private static final int STANDING_TICKS = 60;

    /**
     * Another player's camel ticks after the local player: it and its rider push from where the tick left them, the
     * rider from the seat positionRider gave it the tick before. Both pushes land in the next travel.
     */
    @Test
    void aRiderOfAnotherPlayersCamelPushesFromTheSeatAheadOfTheCamel() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 standing = onFloor(155.30245917471586, 153.81562547628835, 155, 154);
            Vec3 firstPush = onFloor(155.3216572832439, 153.7656254755433, 155, 154);
            Vec3 secondPush = onFloor(155.35190689861747, 153.6895865023089, 155, 154);
            Vec3 riderPush = onFloor(155.40813477563708, 153.568579037637, 155, 154);
            f.place(standing, Vec3.ZERO);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            Vec3 camel = onFloor(154.93652 - 471 / 4096.0, 154.71431 + 1884 / 4096.0, 155, 154);
            f.player.packetEntityReplication.addEntity(CAMEL, EntityTypeIds.CAMEL, camel, -165.9375F, 0, 0, -1);
            f.player.packetEntityReplication.addEntity(RIDER, EntityTypeIds.PLAYER, camel, 0, 0, 0, -1);
            f.setPassengers(CAMEL, List.of(RIDER));
            f.pongThrough(f.player.lastTransactionSent.get());
            for (int tick = 1; tick <= STANDING_TICKS; tick++) {
                if (tick % 20 == 0) assertTrue(exact(f.tick(standing, true)));
                else f.tickEnd();
            }

            moveCamel(f, 471, -1884);
            f.tickEnd();
            f.pongThrough(f.player.lastTransactionSent.get());
            f.tickEnd();
            f.tickEnd();
            PredictionResult first = f.tick(firstPush, true);
            assertTrue(exact(first), describe(first));
            moveCamel(f, 967, -3646);
            f.pongThrough(f.player.lastTransactionSent.get());
            PredictionResult second = f.tick(secondPush, true);
            assertTrue(exact(second), describe(second));
            assertEquals(1, second.getSimulationContext().getWorldData().getNumRidersTickingAfterPlayer());

            PredictionResult both = f.tick(riderPush, true);
            assertEquals(2, both.getSimulationContext().getWorldData().getNumPushing());
            assertTrue(exact(both), describe(both));
        }
    }

    /** The pick runs before the tick's travel, from the eye positionRider set at the end of the last tick. */
    @Test
    void aCamelDriverAttacksFromTheSeatAheadOfTheCamel() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.observer = new PlayerObserver() {};
            Vec3 camel = onFloor(157.7315560557739, 159.35301149466483, 157, 159);
            f.place(camel.add(0, 1.4, 0), Vec3.ZERO);
            f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, camel, 138.2014F, 0, 0);
            f.setPassengers(CAMEL, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            for (int tick = 0; tick < STANDING_TICKS; tick++) drive(f, camel, 138.2014F, 28.249132F);
            f.player.compensatedEntities.addEntity(
                    TARGET, EntityTypeIds.PLAYER, onFloor(155.40295800708179, 156.87265360570887, 157, 159), 0, 0, 0);
            PlayerReach reach = f.player.checkManager.getCheck(PlayerReach.class);
            reach.setEnabled(true);

            var attack = new ServerboundInteract(TARGET, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
            f.player.checkManager.dispatchReceiveHandlers(f.event(ServerboundPackets.INTERACT, attack));
            drive(f, camel, 138.2014F, 28.651735F);

            List<MeasuredAttack> measured = reach.measuredAttacks();
            assertEquals(1, measured.size());
            assertTrue(measured.get(0).reach() < 3.0, "reach " + measured.get(0).reach());
            assertEquals(0.0, reach.violations);
        }
    }

    private static Vec3 onFloor(double x, double z, double originX, double originZ) {
        return new Vec3(x - originX, FLOOR_Y, z - originZ);
    }

    private static void moveCamel(JavaTickFixture f, int x, int z) {
        var move = new ClientboundMoveEntity(
                CAMEL,
                new EntityDelta.Stepped(List.of(new EntityDelta.Step((short) x, (short) 0, (short) z, 3))),
                0,
                0,
                true,
                true,
                false);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, move, false);
        f.player.packetEntityReplication.onMoveEntity(event, f.player, move);
        event.tasksAfter().forEach(Runnable::run);
    }

    // One client tick of a driver: its Rot, the vehicle's move and the tick end.
    private static void drive(JavaTickFixture f, Vec3 to, float yaw, float pitch) {
        var rot = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), yaw, pitch, true, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
    }
}
