package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.platform.api.PlatformLoader;
import ac.grim.grimac.api.plugin.GrimPlugin;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CloudServerIdsTest {
    @Test
    void theDefaultTemplateNamesTheProxyAndTheBackend() {
        assertEquals("edge-1.lobby", CloudServerIds.resolve(CloudSettings.DEFAULT_SERVER_ID, "edge-1", "lobby"));
    }

    @Test
    void aProxyTemplateWithoutAProxyIdResolvesToNothing() {
        assertNull(CloudServerIds.resolve(CloudSettings.DEFAULT_SERVER_ID, "", "lobby"));
        assertNull(CloudServerIds.resolve(CloudSettings.DEFAULT_SERVER_ID, " ", "lobby"));
        assertEquals("lobby", CloudServerIds.resolve("{server}", "", "lobby"));
    }

    @Test
    void idsKeepToTheServicesAlphabetAndLength() {
        assertEquals("edge-1.survival_2-", CloudServerIds.resolve("{proxy}.{server}", "Edge 1", "Survival_2!"));
        assertEquals("a.b", CloudServerIds.resolve("{proxy}.{server}", "..a", "b"));
        String id = CloudServerIds.resolve("{server}", "", "x".repeat(300));
        assertNotNull(id);
        assertEquals(CloudServerIds.MAX_LENGTH, id.length());
        assertNull(CloudServerIds.resolve("{server}", "", "!!!"));
    }

    @Test
    void dotsInANameNeverMoveTheSeparator() {
        assertEquals("edge-a.b", CloudServerIds.resolve(CloudSettings.DEFAULT_SERVER_ID, "edge.a", "b"));
        assertEquals("edge.a-b", CloudServerIds.resolve(CloudSettings.DEFAULT_SERVER_ID, "edge", "a.b"));
        assertEquals("edge.a.b", CloudServerIds.resolve("{proxy}.a.{server}", "edge", "b"));
    }

    @Test
    void aCollisionNamesOnlyTheValuesTheTemplateUses() {
        assertEquals(CloudServerIds.input("{server}", "a", "x"), CloudServerIds.input("{server}", "b", "x"));
        assertNotEquals(
                CloudServerIds.input("{proxy}.{server}", "a", "x"), CloudServerIds.input("{proxy}.{server}", "b", "x"));
    }

    @Test
    void aRejectedCharacterBecomesADash() {
        assertEquals("arena-1", CloudServerIds.resolve("{server}", "", "arena+1"));
        assertNotEquals(
                CloudServerIds.resolve("{server}", "", "arena+1"), CloudServerIds.resolve("{server}", "", "arena1"));
    }

    @Test
    void twoInputsSharingAnIdWarnOnce() throws Exception {
        List<LogRecord> logs = new ArrayList<>();
        Field loaderField = CultAPI.class.getDeclaredField("loader");
        loaderField.setAccessible(true);
        Object previousLoader = loaderField.get(CultAPI.INSTANCE);
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            public void publish(LogRecord record) {
                logs.add(record);
            }

            public void flush() {}

            public void close() {}
        });
        GrimPlugin plugin = Mockito.mock(GrimPlugin.class);
        Mockito.when(plugin.getLogger()).thenReturn(logger);
        PlatformLoader loader = Mockito.mock(PlatformLoader.class);
        Mockito.when(loader.getPlugin()).thenReturn(plugin);
        loaderField.set(CultAPI.INSTANCE, loader);
        try {
            CloudServerIds.Collisions collisions = new CloudServerIds.Collisions();
            assertFalse(collisions.seen("edge.a.b", "edge.a / b"));
            assertFalse(collisions.seen("edge.a.b", "edge.a / b"));
            assertTrue(collisions.seen("edge.a.b", "edge / a.b"));
            assertTrue(collisions.seen("edge.a.b", "edge / a+b"));
            assertEquals(1, logs.size());
            assertEquals(Level.WARNING, logs.get(0).getLevel());
        } finally {
            loaderField.set(CultAPI.INSTANCE, previousLoader);
        }
    }
}
