package ac.cult.cultac.bedrock.bridge;

import org.cloudburstmc.protocol.bedrock.data.entity.EntityLinkData;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;

/** Whether the client rides, from the links and teleports of the player written to it, in the order it gets them. */
final class GeyserClientRiding {
    private boolean riding;

    /** A link of the player written to the client; whether it seats a client that was not riding. */
    boolean link(EntityLinkData link) {
        boolean seats = !riding && link.getType() != EntityLinkData.Type.REMOVE;
        riding = link.getType() != EntityLinkData.Type.REMOVE;
        return seats;
    }

    /**
     * A teleport of the player written to the client. It stops riding to handle one that names no vehicle, and only
     * if it rides when it gets it: a teleport written before the link that seats it is handled on foot.
     */
    boolean stopsRiding(MovePlayerPacket packet) {
        boolean stops = riding
                && packet.getMode() == MovePlayerPacket.Mode.TELEPORT
                && packet.getRidingRuntimeEntityId() == 0;
        if (stops) riding = false;
        return stops;
    }

    void clear() {
        riding = false;
    }
}
