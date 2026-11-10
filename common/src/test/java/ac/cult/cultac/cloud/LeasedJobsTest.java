package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LeasedJobsTest {
    @Test
    void aPollWaitMovesByUpToAFifthEitherWay() {
        assertEquals(8, LeasedJobs.spread(10, 0));
        assertEquals(10, LeasedJobs.spread(10, 0.5));
        assertEquals(12, LeasedJobs.spread(10, 0.999));
        assertEquals(240, LeasedJobs.spread(300, 0));
        assertEquals(360, LeasedJobs.spread(300, 0.999));
    }

    @Test
    void aPollWaitIsNeverUnderASecond() {
        assertEquals(1, LeasedJobs.spread(1, 0));
        assertEquals(1, LeasedJobs.spread(0, 0.5));
    }
}
