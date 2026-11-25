package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ac.cult.cultac.manager.datastore.PlayerToggleStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class StaffTogglesTest {

    @Test
    void eachUserToggleIsKeptHereAndSent() {
        PlayerToggleStore local = mock(PlayerToggleStore.class);
        List<Map<String, String>> sent = new CopyOnWriteArrayList<>();
        UUID uuid = UUID.randomUUID();
        PlayerToggleStore toggles = CloudModule.staffToggles(local, (player, changes) -> {
            assertEquals(uuid, player);
            sent.add(changes);
        });

        toggles.applyUserToggle(uuid, PlayerToggleStore.KEY_ALERTS, false);
        toggles.applyUserToggle(uuid, PlayerToggleStore.KEY_VERBOSE, true);

        verify(local).applyUserToggle(uuid, PlayerToggleStore.KEY_ALERTS, false);
        verify(local).applyUserToggle(uuid, PlayerToggleStore.KEY_VERBOSE, true);
        assertEquals(List.of(Map.of("alerts", "false"), Map.of("verbose", "true")), sent);
    }
}
