package ac.cult.cultac.utils.team;

import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.player.CultPlayer;
import lombok.Getter;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.world.scores.Team;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class EntityTeam {

    public final String name;
    public final Set<String> entries = new HashSet<>();
    private final CultPlayer player;
    @Getter
    private Team.CollisionRule collisionRule;

    public EntityTeam(CultPlayer player, String name) {
        this.player = player;
        this.name = name;
    }

    public void update(ClientboundSetPlayerTeamPacket teams) {
        teams.getParameters().ifPresent(info -> {
            Team.CollisionRule rule = NmsPacketUtil.teamCollisionRule(info);
            if (rule != null) this.collisionRule = rule;
        });

        final TeamHandler teamHandler = player.checkManager.getCheck(TeamHandler.class);

        if (teams.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.ADD) {
            label:
            for (String teamPlayer : teams.getPlayers()) {
                if (teamPlayer.equals(player.user.getName())) {
                    teamHandler.setPlayerTeam(this);
                    continue;
                }

                for (User.Profile profile : player.compensatedEntities.profiles.values()) {
                    if (profile.getName() != null && profile.getName().equals(teamPlayer)) {
                        teamHandler.addEntityToTeam(profile.getUUID().toString(), this);
                        continue label;
                    }
                }

                teamHandler.addEntityToTeam(teamPlayer, this);
            }

        } else if (teams.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
            label:
            for (String teamPlayer : teams.getPlayers()) {
                if (teamPlayer.equals(player.user.getName())) {
                    // Player was removed from their team.
                    teamHandler.setPlayerTeam(null);
                    continue;
                }

                for (User.Profile profile : player.compensatedEntities.profiles.values()) {
                    if (profile.getName() != null && profile.getName().equals(teamPlayer)) {
                        String uuid = profile.getUUID().toString();
                        entries.remove(uuid);
                        teamHandler.removeEntityFromTeam(uuid);
                        continue label;
                    }
                }

                // Entity was removed from their team.
                teamHandler.removeEntityFromTeam(teamPlayer);
                entries.remove(teamPlayer);
            }

        } else if (teams.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) {

            EntityTeam playersTeam = teamHandler.getPlayerTeam();
            // The player's team was deleted, so we must unset the player's team
            if (playersTeam != null && playersTeam.name.equals(name)) {
                teamHandler.setPlayerTeam(null);
            }

            // Also remove the team set on entities
            for (String entry : entries) {
                teamHandler.removeEntityFromTeam(entry);
            }
            entries.clear();
        }
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof EntityTeam t && Objects.equals(name, t.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
