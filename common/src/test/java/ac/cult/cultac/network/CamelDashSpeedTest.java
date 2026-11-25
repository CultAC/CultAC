package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.WorldPackets.TimeUpdate;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.math.Vec3;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A vanilla 26.3 client dashes a sprinting camel (CultAC issue #96, third capture, tick seq 7451), shifted by (-160,
 * -8, -155) onto the fixture's floor. LivingEntity#travelRidden runs Camel#tickRidden, whose
 * AbstractHorse#tickRidden dash sets the 55-tick dash cooldown, before Camel#getRiddenSpeed reads that cooldown: the
 * dash tick's ridden input has no sprint bonus, in water as on land.
 */
class CamelDashSpeedTest {
    private static final int CAMEL = 124;
    private static final int DASH = 19;
    private static final int LAST_POSE_CHANGE_TICK = 20;
    /** The capture's set_time on the arming tick. */
    private static final long GAME_TIME = 1117419L;
    /** LivingEntity#travelInAir: a grounded camel's (0 - 0.08) * 0.98F. */
    private static final double GROUNDED_Y = -0.0784000015258789;
    /**
     * A camel that landed in water: LivingEntity#travelInWater's 0 * 0.8F less gravity / 16, then
     * floatInWaterWhileRidden's 0.04F.
     */
    private static final double LANDED_IN_WATER_Y = 0.04F - 0.08 / 16.0;

    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);

    @Test
    void aDashingCamelsInputHasNoSprintBonus() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 before = new Vec3(3.81794731583975, FLOOR_Y, 0.96208165790574);
            Vec3 start = new Vec3(4.01373381135784, FLOOR_Y, 1.3137001803391);
            addCamel(f, start, 337.13254F);
            arm(f, carried(start.subtract(before)));

            assertExact(ride(f, 337.33383F, 9.769413F, new Vec3(4.92548243143412, 64.52157000337422, 3.4325438438441)));
        }
    }

    /**
     * The dash from a camel that has just landed in still water, with water_movement_efficiency 1.
     * LivingEntity#travelInWater lerps its 0.02F speed toward getSpeed(), which travelRidden set to the bonus-free
     * Camel#getRiddenSpeed.
     */
    @Test
    void aDashingCamelsWaterSpeedHasNoSprintBonus() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = new Vec3(0.5, FLOOR_Y, 0.5);
            addCamel(f, start, 0);
            f.water(new BlockPos(-2, FLOOR_Y, -2), new BlockPos(2, FLOOR_Y, 4));
            waterMovementEfficiency(f, 1.0);
            arm(f, new Vec3(0, LANDED_IN_WATER_Y, 0));

            // Camel#executeRidersJump at full charge: the flat look direction times 22.2222F * movement_speed, and
            // 1.4285F * jump power up.
            double dashZ = 22.2222F * 1.0F * (double) 0.09F;
            double dashY = 1.4285F * 1.0F * (double) 0.42F;
            // On the ground the whole water_movement_efficiency applies: the speed reaches getRiddenSpeed's 0.09F.
            float speed = 0.02F;
            speed += (0.09F - speed) * 1.0F;
            double walk = (double) 0.98F * (double) speed;
            assertExact(ride(f, 0, 0, start.add(0, LANDED_IN_WATER_Y + dashY, dashZ + walk)));
        }
    }

    /** The grounded camel's velocity after it moved {@code moved} unobstructed: LivingEntity#travelInAir's friction. */
    private static Vec3 carried(Vec3 moved) {
        return new Vec3(moved.x * GROUND_FRICTION, GROUNDED_Y, moved.z * GROUND_FRICTION);
    }

    /** The saddled, standing, grounded camel at {@code start}. */
    private static void addCamel(JavaTickFixture f, Vec3 start, float yaw) {
        f.player.compensatedWorld.applyClientTime(new TimeUpdate(GAME_TIME, Map.of()));
        f.place(start, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, start, yaw, 0, 0);
        PacketEntityCamel camel = (PacketEntityCamel) f.player.compensatedEntities.getEntity(CAMEL);
        camel.hasSaddle = true;
        camel.onGround = true;
        f.player.compensatedEntities.updateEntityMetadata(
                CAMEL,
                List.of(
                        new EntityMetadata.Entry(DASH, false, ByteBuffer.allocate(0)),
                        new EntityMetadata.Entry(LAST_POSE_CHANGE_TICK, GAME_TIME - 1000L, ByteBuffer.allocate(0))));
    }

    /** An update_attributes giving the camel {@code value} water_movement_efficiency. */
    private static void waterMovementEfficiency(JavaTickFixture f, double value) {
        var packet = new ClientboundUpdateAttributes(
                CAMEL, List.of(new AttributeSnapshot("minecraft:water_movement_efficiency", value, List.of())));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_ATTRIBUTES, packet, false);
        f.player.packetEntityReplication.onUpdateAttributes(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    /**
     * Mounts the camel carrying {@code carried}, ridden with forward and sprint held. The rider releases a full jump
     * charge: LocalPlayer#aiStep sends START_JUMPING_WITH_HORSE after the camel's tick.
     */
    private static void arm(JavaTickFixture f, Vec3 carried) {
        f.setPassengers(CAMEL, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.command(PlayerCommandAction.START_SPRINTING);
        f.command(PlayerCommandAction.START_JUMPING_WITH_HORSE, 90);
        f.input(FORWARD_SPRINT);
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(carried);
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
    }

    /** One client tick: the rider's Rot, the camel's move at half the pitch and the tick end. */
    private static PredictionResult ride(JavaTickFixture f, float yaw, float pitch, Vec3 to) {
        PredictionResult previous =
                f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), yaw, pitch * 0.5F, false, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != previous, "the vehicle move was not predicted");
        return result;
    }

    private static void assertExact(PredictionResult result) {
        StringBuilder report = new StringBuilder(describe(result));
        for (PredictionResult.Flag flag : result.getFlags()) {
            report.append(' ').append(flag.getVerbose().getString());
        }
        assertTrue(exact(result), report.toString());
    }
}
