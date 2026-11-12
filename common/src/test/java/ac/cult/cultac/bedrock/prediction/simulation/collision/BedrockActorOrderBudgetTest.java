package ac.cult.cultac.bedrock.prediction.simulation.collision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import ac.cult.cultac.bedrock.prediction.api.BedrockActorOrderRange;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import org.junit.Test;

public final class BedrockActorOrderBudgetTest {
    private static final Vec3d ZERO = Vec3d.ZERO;
    private static final BedrockCollisionClipper.BufferedEnds NONE = BedrockCollisionClipper.BufferedEnds.NONE;

    @Test
    public void threeAxesShareOneDistanceBudget() {
        var range = new BedrockActorOrderRange(-.05, -.05, -.05, .05, .05, .05, 7, .05, null);
        var claimed = new Vec3d(.04, .04, .04);
        var selected = range.closest(ZERO, claimed);
        assertEquals(.05, selected.length(), 1e-12);
        assertEquals(.05 / Math.sqrt(3), selected.x(), 1e-12);
        assertEquals(.05, range.buffered(ZERO, claimed), 1e-12);
    }

    @Test
    public void aBlockBoundLeavesTheRemainingBudgetForAnotherAxis() {
        var range = new BedrockActorOrderRange(0, 0, 0, .01, .05, 0, 3, .05, null);
        var selected = range.closest(ZERO, new Vec3d(1, 1, 0));
        assertEquals(.01, selected.x(), 1e-12);
        assertEquals(Math.sqrt(.05 * .05 - .01 * .01), selected.y(), 1e-12);
        assertEquals(.05, range.buffered(ZERO, selected), 1e-12);
    }

    @Test
    public void aValidDiagonalEndIsKeptExactly() {
        var range = new BedrockActorOrderRange(-.05, 0, -.05, .05, 0, .05, 5, .05, null);
        var valid = new Vec3d(.03, 0, .04);
        assertEquals(valid, range.closest(ZERO, valid));
        assertEquals(.05, range.buffered(ZERO, valid), 1e-12);
    }

    @Test
    public void aDepletedPoolStillHasOneSharedBudget() {
        var range = new BedrockActorOrderRange(-.01, 0, -.01, .01, 0, .01, 5, .01, null);
        var selected = range.closest(ZERO, new Vec3d(.009, 0, .009));
        assertEquals(.01, selected.length(), 1e-12);
        assertEquals(.01, range.buffered(ZERO, selected), 1e-12);
    }

    @Test
    public void theActorOrderRangeDoesNotBorrowFromThePool() {
        var order = new BedrockActorOrderRange(-.2, 0, 0, .2, 0, 0);
        var range = new BedrockActorOrderRange(-.25, 0, 0, .25, 0, 0, 1, .05, order);
        var valid = new Vec3d(.2, 0, 0);
        assertEquals(valid, range.closest(ZERO, valid));
        assertEquals(0, range.buffered(ZERO, valid), 0);
    }

    @Test
    public void onlyTheDistanceBeyondActorOrderingIsCharged() {
        var order = new BedrockActorOrderRange(-.2, 0, 0, .2, 0, 0);
        var range = new BedrockActorOrderRange(-.25, -.05, -.05, .25, .05, .05, 7, .05, order);
        var valid = new Vec3d(.22, .01, .01);
        assertEquals(valid, range.closest(ZERO, valid));
        assertEquals(Math.sqrt(.02 * .02 + .01 * .01 + .01 * .01), range.buffered(ZERO, valid), 1e-12);
    }

    @Test
    public void laterCollisionPhasesDoNotMultiplyTheBudget() {
        var ends = new BedrockCollisionClipper.BufferedEnds(-.05F, .05F);
        var one = BedrockActorOrderCrossing.buffered(null, 1, ends, NONE, NONE, .05F);
        var range = BedrockActorOrderCrossing.then(one, one).range(0);
        assertNotNull(range);
        assertEquals(.05, range.closest(ZERO, new Vec3d(.09, 0, 0)).x(), 1e-8);
        assertEquals(.05, range.buffered(ZERO, new Vec3d(.09, 0, 0)), 1e-8);
    }

    @Test
    public void freeOrderAndBudgetBothSurviveLaterPhases() {
        var order = new BedrockActorOrderCrossing(1, 0, 0, -.2F, 0, 0, .2F, 0, 0, 0);
        var x = BedrockActorOrderCrossing.buffered(
                order, 1, new BedrockCollisionClipper.BufferedEnds(-.25F, .25F), NONE, NONE, .05F);
        var y = BedrockActorOrderCrossing.buffered(
                null, 2, NONE, new BedrockCollisionClipper.BufferedEnds(-.05F, .05F), NONE, .05F);
        var range = BedrockActorOrderCrossing.then(x, y).range(0);
        assertNotNull(range);
        var selected = range.closest(ZERO, new Vec3d(.24, .04, 0));
        assertEquals(.2 + .05 / Math.sqrt(2), selected.x(), 1e-8);
        assertEquals(.05 / Math.sqrt(2), selected.y(), 1e-8);
        assertEquals(.05, range.buffered(ZERO, selected), 1e-8);
    }
}
