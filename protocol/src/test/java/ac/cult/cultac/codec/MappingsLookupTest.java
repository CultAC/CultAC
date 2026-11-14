package ac.cult.cultac.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelIdMappings;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Every lookup of a version pair gives the mapping the service computed once for it, or fails as computing fails. */
class MappingsLookupTest {

    @Test
    void everyPairGivesTheOneComputedMappingOrTheSameFailure() throws Exception {
        var service = new PrivateCodecService();
        try {
            Field field = PrivateCodecService.class.getDeclaredField("mappings");
            field.setAccessible(true);
            var computed = (Map<?, ?>) field.get(service);
            int succeeded = 0, failed = 0;
            for (var source : ProtocolVersion.values()) {
                for (var target : ProtocolVersion.values()) {
                    String first = outcome(service, source, target);
                    for (int call = 0; call < 3; call++)
                        assertEquals(first, outcome(service, source, target), source + " -> " + target);
                    if (first.startsWith("mapping ")) {
                        var mapping = service.mappings(source, target);
                        assertSame(mapping, service.mappings(source, target));
                        assertTrue(
                                computed.values().stream().anyMatch(value -> value == mapping),
                                source + " -> " + target);
                        succeeded++;
                    } else failed++;
                }
            }
            assertEquals(computed.size(), succeeded);
            assertTrue(succeeded > 0, "succeeded " + succeeded + ", failed " + failed);
            String unknown = outcome(service, null, ProtocolVersion.V26_3);
            assertEquals(unknown, outcome(service, null, ProtocolVersion.V26_3));
        } finally {
            service.close();
        }
        assertThrows(IllegalStateException.class, () -> service.mappings(ProtocolVersion.V26_3, ProtocolVersion.V26_3));
    }

    private record Pair(ProtocolVersion source, ProtocolVersion target) {}

    /** Bytes per lookup: a map keyed by a new pair with a capturing mapping function, then the service. */
    @Test
    @EnabledIfEnvironmentVariable(named = "CULT_MAPPINGS_BENCH", matches = "1")
    void bench() {
        var service = new PrivateCodecService();
        try {
            var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            var keyed = new ConcurrentHashMap<Pair, ModelIdMappings>();
            var versions = new ProtocolVersion[] {ProtocolVersion.V26_1, ProtocolVersion.V26_2, ProtocolVersion.V26_3};
            long sink = 0;
            for (int rep = -3; rep < 7; rep++) {
                for (int mode = 0; mode < 2; mode++) {
                    long start = bean.getCurrentThreadAllocatedBytes();
                    for (int i = 0; i < 1_000_000; i++) {
                        var source = versions[i % versions.length];
                        ModelIdMappings mapping = mode == 0
                                ? keyed.computeIfAbsent(
                                        new Pair(source, ProtocolVersion.V26_3),
                                        pair -> service.mappings(pair.source(), pair.target()))
                                : service.mappings(source, ProtocolVersion.V26_3);
                        sink += System.identityHashCode(mapping) & 1;
                    }
                    long bytes = bean.getCurrentThreadAllocatedBytes() - start;
                    if (rep >= 0)
                        System.out.printf(
                                "%s %.1f B/lookup%n", mode == 0 ? "keyed map" : "service", bytes / 1_000_000.0);
                }
            }
            System.out.println("sink=" + sink);
        } finally {
            service.close();
        }
    }

    private static String outcome(PrivateCodecService service, ProtocolVersion source, ProtocolVersion target) {
        try {
            ModelIdMappings mapping = service.mappings(source, target);
            return "mapping " + System.identityHashCode(mapping);
        } catch (RuntimeException failure) {
            return failure.getClass().getName() + ": " + failure.getMessage();
        }
    }
}
