package ac.cult.cultac.utils.anticheat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.manager.AlertManagerImpl;
import ac.cult.cultac.manager.AlertSets;
import ac.cult.cultac.manager.datastore.DataStoreLifecycle;
import ac.cult.cultac.manager.datastore.LiveWriteHooks;
import ac.cult.cultac.manager.datastore.PlayerToggleStore;
import ac.cult.cultac.manager.datastore.SessionTracker;
import ac.cult.cultac.manager.datastore.SessionTrackerImpl;
import ac.cult.cultac.manager.datastore.StorageWriteHooks;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.FakePlatformPlayers;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.player.PlatformPlayerCache;
import ac.grim.grimac.api.storage.DataStore;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlayerDataManagerConnectionCleanupTest {
    private final UUID uuid = UUID.randomUUID();
    private final SessionTrackerImpl tracker = new SessionTrackerImpl(mock(DataStore.class), "test", 100);
    private final PlayerToggleStore toggles = mock(PlayerToggleStore.class);
    private final AlertManagerImpl alerts = CultAPI.INSTANCE.getAlertManager();
    private final PlayerDataManager manager = new PlayerDataManager();
    private Field dataStoreField;
    private Object originalDataStore;
    private FakePlatformPlayers players;
    private User user;
    private PlatformPlayer bound;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        OfflineCultTestBootstrap.installConfig();
        players = new FakePlatformPlayers();
        dataStoreField = CultAPI.class.getDeclaredField("dataStoreLifecycle");
        dataStoreField.setAccessible(true);
        originalDataStore = dataStoreField.get(CultAPI.INSTANCE);
        DataStoreLifecycle lifecycle =
                new DataStoreLifecycle(CultAPI.INSTANCE.getGrimPlugin(), CultAPI.INSTANCE.getBackendRegistry());
        StorageWriteHooks hooks = mock(StorageWriteHooks.class);
        doAnswer(invocation -> {
                    tracker.close(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
                    return null;
                })
                .when(hooks)
                .onQuit(any(), anyLong(), any());
        Field sharedField = DataStoreLifecycle.class.getDeclaredField("shared");
        sharedField.setAccessible(true);
        Object shared = sharedField.get(lifecycle);
        setField(shared, "liveWriteHooks", hooks);
        setField(shared, "playerToggleStore", toggles);
        dataStoreField.set(CultAPI.INSTANCE, lifecycle);

        user = TestUsers.create(new User.Profile(uuid, "staff"), new EmbeddedChannel());
        manager.addUser(user);
        bound = players.attach(players.getFromNativePlayerType(new FakePlatformPlayers.Native(uuid)));
        user.bind(bound);
        alerts.setAlertsEnabled(bound, true, true);
        alerts.setVerboseEnabled(bound, true, true);
        alerts.setBrandsEnabled(bound, true, true);
        tracker.observeActivity(uuid, 1000, meta());
        manager.exemptUser(user);
        assertTrue(manager.remove(user));
    }

    @AfterEach
    void tearDown() throws Exception {
        alerts.handlePlayerQuit(bound);
        PlatformPlayerCache.getInstance().removePlayer(uuid);
        dataStoreField.set(CultAPI.INSTANCE, originalDataStore);
        players.close();
        ((EmbeddedChannel) user.getChannel()).finishAndReleaseAll();
    }

    @Test
    void removedPlayerDisconnectReleasesConnectionState() throws ReflectiveOperationException {
        assertFalse(manager.onDisconnect(user));

        assertEquals(List.of(), AlertSets.subscribed(bound));
        assertNull(tracker.currentSessionId(uuid, meta()));
        assertNull(tracker.currentSessionId(uuid));
        verify(toggles).evict(uuid, user.getConnectionId());
        assertNull(PlatformPlayerCache.getInstance().getPlayer(uuid));
        assertFalse(manager.isExemptUser(user));
    }

    @Test
    void removedPlayerTransferKeepsSessionAndSubscriptions() throws ReflectiveOperationException {
        UUID session = tracker.currentSessionId(uuid, meta());
        assertNotNull(session);

        assertFalse(manager.onDisconnect(user, true));

        assertEquals(session, tracker.currentSessionId(uuid, meta()));
        verify(toggles, never()).evict(any(), any());
        assertEquals(List.of("NORMAL", "VERBOSE", "BRAND"), AlertSets.subscribed(bound));
        assertSame(bound, user.getPlayer());
    }

    @Test
    void configuringPlayerRemovedBeforeBindingReleasesItsToggleSlot() {
        UUID configuring = UUID.randomUUID();
        User pending = TestUsers.create(new User.Profile(configuring, "configuring"), new EmbeddedChannel());
        try {
            manager.addUser(pending);
            verify(toggles).prefetch(configuring, pending.getConnectionId());
            // An observe-only ping timeout in configuration exempts the user; the next poll removes it.
            manager.exemptUser(pending);
            assertTrue(manager.remove(pending));
            assertNull(pending.getPlayer());

            assertFalse(manager.onDisconnect(pending));

            verify(toggles).evict(configuring, pending.getConnectionId());
        } finally {
            ((EmbeddedChannel) pending.getChannel()).finishAndReleaseAll();
        }
    }

    private SessionTracker.ClientMeta meta() {
        return LiveWriteHooks.clientMetaFor(user, null);
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
