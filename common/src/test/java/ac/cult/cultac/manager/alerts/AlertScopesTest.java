package ac.cult.cultac.manager.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.reload.api.HostContext;
import ac.grim.grimac.api.storage.DataStore;
import ac.grim.grimac.api.storage.event.SettingEvent;
import ac.grim.grimac.api.storage.model.SettingRecord;
import ac.grim.grimac.api.storage.model.SettingScope;
import ac.grim.grimac.api.storage.query.Page;
import ac.grim.grimac.api.storage.query.Queries;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class AlertScopesTest {
    /** Player settings by {@code scopeKey/key}, as the datastore keeps them. */
    private final Map<String, SettingRecord> settings = new ConcurrentHashMap<>();
    /** When set, reads wait for the test to complete them. */
    private CompletableFuture<Page<SettingRecord>> pendingRead;

    @SuppressWarnings("unchecked")
    private DataStore store() {
        DataStore store = mock(DataStore.class);
        doAnswer(invocation -> {
                    SettingEvent event = new SettingEvent();
                    ((Consumer<SettingEvent>) invocation.getArgument(1)).accept(event);
                    settings.put(
                            event.scopeKey() + "/" + event.key(),
                            new SettingRecord(
                                    event.scope(),
                                    event.scopeKey(),
                                    event.key(),
                                    event.value(),
                                    event.updatedEpochMs()));
                    return null;
                })
                .when(store)
                .submit(any(), any());
        when(store.query(any(), any())).thenAnswer(invocation -> {
            if (pendingRead != null) return pendingRead;
            Queries.GetSetting query = invocation.getArgument(1);
            SettingRecord record = settings.get(query.scopeKey() + "/" + query.key());
            return CompletableFuture.completedFuture(record == null ? Page.empty() : new Page<>(List.of(record), null));
        });
        return store;
    }

    private static PlatformPlayer player(UUID uuid) {
        PlatformPlayer player = mock(PlatformPlayer.class);
        Object handle = new Object();
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getNative()).thenReturn(handle);
        return player;
    }

    @Test
    void aChoiceSurvivesTheNextJoin() {
        DataStore store = store();
        UUID uuid = UUID.randomUUID();
        new AlertScopes(null, () -> store).choose(player(uuid), AlertScope.GLOBAL);

        SettingRecord stored = settings.get(uuid + "/" + AlertScopes.SETTING_KEY);
        assertEquals(SettingScope.PLAYER, stored.scope());
        assertEquals("global", stored.asString());

        AlertScopes nextRuntime = new AlertScopes(null, () -> store);
        PlatformPlayer rejoined = player(uuid);
        nextRuntime.load(rejoined);
        assertEquals(AlertScope.GLOBAL, nextRuntime.chosen(rejoined));
    }

    @Test
    void withNothingStoredTheDefaultApplies() {
        DataStore store = store();
        AlertScopes scopes = new AlertScopes(null, () -> store);
        PlatformPlayer player = player(UUID.randomUUID());
        scopes.load(player);
        assertNull(scopes.chosen(player));
        assertEquals(AlertScope.SERVER, scopes.scope(player));
        assertEquals(Map.of(), settings);
    }

    @Test
    void aChoiceMadeWhileLoadingWinsAndAQuitDropsALateLoad() {
        DataStore store = store();
        UUID uuid = UUID.randomUUID();
        new AlertScopes(null, () -> store).choose(player(uuid), AlertScope.GLOBAL);
        SettingRecord persisted = settings.get(uuid + "/" + AlertScopes.SETTING_KEY);
        AlertScopes scopes = new AlertScopes(null, () -> store);

        pendingRead = new CompletableFuture<>();
        PlatformPlayer choosing = player(uuid);
        scopes.load(choosing);
        assertNull(scopes.chosen(choosing));
        scopes.choose(choosing, AlertScope.SERVER);
        pendingRead.complete(new Page<>(List.of(persisted), null));
        assertEquals(AlertScope.SERVER, scopes.chosen(choosing));

        pendingRead = new CompletableFuture<>();
        PlatformPlayer leaving = player(UUID.randomUUID());
        scopes.load(leaving);
        scopes.forget(leaving);
        pendingRead.complete(new Page<>(List.of(persisted), null));
        assertNull(scopes.chosen(leaving));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyGenerationSeesTheSameChoice() {
        Map<String, Object> shared = new ConcurrentHashMap<>();
        HostContext host = mock(HostContext.class);
        when(host.shared(any(), any()))
                .thenAnswer(invocation -> shared.computeIfAbsent(
                        invocation.getArgument(0), key -> ((Supplier<Object>) invocation.getArgument(1)).get()));
        PlatformPlayer player = player(UUID.randomUUID());

        new AlertScopes(host, () -> null).choose(player, AlertScope.GLOBAL);
        assertEquals(AlertScope.GLOBAL, new AlertScopes(host, () -> null).chosen(player));
    }
}
