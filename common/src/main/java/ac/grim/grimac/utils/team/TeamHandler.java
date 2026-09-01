package ac.grim.grimac.utils.team;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketSendEvent;
import ac.grim.grimac.player.GrimPlayer;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

// Reminder: Entities use UUIDs, players use name, for setting teams.
public class TeamHandler extends Check implements CheckListener {

    private final Map<String, EntityTeam> entityTeams = new Object2ObjectOpenHashMap<>();
    private final Map<String, EntityTeam> entityToTeam = new Object2ObjectOpenHashMap<>();

    private @Getter @Setter @Nullable EntityTeam playerTeam = null;

    public TeamHandler(GrimPlayer player) {
        super(player);
    }

    public void addEntityToTeam(String entityTeamRepresentation, EntityTeam team) {
        entityToTeam.put(entityTeamRepresentation, team);
    }

    public void removeEntityFromTeam(String entityTeamRepresentation) {
        entityToTeam.remove(entityTeamRepresentation);
    }

    @GrimPacketHandler
    public void onSetPlayerTeam(PacketSendEvent event, GrimPlayer player, ClientboundSetPlayerTeamPacket teams) {
        final String teamName = teams.getName();
        player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> {
            final EntityTeam entityTeam;

            if (teams.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.ADD) {
                var newTeam = new EntityTeam(player, teamName);
                entityTeams.put(teamName, newTeam);
                entityTeam = newTeam;
            } else if (teams.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
                entityTeam = entityTeams.remove(teamName);
            } else {
                entityTeam = entityTeams.get(teamName);
            }

            if (entityTeam != null) {
                entityTeam.update(teams);
            }
        });
    }
}
