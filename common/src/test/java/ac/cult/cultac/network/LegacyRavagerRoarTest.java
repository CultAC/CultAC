package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * From 1.21.2 to 1.21.5 the client roars itself: Ravager#handleEntityEvent(39) sets stunnedTick to 40, and
 * Ravager#aiStep, after the interpolation step of super.aiStep, counts roarTick from 20 once stunnedTick reaches 0
 * and runs roar at roarTick 10, the 50th aiStep. Its client branch runs strongKnockback on the local player inside
 * the ravager's box inflated by 4.0.
 */
class LegacyRavagerRoarTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final int RAVAGER = 100;
    private static final int SECOND_RAVAGER = 101;
    private static final int HORSE = 200;
    private static final byte STUN = 39;
    private static final byte ROAR = 69;
    private static final Vec3 STANDING = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 GROUNDED = new Vec3(0, -0.0784000015258789, 0);
    // On the 1/4096 position grid.
    private static final Vec3 RAVAGER_AT = new Vec3(-3328 / 4096.0, FLOOR_Y, 1024 / 4096.0);
    private static final Vec3 SECOND_RAVAGER_AT = new Vec3(0.5, FLOOR_Y, 0.5 + 6144 / 4096.0);
    private static final Vec3 HORSE_RAVAGER_AT = new Vec3(-1.5, FLOOR_Y, 0.25);
    private static final Vec3 KNOCKBACK = new Vec3(0.2, 0.3, 0.1);
    private static final Vec3 EXPLOSION = new Vec3(0.3, 0.1, -0.2);

    /** A ravager added after the local player ticks after it, so the 50th aiStep's push moves the next tick. */
    @Test
    void aStunnedRavagerTickingAfterThePlayerPushesTheTickAfterItsFiftiethAiStep() throws Exception {
        try (var f = standing(ClientVersion.V_1_21_4)) {
            addRavager(f);

            event(f, STUN);
            for (int tick = 1; tick <= 50; tick++) assertExact(f.tick(STANDING, true));

            assertExact(f.tick(STANDING.add(push(RAVAGER_AT)), false));
            assertEquals(0, f.player.checkManager.getExplosionHandler().violations);
        }
    }

    /**
     * A ravager the local player ticks after pushes the 50th tick's travel from where that tick's interpolation step
     * leaves it.
     */
    @Test
    void aStunnedRavagerTickingBeforeThePlayerPushesItsFiftiethTickFromWhereItsInterpolationStepLeavesIt()
            throws Exception {
        try (var f = standing(ClientVersion.V_1_21_4)) {
            addRavager(f);
            f.player.compensatedEntities.appendSelfToClientTickOrder();

            event(f, STUN);
            for (int tick = 1; tick <= 49; tick++) assertExact(f.tick(STANDING, true));
            move(f, 1024);

            // Entity#lerpPositionAndRotationStep moves a third of the way to a move_entity target in its first step.
            Vec3 target = new Vec3(RAVAGER_AT.x + 1024 / 4096.0, RAVAGER_AT.y, RAVAGER_AT.z);
            Vec3 at = new Vec3(
                    RAVAGER_AT.x + 1.0 / 3 * (target.x - RAVAGER_AT.x),
                    RAVAGER_AT.y,
                    RAVAGER_AT.z + 1.0 / 3 * (target.z - RAVAGER_AT.z));
            assertExact(f.tick(STANDING.add(push(at)), false));
            assertEquals(0, f.player.checkManager.getExplosionHandler().violations);
        }
    }

    @Test
    void aPlayerStayingWhereABeforeTravelRoarPushesThemIsFlagged() throws Exception {
        try (var f = standing(ClientVersion.V_1_21_4)) {
            addRavager(f);
            f.player.compensatedEntities.appendSelfToClientTickOrder();

            event(f, STUN);
            for (int tick = 1; tick <= 49; tick++) assertExact(f.tick(STANDING, true));
            f.tick(STANDING, true);

            assertTrue(f.player.checkManager.getExplosionHandler().violations > 0, "the push was not required");
        }
    }

    /**
     * LocalPlayer#sendPosition sends nothing on most ticks a player stands still, so the roar tick's travel is predicted
     * at its tick end without the push, which only moves the next tick.
     */
    @Test
    void aRoarTickSendingNoPositionPushesTheNextTick() throws Exception {
        try (var f = standing(ClientVersion.V_1_21_4)) {
            addRavager(f);

            event(f, STUN);
            for (int tick = 1; tick <= 49; tick++) assertExact(f.tick(STANDING, true));
            f.tickEnd();
            assertExact(f.player.checkManager.getSimulationProcessor().getLastPrediction());

            assertExact(f.tick(STANDING.add(push(RAVAGER_AT)), false));
            assertEquals(0, f.player.checkManager.getExplosionHandler().violations);
        }
    }

    /** A ravager the local player ticks after pushes the travel of a roar tick that sends no position too. */
    @Test
    void aPlayerSendingNoPositionOnABeforeTravelRoarTickIsFlagged() throws Exception {
        try (var f = standing(ClientVersion.V_1_21_4)) {
            addRavager(f);
            f.player.compensatedEntities.appendSelfToClientTickOrder();

            event(f, STUN);
            for (int tick = 1; tick <= 49; tick++) assertExact(f.tick(STANDING, true));
            f.tickEnd();
            f.tick(STANDING, true);

            assertTrue(f.player.checkManager.getExplosionHandler().violations > 0, "the push was not required");
        }
    }

    @Test
    void aPlayerSendingOnlyItsStatusOnABeforeTravelRoarTickIsFlagged() throws Exception {
        try (var f = standing(ClientVersion.V_1_21_4)) {
            addRavager(f);
            f.player.compensatedEntities.appendSelfToClientTickOrder();

            event(f, STUN);
            for (int tick = 1; tick <= 49; tick++) assertExact(f.tick(STANDING, true));
            f.statusTick(true, false);
            f.tick(STANDING, true);

            assertTrue(f.player.checkManager.getExplosionHandler().violations > 0, "the push was not required");
        }
    }

    /** A setback while a set_entity_motion sent before the roar tick's end is unanswered carries that velocity alone. */
    @Test
    void aSetbackAfterTheRoarTickCarriesAVelocityTheClientHandlesAfterIt() throws Exception {
        try (var f = standing(ClientVersion.V_1_21_4)) {
            addRavager(f);

            event(f, STUN);
            for (int tick = 1; tick <= 49; tick++) assertExact(f.tick(STANDING, true));
            f.velocity(KNOCKBACK);
            assertExact(f.tick(STANDING, true));
            f.player.getSetbackTeleportUtil().executeNonSimulatingSetback();

            assertEquals(KNOCKBACK, f.player.getSetbackTeleportUtil().getRequiredSetBack().getVelocity());
        }
    }

    /**
     * A set_entity_motion sent before the roar tick's end reaches the server but handled after that tick follows the
     * roar on the client, so its replacement delta movement leaves nothing of the push.
     */
    @Test
    void aVelocityHandledAfterTheRoarTickReplacesThePush() throws Exception {
        try (var f = standing(ClientVersion.V_1_21_4)) {
            addRavager(f);

            event(f, STUN);
            for (int tick = 1; tick <= 49; tick++) assertExact(f.tick(STANDING, true));
            int proof = f.velocity(KNOCKBACK);
            assertExact(f.tick(STANDING, true));
            f.pongThrough(proof);

            assertExact(f.tick(STANDING.add(KNOCKBACK), false));
            assertEquals(0, f.player.checkManager.getExplosionHandler().violations);
        }
    }

    @Test
    void aVelocitySentAfterTheRoarTickReplacesThePush() throws Exception {
        try (var f = standing(ClientVersion.V_1_21_4)) {
            addRavager(f);

            event(f, STUN);
            for (int tick = 1; tick <= 50; tick++) assertExact(f.tick(STANDING, true));
            f.pongThrough(f.velocity(KNOCKBACK));

            assertExact(f.tick(STANDING.add(KNOCKBACK), false));
            assertEquals(0, f.player.checkManager.getExplosionHandler().violations);
        }
    }

    /** A velocity handled after the roar tick replaces the push, and an explosion after it adds to that velocity. */
    @Test
    void aVelocityAndThenAnExplosionHandledAfterTheRoarTickLeaveNothingOfThePush() throws Exception {
        try (var f = standing(ClientVersion.V_1_21_4)) {
            addRavager(f);

            event(f, STUN);
            for (int tick = 1; tick <= 49; tick++) assertExact(f.tick(STANDING, true));
            int proof = f.velocity(KNOCKBACK);
            assertExact(f.tick(STANDING, true));
            f.pongThrough(proof);
            f.explosion(EXPLOSION);

            assertExact(f.tick(STANDING.add(KNOCKBACK).add(EXPLOSION), false));
        }
    }

    /** An explosion handled between the roar tick and the next adds to the push that tick consumes. */
    @Test
    void anExplosionHandledAfterTheRoarTickAddsToThePush() throws Exception {
        try (var f = standing(ClientVersion.V_1_21_4)) {
            addRavager(f);

            event(f, STUN);
            for (int tick = 1; tick <= 50; tick++) assertExact(f.tick(STANDING, true));
            f.explosion(EXPLOSION);

            assertExact(f.tick(STANDING.add(push(RAVAGER_AT)).add(EXPLOSION), false));
        }
    }

    @Test
    void anExplosionHandledAfterTheRoarTickAddsToThePushAfterAPingInTheRoarTick() throws Exception {
        try (var f = standing(ClientVersion.V_1_21_4)) {
            addRavager(f);

            event(f, STUN);
            for (int tick = 1; tick <= 49; tick++) assertExact(f.tick(STANDING, true));
            f.player.sendTransaction();
            f.pongThrough(f.player.lastTransactionSent.get());
            assertExact(f.tick(STANDING, true));
            f.explosion(EXPLOSION);

            assertExact(f.tick(STANDING.add(push(RAVAGER_AT)).add(EXPLOSION), false));
        }
    }

    /** Two ravagers stunned by one batch roar in the same aiStep, and the next tick moves both pushes. */
    @Test
    void twoRavagersRoaringInOneTickBothPush() throws Exception {
        try (var f = standing(ClientVersion.V_1_21_4)) {
            addRavager(f);
            addRavager(f, SECOND_RAVAGER, SECOND_RAVAGER_AT);

            send(f, RAVAGER, STUN);
            event(f, SECOND_RAVAGER, STUN);
            for (int tick = 1; tick <= 50; tick++) assertExact(f.tick(STANDING, true));

            Vec3 first = push(RAVAGER_AT);
            Vec3 second = push(SECOND_RAVAGER_AT);
            Vec3 move = new Vec3(first.x + second.x, GROUNDED.y + 0.4, first.z + second.z);
            assertExact(f.tick(STANDING.add(move), false));
        }
    }

    /** The ridden horse the player controls is the countdown roar's target, and the next tick moves it. */
    @Test
    void aCountdownRoarPushesTheHorseThePlayerControls() throws Exception {
        try (var f = riding()) {
            addRavager(f, RAVAGER, HORSE_RAVAGER_AT);

            event(f, STUN);
            for (int tick = 1; tick <= 50; tick++) assertVehicleExact(f.rideTick(STANDING, true), tick);

            assertVehicleExact(f.rideTick(STANDING.add(horseMove()), false), 51);
        }
    }

    @Test
    void aHorseStayingWhereTheCountdownRoarPushesItIsFlagged() throws Exception {
        try (var f = riding()) {
            addRavager(f, RAVAGER, HORSE_RAVAGER_AT);

            event(f, STUN);
            for (int tick = 1; tick <= 50; tick++) assertVehicleExact(f.rideTick(STANDING, true), tick);

            PredictionResult result = f.rideTick(STANDING, true);
            assertTrue(
                    result.getFlags().stream()
                            .anyMatch(flag -> flag.getCheck().getCheckName().equals("VehicleOffset")),
                    describe(result));
        }
    }

    @Test
    void aPlayerStayingWhereTheCountdownRoarPushesThemIsFlagged() throws Exception {
        try (var f = standing(ClientVersion.V_1_21_4)) {
            addRavager(f);

            event(f, STUN);
            for (int tick = 1; tick <= 50; tick++) assertExact(f.tick(STANDING, true));
            f.tick(STANDING, true);

            assertTrue(f.player.checkManager.getExplosionHandler().violations > 0, "the push was not required");
        }
    }

    /** A second event 39 sets stunnedTick back to 40 before it reaches 0, so the one roar comes 50 aiSteps after it. */
    @Test
    void aRepeatedStunRestartsTheCountdown() throws Exception {
        try (var f = standing(ClientVersion.V_1_21_4)) {
            addRavager(f);

            event(f, STUN);
            for (int tick = 1; tick <= 20; tick++) assertExact(f.tick(STANDING, true));
            event(f, STUN);
            for (int tick = 1; tick <= 50; tick++) assertExact(f.tick(STANDING, true));

            assertExact(f.tick(STANDING.add(push(RAVAGER_AT)), false));
        }
    }

    /** The server's event 69 for the same roar has no effect on a client before 1.21.6. */
    @Test
    void theServersRoarEventDoesNotPushAgain() throws Exception {
        try (var f = standing(ClientVersion.V_1_21_4)) {
            addRavager(f);

            event(f, STUN);
            for (int tick = 1; tick <= 50; tick++) assertExact(f.tick(STANDING, true));
            event(f, ROAR);

            Vec3 pushed = STANDING.add(push(RAVAGER_AT));
            assertExact(f.tick(pushed, false));
            assertEquals(0, f.player.checkManager.getExplosionHandler().violations);
        }
    }

    /** From 1.21.6 the client's Ravager#roar does nothing off the server, so a stun alone pushes no one. */
    @Test
    void aStunPushesNoOneOn26_1() throws Exception {
        try (var f = standing(ClientVersion.V_26_1)) {
            addRavager(f);

            event(f, STUN);
            for (int tick = 1; tick <= 60; tick++) assertExact(f.tick(STANDING, true));
        }
    }

    private static JavaTickFixture standing(ClientVersion client) throws Exception {
        var f = new JavaTickFixture(
                client == ClientVersion.V_26_1 ? ProtocolVersion.V26_1 : ProtocolVersion.V1_21_4, client);
        f.place(STANDING, GROUNDED);
        f.input(NONE);
        assertExact(f.tick(STANDING, true));
        return f;
    }

    /** The player on a saddled, tame horse standing at {@code STANDING}, which the player controls. */
    private static JavaTickFixture riding() throws Exception {
        var f = new JavaTickFixture(ProtocolVersion.V1_21_4, ClientVersion.V_1_21_4);
        f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, STANDING, 0, 0, 0);
        var horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
        horse.hasSaddle = true;
        horse.isTame = true;
        horse.onGround = true;
        f.setPassengers(HORSE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(NONE);
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(GROUNDED);
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
        for (int tick = 0; tick < 3; tick++) assertVehicleExact(f.rideTick(STANDING, true), tick);
        return f;
    }

    /** The horse's move the tick after the push from {@code HORSE_RAVAGER_AT}. */
    private static Vec3 horseMove() {
        double xd = STANDING.x - HORSE_RAVAGER_AT.x;
        double zd = STANDING.z - HORSE_RAVAGER_AT.z;
        double dd = xd * xd + zd * zd;
        // 1.21.4 LivingEntity#aiStep scales a horse's delta movement by 0.98 while it is not isEffectiveAi.
        return new Vec3(xd / dd * 4.0, GROUNDED.y + 0.2, zd / dd * 4.0).multiply(0.98, 0.98, 0.98);
    }

    private static void addRavager(JavaTickFixture f) {
        addRavager(f, RAVAGER, RAVAGER_AT);
    }

    private static void addRavager(JavaTickFixture f, int id, Vec3 at) {
        var add = new ClientboundAddEntity(id, "minecraft:ravager", new Vec3d(at.x, at.y, at.z), 0, 0, 0);
        var event =
                new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, add, false);
        f.player.packetEntityReplication.onAddEntity(event, f.player, add);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        boolean riding = f.player.compensatedEntities.getSelf().getRiding() != null;
        for (int tick = 0; tick < 3; tick++) {
            if (riding) assertVehicleExact(f.rideTick(STANDING, true), tick);
            else assertExact(f.tick(STANDING, true));
        }
    }

    private static void move(JavaTickFixture f, int dx) {
        var move = new ClientboundMoveEntity(
                RAVAGER, new EntityDelta.Linear((short) dx, (short) 0, (short) 0), 0.0F, 0.0F, true, true, false);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, move, false);
        f.player.packetEntityReplication.onMoveEntity(event, f.player, move);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** The ravager's entity event {@code status}, answered through its trailing ping before the client's next tick. */
    private static void event(JavaTickFixture f, byte status) {
        event(f, RAVAGER, status);
    }

    private static void event(JavaTickFixture f, int id, byte status) {
        send(f, id, status);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** Ravager {@code id}'s entity event {@code status}, unanswered. */
    private static void send(JavaTickFixture f, int id, byte status) {
        var packet = new ClientboundEntityEvent(id, status);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_EVENT, packet, false);
        f.player.packetEntityReplication.onEntityEvent(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    /** Ravager#strongKnockback from {@code at} on the standing player, as its grounded tick's move. */
    private static Vec3 push(Vec3 at) {
        double xd = STANDING.x - at.x;
        double zd = STANDING.z - at.z;
        double dd = Math.max(xd * xd + zd * zd, 0.001);
        return new Vec3(xd / dd * 4.0, GROUNDED.y + 0.2, zd / dd * 4.0);
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
