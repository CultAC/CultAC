package ac.cult.cultac.cloud.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AttributionTest {

    private static final long RECORDING = 7;
    private static final long DISPATCH = 9;

    @Test
    void recorderThreadsArePerfNotOwn() {
        assertEquals(Attribution.PERF, Attribution.threadGroup("cult-perf", RECORDING, RECORDING, DISPATCH));
        assertEquals(Attribution.PERF, Attribution.threadGroup("JFR Event Stream", DISPATCH, RECORDING, DISPATCH));
        assertEquals(Attribution.OWN, Attribution.threadGroup("cult-cloud-x", 3, RECORDING, DISPATCH));
        assertEquals(Attribution.OTHER, Attribution.threadGroup("JFR Event Stream", DISPATCH, RECORDING, -1));
        assertEquals(Attribution.OTHER, Attribution.threadGroup(null, -1, RECORDING, -1));
        assertEquals("perf", Attribution.GROUPS.get(Attribution.PERF));
    }

    @Test
    void groupsThreadsByName() {
        assertEquals(Attribution.TICK, Attribution.threadGroup("Server thread"));
        assertEquals(Attribution.NETTY, Attribution.threadGroup("Netty Epoll Server IO #2"));
        assertEquals(Attribution.OWN, Attribution.threadGroup("cult-cloud-diag-1"));
        assertEquals(Attribution.OTHER, Attribution.threadGroup("Craft Scheduler Thread - 1"));
        assertEquals(Attribution.OTHER, Attribution.threadGroup(null));
    }
}
