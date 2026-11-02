package ac.cult.cultac.protocol.packet.clientbound;

import java.util.List;

/** Client scoreboard membership and collision rule; display-only parameters stay on the original wire frame. */
public record ClientboundSetPlayerTeam(String name, int action, String collisionRule, List<String> members)
        implements ClientboundPacket {
    public ClientboundSetPlayerTeam {
        members = List.copyOf(members);
    }
}
