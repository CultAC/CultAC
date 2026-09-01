package ac.grim.grimac.events.packets;

import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketSendEvent;
import ac.grim.grimac.network.packet.PreservedClientboundBundlePacket;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;

import java.util.List;

/** Sanitizes server-forced rotations and preserves their atomic bundle boundary. */
public class PacketServerPlayerRotation {

    @GrimPacketHandler
    public void onPlayerRotation(PacketSendEvent event, GrimPlayer player, ClientboundPlayerRotationPacket packet) {
        float yaw = packet.yRot();
        float pitch = packet.xRot();

        Packet<?> output = packet;
        if (!Float.isFinite(pitch) || !Float.isFinite(yaw)) {
            if (!Float.isFinite(pitch)) pitch = 0;
            if (!Float.isFinite(yaw)) yaw = 0;
            output = new ClientboundPlayerRotationPacket(
                    yaw, packet.relativeY(), pitch, packet.relativeX());
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
