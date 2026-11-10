package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPlayerTeam;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientTeamCollisionTest {
    @Test
    void collisionRulesUseCurrentMembershipOnBothSides() {
        for (String rule : List.of("always", "never", "pushOwnTeam", "pushOtherTeams")) {
            var teams = new ClientTeamCollision();
            teams.apply(new ClientboundSetPlayerTeam("team", 0, rule, List.of("pusher")));
            assertEquals(rule.equals("always") || rule.equals("pushOwnTeam"), teams.allows("pusher", "local"), rule);
            assertEquals(teams.allows("pusher", "local"), teams.allows("local", "pusher"));
            teams.apply(new ClientboundSetPlayerTeam("team", 3, null, List.of("local")));
            assertEquals(rule.equals("always") || rule.equals("pushOtherTeams"), teams.allows("pusher", "local"), rule);
            teams.apply(new ClientboundSetPlayerTeam("team", 4, null, List.of("local")));
            assertEquals(rule.equals("always") || rule.equals("pushOwnTeam"), teams.allows("pusher", "local"), rule);
            teams.apply(new ClientboundSetPlayerTeam("team", 1, null, List.of()));
            assertTrue(teams.allows("pusher", "local"));
        }
    }

    @Test
    void membershipTransfersAndInvalidLegacyUpdatesMatchTheScoreboard() {
        var teams = new ClientTeamCollision();
        teams.apply(new ClientboundSetPlayerTeam("off", 0, "never", List.of("pusher")));
        teams.apply(new ClientboundSetPlayerTeam("on", 0, "always", List.of("pusher")));
        teams.apply(new ClientboundSetPlayerTeam("off", 4, null, List.of("pusher")));
        assertTrue(teams.allows("pusher", "local"));
        teams.apply(new ClientboundSetPlayerTeam("on", 2, "never", List.of()));
        teams.apply(new ClientboundSetPlayerTeam("on", 2, "invalid", List.of()));
        assertFalse(teams.allows("pusher", "local"));
        teams.clear();
        assertTrue(teams.allows("pusher", "local"));
    }
}
