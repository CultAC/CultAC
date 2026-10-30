package ac.cult.cultac.events.packets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ac.cult.cultac.manager.datastore.PlayerToggleStore;
import ac.cult.cultac.manager.datastore.PlayerToggleStoreImpl;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.api.storage.DataStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class PlayerLoginHooksTest {

    private static final String TOGGLE = "cult.alerts";
    private static final String ON_JOIN = "cult.alerts.enable-on-join";
    private static final String SILENT = "cult.alerts.enable-on-join.silent";

    @Test
    void alertsFollowTheJoinPermissionUntilStaffToggleExplicitly() {
        DataStore store = mock(DataStore.class);
        doReturn(new CompletableFuture<>()).when(store).query(any(), any());
        PlayerToggleStore toggles = new PlayerToggleStoreImpl(store, Logger.getAnonymousLogger());
        try {
            UUID uuid = UUID.randomUUID();
            List<String> applied = new ArrayList<>();

            assertEquals(List.of("on"), join(toggles, uuid, true, applied));
            assertEquals(List.of(), join(toggles, uuid, false, applied));
            assertEquals(List.of("on"), join(toggles, uuid, true, applied));
            verify(store, never()).submit(any(), any());

            UUID connection = UUID.randomUUID();
            toggles.prefetch(uuid, connection);
            toggles.applyUserToggle(uuid, PlayerToggleStore.KEY_ALERTS, false);
            assertEquals(List.of("set false"), join(toggles, uuid, true, applied));
            toggles.evict(uuid, connection);
            verify(store).submit(any(), any());
        } finally {
            toggles.shutdown();
        }
    }

    private static List<String> join(PlayerToggleStore toggles, UUID uuid, boolean onJoin, List<String> applied) {
        applied.clear();
        PlatformPlayer player = mock(PlatformPlayer.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.hasPermission(TOGGLE)).thenReturn(true);
        when(player.hasPermission(ON_JOIN)).thenReturn(onJoin);
        UUID connection = UUID.randomUUID();
        toggles.prefetch(uuid, connection);
        PlayerLoginHooks.applyToggle(
                player,
                toggles,
                PlayerToggleStore.KEY_ALERTS,
                TOGGLE,
                ON_JOIN,
                SILENT,
                (p, silent) -> applied.add("on"),
                (p, value) -> applied.add("set " + value));
        toggles.evict(uuid, connection);
        return List.copyOf(applied);
    }
}
