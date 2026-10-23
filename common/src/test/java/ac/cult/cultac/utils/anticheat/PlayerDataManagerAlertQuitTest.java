package ac.cult.cultac.utils.anticheat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.manager.AlertManagerImpl;
import ac.cult.cultac.manager.AlertSets;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.FakePlatformPlayers;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.player.PlatformPlayerCache;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerDataManagerAlertQuitTest {
    @Test
    void quitRemovesSubscriptionsWhenCacheHoldsAnotherNative() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        UUID uuid = UUID.randomUUID();
        AlertManagerImpl alerts = CultAPI.INSTANCE.getAlertManager();
        PlayerDataManager manager = new PlayerDataManager();
        User user = TestUsers.create(new User.Profile(uuid, "staff"), new EmbeddedChannel());
        PlatformPlayer bound = null;
        try (var players = new FakePlatformPlayers()) {
            manager.addUser(user);
            bound = players.attach(players.getFromNativePlayerType(new FakePlatformPlayers.Native(uuid)));
            user.bind(bound);
            alerts.setAlertsEnabled(bound, true, true);
            alerts.setVerboseEnabled(bound, true, true);
            alerts.setBrandsEnabled(bound, true, true);
            assertEquals(List.of("NORMAL", "VERBOSE", "BRAND"), AlertSets.subscribed(bound));
            PlatformPlayer stale =
                    players.attach(players.getFromNativePlayerType(new FakePlatformPlayers.Native(uuid)));
            assertSame(stale, PlatformPlayerCache.getInstance().getPlayer(uuid));
            assertNotSame(bound, stale);

            assertTrue(manager.onDisconnect(user));

            assertEquals(List.of(), AlertSets.subscribed(bound));
        } finally {
            if (bound != null) alerts.handlePlayerQuit(bound);
            PlatformPlayerCache.getInstance().removePlayer(uuid);
            ((EmbeddedChannel) user.getChannel()).finishAndReleaseAll();
        }
    }
}
