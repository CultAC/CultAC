package ac.cult.cultac.manager.datastore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import ac.grim.grimac.api.storage.DataStore;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class PlayerToggleStoreImplTest {
    @Test
    void lazyFlushWorkerDoesNotInheritTheCallingRuntimeClassloader() throws Exception {
        var scheduler = PlayerToggleStoreImpl.defaultScheduler();
        var caller = Thread.currentThread();
        var previous = caller.getContextClassLoader();
        try (var runtime = new URLClassLoader(new URL[0], previous)) {
            caller.setContextClassLoader(runtime);
            Thread worker = scheduler
                    .schedule(Thread::currentThread, 0, TimeUnit.MILLISECONDS)
                    .get(5, TimeUnit.SECONDS);
            assertSame(PlayerToggleStoreImpl.class.getClassLoader(), worker.getContextClassLoader());
        } finally {
            caller.setContextClassLoader(previous);
            scheduler.shutdownNow();
        }
    }

    @Test
    void slotStaysWhileAnotherConnectionWithTheSameUuidHoldsIt() {
        DataStore store = mock(DataStore.class);
        doReturn(new CompletableFuture<>()).when(store).query(any(), any());
        var scheduler = PlayerToggleStoreImpl.defaultScheduler();
        var toggles = new PlayerToggleStoreImpl(store, Logger.getAnonymousLogger(), scheduler, 60_000, true);
        try {
            UUID player = UUID.randomUUID();
            UUID old = UUID.randomUUID();
            UUID replacement = UUID.randomUUID();
            toggles.prefetch(player, old);
            toggles.prefetch(player, replacement);
            toggles.applyUserToggle(player, PlayerToggleStore.KEY_ALERTS, true);

            // The old connection's late disconnect, and a connection that never held the slot, keep it.
            toggles.evict(player, old);
            toggles.evict(player, UUID.randomUUID());
            assertEquals(Boolean.TRUE, toggles.current(player, PlayerToggleStore.KEY_ALERTS));

            toggles.evict(player, replacement);
            assertNull(toggles.current(player, PlayerToggleStore.KEY_ALERTS));
        } finally {
            toggles.shutdown();
        }
    }
}
