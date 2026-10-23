package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.WorldPackets.TimeUpdate;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
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
 * dash tick's ridden input has no sprint bonus.
 */
class CamelDashSpeedTest {
    private static final int CAMEL = 124;
    private static final int DASH = 19;
    private static final int LAST_POSE_CHANGE_TICK = 20;
    /** The capture's set_time on the arming tick. */
    private static final long GAME_TIME = 1117419L;
    /** LivingEntity#travelInAir: a grounded camel's (0 - 0.08) * 0.98F. */
    private static final double GROUNDED_Y = -0.0784000015258789;

    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);

    @Test
    void aDashingCamelsInputHasNoSprintBonus() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 before = new Vec3(3.81794731583975, FLOOR_Y, 0.96208165790574);
            Vec3 start = new Vec3(4.01373381135784, FLOOR_Y, 1.3137001803391);
            mount(f, start, carried(start.subtract(before)));

            assertExact(ride(f, 337.33383F, 9.769413F, new Vec3(4.92548243143412, 64.52157000337422, 3.4325438438441)));
        }
    }

    /** The grounded camel's velocity after it moved {@code moved} unobstructed: LivingEntity#travelInAir's friction. */
    private static Vec3 carried(Vec3 moved) {
        return new Vec3(moved.x * GROUND_FRICTION, GROUNDED_Y, moved.z * GROUND_FRICTION);
    }

    /**
     * The saddled, standing camel at {@code start} carrying {@code carried}, ridden with forward and sprint held. The
     * rider releases a full jump charge: LocalPlayer#aiStep sends START_JUMPING_WITH_HORSE after the camel's tick.
     */
    private static void mount(JavaTickFixture f, Vec3 start, Vec3 carried) {
        f.player.compensatedWorld.applyClientTime(new TimeUpdate(GAME_TIME, Map.of()));
        f.place(start, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, start, 337.13254F, 0, 0);
        PacketEntityCamel camel = (PacketEntityCamel) f.player.compensatedEntities.getEntity(CAMEL);
        camel.hasSaddle = true;
        camel.onGround = true;
        f.player.compensatedEntities.updateEntityMetadata(
                CAMEL,
                List.of(
                        new EntityMetadata.Entry(DASH, false, ByteBuffer.allocate(0)),
                        new EntityMetadata.Entry(LAST_POSE_CHANGE_TICK, GAME_TIME - 1000L, ByteBuffer.allocate(0))));
        f.setPassengers(CAMEL, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        command(f, PlayerCommandAction.START_SPRINTING, 0);
        command(f, PlayerCommandAction.START_JUMPING_WITH_HORSE, 90);
        f.input(FORWARD_SPRINT);
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(carried);
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
    }

    private static void command(JavaTickFixture f, PlayerCommandAction action, int data) {
        var command = new ServerboundPlayerCommand(f.player.entityID, action, data);
        new PacketEntityAction()
                .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
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
