package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsO;
import ac.cult.cultac.checks.impl.sprint.SprintH;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.listeners.PacketPlayerSteer;
import ac.cult.cultac.events.packets.listeners.PacketPlayerWindow;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenScreen;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * LocalPlayer#handlePortalTransitionEffect sets no screen in place of a LecternScreen, which is no
 * AbstractContainerScreen and sends no container_close, so the player turns freely from the next frame. The player
 * stands with the head in a nether portal, as in a 26.3 capture of a lectern opened there.
 */
class MultiActionsOPortalLecternTest {
    private static final int LECTERN = 1;
    private static final Vec3 STANDING = new Vec3(1.1201, JavaTickFixture.FLOOR_Y, 0.1564);
    private static final float YAW = 112.790276f;
    private static final float PITCH = 29.70734f;

    @Test
    void turningAfterAPortalClosedTheLecternScreenDoesNotFlag() throws Exception {
        try (var f = lecternOpened(true)) {
            look(f, 112.588425f, 28.698072f);
            f.tickEnd();
            look(f, 112.31929f, 16.99057f);
            assertEquals(0, violations(f));
        }
    }

    @Test
    void turningWithTheLecternScreenOpenOutsideAPortalFlags() throws Exception {
        try (var f = lecternOpened(false)) {
            look(f, 112.588425f, 28.698072f);
            f.tickEnd();
            look(f, 112.31929f, 16.99057f);
            assertEquals(1, violations(f));
        }
    }

    @Test
    void sprintingInTheTickThePortalClosesTheLecternScreenDoesNotFlag() throws Exception {
        try (var f = lecternOpened(true)) {
            SprintH check = f.player.checkManager.getCheck(SprintH.class);
            check.setEnabled(true);
            check.reload();
            check.violations = 0;
            var dispatcher = new PacketDispatcher(
                    CultAPI.INSTANCE.getNetworkManager().dispatcher().runtime());
            dispatcher.register(registrar -> {
                var listener = new CheckManagerListener();
                listener.registerForwardingEarlyReceivePackets(registrar, PacketListenerPriority.LOW);
                registrar.registerReceiveListener(PacketListenerPriority.LOW, new PacketEntityAction());
                registrar.registerReceiveListener(PacketListenerPriority.LOW, listener);
                registrar.registerReceiveListener(PacketListenerPriority.LOW, new PacketPlayerSteer());
            });
            var input = new ServerboundPlayerInput(true, false, false, false, false, false, true);
            receive(dispatcher, f.event(ServerboundPackets.PLAYER_INPUT, input));
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            receive(dispatcher, f.event(ServerboundPackets.PLAYER_COMMAND, sprint));
            f.tickEnd();
            assertEquals(0, check.getViolations());
        }
    }

    private static JavaTickFixture lecternOpened(boolean portal) throws Exception {
        var f = new JavaTickFixture();
        MultiActionsO check = f.player.checkManager.getCheck(MultiActionsO.class);
        f.player.setExperimentalChecks(true);
        check.setEnabled(true);
        check.reload();
        check.violations = 0;
        if (portal) {
            for (int y = JavaTickFixture.FLOOR_Y + 1; y <= JavaTickFixture.FLOOR_Y + 2; y++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(0, y, 0), BlockIds.NETHER_PORTAL.defaultState());
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        }
        f.player.packetStateData.playerLoadedIntoLevel = true;
        f.place(STANDING, Vec3.ZERO);
        f.player.xRot = YAW;
        f.player.yRot = PITCH;
        for (int tick = 0; tick < 3; tick++) f.tick(STANDING, true);
        var packet = new ClientboundOpenScreen(LECTERN, "minecraft:lectern");
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.OPEN_SCREEN, packet, false);
        new PacketPlayerWindow().onOpenScreen(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        return f;
    }

    private static <T extends ServerboundPacket> void receive(
            PacketDispatcher dispatcher, PacketReceiveEvent<T> event) {
        dispatcher.receive(event, dispatcher.get(event.getPacketType()).receive());
    }

    private static void look(JavaTickFixture f, float yaw, float pitch) {
        var rot = new ServerboundMovePlayer(0, 0, 0, yaw, pitch, true, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
    }

    private static double violations(JavaTickFixture f) {
        return f.player.checkManager.getCheck(MultiActionsO.class).getViolations();
    }
}
