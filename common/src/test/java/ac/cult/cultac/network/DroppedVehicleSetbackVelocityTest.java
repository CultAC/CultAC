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
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A horse's jump tick is set back, and the horse is removed before the client answers the vehicle correction. The
 * correction then moves the player instead. Entity#rideTick zeroes a passenger's delta movement every ride tick and
 * Entity#removeVehicle leaves it alone, so a player off its vehicle has no velocity of its own: the player correction
 * must not hand it the horse's jump.
 */
class DroppedVehicleSetbackVelocityTest {
    private static final int HORSE = 100;
    private static final Vec3 GROUND = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final double JUMP_STRENGTH = 0.7;
    private static final int FULL_CHARGE = 100;
    private static final double GROUNDED_Y = -0.0784000015258789;
    private static final int VEHICLE_SWITCH_TICKS = 5;

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
            ride(f, GROUND, true, true);
            // The jump tick claims a height no jump reaches.
            assertRejected(ride(f, GROUND.add(0, 3, 0), false, false), "VehicleOffset");
            SetBackData vehicleSetback = f.player.getSetbackTeleportUtil().getRequiredSetBack();
            assertNotNull(vehicleSetback);
            assertTrue(
                    vehicleSetback.getVelocity().y > 0.3,
                    "the vehicle setback carries the jump: " + vehicleSetback.getVelocity());

            // The horse is removed before the client answers the vehicle correction.
            var removal = new ClientboundRemoveEntities(List.of(HORSE));
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_ENTITIES, removal, false);
            f.player.packetEntityReplication.onRemoveEntities(event, f.player, removal);
            event.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertFalse(f.player.inVehicle());

            SetBackData playerSetback = f.player.getSetbackTeleportUtil().getRequiredSetBack();
            assertNotNull(playerSetback);
            TeleportData sent = f.player.getSetbackTeleportUtil().pendingTeleports.stream()
                    .filter(teleport -> teleport.getTransaction()
                            == playerSetback.getTeleportData().getTransaction())
                    .findFirst()
                    .orElse(null);
            assertNotNull(sent, "no player correction was sent for the removed horse's setback");
            assertEquals(Vec3.ZERO, sent.getDeltaMovement(), "the player_position handed over the horse's jump");
            Vec3 velocity = playerSetback.getVelocity() == null ? Vec3.ZERO : playerSetback.getVelocity();
            assertEquals(0.0, velocity.y, 1.0E-9, "the dismounted player kept the horse's jump: " + velocity);
            assertEquals(0.0, velocity.x, 1.0E-9);
            assertEquals(0.0, velocity.z, 1.0E-9);
        }
    }

    /** LocalPlayer#aiStep may release the jump, then LocalPlayer#tick sends the rider's Rot, the move and the tick end. */
    private static PredictionResult ride(JavaTickFixture f, Vec3 to, boolean onGround, boolean releaseJump) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        if (releaseJump) f.command(PlayerCommandAction.START_JUMPING_WITH_HORSE, FULL_CHARGE);
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
