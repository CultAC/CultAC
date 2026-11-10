package ac.cult.cultac.bedrock.bridge;

import java.util.List;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;

/**
 * GFP Build 5 moves an entity's spawn, position sync and vehicle move onto the session origin but hands an entity
 * teleport on unchanged, and Geyser places the entity at the teleport's absolute coordinates as they are. The
 * Bedrock client then sees the entity a whole origin away from where its other packets put it.
 */
final class GfpEntityTeleportRebase {
    private GfpEntityTeleportRebase() {}

    /**
     * @param received the packet the server sent
     * @param rewritten what GFP hands on for it, null when GFP cancelled it
     * @return the packet Geyser must translate
     */
    static Packet apply(Packet received, Packet rewritten, Vector3i origin) {
        // A GFP that rebases the teleport hands on a packet of its own. Its coordinates are already local.
        if (rewritten != received || !(received instanceof ClientboundTeleportEntityPacket teleport)) {
            return rewritten;
        }
        // A relative element is added to the entity's position, which Geyser already holds in the session's frame.
        List<PositionElement> relatives = teleport.getRelatives();
        int x = relatives.contains(PositionElement.X) ? 0 : origin.getX();
        int z = relatives.contains(PositionElement.Z) ? 0 : origin.getZ();
        return x == 0 && z == 0 ? teleport : teleport.withPosition(teleport.getPosition().sub(x, 0, z));
    }
}
