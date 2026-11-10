package ac.cult.cultac.network.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolVersion;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.InternalThreadLocalMap;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A thread that ran a single packet read keeps no object of an anticheat class strongly reachable from its netty
 * thread-local map: the scratch is held through a plain {@link FastThreadLocal} and a plain {@link WeakReference}, so a
 * host thread that outlives a runtime generation does not keep that generation's class loader.
 */
class ThreadScratchHolderTest {
    @Test
    void theThreadMapHoldsTheScratchOnlyWeaklyThroughHostTypes() throws Exception {
        Field field = ModelEntityMetadataValues.class.getDeclaredField("THREAD_SCRATCH");
        field.setAccessible(true);
        Object holder = field.get(null);
        assertSame(FastThreadLocal.class, field.getType());
        assertSame(FastThreadLocal.class, holder.getClass(), "no subclass");
        Field indexField = FastThreadLocal.class.getDeclaredField("index");
        indexField.setAccessible(true);
        int index = indexField.getInt(holder);

        var failure = new AtomicReference<Throwable>();
        var report = new ArrayList<String>();
        var thread = new Thread(() -> {
            try {
                readOnePacket();
                var map = InternalThreadLocalMap.getIfSet();
                assertNotNull(map);
                Object held = map.indexedVariable(index);
                assertSame(WeakReference.class, held.getClass());
                assertEquals(
                        ModelEntityMetadataValues.class.getName() + "$Scratch",
                        ((WeakReference<?>) held).get().getClass().getName());
                for (int i = 0; i <= InternalThreadLocalMap.lastVariableIndex(); i++) {
                    Object value = map.indexedVariable(i);
                    if (value == InternalThreadLocalMap.UNSET || value instanceof WeakReference<?>) continue;
                    List<Object> objects = new ArrayList<>(List.of(value));
                    if (value instanceof Collection<?> collection) objects.addAll(collection);
                    for (Object object : objects)
                        if (object.getClass().getName().startsWith("ac.cult."))
                            report.add(
                                    "index " + i + " holds " + object.getClass().getName());
                }
                var reference = (WeakReference<?>) held;
                held = null;
                for (int gc = 0; gc < 50 && reference.get() != null; gc++) System.gc();
                assertNull(reference.get(), "the scratch is strongly reachable from the thread");
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        thread.start();
        thread.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertTrue(report.isEmpty(), String.join("; ", report));
    }

    private static void readOnePacket() {
        var version = ProtocolVersion.V26_3;
        var reader = new ModelEntityMetadataValues(
                ProtocolCodecs.decoder(), version, new WireRegistryState(version, () -> null), name -> null);
        var metadata = reader.readOnce(Unpooled.wrappedBuffer(new byte[] {5, (byte) 255}), null);
        assertEquals(5, metadata.id());
    }
}
