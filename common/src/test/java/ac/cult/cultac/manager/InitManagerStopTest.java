package ac.cult.cultac.manager;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.init.stop.StoppableInitable;
import ac.cult.cultac.platform.api.PlatformLoader;
import com.google.common.collect.ImmutableList;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InitManagerStopTest {
    private final List<String> stopped = new ArrayList<>();
    private Field loader;
    private Object originalLoader;

    @BeforeEach
    void installLoader() throws Exception {
        loader = CultAPI.class.getDeclaredField("loader");
        loader.setAccessible(true);
        originalLoader = loader.get(CultAPI.INSTANCE);
        loader.set(CultAPI.INSTANCE, mock(PlatformLoader.class, RETURNS_DEEP_STUBS));
    }

    @AfterEach
    void restoreLoader() throws Exception {
        loader.set(CultAPI.INSTANCE, originalLoader);
    }

    @Test
    void errorIsRethrownAfterTheRemainingStepsRan() throws Exception {
        var error = new NoClassDefFoundError("closed jar");
        var later = new IllegalStateException("later");
        var manager = manager(
                () -> {
                    throw error;
                },
                () -> {
                    throw later;
                },
                () -> stopped.add("last"));

        assertSame(error, assertThrows(NoClassDefFoundError.class, manager::stop));

        assertEquals(List.of("last"), stopped);
        assertEquals(1, error.getSuppressed().length);
        assertArrayEquals(new Throwable[] {later}, error.getSuppressed()[0].getSuppressed());
    }

    private static InitManager manager(StoppableInitable... steps) throws Exception {
        var manager = mock(InitManager.class, CALLS_REAL_METHODS);
        Field field = InitManager.class.getDeclaredField("initializersOnStop");
        field.setAccessible(true);
        field.set(manager, ImmutableList.copyOf(steps));
        return manager;
    }
}
