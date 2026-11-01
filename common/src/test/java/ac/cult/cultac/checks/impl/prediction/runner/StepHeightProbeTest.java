package ac.cult.cultac.checks.impl.prediction.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StepHeightProbeTest {
    private static final double MAX_UP_STEP = 0.6D;

    private static double probedStepHeight(double packetY, double attemptedMinY, double stuckY) {
        return SimulationProcessor.stepHeightProbeY(packetY / stuckY, attemptedMinY, stuckY, MAX_UP_STEP) * stuckY;
    }

    @Test
    void probesAtThePacketStepHeightWithoutStuckSpeed() {
        assertEquals(0.375D, probedStepHeight(0.375D, -0.0784D, 1.0D), 1.0E-12D);
    }

    @Test
    void probesAtThePacketStepHeightUnderStuckSpeed() {
        assertEquals(0.5D, probedStepHeight(0.5D, -0.0784D / 0.75D, 0.75D), 1.0E-12D);
        assertEquals(0.1D, probedStepHeight(0.1D, -0.0784D / 0.05D, 0.05D), 1.0E-12D);
        assertEquals(0.5D, probedStepHeight(0.5D, -0.0784D / 1.5D, 1.5D), 1.0E-12D);
    }

    @Test
    void boundsTheStepHeightByTheUnscaledMaxUpStep() {
        assertEquals(MAX_UP_STEP, probedStepHeight(1.2D, -0.0784D, 1.0D), 1.0E-12D);
        assertEquals(MAX_UP_STEP, probedStepHeight(0.7D, -0.0784D / 0.75D, 0.75D), 1.0E-12D);
    }

    @Test
    void boundsADescentByTheAttemptedMovement() {
        assertEquals(-0.3D, probedStepHeight(-1.0D, -0.3D, 1.0D), 1.0E-12D);
        assertEquals(-0.3D, probedStepHeight(-1.0D, -0.3D / 0.75D, 0.75D), 1.0E-12D);
    }
}
