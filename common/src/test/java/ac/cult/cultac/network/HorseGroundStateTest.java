package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.vehicle.VehicleGround;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The ridden horse's onGround, which AbstractHorse#tickRidden's jump and the next travel's speed start from. Entity#move
 * sets it from the move's collision, so a claim that collision disproves is replaced before the next tick and before
 * the server reads it. ClientPacketListener#handleMoveVehicle leaves it alone (Entity#absSnapTo), so a setback would
 * carry the onGround of the rejected tick to wherever it moves the horse; the horse leaves a setback with the onGround
 * of the move that reached the setback position instead.
 */
class HorseGroundStateTest {
    private static final int HORSE = 100;
    private static final Vec3 AIR = new Vec3(0.5, FLOOR_Y + 10, 0.5);
    private static final Vec3 GROUND = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final double JUMP_STRENGTH = 0.7;
    /** START_JUMPING_WITH_HORSE at 90 or more: AbstractHorse#onPlayerJump's full 1.0 scale. */
    private static final int FULL_CHARGE = 100;
    /** AbstractHorse#executeRidersJump at full scale replaces the Y velocity the move then travels. */
    private static final Vec3 JUMP = new Vec3(0, JUMP_STRENGTH, 0);
    /** LivingEntity#travelInAir: a grounded horse's (0 - 0.08) * 0.98F. */
    private static final double GROUNDED_Y = -0.0784000015258789;
    private static final double GRAVITY = 0.08D;
    private static final double VERTICAL_DRAG = 0.98F;
    /** CompensatedVehicleState absorbs offsets for this many client ticks after the first ridden move. */
    private static final int VEHICLE_SWITCH_TICKS = 5;

    /** The control: the same full-charge jump is vanilla from a horse that is on the ground. */
    @Test
    void aGroundedHorseJumps() throws Exception {
        try (Ride ride = mount(GROUND, new Vec3(0, GROUNDED_Y, 0), true)) {
            ride.tick(GROUND, true, true);
            assertVehicleExact(ride.tick(GROUND.add(JUMP), false, false).result(), 0);
        }
    }

    @Test
    void aDisprovenGroundClaimIsReplacedForTheServerAndTheNextTick() throws Exception {
        try (Ride ride = mount(AIR, Vec3.ZERO, false)) {
            Vec3[] fall = ride.fallFromRest(AIR);
            Vec3 at = fall[0].add(fall[1]);
            Move claim = ride.tick(at, true, true);

            assertFalse(claim.event().isCancelled(), "the move itself is legal");
            assertFalse(claim.event().getPacket().onGround(), "the server received the claimed ground");
            VehicleGround check = ride.f.player.checkManager.getCheck(VehicleGround.class);
            assertTrue(check.violations > 0);
            assertFalse(ride.f.player.getSetbackTeleportUtil().isPendingSetback(), "one claim set back");
            assertFalse(ride.horse.onGround);
            assertEquals(DesyncStatus.FALSE, ride.f.player.checkManager.getSimulationProcessor().getLastOnGround());

            Vec3 motion = new Vec3(0, (fall[1].y - GRAVITY) * VERTICAL_DRAG, 0);
            assertRejected(ride.tick(jumpFrom(at, motion), false, false).result(), "VehicleOffset");
        }
    }

    /** Entity#move refreshes onGround on every downward collision, even when it began grounded. */
    @Test
    void aGroundedHorseCannotClaimAirOnConsecutiveFloorTicks() throws Exception {
        try (Ride ride = mount(GROUND, new Vec3(0, GROUNDED_Y, 0), true)) {
            ride.idleOnFloor();
            Move claim = ride.tick(GROUND, false, false);

            assertFalse(claim.event().isCancelled(), "the move itself is legal");
            assertTrue(claim.event().getPacket().onGround(), "the server received the false airborne claim");
            assertTrue(ride.f.player.checkManager.getCheck(VehicleGround.class).violations > 0);
            assertTrue(ride.horse.onGround);
            assertEquals(DesyncStatus.TRUE, ride.f.player.checkManager.getSimulationProcessor().getLastOnGround());
        }
    }

