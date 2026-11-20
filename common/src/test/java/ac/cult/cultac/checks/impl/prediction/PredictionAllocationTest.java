package ac.cult.cultac.checks.impl.prediction;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

class PredictionAllocationTest {
    @Test
    void scalarConstructionRetainsLineageFlagsAndModifierCopyOnWrite() {
        var root = new PredVector(new Vec3(-0.0, .2, -.3));
        root.isJump = true;
        root.isBoundedStep = true;
        root.setTickSkip(true);
        root.setAttackSlows(2);
        var first = new TransactionVel(new Vec3(1, 2, 3), 1, true, false);
        root.addPacketModifier(first);
        var child = root.withXYZ(-0.0, .2, -.3, "child");
        assertNotSame(root, child);
        assertSame(root, child.lastVector);
        assertTrue(child.isJump());
        assertTrue(child.isKnockback());
        assertTrue(child.isBoundedStep());
        assertTrue(child.isTickSkip());
        assertEquals(2, child.getAttackSlows());
        assertTrue(child.hasReason("child"));
        assertSame(root.packetModifiers, child.packetModifiers);
        var second = new TransactionVel(Vec3.ZERO, 2, true, false);
        child.addPacketModifier(second);
        assertNotSame(root.packetModifiers, child.packetModifiers);
        assertFalse(root.hasPacketModifier(second));
        assertTrue(child.hasPacketModifier(first));
        var clone = child.clone();
        assertSame(root, clone.lastVector);
        assertEquals("clone", clone.reason);
        bits(-0.0, root.withX(-0.0, "x").x);
        bits(.2 + .1, root.add(0, .1, 0, "add").y);
        bits(-.3 * .6, root.multiply(.6, "scale").z);
    }

    @Test
    void traceReuseRequiresIdenticalInputsWithoutLosingProvenance() {
        var root = new PredVector(new Vec3(1, 2, 3));
        var trace = MovementTrace.start(root);
        assertSame(trace, trace.withPosition(root));
        assertSame(trace, trace.withPreCollisionMovement(root));
        assertSame(trace, trace.withPreCollisionVelocity(root));
        var equal = root.withXYZ(1, 2, 3, "different lineage");
        var changed = trace.withPosition(equal);
        assertNotSame(trace, changed);
        assertSame(equal, changed.position());
        assertSame(trace.preCollisionMovement(), changed.preCollisionMovement());
        assertSame(trace.preCollisionVelocity(), changed.preCollisionVelocity());
        assertNotSame(trace, trace.withPreCollisionMovement(new Vec3(1, 2, 3)));
    }

    private static void bits(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }
}
