package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.checks.impl.multiactions.MultiActionsO;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerLookAt;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRotateHead;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ClientPacketListener#handleSetEntityPassengersPacket calls Entity#startRiding for every passenger it lists, and
 * AbstractHorse#addPassenger turns the rider with Entity#absSnapRotationTo to the horse's getViewYRot(0) and
 * getViewXRot(0): its yHeadRotO and xRotO. LocalPlayer#tick reports that look in the passenger Rot of the next tick,
 * with the inventory screen open or not. The looks and the two set_passengers come from a 26.3 capture of
 * {@code ride @s mount} run on a player with the inventory open, on a horse summoned with Rotation [90, 0], whose
 * head yaw the spawn sets to 90 as well.
 */
class MultiActionsOHorseMountTest {
    private static final int HORSE = 4230;
    private static final int RIDER = 4231;
    private static final int BOAT = 4232;
    private static final Vec3 PLAYER_AT = new Vec3(0.5, FLOOR_Y, 4.5);
    private static final ClientboundAddEntity SUMMONED =
            new ClientboundAddEntity(HORSE, "minecraft:horse", new Vec3d(0.5, FLOOR_Y, 0.5), 90f, 0f, 0);
    private static final float YAW = -0.46694946f;
    private static final float PITCH = 27.451254f;

    @Test
    void theLookAServerMountOnAHorseGivesOverTheInventoryDoesNotFlag() throws Exception {
        try (var f = inventoryOpen()) {
            mount(f);
            mount(f);
            look(f, 90.0f, 0.0f);
            f.tickEnd();
            assertEquals(0, violations(f));
        }
    }

    @Test
    void aTurnPastTheLookAHorseMountGivesFlags() throws Exception {
        try (var f = inventoryOpen()) {
            mount(f);
            mount(f);
            look(f, 90.5f, 0.0f);
            f.tickEnd();
            assertEquals(1, violations(f));
        }
    }

    /** Before 1.21.9 AbstractHorse has no addPassenger turn: the rider's next Rot keeps its own look. */
    @ParameterizedTest(name = "{1}")
    @MethodSource("beforeTheMountTurn")
    void aRiderBeforeTheMountTurnKeepsItsLookOnAServerMount(ProtocolVersion host, ClientVersion client)
            throws Exception {
        try (var f = inventoryOpen(SUMMONED, false, host, client)) {
            mount(f);
            mount(f);
            look(f, YAW, PITCH);
            f.tickEnd();
            assertEquals(0, violations(f));
        }
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("beforeTheMountTurn")
    void aRiderBeforeTheMountTurnReportingTheHorsesLookFlags(ProtocolVersion host, ClientVersion client)
            throws Exception {
        try (var f = inventoryOpen(SUMMONED, false, host, client)) {
            mount(f);
            mount(f);
            look(f, 90.0f, 0.0f);
            f.tickEnd();
            assertEquals(1, violations(f));
        }
    }

    static Stream<Arguments> beforeTheMountTurn() {
        return Stream.of(
                Arguments.of(ProtocolVersion.V1_21_3, ClientVersion.V_1_21_2),
                Arguments.of(ProtocolVersion.V1_21_4, ClientVersion.V_1_21_4),
                Arguments.of(ProtocolVersion.V1_21_5, ClientVersion.V_1_21_5));
    }

    /**
     * LivingEntity#lerpHeadTo moves the head a third of the way to 45 in the horse's first tick, to 75, and
     * LivingEntity#baseTick of its second tick makes that yHeadRotO.
     */
    @Test
    void theLookAMountGivesFromAHeadMidwayThroughItsLerpDoesNotFlag() throws Exception {
        try (var f = inventoryOpen()) {
            var turn = new ClientboundRotateHead(HORSE, 45.0f);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ROTATE_HEAD, turn, false);
            f.player.packetEntityReplication.onRotateHead(event, f.player, turn);
            event.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.tickEnd();
            f.tickEnd();
            mount(f);
            look(f, 75.0f, 0.0f);
            f.tickEnd();
            assertEquals(0, violations(f));
        }
    }

