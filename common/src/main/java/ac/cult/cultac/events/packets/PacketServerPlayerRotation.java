package ac.cult.cultac.events.packets;

import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.PreservedClientboundBundlePacket;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;

import java.util.List;

/** Sanitizes server-forced rotations and preserves their atomic bundle boundary. */
public class PacketServerPlayerRotation {

    @CultPacketHandler
    public void onPlayerRotation(PacketSendEvent event, CultPlayer player, ClientboundPlayerRotationPacket packet) {
        float yaw = packet.yRot();
        float pitch = packet.xRot();

        Packet<?> output = packet;
        if (!Float.isFinite(pitch) || !Float.isFinite(yaw)) {
            if (!Float.isFinite(pitch)) pitch = 0;
            if (!Float.isFinite(yaw)) yaw = 0;
            output = NmsPacketUtil.withPlayerRotation(packet, yaw, pitch);
            event.markForReEncode(true);
        }

        if (event.isInsideBundle()) {
            event.setNmsPacket(output);
            return;
        }

        // BundlerInfo adds the wire delimiters around this high-level bundle.
        // Never place delimiter packets inside ClientboundBundlePacket itself.
        @SuppressWarnings("unchecked")
        Packet<? super ClientGamePacketListener> rotation =
                (Packet<? super ClientGamePacketListener>) output;
        event.setNmsPacket(new PreservedClientboundBundlePacket(List.of(rotation)));
    }
}
