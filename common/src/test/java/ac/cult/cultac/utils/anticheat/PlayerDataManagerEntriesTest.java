package ac.cult.cultac.utils.anticheat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerDataManagerEntriesTest {
    /** The entries are one live, read-only view: later adds and removes show through an earlier reference. */
    @Test
    void entriesAreOneLiveReadOnlyView() {
        OfflineCultTestBootstrap.installConfig();
        PlayerDataManager manager = new PlayerDataManager();
        Collection<CultPlayer> before = manager.getEntries();
        assertTrue(before.isEmpty());
        List<User> users = new ArrayList<>();
        for (int i = 0; i < 3; i++)
            users.add(TestUsers.create(new User.Profile(UUID.randomUUID(), "entries" + i), new EmbeddedChannel()));
        try {
            for (User user : users) manager.addUser(user);
            assertSame(before, manager.getEntries());
            assertEquals(3, before.size());
            for (User user : users) assertTrue(before.contains(manager.getPlayer(user)));
            CultPlayer first = manager.getPlayer(users.get(0));
            assertThrows(UnsupportedOperationException.class, () -> before.remove(first));
            assertThrows(UnsupportedOperationException.class, () -> before.add(first));
            assertThrows(UnsupportedOperationException.class, before::clear);
            assertThrows(UnsupportedOperationException.class, () -> {
                var iterator = before.iterator();
                iterator.next();
                iterator.remove();
            });
            assertTrue(manager.remove(users.get(0)));
            assertFalse(before.contains(first));
            assertEquals(2, before.size());
            assertEquals(manager.size(), before.size());
        } finally {
            for (User user : users) {
                manager.remove(user);
                ((EmbeddedChannel) user.getChannel()).finishAndReleaseAll();
            }
        }
    }
}
