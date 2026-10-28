package ac.cult.cultac.manager.datastore;

import ac.grim.grimac.api.storage.DataStore;
import ac.grim.grimac.api.storage.category.Categories;
import ac.grim.grimac.api.storage.event.SettingEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PlayerToggleStoreImplTest {
    @Test
    void shutdownFlushesLatestValuesAndCancelsPendingWork() {
        DataStore dataStore = mock(DataStore.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> pending = mock(ScheduledFuture.class);
        List<Runnable> callbacks = new ArrayList<>();
        doAnswer(invocation -> {
            callbacks.add(invocation.getArgument(0));
            return pending;
        }).when(scheduler).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
        List<SettingEvent> writes = new ArrayList<>();
        doAnswer(invocation -> {
            SettingEvent event = new SettingEvent();
            invocation.<Consumer<SettingEvent>>getArgument(1).accept(event);
            writes.add(event);
            return null;
        }).when(dataStore).submit(eq(Categories.SETTING), any());

        PlayerToggleStoreImpl store = new PlayerToggleStoreImpl(dataStore,
                Logger.getAnonymousLogger(), scheduler, 500, false);
        UUID player = UUID.randomUUID();
        store.applyUserToggle(player, PlayerToggleStore.KEY_ALERTS, true);
        store.applyUserToggle(player, PlayerToggleStore.KEY_ALERTS, false);
        store.applyUserToggle(player, PlayerToggleStore.KEY_BRANDS, true);
        assertEquals(0, writes.size());

        store.shutdown();
        assertEquals(2, writes.size());
        assertEquals(List.of("alerts", "brands"), writes.stream().map(SettingEvent::key).toList());
        assertEquals(0, writes.get(0).value()[0]);
        assertEquals(1, writes.get(1).value()[0]);
        assertEquals(player.toString(), writes.get(0).scopeKey());
        verify(pending).cancel(false);
        verify(scheduler, never()).shutdownNow();

        callbacks.forEach(Runnable::run);
        store.applyUserToggle(player, PlayerToggleStore.KEY_ALERTS, true);
        store.applyPermissionDefault(UUID.randomUUID(), PlayerToggleStore.KEY_ALERTS, true);
        store.shutdown();
        assertEquals(2, writes.size(), "shutdown must finish writes before the datastore drains");
        assertEquals(1, callbacks.size());
    }

    @Test
    void shutdownStopsAnOwnedScheduler() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        PlayerToggleStoreImpl store = new PlayerToggleStoreImpl(mock(DataStore.class),
                Logger.getAnonymousLogger(), scheduler, 500, true);
        store.shutdown();
        store.shutdown();
        verify(scheduler).shutdownNow();
    }
}
