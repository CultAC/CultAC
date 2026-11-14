package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.sprint.SprintD;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveMobEffect;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateMobEffect;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LocalPlayer#aiStep stops a sprint before travel once LocalPlayer#isSprintingPossible fails, which
 * Player#isMobilityRestricted does under blindness, whenever the sprint started.
 */
class BlindSprintTest {
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final double FRICTION = 0.6F * 0.91F;
    private static final float WALK_SPEED = 0.1F;
    private static final float SPRINT_SPEED = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
    private static final float YAW = 30.0F;
    private static final int HORSE = 100;

    @Test
    void aSprintKeptOnceBlindnessIsProvenFlags() throws Exception {
        try (var f = fixture()) {
            f.command(PlayerCommandAction.START_SPRINTING);
            Vec3[] state = sprint(f, new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO, 3);
            int proof = blindness(f);
            f.pongThrough(proof);
            move(f, state, SPRINT_SPEED);
            assertTrue(sprintD(f).violations > 0);
        }
    }

    @Test
    void aSprintStoppedOnceBlindnessIsProvenIsExact() throws Exception {
        try (var f = fixture()) {
            f.command(PlayerCommandAction.START_SPRINTING);
            Vec3[] state = sprint(f, new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO, 3);
            int proof = blindness(f);
            f.pongThrough(proof);
            f.command(PlayerCommandAction.STOP_SPRINTING);
            for (int tick = 0; tick < 5; tick++) {
                PredictionResult result = move(f, state, WALK_SPEED);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
            }
            assertEquals(0.0, sprintD(f).violations, 0.0);
        }
    }

    /** The client handles the effect before it answers the ping after it, so until then it may still sprint. */
    @Test
    void aSprintBeforeTheBlindnessPongIsNotFlagged() throws Exception {
        try (var f = fixture()) {
            f.command(PlayerCommandAction.START_SPRINTING);
            Vec3[] state = sprint(f, new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO, 3);
            blindness(f);
            assertFalse(f.player.compensatedEntities.hasPotionEffect(MovementEffect.BLINDNESS));
            for (int tick = 0; tick < 10; tick++) {
                PredictionResult result = move(f, state, SPRINT_SPEED);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
            }
            assertEquals(0.0, sprintD(f).violations, 0.0);
        }
    }

    @Test
    void aSprintStartedWhileBlindFlags() throws Exception {
        try (var f = fixture()) {
            f.pongThrough(blindness(f));
            f.command(PlayerCommandAction.START_SPRINTING);
            move(f, new Vec3[] {new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO}, SPRINT_SPEED);
            assertTrue(sprintD(f).violations > 0);
        }
    }

    /** ClientPacketListener#handleRemoveMobEffect runs before the client answers the ping after it and ticks. */
    @Test
    void aStartInTheTickAfterTheRemovalPongIsNotFlagged() throws Exception {
        try (var f = fixture()) {
            f.pongThrough(blindness(f));
            Vec3[] state = {new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO};
            for (int tick = 0; tick < 3; tick++) assertTrue(exact(move(f, state, WALK_SPEED)), "tick " + tick);
            f.pongThrough(removeBlindness(f));
            f.command(PlayerCommandAction.START_SPRINTING);
            for (int tick = 0; tick < 3; tick++) {
                PredictionResult result = move(f, state, SPRINT_SPEED);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
            }
            assertEquals(0.0, sprintD(f).violations, 0.0);
        }
    }

    /** A late pong leaves the client blind and walking until it answers; its first sprint follows that answer. */
    @Test
    void aStartAfterALateRemovalPongIsNotFlagged() throws Exception {
        try (var f = fixture()) {
            f.pongThrough(blindness(f));
            Vec3[] state = {new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO};
            for (int tick = 0; tick < 2; tick++) assertTrue(exact(move(f, state, WALK_SPEED)), "tick " + tick);
            int removal = removeBlindness(f);
            for (int tick = 0; tick < 10; tick++) {
                PredictionResult result = move(f, state, WALK_SPEED);
                assertTrue(exact(result), "in flight tick " + tick + ": " + describe(result));
            }
            assertTrue(f.player.compensatedEntities.hasPotionEffect(MovementEffect.BLINDNESS));
            f.pongThrough(removal);
            f.command(PlayerCommandAction.START_SPRINTING);
            for (int tick = 0; tick < 3; tick++) {
                PredictionResult result = move(f, state, SPRINT_SPEED);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
            }
            assertEquals(0.0, sprintD(f).violations, 0.0);
        }
    }

