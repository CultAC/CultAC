package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.SneakEdgeClient.EDGE_X;
import static ac.cult.cultac.network.SneakEdgeClient.SNEAK;
import static ac.cult.cultac.network.SneakEdgeClient.SNEAK_ALONG_EDGE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Sneaking holds at an edge with the keys on a diagonal the edge does not line up with. From a carried start,
 * Entity#move moves the client's deltaMovement plus the input through Player#maybeBackOffFromEdge, so the input the
 * position shows is the move less the start less the back-off; one 0.01 faster flags.
 */
class JavaSneakEdgeTurnTest {
    // Forward and left at this yaw lean the diagonal a little toward the edge at +x.
    private static final float TOWARD_EDGE_YAW = 34.0F;
    // At this yaw the same keys lean the diagonal away from the edge.
    private static final float AWAY_FROM_EDGE_YAW = 60.0F;
    private static final int HELD = 6;

    enum Shape {
        // The back-off takes the input's x off every held tick's move.
        BACKED_OFF_DIAGONAL(TOWARD_EDGE_YAW),
        // The turned tick's move leaves the edge, so the back-off takes nothing off it.
        TURNED_OFF_THE_EDGE(AWAY_FROM_EDGE_YAW);

        private final float lastYaw;

        Shape(float lastYaw) {
            this.lastYaw = lastYaw;
        }
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void diagonalHoldIsExact(Shape shape) throws Exception {
        try (var f = new JavaTickFixture()) {
            SneakEdgeClient client = hold(f);
            f.player.xRot = shape.lastYaw;
            PredictionResult last = client.tick(SNEAK_ALONG_EDGE, true, 0.0);
            assertTrue(exact(last), "last " + describe(last));
            for (SneakEdgeClient.Tick tick : client.ticks) assertTrue(exact(tick.result()), describe(tick.result()));
        }
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void diagonalHoldPastTheEdgeIsFlagged(Shape shape) throws Exception {
        try (var f = new JavaTickFixture()) {
            SneakEdgeClient client = hold(f);
            f.player.xRot = shape.lastYaw;
            PredictionResult last = client.tick(SNEAK_ALONG_EDGE, true, 0.01);
            assertFalse(exact(last), describe(last));
        }
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void diagonalHoldIsCarried(Shape shape) throws Exception {
        try (var f = new JavaTickFixture()) {
            SneakEdgeClient client = hold(f);
            for (int i = 1; i < client.ticks.size(); i++) {
                SneakEdgeClient.Tick tick = client.ticks.get(i);
                assertTrue(tick.result().isSneakEdgeCarried(), "tick " + i + " " + describe(tick.result()));
            }
        }
    }

    private static SneakEdgeClient hold(JavaTickFixture f) {
        SneakEdgeClient client = new SneakEdgeClient(f, EDGE_X);
        f.player.xRot = TOWARD_EDGE_YAW;
        client.tick(SNEAK, true, 0.0);
        for (int i = 0; i < HELD; i++) client.tick(SNEAK_ALONG_EDGE);
        return client;
    }
}
