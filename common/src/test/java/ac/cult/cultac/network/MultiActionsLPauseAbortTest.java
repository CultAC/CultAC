package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.checks.impl.multiactions.MultiActionsL;
import ac.cult.cultac.events.packets.listeners.PacketPlayerDigging;
import ac.cult.cultac.events.packets.listeners.PacketPlayerWindow;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenScreen;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A container screen stops Minecraft#handleKeybinds, so Minecraft#continueAttack neither continues nor stops a dig the
 * player started before the screen opened. From 26.3, Minecraft#pauseIfInactive calls Minecraft#pauseGame when the
 * window loses focus, and Minecraft#pauseGame calls MultiPlayerGameMode#stopDestroyBlock, which sends
 * ABORT_DESTROY_BLOCK for the block with DOWN while the screen stays open.
 */
class MultiActionsLPauseAbortTest {
    private static final Vec3 STAND = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final BlockPos DUG = new BlockPos(1, FLOOR_Y - 1, 1);
    private static final int CHEST = 1;

    private final PacketPlayerWindow window = new PacketPlayerWindow();

    @Test
    void theAbortOfTheDigInProgressOverAnOpenContainerDoesNotFlag() throws Exception {
        try (var f = new JavaTickFixture()) {
            MultiActionsL check = enable(f);
            action(f, PlayerAction.START_DESTROY_BLOCK, DUG, Direction.UP);
            f.tick(STAND, true);
            openChest(f);
            action(f, PlayerAction.ABORT_DESTROY_BLOCK, DUG, Direction.DOWN);
            assertEquals(0, check.getViolations());
        }
    }

    @Test
    void anAbortWithNoDigInProgressOverAnOpenContainerFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            MultiActionsL check = enable(f);
            action(f, PlayerAction.START_DESTROY_BLOCK, DUG, Direction.UP);
            f.tick(STAND, true);
            action(f, PlayerAction.ABORT_DESTROY_BLOCK, DUG, Direction.DOWN);
            f.tick(STAND, true);
            openChest(f);
            action(f, PlayerAction.ABORT_DESTROY_BLOCK, DUG, Direction.DOWN);
            assertEquals(1, check.getViolations());
        }
    }

    @Test
    void theAbortOfTheDigInProgressOverAnOpenContainerFlagsBefore26_3() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, ClientVersion.V_26_2)) {
            MultiActionsL check = enable(f);
            action(f, PlayerAction.START_DESTROY_BLOCK, DUG, Direction.UP);
            f.tick(STAND, true);
            openChest(f);
            action(f, PlayerAction.ABORT_DESTROY_BLOCK, DUG, Direction.DOWN);
            assertEquals(1, check.getViolations());
        }
    }

    private static MultiActionsL enable(JavaTickFixture f) {
        f.place(STAND, Vec3.ZERO);
        MultiActionsL check = f.player.checkManager.getCheck(MultiActionsL.class);
        check.setEnabled(true);
        check.reload();
        check.violations = 0;
        return check;
    }

    private void openChest(JavaTickFixture f) {
        var packet = new ClientboundOpenScreen(CHEST, "minecraft:generic_9x3");
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.OPEN_SCREEN, packet, false);
        window.onOpenScreen(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        for (int tick = 0; tick < 3; tick++) {
            f.tick(STAND, true);
            f.tickEnd();
        }
    }

    private static void action(JavaTickFixture f, PlayerAction action, BlockPos position, Direction face) {
        var packet = new ServerboundPlayerAction(action, position, face, 0);
        var event = f.event(ServerboundPackets.PLAYER_ACTION, packet);
        new PacketPlayerDigging().onPlayerAction(event, f.player, packet);
        if (!event.isCancelled()) f.listener.onPlayerAction(event, f.player, packet);
    }
}
