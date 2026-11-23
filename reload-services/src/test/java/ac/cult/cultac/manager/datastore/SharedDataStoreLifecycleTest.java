package ac.cult.cultac.manager.datastore;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import ac.grim.grimac.api.storage.config.WritePathConfig;
import ac.grim.grimac.internal.storage.core.CategoryRouter;
import ac.grim.grimac.internal.storage.core.DataStoreImpl;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class SharedDataStoreLifecycleTest {
    @Test
    void lazyReadersDoNotInheritCallingRuntimeClassloaders() throws Exception {
        var store = new DataStoreImpl(
                new CategoryRouter(Map.of()), WritePathConfig.defaults(), Logger.getAnonymousLogger());
        var caller = Thread.currentThread();
        var previous = caller.getContextClassLoader();
        try (var first = new URLClassLoader(new URL[0], previous);
                var second = new URLClassLoader(new URL[0], previous)) {
            SharedDataStoreLifecycle.hostReaderThreads(store);
            caller.setContextClassLoader(first);
            Thread firstReader =
                    store.readerExecutor().submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
            caller.setContextClassLoader(second);
            Thread secondReader =
                    store.readerExecutor().submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
            assertNotSame(firstReader, secondReader);
            assertSame(SharedDataStoreLifecycle.class.getClassLoader(), firstReader.getContextClassLoader());
            assertSame(SharedDataStoreLifecycle.class.getClassLoader(), secondReader.getContextClassLoader());
        } finally {
            caller.setContextClassLoader(previous);
            store.flushAndClose(1000);
        }
    }
}
