package ac.cult.cultac.network.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.InternalThreadLocalMap;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The column scratch a chunk decode leaves on its thread is held through a plain {@link FastThreadLocal} and a plain
 * {@link WeakReference} to a netty buffer, so a host thread that outlives a runtime generation keeps neither that
 * generation's class loader nor the scratch.
 */
class SectionProjectionScratchTest {
    @Test
    void theThreadMapHoldsTheColumnScratchOnlyWeaklyThroughHostTypes() throws Exception {
        Field field = SectionProjection.class.getDeclaredField("COLUMN_BYTES");
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
                assertEquals(1, decodeOneSection().size());
                var map = InternalThreadLocalMap.getIfSet();
                assertNotNull(map);
                Object held = map.indexedVariable(index);
                assertSame(WeakReference.class, held.getClass());
                var scratch = assertInstanceOf(ByteBuf.class, ((WeakReference<?>) held).get());
                assertTrue(
                        scratch.getClass().getName().startsWith("io.netty."),
                        scratch.getClass().getName());
                for (int i = 0; i <= InternalThreadLocalMap.lastVariableIndex(); i++) {
                    Object value = map.indexedVariable(i);
                    if (value != InternalThreadLocalMap.UNSET
                            && value.getClass().getName().startsWith("ac.cult."))
                        report.add("index " + i + " holds " + value.getClass().getName());
                }
                var reference = (WeakReference<?>) held;
                held = null;
                scratch = null;
                for (int gc = 0; gc < 50 && reference.get() != null; gc++) System.gc();
                assertNull(reference.get(), "the scratch is strongly reachable from the thread");
                assertEquals(1, decodeOneSection().size(), "a cleared scratch is made again");
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        thread.start();
        thread.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertTrue(report.isEmpty(), String.join("; ", report));
    }

    /** One air section with a single-entry biome container, read through the scratch. */
    private static List<?> decodeOneSection() {
        ByteBuf column = Unpooled.wrappedBuffer(new byte[] {0, 0, 0, 0, 0, 0, 0, 0});
        var sections =
                SectionProjection.modelSections(column, 1, ModelRegistryNamesState.defaults(), "test:scratch", 0, 0);
        assertNotNull(sections);
        return sections;
    }
}
