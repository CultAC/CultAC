package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.enums.Pose;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CollisionModifierTest {
    @Test
    public void ceilingClipAtExactGapIsApplied() {
        // MCP-Reborn 1.21.11: the standing player box is 1.8f tall, so the ceiling
        // block at y = 27 clips a +0.42 jump to 27.0 - (25.0 + 1.8f).
        double trueClip = 27.0D - (25.0D + 1.8F);
        // CollisionModifier.expandUpwards raises the unknown-height box by
        // (maxHeight - initialHeight) - COLLISION_EPSILON, so the probe reports a
        // clip as trueClip + COLLISION_EPSILON. This is the exact value the probe
        // produced in the flagged tick, and subtracting the epsilon back rounds
        // 1.17E-15 above trueClip: a plain epsilon comparison drops the clamp.
        double probeClip = 0.200000147683717D;
        assertFalse(trueClip >= probeClip - SimpleCollisionBox.COLLISION_EPSILON);

        PredVector start = new PredVector(
                new Vec3(-0.26878598925804453D, 0.41999998688697815D, -0.2671391848841353D));
        CollideAxisData collide = new CollideAxisData(
                new CollideAxisData.CollideResult(true, -0.2167751367791848D),
                new CollideAxisData.CollideResult(true, probeClip),
                null,
                new CollideAxisData.CollideResult(false, -0.3556917605745653D),
                new ArrayList<>());

        PredVector clipped = CollisionModifier.transformWithCollisions(
                context(), collide, start,
                new Vec3(-0.2167751367791848D, trueClip, -0.3556917605745653D));

        assertEquals(probeClip, clipped.y, 1.0E-9D);
        assertTrue("ceiling clip must not keep the unclipped jump velocity", clipped.y < 0.21D);
    }

    @Test
    public void ceilingClipIsNotAppliedToDownwardPacketMovement() {
        // The same ceiling must not rescue a candidate whose packet moves down.
        PredVector start = new PredVector(new Vec3(0.0D, 0.41999998688697815D, 0.0D));
        CollideAxisData collide = new CollideAxisData(
                new CollideAxisData.CollideResult(false, 0.0D),
                new CollideAxisData.CollideResult(true, 0.200000147683717D),
                null,
                new CollideAxisData.CollideResult(false, 0.0D),
                new ArrayList<>());

        PredVector result = CollisionModifier.transformWithCollisions(
                context(), collide, start, new Vec3(0.0D, -0.0784000015258789D, 0.0D));

        assertEquals(0.41999998688697815D, result.y, 1.0E-12D);
    }

    @Test
    public void floorClipIsAppliedAtExactGap() {
        double fall = -0.5D;
        double clip = -0.3216D;
        PredVector start = new PredVector(new Vec3(0.0D, fall, 0.0D));
        CollideAxisData collide = new CollideAxisData(
                new CollideAxisData.CollideResult(false, 0.0D),
                null,
                new CollideAxisData.CollideResult(true, clip),
                new CollideAxisData.CollideResult(false, 0.0D),
                new ArrayList<>());

        PredVector result = CollisionModifier.transformWithCollisions(
                context(), collide, start, new Vec3(0.0D, clip, 0.0D));

        assertEquals(clip, result.y, 1.0E-9D);
    }

    private static SimulationContext context() {
        return new SimulationContext(
                Vec3.ZERO, Vec3.ZERO, Vec3.ZERO,
                ClientVersion.V_1_21_11, null, null, null,
                0.0F, 0.0F, 0.0F, 0.0F,
                true, false, false, false,
                null, 0.0F, 0, DesyncStatus.FALSE, 0, 0, 0,
                null, false, null, false, null, null, Pose.STANDING, 1.0F);
    }
}
