package ac.cult.cultac.platform.api.player;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

public class PlatformPlayerOwnershipTest {
    @Test
    void lookupsNeverCacheAPlayerTheRuntimeDoesNotOwn() throws Exception {
        var cache = PlatformPlayerCache.getInstance();
        List<UUID> used = new ArrayList<>();
        try (var players = new FakePlatformPlayers()) {
            var foreign = join(players.online, used);

            assertSame(foreign, players.getFromNativePlayerType(foreign).getNative());
            assertSame(foreign, players.getFromUUID(foreign.uuid()).getNative());
            assertSame(foreign, players.getFromName(foreign.uuid().toString()).getNative());
            assertSame(foreign, players.getOnlinePlayers().iterator().next().getNative());
            assertNull(cache.getPlayer(foreign.uuid()));

            var owned = join(players.online, used);
            PlatformPlayer attached = players.attach(players.getFromNativePlayerType(owned));
            assertSame(attached, cache.getPlayer(owned.uuid()));
            assertSame(attached, players.attach(players.getFromNativePlayerType(owned)));
            assertSame(attached, players.getFromNativePlayerType(owned));
            assertSame(attached, players.getFromUUID(owned.uuid()));
            assertSame(attached, players.getFromName(owned.uuid().toString()));
        } finally {
            used.forEach(cache::removePlayer);
        }
    }

    @Test
    void lookupNeverHandsOutAWrapperOfAReplacedNative() throws Exception {
        var cache = PlatformPlayerCache.getInstance();
        UUID uuid = UUID.randomUUID();
        try (var players = new FakePlatformPlayers()) {
            PlatformPlayer stale =
                    players.attach(players.getFromNativePlayerType(new FakePlatformPlayers.Native(uuid)));
            var rejoined = new FakePlatformPlayers.Native(uuid);
            players.online.add(rejoined);

            PlatformPlayer found = players.getFromUUID(uuid);

            assertNotSame(stale, found);
            assertSame(rejoined, found.getNative());
            assertSame(stale, cache.getPlayer(uuid));
        } finally {
            cache.removePlayer(uuid);
        }
    }

    /**
     * A draining runtime and the current one share the server's players but cache them in their own classloaders.
     * Listing players from both must leave each departed player only in the cache of the runtime that owned it, which
     * is the one that receives its disconnect.
     */
    @Test
    void drainingRuntimeRetainsNoForeignPlayerAfterItDisconnects() throws Exception {
        List<FakePlatformPlayers.Native> online = new CopyOnWriteArrayList<>();
        List<UUID> used = new ArrayList<>();
        var draining = runtime(online);
        var current = runtime(online);
        assertNotSame(draining.getClass(), current.getClass());

        var longLived = join(online, used);
        PlatformPlayer drainingOwned = draining.attach(draining.getFromNativePlayerType(longLived));

        for (int round = 0; round < 32; round++) {
            var joined = join(online, used);
            PlatformPlayer currentOwned = current.attach(current.getFromNativePlayerType(joined));

            for (PlatformPlayerFactory runtime : List.of(draining, current)) {
                runtime.getOnlinePlayers();
                assertNotNull(runtime.getFromUUID(joined.uuid()));
                assertNotNull(runtime.getFromName(joined.uuid().toString()));
                runtime.getFromNativePlayerType(joined);
            }

            online.remove(joined);
            current.invalidatePlayer(currentOwned);
        }

        for (UUID uuid : used) {
            assertNull(cached(current, uuid), "current runtime retained " + uuid);
            if (!uuid.equals(longLived.uuid())) assertNull(cached(draining, uuid), "draining runtime retained " + uuid);
        }
        assertSame(drainingOwned, cached(draining, longLived.uuid()));
    }

    private static FakePlatformPlayers.Native join(Collection<FakePlatformPlayers.Native> online, List<UUID> used) {
        var player = new FakePlatformPlayers.Native(UUID.randomUUID());
        used.add(player.uuid());
        online.add(player);
        return player;
    }

    @SuppressWarnings("unchecked")
    private static PlatformPlayer cached(PlatformPlayerFactory runtime, UUID uuid) {
        return ((Function<UUID, PlatformPlayer>) runtime).apply(uuid);
    }

    private static PlatformPlayerFactory runtime(List<FakePlatformPlayers.Native> online) throws Exception {
        Class<?> factory = new RuntimeLoader().loadClass(RuntimeFactory.class.getName());
        return (PlatformPlayerFactory) factory.getConstructor(List.class).newInstance(online);
    }

    /** One runtime's factory over the server's shared natives; it reports its own cache's entry for a UUID. */
    public static final class RuntimeFactory extends AbstractPlatformPlayerFactory<FakePlatformPlayers.Native>
            implements Function<UUID, PlatformPlayer> {
        private final List<FakePlatformPlayers.Native> online;

        public RuntimeFactory(List<FakePlatformPlayers.Native> online) {
            this.online = online;
        }

        @Override
        public PlatformPlayer apply(UUID uuid) {
            return cache.getPlayer(uuid);
        }

        @Override
        protected FakePlatformPlayers.Native getNativePlayer(@NotNull UUID uuid) {
            return FakePlatformPlayers.find(online, uuid.toString());
        }

        @Override
        protected FakePlatformPlayers.Native getNativePlayer(@NotNull String name) {
            return FakePlatformPlayers.find(online, name);
        }

        @Override
        protected PlatformPlayer createPlatformPlayer(@NotNull FakePlatformPlayers.Native nativePlayer) {
            return FakePlatformPlayers.wrapper(nativePlayer);
        }

        @Override
        protected UUID getPlayerUUID(@NotNull FakePlatformPlayers.Native nativePlayer) {
            return nativePlayer.uuid();
        }

        @Override
        protected Collection<FakePlatformPlayers.Native> getNativeOnlinePlayers() {
            return online;
        }
    }

    /** Defines the factory and its cache itself, as a runtime's private loader does; shares everything else. */
    private static final class RuntimeLoader extends ClassLoader {
        private static final Set<String> OWN = Set.of(
                PlatformPlayerCache.class.getName(),
                AbstractPlatformPlayerFactory.class.getName(),
                RuntimeFactory.class.getName());

        RuntimeLoader() {
            super(PlatformPlayerOwnershipTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!OWN.contains(name)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) return loaded;
                try (InputStream bytes = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    byte[] code = bytes.readAllBytes();
                    return defineClass(name, code, 0, code.length);
                } catch (IOException unreadable) {
                    throw new ClassNotFoundException(name, unreadable);
                }
            }
        }
    }
}
