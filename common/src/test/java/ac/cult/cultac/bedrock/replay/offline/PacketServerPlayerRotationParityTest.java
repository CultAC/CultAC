package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.events.packets.PacketServerPlayerRotation;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.PreservedClientboundBundlePacket;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundleDelimiterPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import org.junit.Test;

import java.util.List;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PacketServerPlayerRotationParityTest {
    @Test
    public void standaloneRotationUsesHighLevelBundleWithoutNestedDelimiters() {
        ClientboundPlayerRotationPacket rotation =
                new ClientboundPlayerRotationPacket(30.0F, false, 20.0F, false);
        PacketSendEvent event = new PacketSendEvent(null, rotation, ConnectionProtocol.PLAY);

        new PacketServerPlayerRotation().onPlayerRotation(event, null, rotation);

        assertTrue(event.getNmsPacket() instanceof PreservedClientboundBundlePacket);
        List<Packet<?>> children = children(event.getNmsPacket());
        assertEquals(List.of(rotation), children);
        assertFalse(children.stream().anyMatch(ClientboundBundleDelimiterPacket.class::isInstance));
    }

    @Test
    public void rotationAlreadyInsideBundleIsNotNested() {
        ClientboundPlayerRotationPacket rotation =
                new ClientboundPlayerRotationPacket(30.0F, false, 20.0F, false);
        PacketSendEvent event = new PacketSendEvent(null, rotation, ConnectionProtocol.PLAY, true);

        new PacketServerPlayerRotation().onPlayerRotation(event, null, rotation);

        assertTrue(event.getNmsPacket() instanceof ClientboundPlayerRotationPacket);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Packet<?>> children(Packet<?> packet) {
        List<Packet<?>> children = new ArrayList<>();
        for (Object child : ((PreservedClientboundBundlePacket) packet).subPackets()) {
            children.add((Packet<?>) child);
        }
        return children;
    }
}
