package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A saddled horse ridden forward from rest on 0.6-friction ground (CultAC issue #75). Before 1.21.5,
 * LivingEntity#aiStep scales the delta movement by 0.98 when the entity is not interpolating and
 * Entity#isEffectiveAi is false, which Mob#isEffectiveAi is on every client, including for a horse the local player
 * controls. From 1.21.5 the branch tests canSimulateMovement, which is true for a locally controlled horse.
 */
class LegacyHorseDragTest {
    private static final int HORSE = 100;
    /** The reported horse's movement_speed: its move from rest covers 0.98F of it. */
    private static final float SPEED = 0.225F;
    /** LivingEntity#travelInAir on a 0.6-friction block. */
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    /** LivingEntity#travelInAir: a grounded horse's (0 - 0.08) * 0.98F. */
    private static final double GROUNDED_Y = -0.0784000015258789;
    /**
     * LivingEntity#moveRelative for the ridden input's 0.98F along the horse's facing on 0.6-friction ground:
     * getFrictionInfluencedSpeed scales movement_speed by 0.21600002F over the friction cubed.
     */
    private static final double GROUND_INPUT = (double) 0.98F * (SPEED * (0.21600002F / (0.6F * 0.6F * 0.6F)));

    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_RIGHT =
            new ServerboundPlayerInput(true, false, false, true, false, false, false);

    /**
     * Each move scales the carried velocity by the client's drag, zeroes it under LivingEntity#aiStep's 0.003
     * threshold, adds the ridden input, and carries the move times the ground friction. The reported 1.21.4 moves
     * follow this with the 0.98 drag, settling at 0.4743 per tick for a 0.225 horse.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"V_1_21_2, 0.98", "V_1_21_4, 0.98", "V_1_21_5, 1.0", "V_26_3, 1.0"})
    void aRiddenHorseCarriesTheClientsDrag(ClientVersion version, double drag) throws Exception {
        try (var f = new JavaTickFixture()) {
            setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 start = new Vec3(0.5, FLOOR_Y, 0.5);
            mount(f, start, FORWARD, SPEED);

            double velocity = 0.0;
            double z = start.z;
            for (int tick = 0; tick < 12; tick++) {
                double move = velocity * drag;
                if (Math.abs(move) < 0.003) move = 0.0;
                move += GROUND_INPUT;
                z += move;
                assertVehicleExact(ride(f, new Vec3(start.x, FLOOR_Y, z), 0F), tick);
                velocity = move * GROUND_FRICTION;
            }
        }
    }

    /**
     * The reported 1.21.4 move from rest with forward and right held (CultAC issue #128). Before 1.21.5,
     * LocalPlayer#serverAiStep stores the raw impulses in xxa/zza and LivingEntity#aiStep scales them by 0.98F, so
     * AbstractHorse#getRiddenInput returns (-0.49, 0, 0.98); its squared length is over 1, so
     * Entity#getInputVector normalizes it and the move covers the whole movement_speed.
     */
    @Test
    void aLegacyRiddenHorseMovesAtFullSpeedWithForwardAndStrafeHeld() throws Exception {
        try (var f = new JavaTickFixture()) {
            setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
            Vec3 start = new Vec3(3.17404, FLOOR_Y, 5.16243);
            mount(f, start, FORWARD_RIGHT, 0.22499502F);
            assertVehicleExact(ride(f, start.add(0.17158700747256717, 0.0, 0.14553574750553366), -76.26071F), 0);
        }
    }

    /** The saddled horse at rest at {@code start} facing +z, ridden with {@code input} held. */
    private static void mount(JavaTickFixture f, Vec3 start, ServerboundPlayerInput input, float speed) {
        f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, start, 0F, 0, 0);
        PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
        horse.hasSaddle = true;
        horse.isTame = true;
        horse.movementSpeedAttribute = speed;
        horse.onGround = true;
        f.setPassengers(HORSE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        // AbstractHorse#tick runs before LocalPlayer#rideTick hands it this tick's keys.
        f.input(input);
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0, GROUNDED_Y, 0));
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
    }

    /** One client tick: the rider's Rot at {@code yaw}, the horse's move and the tick end. */
    private static PredictionResult ride(JavaTickFixture f, Vec3 to, float yaw) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, yaw, 0F, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), yaw, 0F, true, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != before, "the vehicle move was not predicted");
        return result;
    }
}
