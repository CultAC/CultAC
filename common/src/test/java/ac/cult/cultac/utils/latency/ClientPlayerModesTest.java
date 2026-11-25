package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ac.cult.blocksim.data.nbt.BinaryNbt;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoUpdate.Action;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoUpdate.Entry;
import ac.cult.cultac.protocol.value.ByteArray;
import ac.cult.cultac.protocol.value.GameMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientPlayerModesTest {

    private static Entry displayed(NbtValue text) {
        byte[] nbt = BinaryNbt.write(text);
        byte[] encoded = new byte[nbt.length + 1];
        encoded[0] = 1;
        System.arraycopy(nbt, 0, encoded, 1, nbt.length);
        return display(encoded);
    }

    private static Entry display(byte[] encoded) {
        return new Entry(
                UUID.randomUUID(), GameMode.SURVIVAL, Map.of(Action.UPDATE_DISPLAY_NAME, new ByteArray(encoded)));
    }

    @Test
    void aDisplayNameReadsAsPlainTextWithoutLegacyCodes() {
        NbtValue component = new NbtValue.Compound(Map.of(
                "text", new NbtValue.Text("§6[VIP] "),
                "color", new NbtValue.Text("gold"),
                "extra", new NbtValue.Sequence(List.of(
                        new NbtValue.Text("§fVk"),
                        new NbtValue.Compound(Map.of("text", new NbtValue.Text("4817"), "bold", new NbtValue.Numeric(
                                NbtValue.Kind.BYTE, (byte) 1)))))));

        assertEquals("[VIP] Vk4817", ClientPlayerModes.displayName(displayed(component)));
        assertEquals("Vk4817", ClientPlayerModes.displayName(displayed(new NbtValue.Text("Vk4817"))));
        assertNull(ClientPlayerModes.displayName(display(new byte[] {0})));
        assertNull(ClientPlayerModes.displayName(new Entry(UUID.randomUUID(), GameMode.SURVIVAL, Map.of())));
    }

    @Test
    void aDisplayNameListsItsLastPlayerNameWhenItHoldsNotTheAccount() {
        assertEquals("Vk4817", ClientPlayerModes.listedName("Vk4817", "HB_Evan_x"));
        assertEquals("Vk4817", ClientPlayerModes.listedName("[VIP] Vk4817", "HB_Evan_x"));
        assertEquals(".Vk4817", ClientPlayerModes.listedName("✦ .Vk4817", "HB_Evan_x"));
        assertNull(ClientPlayerModes.listedName("✦ HB_Evan_x", "HB_Evan_x"));
        assertNull(ClientPlayerModes.listedName("[VIP] hb_evan_x Guild", "HB_Evan_x"));
        assertNull(ClientPlayerModes.listedName("[✦]", "HB_Evan_x"));
        assertNull(ClientPlayerModes.listedName(null, "HB_Evan_x"));
    }

    @Test
    void theOwnEntrysProfileNameComesBeforeItsDisplayName() {
        OfflinePlayers.installConfig();
        CultPlayer player = OfflinePlayers.player();
        try {
            String account = player.getName();
            player.listed(account, ClientPlayerModes.listedName("Vk4817", account));
            assertEquals("Vk4817", player.shownName);

            player.listed(account, ClientPlayerModes.listedName(" " + account, account));
            assertNull(player.shownName);

            player.listed("Profile", "Vk4817");
            assertEquals("Profile", player.shownName);

            player.listed(null, null);
            assertNull(player.shownName);
        } finally {
            OfflinePlayers.close(player);
        }
    }
}
