package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.listeners.PacketPlayerAttack;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.WorldPackets.TimeUpdate;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.math.Vec3;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A vanilla 26.3 client sprinting forward on a camel attacks a player (CultAC issue #94, tick seq 8214). Minecraft#tick
 * handles the attack key before the level tick, so Player#causeExtraKnockback's setSprinting(false) holds through the
 * camel's travel and Camel#getRiddenSpeed has no sprint bonus that tick. The capture's moves, shifted by (-157, -8,
 * -155) onto the fixture's floor.
 */
class CamelRiderAttackTest {
    private static final int CAMEL = 72;
    private static final int TARGET = 2;
    private static final int DASH = 19;
    private static final int LAST_POSE_CHANGE_TICK = 20;
    /** The capture's set_time after the attack tick. */
    private static final long GAME_TIME = 1027398L;
    /** LivingEntity#travelInAir: a grounded camel's (0 - 0.08) * 0.98F. */
    private static final double GROUNDED_Y = -0.0784000015258789;

    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);

    @Test
    void anAttackFromASprintingCamelDropsTheSprintBonusForThatMove() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 before = new Vec3(3.17344922121455, FLOOR_Y, -0.53820141213555);
            Vec3 start = new Vec3(2.8354169849571, FLOOR_Y, -0.37897121093454);
            mount(f, start, carried(start.subtract(before)));
            // Ticks seq 7715 to 8179: the sprint ticks before the attack, which leave the attack at full strength.
            assertExact(ride(f, 64.12334F, 33.818405F, new Vec3(2.48332290670302, FLOOR_Y, -0.21076247191422)));
            assertExact(ride(f, 63.922035F, 33.818405F, new Vec3(2.12384039672222, FLOOR_Y, -0.0370576870812)));
            assertExact(ride(f, 63.854935F, 33.818405F, new Vec3(1.76041810552684, FLOOR_Y, 0.13984029077264)));
            assertExact(ride(f, 63.720734F, 33.818405F, new Vec3(1.39503393209995, FLOOR_Y, 0.31886613368212)));
            assertExact(ride(f, 63.720734F, 33.885506F, new Vec3(1.02857857085024, FLOOR_Y, 0.49905379104692)));
            assertExact(ride(f, 63.720734F, 33.885506F, new Vec3(0.66153834098134, FLOOR_Y, 0.67987579917803)));
            f.player.compensatedEntities.addEntity(TARGET, EntityTypeIds.PLAYER, new Vec3(-1.5, FLOOR_Y, 2.0), 0, 0, 0);
            f.pongThrough(f.player.lastTransactionSent.get());

            var attack =
                    new ServerboundInteract(TARGET, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
            new PacketPlayerAttack().onInteract(f.event(ServerboundPackets.INTERACT, attack), f.player, attack);

            assertExact(ride(f, 63.653633F, 34.019707F, new Vec3(0.38209885984097, FLOOR_Y, 0.81775347256607)));
        }
    }

    /** The grounded camel's velocity after it moved {@code moved} unobstructed: LivingEntity#travelInAir's friction. */
    private static Vec3 carried(Vec3 moved) {
        return new Vec3(moved.x * GROUND_FRICTION, GROUNDED_Y, moved.z * GROUND_FRICTION);
    }

    /** The saddled, standing camel at {@code start} carrying {@code carried}, ridden with forward and sprint held. */
    private static void mount(JavaTickFixture f, Vec3 start, Vec3 carried) {
        f.player.compensatedWorld.applyClientTime(new TimeUpdate(GAME_TIME, Map.of()));
        f.place(start, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, start, 63.720734F, 0, 0);
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
        f.input(FORWARD_SPRINT);
        var command = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
        new PacketEntityAction()
                .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(carried);
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
    }

    /** One client tick: the rider's Rot, the camel's grounded move at half the pitch and the tick end. */
    private static PredictionResult ride(JavaTickFixture f, float yaw, float pitch, Vec3 to) {
        PredictionResult previous =
                f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), yaw, pitch * 0.5F, true, true);
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
