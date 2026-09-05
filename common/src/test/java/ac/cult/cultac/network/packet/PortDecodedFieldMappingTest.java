package ac.cult.cultac.network.packet;

import ac.cult.cultac.checks.impl.chat.ChatB;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import java.util.OptionalInt;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public final class PortDecodedFieldMappingTest {
    @Test
    public void chatCommandHandlersBindSignedAndUnsignedNmsFamiliesCorrectly() throws Exception {
        assertNotNull(ChatB.class.getDeclaredMethod(
                "onChatCommandUnsigned",
                PacketReceiveEvent.class,
                CultPlayer.class,
                ServerboundChatCommandPacket.class));
        assertNotNull(ChatB.class.getDeclaredMethod(
                "onChatCommand",
                PacketReceiveEvent.class,
                CultPlayer.class,
                ServerboundChatCommandSignedPacket.class));
    }

    @Test
    public void spectatorSentinelConversionPreservesPacketEventsIntegerContract() {
        assertEquals(42, NmsPacketUtil.readSpectatorEntityId(
                new ServerboundSpectatorActionPacket(OptionalInt.of(42))));
        assertEquals(0, NmsPacketUtil.readSpectatorEntityId(
                new ServerboundSpectatorActionPacket(OptionalInt.empty())));
    }

    @Test
    public void transactionAcceptanceDefaultsToFalseOnEachReceiveEvent() {
        PacketReceiveEvent event = new PacketReceiveEvent(
                null,
                new ServerboundSpectatorActionPacket(OptionalInt.empty()),
                net.minecraft.network.ConnectionProtocol.PLAY);
        assertFalse(event.isAcceptedTransactionResponse());
    }
}
