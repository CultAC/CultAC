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
 * Ravager#handleEntityEvent(69) runs applyRoarKnockbackClient: strongKnockback adds to the delta movement of the local
 * player inside the ravager's box inflated by 4.0, from where both are when the client handles the event.
 */
class JavaRavagerRoarTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput SPRINT_FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final int RAVAGER = 100;
    private static final int HORSE = 200;
    private static final int BOAT = 300;
    private static final byte ROAR = 69;
    private static final Vec3 STANDING = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 GROUNDED = new Vec3(0, -0.0784000015258789, 0);

    /**
     * The reported tick, moved by (-210, 2, -187): a sprinting player 1.375 blocks from the ravager moves
     * (0.9554, 0.1216, -2.5011) with the move's own rotation. The capture has no spawn of the ravager; strongKnockback
     * gives the move's difference from the tick's sprint without the push from a ravager on the 1/4096 position grid.
     */
    @Test
    void theReportedRoarPushesTheSprintingPlayer() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_1, ClientVersion.V_26_1)) {
            Vec3 from = new Vec3(210.7777344484142 - 210, 62.0 + 2, 186.9704566877583 - 187);
            f.place(from, new Vec3(-0.05482678475034155, -0.0784000015258789, 0.09192651710493299));
            f.player.xRot = 29.558634F;
            f.player.yRot = 17.710155F;
            f.player.isSprinting = true;
            f.player.sprintState.onClientTick(true);
            f.input(SPRINT_FORWARD);
            addRavager(f, new Vec3(861268 / 4096.0 - 210, 62.0 + 2, 771066 / 4096.0 - 187));

            roar(f);

            Vec3 to = new Vec3(211.73313200346954 - 210, 62.121599998474125 + 2, 184.46937239899904 - 187);
            assertExact(f.tick(to, false));
            assertEquals(0, f.player.checkManager.getExplosionHandler().violations);
        }
    }

    @Test
    void aRoarPushesAStandingPlayerAwayFromTheRavager() throws Exception {
        try (var f = standing()) {
            addRavager(f, new Vec3(-0.7, FLOOR_Y, 0.0));

            roar(f);

            // xd = 1.2, zd = 0.5: the push is (xd, zd) / (xd * xd + zd * zd) * 4.0 with 0.2 up.
            double dd = 1.2 * 1.2 + 0.5 * 0.5;
            Vec3 move = new Vec3(1.2 / dd * 4.0, GROUNDED.y + 0.2, 0.5 / dd * 4.0);
            assertExact(f.tick(STANDING.add(move), false));
            assertEquals(0, f.player.checkManager.getExplosionHandler().violations);
        }
    }

    /**
     * A move_entity before the event in the roar's bundle only sets the interpolation target, so the push is from where
     * the ravager stood.
     */
    @Test
    void aMoveBundledBeforeTheRoarPushesFromTheRavagersOldPosition() throws Exception {
        try (var f = standing()) {
            Vec3 at = new Vec3(-3328 / 4096.0, FLOOR_Y, 1024 / 4096.0);
            var add = new ClientboundAddEntity(RAVAGER, "minecraft:ravager", new Vec3d(at.x, at.y, at.z), 0, 0, 0);
            var addEvent = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, add, false);
            f.player.packetEntityReplication.onAddEntity(addEvent, f.player, add);
            addEvent.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            for (int tick = 0; tick < 3; tick++) assertExact(f.tick(STANDING, true));

            var move = new ClientboundMoveEntity(
                    RAVAGER, new EntityDelta.Linear((short) 1024, (short) 0, (short) 0), 0.0F, 0.0F, true, true, false);
            var moveEvent = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, move, false);
            f.player.packetEntityReplication.onMoveEntity(moveEvent, f.player, move);
            moveEvent.tasksAfter().forEach(Runnable::run);
            roar(f);

            double xd = STANDING.x - at.x;
            double zd = STANDING.z - at.z;
            double dd = xd * xd + zd * zd;
            Vec3 push = new Vec3(xd / dd * 4.0, GROUNDED.y + 0.2, zd / dd * 4.0);
            assertExact(f.tick(STANDING.add(push), false));
            assertEquals(0, f.player.checkManager.getExplosionHandler().violations);
        }
    }

    @Test
    void aPlayerStayingWhereTheRoarPushesThemIsFlagged() throws Exception {
        try (var f = standing()) {
            addRavager(f, new Vec3(-0.7, FLOOR_Y, 0.0));

            roar(f);
            f.tick(STANDING, true);

            assertTrue(f.player.checkManager.getExplosionHandler().violations > 0, "the push was not required");
        }
    }

    @Test
    void aRoarDoesNotReachAPlayerOutsideTheInflatedBox() throws Exception {
        try (var f = standing()) {
            // The ravager's half width 0.975 inflated by 4.0, and the player's 0.3, end at 5.275.
            addRavager(f, new Vec3(STANDING.x + 5.28, FLOOR_Y, STANDING.z));

            roar(f);

            assertExact(f.tick(STANDING, true));
            assertEquals(0, f.player.checkManager.getExplosionHandler().violations);
        }
    }

    /** A ridden horse is a living entity its rider controls, so strongKnockback pushes the horse from where it is. */
    @Test
    void aRoarPushesTheHorseThePlayerControls() throws Exception {
        try (var f = riding(ProtocolVersion.V26_1, ClientVersion.V_26_1)) {
            pushesTheHorse(f);
        }
    }

    @Test
    void aRoarPushesTheHorseThePlayerControlsOn26_3() throws Exception {
        try (var f = riding(ProtocolVersion.V26_3, ClientVersion.V_26_3)) {
            pushesTheHorse(f);
        }
    }

    private static void pushesTheHorse(JavaTickFixture f) {
        Vec3 ravager = new Vec3(-1.5, FLOOR_Y, 0.25);
        addRavager(f, ravager);

        roar(f);

        double xd = STANDING.x - ravager.x;
        double zd = STANDING.z - ravager.z;
        double dd = xd * xd + zd * zd;
        Vec3 move = new Vec3(xd / dd * 4.0, GROUNDED.y + 0.2, zd / dd * 4.0);
        assertVehicleExact(f.rideTick(STANDING.add(move), false), 3);
    }

    @Test
    void aHorseStayingWhereTheRoarPushesItIsFlagged() throws Exception {
        try (var f = riding(ProtocolVersion.V26_1, ClientVersion.V_26_1)) {
            addRavager(f, new Vec3(-1.5, FLOOR_Y, 0.25));

            roar(f);

            // The missed push is a desync, so the flagged tick is also resynced.
            PredictionResult result = f.rideTick(STANDING, true);
            assertTrue(
                    result.getFlags().stream()
                            .anyMatch(flag -> flag.getCheck().getCheckName().equals("VehicleOffset")),
                    describe(result));
        }
    }

    /** Ravager#ROAR_TARGET_ON_CLIENT selects only living entities, so the boat the player steers keeps falling. */
    @Test
    void aRoarDoesNotPushTheBoatThePlayerSteers() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_1, ClientVersion.V_26_1)) {
            Vec3 seat = new Vec3(0.5, FLOOR_Y + 10, 0.5);
            f.place(seat, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, seat, 0, 0, 0);
            f.setPassengers(BOAT, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.player.xRot = 0;
            f.player.yRot = 0;
            addRavager(f, new Vec3(-1.5, seat.y, 0.25));

            roar(f);

            // AbstractBoat#floatBoat in the air: 0.9 friction and 0.04 gravity, then no input.
            assertVehicleExact(f.rideTick(seat.add(0, -0.04F, 0), false), 0);
            assertEquals(0, f.player.checkManager.getExplosionHandler().violations);
        }
    }

    private static JavaTickFixture riding(ProtocolVersion protocol, ClientVersion client) throws Exception {
        var f = new JavaTickFixture(protocol, client);
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

    private static JavaTickFixture standing() throws Exception {
        var f = new JavaTickFixture(ProtocolVersion.V26_1, ClientVersion.V_26_1);
        f.place(STANDING, GROUNDED);
        f.input(NONE);
        assertExact(f.tick(STANDING, true));
        return f;
    }

    private static void addRavager(JavaTickFixture f, Vec3 at) {
        f.player.compensatedEntities.addEntity(RAVAGER, EntityTypeIds.RAVAGER, at, 0, 0, 0);
    }

    /** Ravager#roar's broadcast event 69, answered through its trailing ping before the client's next tick. */
    private static void roar(JavaTickFixture f) {
        var packet = new ClientboundEntityEvent(RAVAGER, ROAR);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_EVENT, packet, false);
        f.player.packetEntityReplication.onEntityEvent(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
