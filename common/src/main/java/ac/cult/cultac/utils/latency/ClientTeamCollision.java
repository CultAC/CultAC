package ac.cult.cultac.utils.latency;

import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPlayerTeam;
import java.util.HashMap;
import java.util.Map;

/** The received client scoreboard, as EntitySelector#pushableBy reads it. */
public final class ClientTeamCollision {
    private final Map<String, Team> teams = new HashMap<>();
    private final Map<String, Team> membership = new HashMap<>();

    private static final class Team {
        private String collision = "always";
    }

    public void clear() {
        teams.clear();
        membership.clear();
    }

    public void apply(ClientboundSetPlayerTeam packet) {
        if (packet.action() == 1) {
            Team removed = teams.remove(packet.name());
            if (removed != null) membership.values().removeIf(team -> team == removed);
            return;
        }
        Team team = packet.action() == 0
                ? teams.computeIfAbsent(packet.name(), ignored -> new Team())
                : teams.get(packet.name());
        if (team == null) return;
        String rule = packet.collisionRule();
        if ((packet.action() == 0 || packet.action() == 2)
                && ("always".equals(rule)
                        || "never".equals(rule)
                        || "pushOwnTeam".equals(rule)
                        || "pushOtherTeams".equals(rule))) team.collision = rule;
        if (packet.action() == 0 || packet.action() == 3)
            for (String member : packet.members()) membership.put(member, team);
        else if (packet.action() == 4) for (String member : packet.members()) membership.remove(member, team);
    }

    public boolean allows(String pusher, String player) {
        Team own = membership.get(pusher), target = membership.get(player);
        String a = own == null ? "always" : own.collision, b = target == null ? "always" : target.collision;
        if (a.equals("never") || b.equals("never")) return false;
        boolean same = own != null && own == target;
        if ((a.equals("pushOwnTeam") || b.equals("pushOwnTeam")) && same) return false;
        return !a.equals("pushOtherTeams") && !b.equals("pushOtherTeams") || same;
    }
}
