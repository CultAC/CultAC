package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.NoFallPseudo;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * From 1.21.4 ServerboundMoveVehiclePacket#fromEntity reports the root's Entity#onGround, which the root's last
 * Entity#move set, and ServerGamePacketListenerImpl#handleMoveVehicle applies it. AbstractHorse#tickRidden executes the
 * rider's pending jump only while that ground holds, so the next ridden travel starts from the ground the move set.
 */
class JavaRiddenRootGroundTest {
    private static final int HORSE = 100;
    private static final float SPEED = 0.2249995F;
    private static final double JUMP_STRENGTH = 0.7;
    /** START_JUMPING_WITH_HORSE's charge: 0.4F + 0.4F * 82 / 90.0F of the jump strength. */
    private static final int JUMP_CHARGE = 82;
    /** AbstractHorse#executeRidersJump's vertical velocity at that charge, as in LegacyHorseJumpLandingTest. */
    private static final double JUMP = 0.535111129283905;

    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @Test
    void anAirborneRootStartsItsNextTravelAirborne() throws Exception {
        try (var f = fixture(ClientVersion.V_1_21_4)) {
            Vec3 start = new Vec3(0.5, FLOOR_Y + 3, 0.5);
            PacketEntityHorse horse = mount(f, start);

            // At rest in the air, LivingEntity#travelInAir moves the root nowhere: no downward collision.
            PredictionResult before = last(f);
            var event = ride(f, start, true);
            // LocalPlayer#aiStep releases the charged jump after the root's tick.
            var jump = new ServerboundPlayerCommand(
                    f.player.entityID, PlayerCommandAction.START_JUMPING_WITH_HORSE, JUMP_CHARGE);
            new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, jump), f.player, jump);
            f.tickEnd();
            PredictionResult still = last(f);
            assertTrue(still != null && still != before, "the vehicle move was not predicted");
            assertFalse(still.isExempt(), describe(still));
            assertTrue(still.getOffset() < 1.0E-5, describe(still));
            assertTrue(still.hasFlag(NoFallPseudo.class), describe(still));
            assertEquals(Boolean.FALSE, still.getDesiredOnGround());
            assertFalse(event.getPacket().onGround(), "handleMoveVehicle must receive the root's own ground");
            assertFalse(horse.onGround, "the next travel must start from the root's own ground");
            assertEquals(
                    DesyncStatus.FALSE,
                    f.player.checkManager.getSimulationProcessor().getLastOnGround());

            // AbstractHorse#tickRidden keeps the pending jump until the root starts a tick on the ground.
            try (var ignored = JavaTickFixture.observeOnly()) {
                ride(f, start.add(0, JUMP, 0), false);
                f.tickEnd();
                PredictionResult jumped = last(f);
                assertTrue(jumped != still, "the vehicle move was not predicted");
                assertTrue(jumped.getOffset() > 0.5, describe(jumped));
            }
        }
    }

    /**
     * A 1.21.2 client sends no ground, yet the packet carries one. The client claimed nothing, and handleMoveVehicle
     * receives the ground the root's own move set.
     */
    @Test
    void anUnsentGroundReachesTheServerAsTheRootsOwn() throws Exception {
        try (var f = fixture(ClientVersion.V_1_21_2)) {
            Vec3 start = new Vec3(0.5, FLOOR_Y + 3, 0.5);
            PacketEntityHorse horse = mount(f, start);

            PredictionResult before = last(f);
            var event = ride(f, start, true);
            f.tickEnd();
            PredictionResult still = last(f);
            assertTrue(still != null && still != before, "the vehicle move was not predicted");
            assertTrue(JavaTickFixture.exact(still), describe(still));
            assertFalse(event.getPacket().onGround(), "handleMoveVehicle must receive the root's own ground");
            assertFalse(horse.onGround, "the next travel must start from the root's own ground");
            assertEquals(
                    DesyncStatus.FALSE,
                    f.player.checkManager.getSimulationProcessor().getLastOnGround());
        }
    }

    private static JavaTickFixture fixture(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.player.checkManager.getNoFall().setEnabled(true);
        assertTrue(f.player.checkManager.getNoFall().shouldModifyPackets());
        return f;
    }

    private static PacketEntityHorse mount(JavaTickFixture f, Vec3 start) {
        f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, start, 0, 0, 0);
        PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
        horse.hasSaddle = true;
        horse.isTame = true;
        horse.movementSpeedAttribute = SPEED;
        horse.jumpStrength = JUMP_STRENGTH;
        // The spawned horse's interpolation target settles before it is ridden.
        for (int tick = 0; tick < 3; tick++) f.tickEnd();
        f.setPassengers(HORSE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(NONE);
        f.tickEnd();
        horse.onGround = false;
        horse.deltaMovement = Vec3.ZERO;
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(Vec3.ZERO);
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
        return horse;
    }

    /** The rider's Rot and the root's move, as LocalPlayer#tick sends them. */
    private static PacketReceiveEvent<ServerboundMoveVehicle> ride(JavaTickFixture f, Vec3 to, boolean onGround) {
        var rot = new ServerboundMovePlayer(0, 0, 0, 0.0F, 0.0F, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), 0.0F, 0.0F, onGround, true);
        var event = f.event(ServerboundPackets.MOVE_VEHICLE, move);
        f.listener.onMoveVehicle(event, f.player, move);
        return event;
    }

    private static PredictionResult last(JavaTickFixture f) {
        return f.player.checkManager.getSimulationProcessor().getLastPrediction();
    }
}
