package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.events.packets.PacketServerRegistries;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPlayerTeam;
import ac.cult.cultac.protocol.value.GameMode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Each play session after configuration gets a new ClientPacketListener, whose Scoreboard starts empty. A team only a
 * plugin sent, which the server never sends again, therefore no longer filters EntitySelector#pushableBy.
 */
class ReconfigurationTeamResetTest {
    @Test
    void aPluginTeamDoesNotSurviveAReconfiguration() throws Exception {
        try (var f = new JavaTickFixture()) {
            var teams = f.player.compensatedEntities.teamCollision;
            teams.apply(new ClientboundSetPlayerTeam("nopush", 0, "never", List.of(f.player.getName())));
            assertNotNull(teams.teamOf(f.player.getName()));

            var type = ClientboundPackets.FINISH_CONFIGURATION;
            var finish = new PacketSendEvent<>(
                    f.player.user, ConnectionPhase.CONFIGURATION, type, new Opaque(type), false);
            new PacketServerRegistries().onFinishConfiguration(finish, f.player, new Opaque(type));
            finish.getTasksAfterSend().forEach(Runnable::run);
            f.pongThrough(ItemUseTestSupport.login(f.player, GameMode.SURVIVAL));

            assertNull(teams.teamOf(f.player.getName()));
            assertTrue(teams.allows("cow", f.player.getName()));
        }
    }
}
