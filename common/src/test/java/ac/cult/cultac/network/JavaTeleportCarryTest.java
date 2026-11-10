package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.CultMath;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** PositionMoveRotation#calculateAbsolute applies the position packet's delta transform to every stored velocity. */
class JavaTeleportCarryTest {
    private static final Vec3 TARGET = new Vec3(2.5, 64, 2.5);
    private static final Vec3 POINT = new Vec3(0.2, -0.1, 0.3);
    private static final Vec3 STUCK = new Vec3(0.25, 0.05, 0.25);

    @Test
    void absoluteMotionReplacesTheVelocityAndKeepsTheRestOfTheState() throws Exception {
        try (var f = new JavaTickFixture()) {
            carry(f, POINT, true);
            f.playerPosition(TARGET, Vec3.ZERO, Set.of());
            JavaPredictionCarry carry = carried(f);
            assertEquals(Vec3.ZERO, carry.states().getFirst().velocity());
            assertEquals(3.0, carry.states().getFirst().fallDistance());
            assertEquals(STUCK, carry.states().getFirst().stuckSpeed());
            assertEquals(Set.of(Vec3.ZERO), f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());
        }
    }

    @Test
    void relativeMotionKeepsOnlyTheInheritedAxes() throws Exception {
        try (var f = new JavaTickFixture()) {
            carry(f, POINT, true);
            f.playerPosition(TARGET, new Vec3(0.5, 0.6, 0.7), Set.of(Relative.DELTA_X, Relative.DELTA_Z));
            Vec3 point = new Vec3(POINT.x + 0.5, 0.6, POINT.z + 0.7);
            JavaPredictionCarry carry = carried(f);
            assertEquals(point, carry.states().getFirst().velocity());
            assertEquals(Set.of(point), f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());
        }
    }

    @Test
    void rotatingMotionRotatesTheCarriedVelocity() throws Exception {
        try (var f = new JavaTickFixture()) {
            carry(f, POINT, true);
            int flags = RelativeFlag.DELTA_X.getMask() | RelativeFlag.DELTA_Y.getMask()
                    | RelativeFlag.DELTA_Z.getMask() | RelativeFlag.ROTATE_DELTA.getMask();
            f.teleport(f.player.sendTrailingTransaction(), TARGET, new RelativeFlag(flags), 0, 45);
            var state = carried(f).states().getFirst();
            // Vec3#yRot uses vanilla's float-angle trig table, including its adjacent indices at -45 degrees.
            double cosine = CultMath.cos((float) Math.toRadians(-45));
            double sine = CultMath.sin((float) Math.toRadians(-45));
            assertEquals(0.2 * cosine + 0.3 * sine, state.velocity().x, 1e-12);
            assertEquals(0.3 * cosine - 0.2 * sine, state.velocity().z, 1e-12);
        }
    }

    private static void carry(JavaTickFixture f, Vec3 point, boolean states) throws Exception {
        Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
        f.place(new Vec3(0.5, 64, 0.5), point);
        var carry = new JavaPredictionCarry(
                f.player.compensatedEntities.getSelf(),
                3.0,
                states ? List.of(new JavaPredictionCarry.State(point, 3.0, STUCK)) : List.of());
        setField(SimulationProcessor.class, f.player.checkManager.getSimulationProcessor(), "profileCarry", carry);
    }

    private static JavaPredictionCarry carried(JavaTickFixture f) {
        return (JavaPredictionCarry) f.player.checkManager.getSimulationProcessor().getCurrentPredictionCarry();
    }
}
