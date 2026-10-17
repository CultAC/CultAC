package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NextTickVelocityDeriverTest {
    @Test
    void nestedTakeGetsAFreshScratch() {
        var owned = new NextTickVelocityDeriver.Scratch();
        var outer = owned.take();
        var nested = owned.take();
        assertSame(owned, outer);
        assertNotSame(owned, nested);
        nested.release();
        outer.release();
        assertSame(owned, owned.take());
    }

    @Test
    void aScratchUsedOnALongLivedThreadDoesNotPinItsLoader() throws Exception {
        var used = new CountDownLatch(1);
        var exit = new CountDownLatch(1);
        var loader = new AtomicReference<WeakReference<ClassLoader>>();
        var failure = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try {
                loader.set(useScratchInOwnLoader());
            } catch (Throwable error) {
                failure.set(error);
            }
            used.countDown();
            try {
                exit.await();
            } catch (InterruptedException ignored) {
            }
        });
        worker.start();
        try {
            used.await();
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertTrue(collected(loader.get()), "the thread that used a scratch still reaches its loader");
        } finally {
            exit.countDown();
            worker.join();
        }
    }

    private static WeakReference<ClassLoader> useScratchInOwnLoader() throws Exception {
        var loader = new ScratchLoader();
        Class<?> scratchType = loader.loadClass(NextTickVelocityDeriver.Scratch.class.getName());
        Object owned = scratchType.getConstructor().newInstance();
        Object taken = scratchType.getMethod("take").invoke(owned);
        scratchType.getMethod("release").invoke(taken);
        return new WeakReference<>(loader);
    }

    private static boolean collected(WeakReference<ClassLoader> loader) throws InterruptedException {
        for (int attempt = 0; attempt < 10 && loader.get() != null; attempt++) {
            System.gc();
            Thread.sleep(20);
        }
        return loader.get() == null;
    }

    /** Defines the deriver's own classes itself, as a runtime's private loader does. */
    private static final class ScratchLoader extends ClassLoader {
        private static final Set<String> OWN = Set.of(
                NextTickVelocityDeriver.class.getName(),
                NextTickVelocityDeriver.Scratch.class.getName(),
                ReusableVelocitySet.class.getName());

        ScratchLoader() {
            super(NextTickVelocityDeriverTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!OWN.contains(name)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) return loaded;
                try (InputStream bytes = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    byte[] code = bytes.readAllBytes();
                    return defineClass(name, code, 0, code.length);
                } catch (IOException unreadable) {
                    throw new ClassNotFoundException(name, unreadable);
                }
            }
        }
    }
}
