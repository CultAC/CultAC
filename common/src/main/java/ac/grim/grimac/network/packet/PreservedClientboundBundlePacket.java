package ac.grim.grimac.network.packet;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;

/** A generated bundle that must remain grouped through PacketApi replacement handling. */
public final class PreservedClientboundBundlePacket extends ClientboundBundlePacket {
    public PreservedClientboundBundlePacket(Iterable<Packet<? super ClientGamePacketListener>> packets) {
        super(packets);
    }
}