    /**
     * A horse cannot sprint, so LocalPlayer#aiStep stops a passenger's sprint on the first ride tick and sends the
     * STOP_SPRINTING after the vehicle move; blindness changes nothing a passenger then sends.
     */
    @Test
    void aBlindHorseRiderThatStopsItsSprintIsNotFlagged() throws Exception {
        try (var f = fixture()) {
            Vec3 horseAt = mountHorse(f);
            f.pongThrough(blindness(f));
            for (int tick = 0; tick < 4; tick++) {
                rideTick(f, horseAt, tick == 0);
            }
            assertEquals(0.0, sprintD(f).violations, 0.0);
        }
    }

    @Test
    void aBlindHorseRiderThatKeepsItsSprintFlags() throws Exception {
        try (var f = fixture()) {
            Vec3 horseAt = mountHorse(f);
            f.pongThrough(blindness(f));
            for (int tick = 0; tick < 4; tick++) {
                rideTick(f, horseAt, false);
            }
            assertTrue(sprintD(f).violations > 0);
        }
    }

    /** Seats the sprinting player on a saddled horse standing on the floor; the horse's position. */
    private static Vec3 mountHorse(JavaTickFixture f) {
        Vec3 horseAt = new Vec3(0.5, FLOOR_Y, 3.5);
        f.place(horseAt.add(0, 1, 0), Vec3.ZERO);
        f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, horseAt, 0, 0, 0);
        ((PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE)).hasSaddle = true;
        f.setPassengers(HORSE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
        f.player.isSprinting = true;
        f.player.sprintState.onClientTick(true);
        f.player.sprintState.onClientTickEnd();
        return horseAt;
    }

    /** A ride tick: the passenger's Rot, the horse's move, then STOP_SPRINTING when {@code stop}. */
    private static void rideTick(JavaTickFixture f, Vec3 horseAt, boolean stop) {
        var rot = new ServerboundMovePlayer(0, 0, 0, f.player.xRot, f.player.yRot, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        f.moveVehicle(horseAt, f.player.xRot, f.player.yRot, true);
        if (stop) f.command(PlayerCommandAction.STOP_SPRINTING);
        f.tickEnd();
    }

    /** A blindness removal for the player; the ping the client answers once it has handled it. */
    private static int removeBlindness(JavaTickFixture f) {
        var packet = new ClientboundRemoveMobEffect(f.player.entityID, "minecraft:blindness");
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_MOB_EFFECT, packet, false);
        f.player.packetEntityReplication.onRemoveMobEffect(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    private static Vec3[] sprint(JavaTickFixture f, Vec3 position, Vec3 velocity, int ticks) {
        Vec3[] state = {position, velocity};
        for (int tick = 0; tick < ticks; tick++) {
            PredictionResult result = move(f, state, SPRINT_SPEED);
            assertTrue(exact(result), "tick " + tick + ": " + describe(result));
        }
        return state;
    }

    /** One ground tick at {@code speed}; advances {@code state}, the position and carried velocity. */
    private static PredictionResult move(JavaTickFixture f, Vec3[] state, float speed) {
        Vec3 delta = state[1].add(ClientMoveInput.of(f, SPRINT, 1.0F, speed, YAW));
        state[0] = state[0].add(delta.x, 0.0, delta.z);
        state[1] = new Vec3(delta.x * FRICTION, 0.0, delta.z * FRICTION);
        return f.tick(state[0], true);
    }

    /** A blindness update for the player; the ping the client answers once it has handled it. */
    private static int blindness(JavaTickFixture f) {
        var packet = new ClientboundUpdateMobEffect(f.player.entityID, "minecraft:blindness", 0);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_MOB_EFFECT, packet, false);
        f.player.packetEntityReplication.onUpdateMobEffect(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    private static SprintD sprintD(JavaTickFixture f) {
        return f.player.checkManager.getCheck(SprintD.class);
    }

    private static JavaTickFixture fixture() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.setExperimentalChecks(true);
        SprintD check = sprintD(f);
        check.setEnabled(true);
        check.reload();
        check.violations = 0;
        f.place(new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO);
        f.player.xRot = YAW;
        f.input(SPRINT);
        return f;
    }
}
