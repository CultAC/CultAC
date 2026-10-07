package ac.cult.cultac.network.packet;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntityPositionPathTest {
    @Test
    void zeroDeltaKeepsTheBaseAndSignedZeros() {
        Vec3 base = new Vec3(-0.0, 64.1, -0.0);
        var path = EntityPositionPath.decodeRelative(new EntityDelta.Linear((short) 0, (short) 0, (short) 0), base);
        assertSame(base, path.endPosition());
        assertTrue(path.steps().isEmpty());
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(path.endPosition().x));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(path.endPosition().z));
    }

    @Test
    void steppedDeltasAdvanceFromThePreviousStepAndKeepUnchangedAxes() {
        Vec3 base = new Vec3(0.25, 64.1, -3.75);
        var path = EntityPositionPath.decodeRelative(
                new EntityDelta.Stepped(List.of(
                        new EntityDelta.Step((short) 4096, (short) 0, (short) -2048, 2),
                        new EntityDelta.Step((short) 0, (short) 1, (short) 0, 5))),
                base);
        assertEquals(
                List.of(
                        new EntityPositionPath.Step(new Vec3(1.25, 64.1, -4.25), 2),
                        new EntityPositionPath.Step(new Vec3(1.25, 262555.0 / 4096.0, -4.25), 5)),
                path.steps());
        assertEquals(path.steps().get(1).position(), path.endPosition());
        assertEquals(new Vec3(0.25, 64.1, -3.75), base);
    }
}
