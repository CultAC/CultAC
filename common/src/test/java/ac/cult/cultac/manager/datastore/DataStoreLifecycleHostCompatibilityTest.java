package ac.cult.cultac.manager.datastore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

// TODO(cultLoaderVersion>5): remove with DataStoreLifecycle's host check.
class DataStoreLifecycleHostCompatibilityTest {
    /** The toggle store a loader-5 host shipped before connection holds. */
    interface HostBeforeConnectionHolds {
        void prefetch(UUID uuid);

        void evict(UUID uuid);
    }

    @Test
    void detectsWhetherTheHostHoldsSlotsPerConnection() {
        assertTrue(DataStoreLifecycle.holdsPerConnection(PlayerToggleStore.class));
        assertFalse(DataStoreLifecycle.holdsPerConnection(HostBeforeConnectionHolds.class));
    }
}
