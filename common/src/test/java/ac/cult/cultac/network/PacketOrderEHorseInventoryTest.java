package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.checks.impl.packetorder.PacketOrderE;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.SwingKind;
import org.junit.jupiter.api.Test;

/**
 * 1.21.4 Minecraft#handleKeybinds picks the hotbar slot, sends OPEN_INVENTORY for a horse through
 * LocalPlayer#sendOpenInventory and then runs Minecraft#continueAttack, whose MultiPlayerGameMode#continueDestroyBlock
 * sends the slot through ensureHasSentCarriedItem before the swing. LocalPlayer#aiStep sends the horse jump after
 * handleKeybinds, so no slot follows it in the same tick.
 */
class PacketOrderEHorseInventoryTest {
    @Test
    void aSlotSentWhileDiggingAfterOpeningTheHorseInventoryDoesNotFlag() throws Exception {
        assertEquals(0, violations(PlayerCommandAction.OPEN_INVENTORY));
    }

    @Test
    void aSlotAfterAHorseJumpInTheSameTickFlags() throws Exception {
        assertEquals(1, violations(PlayerCommandAction.START_JUMPING_WITH_HORSE));
    }

    /** One tick of the capture: the {@code command}, set_carried_item, the swing and the tick end. */
    private static double violations(PlayerCommandAction command) throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_4, ClientVersion.V_1_21_4)) {
            f.player.setExperimentalChecks(true);
            f.tickEnd();
            var playerCommand = new ServerboundPlayerCommand(f.player.entityID, command, 0);
            f.listener.onPlayerCommand(
                    f.event(ServerboundPackets.PLAYER_COMMAND, playerCommand), f.player, playerCommand);
            var slot = new ServerboundSetCarriedItem(1);
            f.listener.onSetCarriedItem(f.event(ServerboundPackets.SET_CARRIED_ITEM, slot), f.player, slot);
            var swing = new ServerboundSwing(Hand.MAIN_HAND, SwingKind.SWING);
            f.listener.onSwing(f.event(ServerboundPackets.SWING, swing), f.player, swing);
            f.tickEnd();
            return f.player.checkManager.getListener(PacketOrderE.class).getViolations();
        }
    }
}
