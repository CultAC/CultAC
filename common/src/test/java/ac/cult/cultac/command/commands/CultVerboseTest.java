package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.manager.datastore.PlayerToggleStore;
import ac.cult.cultac.manager.datastore.PlayerToggleStoreImpl;
import ac.grim.grimac.api.storage.DataStore;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class CultVerboseTest {

    @Test
    void verboseOnLeavesAnUnchosenAlertsToggleToTheJoinPermission() {
        DataStore store = mock(DataStore.class);
        doReturn(new CompletableFuture<>()).when(store).query(any(), any());
        PlayerToggleStore toggles = new PlayerToggleStoreImpl(store, Logger.getAnonymousLogger());
        try {
            UUID unchosen = UUID.randomUUID();
            toggles.prefetch(unchosen, UUID.randomUUID());
            CultVerbose.store(toggles, unchosen, true);
            assertEquals(Boolean.TRUE, toggles.current(unchosen, PlayerToggleStore.KEY_VERBOSE));
            assertNull(toggles.current(unchosen, PlayerToggleStore.KEY_ALERTS));

            UUID chosen = UUID.randomUUID();
            toggles.prefetch(chosen, UUID.randomUUID());
            toggles.applyUserToggle(chosen, PlayerToggleStore.KEY_ALERTS, false);
            CultVerbose.store(toggles, chosen, true);
            assertEquals(Boolean.TRUE, toggles.current(chosen, PlayerToggleStore.KEY_ALERTS));

            CultVerbose.store(toggles, chosen, false);
            assertEquals(Boolean.FALSE, toggles.current(chosen, PlayerToggleStore.KEY_VERBOSE));
            assertEquals(Boolean.TRUE, toggles.current(chosen, PlayerToggleStore.KEY_ALERTS));
        } finally {
            toggles.shutdown();
        }
    }
}