    /**
     * A horse spawned facing -170.15625 with its head at 170.15625 moves a quarter block. On its first moving tick
     * BodyRotationControl#clientTick sets yBodyRot to the yaw and Mth#rotateIfNecessary gives the head
     * -170.15625 - wrapDegrees(-340.3125) = -189.84375, which LivingEntity#tick's range check gives yHeadRotO too.
     */
    @Test
    void theLookAMountGivesFromAHeadTheHorsesMoveTurnedDoesNotFlag() throws Exception {
        var spawn = new ClientboundAddEntity(
                HORSE,
                "minecraft:horse",
                new Vec3d(0.5, FLOOR_Y, 0.5),
                -170.15625f,
                0f,
                0,
                new UUID(0, 0),
                Vec3d.ZERO,
                170.15625f);
        try (var f = inventoryOpen(spawn, true)) {
            mount(f);
            look(f, -189.84375f, 0.0f);
            f.tickEnd();
            assertEquals(0, violations(f));
        }
    }

    /**
     * Another player rides the saddled horse. AbstractHorse#tickRidden turns the horse's head to that player's yaw and
     * the horse to half its pitch, where they stay once it gets off. AbstractHorse#addPassenger then turns the player
     * the server mounts to that look, here 135 and 10.
     */
    @Test
    void theLookAMountGivesFromAHeadAnotherRiderTurnedDoesNotFlag() throws Exception {
        try (var f = inventoryOpen()) {
            riddenByAnotherPlayer(f);
            mount(f);
            look(f, 135.0f, 10.0f);
            f.tickEnd();
            assertEquals(0, violations(f));
        }
    }

    @Test
    void aTurnAfterTheLookAMountGivesFromAHeadAnotherRiderTurnedFlags() throws Exception {
        try (var f = inventoryOpen()) {
            riddenByAnotherPlayer(f);
            mount(f);
            look(f, 135.0f, 10.0f);
            f.tickEnd();
            look(f, 136.0f, 10.0f);
            f.tickEnd();
            assertEquals(1, violations(f));
        }
    }

    /**
     * After another player has ridden the horse, a rotate_head sends the head's -90. The last step of
     * LivingEntity#lerpHeadTo gives the head that player left plus wrapDegrees(-90 less it), the sent angle plus whole
     * turns, 270 from a head at 270, and AbstractHorse#addPassenger turns the player to it and to the pitch the ride
     * left, here 10.
     */
    @Test
    void theLookAMountGivesFromAHeadARotateHeadSetAfterARideDoesNotFlag() throws Exception {
        try (var f = inventoryOpen()) {
            riddenByAnotherPlayer(f);
            rotateHead(f, -90.0f);
            for (int tick = 0; tick < 4; tick++) f.tickEnd();
            mount(f);
            look(f, 270.0f, 10.0f);
            f.tickEnd();
            assertEquals(0, violations(f));
        }
    }

    @Test
    void aLookOffTheHeadARotateHeadSetAfterARideFlags() throws Exception {
        try (var f = inventoryOpen()) {
            riddenByAnotherPlayer(f);
            rotateHead(f, -90.0f);
            for (int tick = 0; tick < 4; tick++) f.tickEnd();
            mount(f);
            look(f, 90.0f, 10.0f);
            f.tickEnd();
            assertEquals(1, violations(f));
        }
    }

    /**
     * The horse moves the tick after the rotate_head lerp ends, and BodyRotationControl#clientTick turns its head
     * toward its yaw. LivingEntity#tick's range check adds whole turns to yHeadRotO, which stays the sent -90 plus
     * whole turns, so AbstractHorse#addPassenger turns the player to -90 or 270, never 90.
     */
    @Test
    void aLookOffTheHeadAMoveAfterARotateHeadLeavesFlags() throws Exception {
        try (var f = inventoryOpen()) {
            riddenByAnotherPlayer(f);
            rotateHead(f, -90.0f);
            for (int tick = 0; tick < 3; tick++) f.tickEnd();
            moveHorse(f);
            f.tickEnd();
            mount(f);
            look(f, 90.0f, 10.0f);
            f.tickEnd();
            assertEquals(1, violations(f));
        }
    }

