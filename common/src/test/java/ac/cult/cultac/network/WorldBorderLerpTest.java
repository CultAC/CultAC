package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.events.packets.PacketWorldBorder;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorldBorderLerpTest {
    /**
     * MovingBorderExtent#getCollisionShape reads getMin/Max(0.0F), the size before the last WorldBorder#tick, and
     * Minecraft#tick moves entities before that tick: a move collides with the size two border ticks behind it.
     */
    @Test
    void aLerpingBorderCollidesAtItsPreviousTickSize() throws Exception {
        for (ClientVersion client : List.of(ClientVersion.V_1_21_11, ClientVersion.V_26_2, ClientVersion.V_26_3)) {
            try (var f = new JavaTickFixture(ProtocolVersion.V26_3, client)) {
                PacketWorldBorder border = f.player.checkManager.getWorldBorder();
                set(border, "oldDiameter", 16.0D);
                set(border, "newDiameter", 3.0D);
                set(border, "tickBasedLerp", true);
                set(border, "lerpDurationTicks", 300L);
                set(border, "lerpElapsedTicks", 0L);

                assertEquals(16.0D, border.getCurrentDiameter(), client.toString());
                border.onPlayerTickEnd(null);
                assertEquals(16.0D, border.getCurrentDiameter(), client.toString());
                border.onPlayerTickEnd(null);
                assertEquals(16.0D - 13.0D / 300.0D, border.getCurrentDiameter(), 1.0E-12, client.toString());
            }
        }
    }

    private static void set(PacketWorldBorder border, String name, Object value) throws Exception {
        JavaTickFixture.setField(PacketWorldBorder.class, border, name, value);
    }
}
