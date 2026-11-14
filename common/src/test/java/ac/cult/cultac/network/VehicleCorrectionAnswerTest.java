package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ClientPacketListener#handleMoveVehicle snaps the controlled root to a move_vehicle more than 1.0E-5 away and answers
 * with ServerboundMoveVehiclePacket#fromEntity, so the answer is the packet's exact position. The engine and the server
 * take the queued target for an accepted answer, never the client's coordinates beside it.
 */
class VehicleCorrectionAnswerTest {
    private static final int BOAT = 100;
    private static final double HALF = 0.6875;
    // Wall blocks at z = 2, boat flush against its face at z = 2 - HALF.
    private static final Vec3 FLUSH = new Vec3(0.5, FLOOR_Y, 2 - HALF);
    private static final Vec3 OPEN = new Vec3(0.5, FLOOR_Y, -2.5);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput RELEASED =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    private record Rig(JavaTickFixture f, List<ClientboundMoveVehicle> moves) {
        Vec3 root() {
            return f.player.compensatedEntities.getEntity(BOAT).clientPhysicalPosition;
        }
    }

    private static Rig rig(ServerboundPlayerInput input) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        List<ClientboundMoveVehicle> moves = new ArrayList<>();
        f.fixture.registrar.register(routes -> routes.send(
                ClientboundPackets.MOVE_VEHICLE, PacketListenerPriority.LOW, (event, player, packet) -> {
                    moves.add(packet);
                    new PacketServerTeleport().onMoveVehicle(event, player, packet);
                }));
        for (int x = -3; x <= 3; x++) {
            for (int y = FLOOR_Y; y <= FLOOR_Y + 2; y++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, y, 2), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.place(FLUSH, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, FLUSH, 0, 0, 0);
        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.xRot = 0;
        f.player.yRot = 0;
        f.input(input);
        for (int i = 0; i < 4; i++) JavaTickFixture.assertVehicleExact(f.rideTick(FLUSH, true), i);
        return new Rig(f, moves);
    }

    /** Pushes 0.3 into the wall and answers the setback at its target plus {@code offset} along z; the target. */
    private static Vec3 setbackAndAnswer(Rig r, double offset) {
        JavaTickFixture f = r.f();
        PredictionResult push = f.rideTick(FLUSH.add(0, 0, 0.3), true);
        assertTrue(f.player.getSetbackTeleportUtil().isPendingSetback(), JavaTickFixture.describe(push));
        SetBackData setback = f.player.getSetbackTeleportUtil().getRequiredSetBack();
        Vec3 target = setback.getTeleportData().getLocation();
        f.pongThrough(f.player.lastTransactionSent.get());
        ClientboundMoveVehicle snap = r.moves().getLast();
        PacketReceiveEvent<ServerboundMoveVehicle> answer =
                f.moveVehicle(target.add(0, 0, offset), snap.yaw(), snap.pitch(), true);
        assertTrue(setback.isComplete(), "the answer was not accepted");
        assertFalse(answer.isCancelled());
        assertPosition(target, answer.getPacket().position());
        f.tickEnd();
        return target;
    }

    private static void assertPosition(Vec3 expected, Vec3d actual) {
        assertEquals(expected.x, actual.x());
        assertEquals(expected.y, actual.y());
        assertEquals(expected.z, actual.z());
    }

    @Test
    void anExactSetbackAnswerKeepsTheWallSolid() throws Exception {
        Rig r = rig(FORWARD);
        try (JavaTickFixture f = r.f()) {
            Vec3 target = setbackAndAnswer(r, 0);
            assertEquals(FLUSH.z, target.z, 1.0E-6);
            assertEquals(target, r.root());
            JavaTickFixture.assertVehicleExact(f.rideTick(target, true), 0);
            JavaTickFixture.assertRejected(f.rideTick(target.add(0, 0, 0.04), true), "VehicleOffset");
        }
    }

    @Test
    void aSetbackAnswerBesideItsTargetLeavesTheBoatAtTheTarget() throws Exception {
        Rig r = rig(FORWARD);
        try (JavaTickFixture f = r.f()) {
            Vec3 target = setbackAndAnswer(r, 9.9E-6);
            assertEquals(target, r.root());
            JavaTickFixture.assertRejected(f.rideTick(target.add(0, 0, 9.9E-6 + 0.04), true), "VehicleOffset");
        }
    }

    @Test
    void aServerVehicleMoveIsAnsweredExactly() throws Exception {
        Rig r = rig(RELEASED);
        try (JavaTickFixture f = r.f()) {
            f.serverMoveVehicle(OPEN, 0, 0);
            f.pongThrough(f.player.lastTransactionSent.get());
            PacketReceiveEvent<ServerboundMoveVehicle> answer = f.moveVehicle(OPEN, 0, 0, true);
            assertFalse(answer.isCancelled());
            assertPosition(OPEN, answer.getPacket().position());
            f.tickEnd();
            assertEquals(OPEN, r.root());
            for (int i = 0; i < 3; i++) JavaTickFixture.assertVehicleExact(f.rideTick(OPEN, true), i);
        }
    }

    @Test
    void aSnappedServerVehicleMoveAnsweredBesideItsTargetIsNotAccepted() throws Exception {
        Rig r = rig(RELEASED);
        try (JavaTickFixture f = r.f()) {
            f.serverMoveVehicle(OPEN, 0, 0);
            f.pongThrough(f.player.lastTransactionSent.get());
            PacketReceiveEvent<ServerboundMoveVehicle> answer = f.moveVehicle(OPEN.add(0, 0, 5.0E-6), 0, 0, true);
            assertFalse(answer.answersTeleport());
            assertEquals(1, f.player.getSetbackTeleportUtil().vehicleTeleports().queuedVehicleTeleportCount());
        }
    }

    /** Ticks the client sent before the move_vehicle reached it run from the old position; the answer follows them. */
    @Test
    void aLateAnswerAfterTicksInFlightIsExact() throws Exception {
        Rig r = rig(RELEASED);
        try (JavaTickFixture f = r.f()) {
            f.serverMoveVehicle(OPEN, 0, 0);
            for (int i = 0; i < 6; i++) JavaTickFixture.assertVehicleExact(f.rideTick(FLUSH, true), i);
            f.pongThrough(f.player.lastTransactionSent.get());
            PacketReceiveEvent<ServerboundMoveVehicle> answer = f.moveVehicle(OPEN, 0, 0, true);
            assertFalse(answer.isCancelled());
            assertPosition(OPEN, answer.getPacket().position());
            f.tickEnd();
            assertEquals(OPEN, r.root());
            for (int i = 0; i < 3; i++) JavaTickFixture.assertVehicleExact(f.rideTick(OPEN, true), i);
        }
    }
}