    /**
     * ClientPacketListener#handleLookAt turns the player after the mount with Entity#lookAt, here to -90 and 0, and the
     * next Rot reports that look.
     */
    @Test
    void aLookOffALookAtAfterTheMountFlags() throws Exception {
        try (var f = inventoryOpen()) {
            riddenByAnotherPlayer(f);
            mount(f);
            var packet = new ClientboundPlayerLookAt(false, 5.5, FLOOR_Y, 4.5, false, 0, false);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_LOOK_AT, packet, false);
            new PacketServerTeleport().onPlayerLookAt(event, f.player, packet);
            event.getTasksAfterSend().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            look(f, 123.0f, 7.0f);
            f.tickEnd();
            assertEquals(1, violations(f));
        }
    }

    /**
     * ClientPacketListener#handleSetEntityPassengersPacket moves the player from the horse to a boat listed after it,
     * and turns a player newly aboard a boat to the boat's yaw, here 0.
     */
    @Test
    void aLookOffABoatMountAfterAHorseMountFlags() throws Exception {
        try (var f = inventoryOpen()) {
            riddenByAnotherPlayer(f);
            f.addEntity(BOAT, "minecraft:oak_boat", new Vec3(2.5, FLOOR_Y, 4.5));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.tickEnd();
            mount(f);
            f.setPassengers(BOAT, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            look(f, 77.0f, 10.0f);
            f.tickEnd();
            assertEquals(1, violations(f));
        }
    }

    private static void riddenByAnotherPlayer(JavaTickFixture f) {
        f.addRemotePlayer(RIDER, new Vec3(0.5, FLOOR_Y, 0.5), GameMode.SURVIVAL);
        ((PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE)).hasSaddle = true;
        f.setPassengers(HORSE, List.of(RIDER));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.tickEnd();
        f.tickEnd();
        f.setPassengers(HORSE, List.of());
        f.pongThrough(f.player.lastTransactionSent.get());
        f.tickEnd();
        f.tickEnd();
    }

    private static void rotateHead(JavaTickFixture f, float headYaw) {
        var turn = new ClientboundRotateHead(HORSE, headYaw);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ROTATE_HEAD, turn, false);
        f.player.packetEntityReplication.onRotateHead(event, f.player, turn);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static JavaTickFixture inventoryOpen() throws Exception {
        return inventoryOpen(SUMMONED, false);
    }

    private static JavaTickFixture inventoryOpen(ClientboundAddEntity spawn, boolean moves) throws Exception {
        return inventoryOpen(spawn, moves, ProtocolVersion.V26_3, ClientVersion.V_26_3);
    }

    private static JavaTickFixture inventoryOpen(
            ClientboundAddEntity spawn, boolean moves, ProtocolVersion host, ClientVersion client) throws Exception {
        var f = new JavaTickFixture(host, client);
        MultiActionsO check = f.player.checkManager.getCheck(MultiActionsO.class);
        f.player.setExperimentalChecks(true);
        check.setEnabled(true);
        check.reload();
        check.violations = 0;
        f.place(PLAYER_AT, Vec3.ZERO);
        var add = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, spawn, false);
        f.player.packetEntityReplication.onAddEntity(add, f.player, spawn);
        add.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        if (moves) moveHorse(f);
        f.tickEnd();
        look(f, YAW, PITCH);
        f.tickEnd();
        // PacketPlayerWindow's container_click on window 0: the client shows its inventory.
        f.player.openWindow.onClientOpenOrCloseWindow(true);
        f.tickEnd();
        f.tickEnd();
        return f;
    }

    private static void moveHorse(JavaTickFixture f) {
        var move = new ClientboundMoveEntity(
                HORSE, new EntityDelta.Linear((short) 1024, (short) 0, (short) 0), 0, 0, true, true, false);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, move, false);
        f.player.packetEntityReplication.onMoveEntity(event, f.player, move);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** A set_passengers that lists the player, handled in the bundle task of its proof. */
    private static void mount(JavaTickFixture f) {
        f.setPassengers(HORSE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void look(JavaTickFixture f, float yaw, float pitch) {
        var rot = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
    }

    private static double violations(JavaTickFixture f) {
        return f.player.checkManager.getCheck(MultiActionsO.class).getViolations();
    }
}
