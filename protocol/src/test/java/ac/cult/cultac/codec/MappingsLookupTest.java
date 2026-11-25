package ac.cult.cultac.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelIdMappings;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;

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

    private static String outcome(PrivateCodecService service, ProtocolVersion source, ProtocolVersion target) {
        try {
            ModelIdMappings mapping = service.mappings(source, target);
            return "mapping " + System.identityHashCode(mapping);
        } catch (RuntimeException failure) {
            return failure.getClass().getName() + ": " + failure.getMessage();
        }
    }
}