    /** A ground-spoof setback rejects this vehicle tick without giving it a second simulated move. */
    @Test
    void aVehicleGroundSetbackKeepsThePreviousSafeTick() throws Exception {
        try (Ride ride = mount(AIR, Vec3.ZERO, false)) {
            Vec3[] fall = ride.fallFromRest(AIR);
            VehicleGround check = ride.f.player.checkManager.getCheck(VehicleGround.class);
            check.violations = 10;
            Vec3 safe = ride.f.player.getSetbackTeleportUtil().safePosition().getPos();
            assertEquals(fall[0], safe);

            Move claim = ride.tick(fall[0].add(fall[1]), true, false);

            assertTrue(check.violations > 10);
            assertTrue(claim.event().isCancelled());
            SetBackData setback = ride.f.player.getSetbackTeleportUtil().getRequiredSetBack();
            assertEquals(safe, setback.getTeleportData().getLocation(),
                    "the ground-spoof setback simulated the rejected vehicle tick again");
        }
    }

    @Test
    void aSetbackEchoClaimingGroundCannotJumpAnAirborneHorse() throws Exception {
        try (Ride ride = mount(AIR, Vec3.ZERO, false)) {
            Vec3[] fall = ride.fallFromRest(AIR);
            // The rider arms a full jump in the tick whose move is rejected.
            assertRejected(ride.tick(fall[0].add(0, 0.5, 0), false, true).result(), "VehicleOffset");
            SetBackData setback = ride.f.player.getSetbackTeleportUtil().getRequiredSetBack();
            Vec3 at = setback.getTeleportData().getLocation();
            var echo = ride.acceptSetback(setback, true);
            assertFalse(ride.horse.onGround, "the setback echo's onGround reached the horse");
            assertFalse(echo.getPacket().onGround(), "the server received the echo's onGround");

            assertRejected(ride.tick(jumpFrom(at, setback.getVelocity()), false, false).result(), "VehicleOffset");
        }
    }

    /**
     * A setback of the jump tick moves the horse to where the jump took it. The tick's onGround left the floor with the
     * jump, so the jump armed after it cannot start in the air, though the horse was grounded when the tick began.
     */
    @Test
    void aSetbackOfAJumpCannotStartAnotherJumpInTheAir() throws Exception {
        try (Ride ride = mount(GROUND, new Vec3(0, GROUNDED_Y, 0), true)) {
            ride.idleOnFloor();
            ride.tick(GROUND, true, true);
            // The jump tick claims a height no jump reaches and re-arms the jump for the next tick.
            assertRejected(ride.tick(GROUND.add(0, 3, 0), false, true).result(), "VehicleOffset");
            SetBackData setback = ride.f.player.getSetbackTeleportUtil().getRequiredSetBack();
            Vec3 at = setback.getTeleportData().getLocation();
            assertTrue(at.y > GROUND.y + 0.5, "the setback did not keep the jump's movement: " + at);
            var echo = ride.acceptSetback(setback, true);
            assertFalse(ride.horse.onGround, "the horse left the jump's setback on the ground");
            assertFalse(echo.getPacket().onGround(), "the server received the echo's onGround");

            assertRejected(ride.tick(jumpFrom(at, setback.getVelocity()), false, false).result(), "VehicleOffset");
        }
    }

    /** A setback of a grounded tick leaves the horse on the floor, where the armed jump is vanilla. */
    @Test
    void aSetbackOnTheFloorKeepsTheJump() throws Exception {
        try (Ride ride = mount(GROUND, new Vec3(0, GROUNDED_Y, 0), true)) {
            ride.idleOnFloor();
            // A move no grounded tick reaches; the rider arms a full jump for the next tick.
            assertRejected(ride.tick(GROUND.add(0.5, 0, 0), true, true).result(), "VehicleOffset");
            SetBackData setback = ride.f.player.getSetbackTeleportUtil().getRequiredSetBack();
            Vec3 at = setback.getTeleportData().getLocation();
            assertEquals(GROUND.y, at.y, 1.0E-9);
            var echo = ride.acceptSetback(setback, false);
            assertTrue(ride.horse.onGround, "the floor setback left the horse in the air");
            assertTrue(echo.getPacket().onGround(), "the server received the echo's onGround");

            assertVehicleExact(ride.tick(jumpFrom(at, setback.getVelocity()), false, false).result(), 0);
        }
    }

    /**
     * Where a full jump from {@code at} ends: AbstractHorse#executeRidersJump replaces only the Y of the deltaMovement
     * the move then travels.
     */
    private static Vec3 jumpFrom(Vec3 at, Vec3 deltaMovement) {
        return at.add(deltaMovement.x, JUMP.y, deltaMovement.z);
    }

