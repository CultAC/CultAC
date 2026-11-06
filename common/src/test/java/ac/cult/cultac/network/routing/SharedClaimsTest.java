package ac.cult.cultac.network.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.manager.alerts.AlertNetwork;
import ac.cult.reload.api.HostContext;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SharedClaimsTest {
    private final AtomicLong clock = new AtomicLong(1_793_140_000_000L);

    @SuppressWarnings("unchecked")
    private static HostContext host() {
        Map<String, Object> shared = new ConcurrentHashMap<>();
        HostContext host = mock(HostContext.class);
        when(host.shared(any(), any()))
                .thenAnswer(invocation -> shared.computeIfAbsent(
                        invocation.getArgument(0), key -> ((Supplier<Object>) invocation.getArgument(1)).get()));
        return host;
    }

    @Test
    void theFirstClaimWinsUntilTheReplayWindowPasses() {
        SharedClaims claims = new SharedClaims(null, AlertNetwork.CLAIMS, clock::get);
        assertTrue(claims.claim("a"));
        assertFalse(claims.claim("a"));
        assertTrue(claims.claim("b"));

        clock.addAndGet(EnvelopeCodec.REPLAY_WINDOW_MILLIS);
        assertFalse(claims.claim("a"));
        clock.addAndGet(1);
        assertTrue(claims.claim("a"));
    }

    @Test
    void generationsOnOneHostShareOneMapWithTheirClaimsPerScope() {
        HostContext host = host();
        SharedClaims older = new SharedClaims(host, AlertNetwork.CLAIMS, clock::get);
        SharedClaims newer = new SharedClaims(host, AlertNetwork.CLAIMS, clock::get);
        SharedClaims other = new SharedClaims(host, RemoteCommands.CLAIMS, clock::get);

        assertTrue(newer.claim("a"));
        assertFalse(older.claim("a"));
        assertTrue(other.claim("a"));
        assertEquals(
                Set.of(AlertNetwork.CLAIMS + ":a", RemoteCommands.CLAIMS + ":a"),
                ((Map<?, ?>) host.shared(SharedClaims.KEY, ConcurrentHashMap::new)).keySet());
    }

    @Test
    void anExpiredClaimSweptByAnotherGenerationMidClaimIsStillWon() {
        Map<String, Long> racing = new ConcurrentHashMap<>() {
            @Override
            public boolean replace(String key, Long oldValue, Long newValue) {
                remove(key);
                return super.replace(key, oldValue, newValue);
            }
        };
        HostContext host = mock(HostContext.class);
        when(host.shared(any(), any())).thenReturn(racing);
        SharedClaims claims = new SharedClaims(host, AlertNetwork.CLAIMS, clock::get);

        assertTrue(claims.claim("a"));
        clock.addAndGet(EnvelopeCodec.REPLAY_WINDOW_MILLIS);
        assertTrue(claims.claim("b"));
        clock.addAndGet(1);
        assertTrue(claims.claim("a"));
        assertFalse(claims.claim("a"));
    }

    @Test
    void withoutAHostEachHasItsOwn() {
        assertTrue(new SharedClaims(null, AlertNetwork.CLAIMS, clock::get).claim("a"));
        assertTrue(new SharedClaims(null, AlertNetwork.CLAIMS, clock::get).claim("a"));
    }
}
