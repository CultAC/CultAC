package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A horse's jump tick is set back, and the horse is removed before the client's answer to the vehicle correction
 * arrives. ClientPacketListener#handleMoveVehicle echoed the snap while the client still rode, and that echo completes
 * the correction. Entity#rideTick zeroes a passenger's delta movement every ride tick and Entity#removeVehicle leaves
 * it alone, so a player off its vehicle has none of the horse's velocity: it must not leave with the horse's jump.
 */
class DroppedVehicleSetbackVelocityTest {
    private static final int HORSE = 100;
    private static final Vec3 GROUND = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final double JUMP_STRENGTH = 0.7;
    private static final int FULL_CHARGE = 100;
    private static final double GROUNDED_Y = -0.0784000015258789;
    private static final int VEHICLE_SWITCH_TICKS = 5;
    /** LocalPlayer#aiStep's jumpRidingScale reaches 1.0 after the press and ten held ticks. */
    private static final int FULL_CHARGE_TICKS = 10;
    private static final ServerboundPlayerInput JUMP_HELD =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final ServerboundPlayerInput RELEASED =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @Test
    void aHorseRemovedDuringItsJumpSetbackGivesThePlayerNoJumpVelocity() throws Exception {
        try (var f = new JavaTickFixture()) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            List<ClientboundMoveVehicle> moves = new ArrayList<>();
            f.fixture.registrar.register(routes -> routes.send(
                    ClientboundPackets.MOVE_VEHICLE, PacketListenerPriority.LOW, (event, player, packet) -> {
                        moves.add(packet);
                        new PacketServerTeleport().onMoveVehicle(event, player, packet);
                    }));
            f.place(GROUND, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, GROUND, 0, 0, 0);
            PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
            horse.hasSaddle = true;
            horse.isTame = true;
            horse.jumpStrength = JUMP_STRENGTH;
            horse.onGround = true;
            f.setPassengers(HORSE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.pongThrough(f.player.sendTrailingTransaction());
            f.tickEnd();
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0, GROUNDED_Y, 0));
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
            horse.onGround = true;

            for (int i = 0; i < VEHICLE_SWITCH_TICKS; i++) assertVehicleExact(ride(f, GROUND, true, false), i);
            f.input(JUMP_HELD);
            for (int i = 0; i <= FULL_CHARGE_TICKS; i++) assertVehicleExact(ride(f, GROUND, true, false), i);
            ride(f, GROUND, true, true);
            // The jump tick claims a height no jump reaches.
            assertRejected(ride(f, GROUND.add(0, 3, 0), false, false), "VehicleOffset");
            SetBackData vehicleSetback = f.player.getSetbackTeleportUtil().getRequiredSetBack();
            assertNotNull(vehicleSetback);
            assertTrue(
                    vehicleSetback.getVelocity().y > 0.3,
                    "the vehicle setback carries the jump: " + vehicleSetback.getVelocity());

            // The horse is removed before the client's answer to the vehicle correction arrives.
            var removal = new ClientboundRemoveEntities(List.of(HORSE));
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_ENTITIES, removal, false);
            f.player.packetEntityReplication.onRemoveEntities(event, f.player, removal);
            event.tasksAfter().forEach(Runnable::run);

            // The client answers the ping before the move_vehicle, echoes the snap from the handler while it still
            // rides, and then reads the removal.
            f.pongThrough(vehicleSetback.getTeleportData().getTransaction());
            ClientboundMoveVehicle snap = moves.getLast();
            Vec3 at = vehicleSetback.getTeleportData().getLocation();
            var echo = new ServerboundMoveVehicle(new Vec3d(at.x, at.y, at.z), snap.yaw(), snap.pitch(), false, true);
            f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, echo), f.player, echo);
            assertTrue(vehicleSetback.isComplete(), "the setback echo was not accepted");
            f.pongThrough(f.player.lastTransactionSent.get());
            assertFalse(f.player.inVehicle());
            assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback());

            Vec3 velocity = f.player.compensatedEntities.getSelf().deltaMovement;
            assertTrue(velocity.y <= 0.0, "the dismounted player kept the horse's jump: " + velocity);
            assertEquals(0.0, velocity.x, 1.0E-9);
            assertEquals(0.0, velocity.z, 1.0E-9);
            // A first free move that rises by the horse's jump is not the player's own.
            Vec3 free = new Vec3(f.player.x, f.player.y, f.player.z);
            assertRejected(f.tick(free.add(0, vehicleSetback.getVelocity().y, 0), false), "VerticalOffset");
        }
    }

    /** LocalPlayer#aiStep may release the jump, then LocalPlayer#tick sends the rider's Rot, the move and the tick end. */
    private static PredictionResult ride(JavaTickFixture f, Vec3 to, boolean onGround, boolean releaseJump) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        if (releaseJump) {
            f.command(PlayerCommandAction.START_JUMPING_WITH_HORSE, FULL_CHARGE);
            f.input(RELEASED);
        }
        var rot = new ServerboundMovePlayer(0, 0, 0, 0, 0, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), 0, 0, onGround, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertNotSame(before, result, "the horse's move was not predicted");
        return result;
    }
}