    private record Move(PredictionResult result, PacketReceiveEvent<ServerboundMoveVehicle> event) {}

    /** A ridden horse and the setback ClientboundMoveVehiclePackets the server sends it. */
    private record Ride(JavaTickFixture f, PacketEntityHorse horse, List<ClientboundMoveVehicle> moves)
            implements AutoCloseable {
        /**
         * One client tick: LocalPlayer#aiStep may release the jump, then LocalPlayer#tick sends the rider's Rot and the
         * horse's move, then the tick ends.
         */
        Move tick(Vec3 to, boolean onGround, boolean releaseJump) {
            PredictionResult before =
                    f.player.checkManager.getSimulationProcessor().getLastPrediction();
            if (releaseJump) f.command(PlayerCommandAction.START_JUMPING_WITH_HORSE, FULL_CHARGE);
            var rot = new ServerboundMovePlayer(0, 0, 0, 0, 0, false, false, false, true);
            f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
            var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), 0, 0, onGround, true);
            var event = f.event(ServerboundPackets.MOVE_VEHICLE, move);
            f.listener.onMoveVehicle(event, f.player, move);
            f.tickEnd();
            PredictionResult result =
                    f.player.checkManager.getSimulationProcessor().getLastPrediction();
            assertTrue(result != null && result != before, "the horse's move was not predicted");
            return new Move(result, event);
        }

        /** Rides out the vehicle-switch window standing on the floor. */
        void idleOnFloor() {
            for (int i = 0; i < VEHICLE_SWITCH_TICKS; i++) {
                assertVehicleExact(tick(GROUND, true, false).result(), i);
            }
        }

        /**
         * Rides out the vehicle-switch window falling from rest at {@code start}: LivingEntity#travel moves by the
         * deltaMovement, then applies gravity and drag. The position and deltaMovement it leaves.
         */
        Vec3[] fallFromRest(Vec3 start) {
            Vec3 at = start;
            double motion = 0.0D;
            for (int i = 0; i < VEHICLE_SWITCH_TICKS; i++) {
                at = at.add(0, motion, 0);
                assertVehicleExact(tick(at, false, false).result(), i);
                motion = (motion - GRAVITY) * VERTICAL_DRAG;
            }
            return new Vec3[] {at, new Vec3(0, motion, 0)};
        }

        /** The client answers the setback's ping and handleMoveVehicle echoes the snap with {@code onGround}. */
        PacketReceiveEvent<ServerboundMoveVehicle> acceptSetback(SetBackData setback, boolean onGround) {
            f.pongThrough(f.player.lastTransactionSent.get());
            ClientboundMoveVehicle snap = moves.getLast();
            Vec3 at = setback.getTeleportData().getLocation();
            var echo =
                    new ServerboundMoveVehicle(new Vec3d(at.x, at.y, at.z), snap.yaw(), snap.pitch(), onGround, true);
            var event = f.event(ServerboundPackets.MOVE_VEHICLE, echo);
            f.listener.onMoveVehicle(event, f.player, echo);
            assertTrue(setback.isComplete(), "the setback echo was not accepted");
            assertFalse(event.isCancelled(), "the setback echo did not reach the server");
            return event;
        }

        @Override
        public void close() throws Exception {
            f.close();
        }
    }

    private static Ride mount(Vec3 at, Vec3 velocity, boolean onGround) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.checkManager.getCheck(VehicleGround.class).setEnabled(true);
        List<ClientboundMoveVehicle> moves = new ArrayList<>();
        f.fixture.registrar.register(routes ->
                routes.send(ClientboundPackets.MOVE_VEHICLE, PacketListenerPriority.LOW, (event, player, packet) -> {
                    moves.add(packet);
                    new PacketServerTeleport().onMoveVehicle(event, player, packet);
                }));
        f.place(at, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, at, 0, 0, 0);
        PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
        horse.hasSaddle = true;
        horse.isTame = true;
        horse.jumpStrength = JUMP_STRENGTH;
        horse.onGround = onGround;
        f.setPassengers(HORSE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        // The client has the chunk it rides in once it answers a ping sent after it; until then a setback resyncs.
        f.pongThrough(f.player.sendTrailingTransaction());
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(velocity);
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.fromBoolean(onGround));
        horse.onGround = onGround;
        return new Ride(f, horse, moves);
    }
}
