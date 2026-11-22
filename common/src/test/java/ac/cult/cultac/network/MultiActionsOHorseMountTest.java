package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.checks.impl.multiactions.MultiActionsO;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRotateHead;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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

    private static JavaTickFixture inventoryOpen() throws Exception {
        return inventoryOpen(SUMMONED, false);
    }

    private static JavaTickFixture inventoryOpen(ClientboundAddEntity spawn, boolean moves) throws Exception {
        var f = new JavaTickFixture(ProtocolVersion.V26_3, ClientVersion.V_26_3);
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
        if (moves) {
            var move = new ClientboundMoveEntity(
                    HORSE, new EntityDelta.Linear((short) 1024, (short) 0, (short) 0), 0, 0, true, true, false);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, move, false);
            f.player.packetEntityReplication.onMoveEntity(event, f.player, move);
            event.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
        }
        f.tickEnd();
        look(f, YAW, PITCH);
        f.tickEnd();
        // PacketPlayerWindow's container_click on window 0: the client shows its inventory.
        f.player.openWindow.onClientOpenOrCloseWindow(true);
        f.tickEnd();
        f.tickEnd();
        return f;
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
