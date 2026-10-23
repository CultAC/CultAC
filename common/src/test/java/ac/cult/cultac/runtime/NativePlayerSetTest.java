package ac.cult.cultac.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.platform.api.player.FakePlatformPlayers;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.player.PlatformPlayerCache;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NativePlayerSetTest {
    @Test
    void iteratingForeignNativeKeepsCachedWrapper() throws Exception {
        UUID uuid = UUID.randomUUID();
        var cache = PlatformPlayerCache.getInstance();
        try (var players = new FakePlatformPlayers()) {
            var live = new FakePlatformPlayers.Native(uuid);
            var stale = new FakePlatformPlayers.Native(uuid);
            PlatformPlayer wrapper = players.attach(players.getFromNativePlayerType(live));
            Set<Object> natives = new HashSet<>();
            natives.add(stale);
            natives.add(live);

            Set<PlatformPlayer> listeners = new HashSet<>(new NativePlayerSet(natives));

            assertSame(wrapper, cache.getPlayer(uuid));
            assertEquals(2, listeners.size());
            assertTrue(listeners.contains(wrapper));
            assertTrue(listeners.stream().anyMatch(player -> player.getNative() == stale));
        } finally {
            cache.removePlayer(uuid);
        }
    }

    @Test
    void iteratingUncachedNativeLeavesCacheEmpty() throws Exception {
        UUID uuid = UUID.randomUUID();
        var cache = PlatformPlayerCache.getInstance();
        try (var players = new FakePlatformPlayers()) {
            Set<Object> natives = new HashSet<>();
            natives.add(new FakePlatformPlayers.Native(uuid));

            new HashSet<>(new NativePlayerSet(natives));

            assertNull(cache.getPlayer(uuid));
        } finally {
            cache.removePlayer(uuid);
        }
    }
}
