package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.checks.impl.packetorder.PacketOrderI;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * From 26.3 ClientPacketListener#handleMovePlayer answers a teleport with ServerboundAcceptTeleportationPacket and then
 * calls MultiPlayerGameMode#stopDestroyBlock, which sends ABORT_DESTROY_BLOCK while a dig is in progress. That runs
 * while packets are handled, before the next tick's Minecraft#startUseItem, so the abort does not share a tick with the
 * use that follows it.
 */
class PacketOrderITeleportAbortTest {
    private static final Vec3 STAND = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final BlockPos DUG = new BlockPos(1, FLOOR_Y - 1, 1);
    private static final BlockPos CLICKED = new BlockPos(2, FLOOR_Y - 1, 1);
    private static final int BOAT = 9039;
    private static final int TELEPORT_ID = 77;

    @Test
    void aUseAfterTheAbortOfATeleportAcceptanceDoesNotFlag() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketOrderI check = digThenTeleport(f);
            action(f, PlayerAction.ABORT_DESTROY_BLOCK, DUG);
            use(f, Hand.MAIN_HAND);
            use(f, Hand.OFF_HAND);
            assertEquals(0, check.getViolations());
        }
    }

    @Test
    void anInteractAfterTheAbortOfATeleportAcceptanceDoesNotFlag() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketOrderI check = digThenTeleport(f);
            action(f, PlayerAction.ABORT_DESTROY_BLOCK, DUG);
            var interact =
                    new ServerboundInteract(BOAT, InteractAction.INTERACT, Hand.MAIN_HAND, Optional.empty(), false);
            f.listener.onInteract(f.event(ServerboundPackets.INTERACT, interact), f.player, interact);
            assertEquals(0, check.getViolations());
        }
    }

    @Test
    void aUseAfterAnAbortInTheSameTickFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(STAND, Vec3.ZERO);
            PacketOrderI check = f.player.checkManager.getListener(PacketOrderI.class);
            action(f, PlayerAction.START_DESTROY_BLOCK, DUG);
            f.tick(STAND, true);
            action(f, PlayerAction.ABORT_DESTROY_BLOCK, DUG);
            use(f, Hand.MAIN_HAND);
            assertEquals(1, check.getViolations());
        }
    }

    @Test
    void aUseAfterAnAbortThatFollowsTheTeleportAcceptanceAfterAPongFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketOrderI check = digThenTeleport(f);
            var pong = new ServerboundPong(f.player.lastTransactionReceived.get());
            f.listener.onPong(f.event(ServerboundPackets.PONG, pong), f.player, pong);
            action(f, PlayerAction.ABORT_DESTROY_BLOCK, DUG);
            use(f, Hand.MAIN_HAND);
            assertEquals(1, check.getViolations());
        }
    }

    @Test
    void aUseAfterTheAbortOfAnAcceptanceOfAnUnknownTeleportFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketOrderI check = digThenTeleport(f, 4242, STAND);
            action(f, PlayerAction.ABORT_DESTROY_BLOCK, DUG);
            use(f, Hand.MAIN_HAND);
            assertEquals(1, check.getViolations());
        }
    }

    @Test
    void aUseAfterTheAbortOfAnAcceptanceAtTheWrongPositionFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketOrderI check = digThenTeleport(f, TELEPORT_ID, new Vec3(99, 99, 99));
            action(f, PlayerAction.ABORT_DESTROY_BLOCK, DUG);
            use(f, Hand.MAIN_HAND);
            assertEquals(1, check.getViolations());
        }
    }

    @Test
    void aUseAfterTheAbortOfAnAcceptanceThatFollowsAnInTickUseFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(STAND, Vec3.ZERO);
            PacketOrderI check = f.player.checkManager.getListener(PacketOrderI.class);
            action(f, PlayerAction.START_DESTROY_BLOCK, DUG);
            f.tick(STAND, true);
            int transaction = f.player.sendTrailingTransaction();
            f.player
                    .getSetbackTeleportUtil()
                    .addSentTeleport(STAND, transaction, new RelativeFlag(0), false, TELEPORT_ID);
            f.pongThrough(transaction);
            use(f, Hand.MAIN_HAND);
            accept(f, TELEPORT_ID, STAND);
            action(f, PlayerAction.ABORT_DESTROY_BLOCK, DUG);
            use(f, Hand.MAIN_HAND);
            assertEquals(1, check.getViolations());
        }
    }

    /** Before 26.3 handleMovePlayer answers with a PosRot and leaves the dig to Minecraft#continueAttack. */
    @Test
    void aUseAfterAnAbortFollowingTheAcceptanceOfAnOlderClientFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_26_2);
            PacketOrderI check = digThenTeleport(f);
            action(f, PlayerAction.ABORT_DESTROY_BLOCK, DUG);
            use(f, Hand.MAIN_HAND);
            assertEquals(1, check.getViolations());
        }
    }

    /** A dig started, a tick sent, and a teleport the client accepted; the PacketOrderI listener. */
    private static PacketOrderI digThenTeleport(JavaTickFixture f) {
        return digThenTeleport(f, TELEPORT_ID, STAND);
    }

    /** A dig started, a tick sent, a teleport to STAND, and an acceptance of {@code id} at {@code position}. */
    private static PacketOrderI digThenTeleport(JavaTickFixture f, int id, Vec3 position) {
        f.place(STAND, Vec3.ZERO);
        PacketOrderI check = f.player.checkManager.getListener(PacketOrderI.class);
        action(f, PlayerAction.START_DESTROY_BLOCK, DUG);
        f.tick(STAND, true);
        int transaction = f.player.sendTrailingTransaction();
        f.player.getSetbackTeleportUtil().addSentTeleport(STAND, transaction, new RelativeFlag(0), false, TELEPORT_ID);
        f.pongThrough(transaction);
        accept(f, id, position);
        assertEquals(0, check.getViolations());
        return check;
    }

    /** The acceptance through PacketServerTeleport and then CheckManagerListener on one event. */
    private static void accept(JavaTickFixture f, int id, Vec3 position) {
        var packet = new ServerboundAcceptTeleportation(id, new Vec3d(position.x, position.y, position.z), 0, 0);
        var event = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet);
        new PacketServerTeleport().onAcceptTeleportation(event, f.player, packet);
        f.listener.onAcceptTeleportation(event, f.player, packet);
    }

    private static void action(JavaTickFixture f, PlayerAction action, BlockPos position) {
        var packet = new ServerboundPlayerAction(action, position, Direction.UP, 0);
        f.listener.onPlayerAction(f.event(ServerboundPackets.PLAYER_ACTION, packet), f.player, packet);
    }

    private static void use(JavaTickFixture f, Hand hand) {
        var packet = new ServerboundUseItemOn(hand, CLICKED, Direction.UP, new Vec3d(0.229, 1.0, 0.8), false, false, 0);
        f.listener.onUseItemOn(f.event(ServerboundPackets.USE_ITEM_ON, packet), f.player, packet);
    }
}
