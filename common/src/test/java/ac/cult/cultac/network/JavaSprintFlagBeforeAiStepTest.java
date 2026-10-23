package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/** Entity#isSprinting as Entity#baseTick reads it, before LocalPlayer#aiStep, for each move a 26.3 client sends. */
class JavaSprintFlagBeforeAiStepTest {
    @Test
    void eachMoveCapturesTheFlagBeforeItsOwnAiStep() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, Vec3.ZERO);
            command(f, PlayerCommandAction.START_SPRINTING);
            f.move(position, true);
            f.tickEnd();

            // This tick's aiStep stopped sprinting; baseTick ran with the previous tick's flag.
            command(f, PlayerCommandAction.STOP_SPRINTING);
            f.move(position, true);
            assertTrue(f.player.sprintState.flagBeforeAiStep());

            // The next move arrives without a tick end; its baseTick ran with the stopped flag.
            f.move(position, true);
            assertFalse(f.player.sprintState.flagBeforeAiStep());
        }
    }

    private static void command(JavaTickFixture f, PlayerCommandAction action) {
        var packet = new ServerboundPlayerCommand(f.player.entityID, action, 0);
        new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, packet), f.player, packet);
    }
}
