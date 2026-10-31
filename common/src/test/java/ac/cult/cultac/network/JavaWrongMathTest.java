package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMathRouter;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementMath;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class JavaWrongMathTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final double FRICTION = 0.6F * 0.91F;
    // Independent oracle: OptiFine 1.20.1 Mth's table/indexing, not the engine's FastMath helpers.
    // https://github.com/0xFruzz/Extazyy-1.20.1/blob/master/src/main/java/net/minecraft/util/Mth.java
    private static final float[] FAST = new float[4096];

    static {
        for (int i = 0; i < FAST.length; i++)
            FAST[i] = (float) ((double) Math.round(Math.sin((double) i * Math.PI * 2.0D / 4096.0D) * 1.0E8D) / 1.0E8D);
    }

    @Test
    void aLearnedProfileDoesNotEvaluateTheOtherTableOnSteadyMovement() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.trigHandler = spy(f.player.trigHandler);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            Vec3 velocity = Vec3.ZERO;
            f.place(position, new Vec3(0, -0.08D * 0.98F, 0));
            f.input(FORWARD);
            f.player.xRot = 17.293F;
            for (int tick = 0; tick < 12; tick++) {
                clearInvocations(f.player.trigHandler);
                Vec3 delta = velocity.add(forward(ClientVersion.V_26_3, f.player.xRot, 0.1F, true));
                position = position.add(delta.x, 0, delta.z);
                var result = f.tick(position, true);
                assertFalse(result.hasEffectiveFlags(), describe(result));
                assertEquals(0.0D, result.getOffset(), 1.0E-13D, describe(result));
                assertFalse(f.player.trigHandler.isPreferredVanillaMath());
                assertNull(f.player.trigHandler.getPredictionMath());
                if (tick > 0) {
                    verify(f.player.trigHandler, times(1)).setPredictionMath(Boolean.FALSE);
                    verify(f.player.trigHandler, never()).setPredictionMath(Boolean.TRUE);
                }
                velocity = new Vec3(delta.x * FRICTION, 0, delta.z * FRICTION);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_4", "V_1_21_11", "V_26_3"})
    void firstFastMathMoveAndPerTickModeChangesAreExact(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, version)) {
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            Vec3 velocity = Vec3.ZERO;
            f.place(position, new Vec3(0, -0.08D * 0.98F, 0));
            f.input(FORWARD);
            float[] yaws = {17.293F, -33.713F, 90.0F, 1380.5804F, -128.31F, 0.0F, 29.13F, 83.3F};
            for (int tick = 0; tick < yaws.length; tick++) {
                f.player.xRot = yaws[tick];
                Vec3 delta = velocity.add(forward(version, yaws[tick], 0.1F, tick % 2 == 0));
                position = position.add(delta.x, 0.0D, delta.z);
                var result = f.tick(position, true);
                assertFalse(result.hasEffectiveFlags(), describe(result));
                assertEquals(0.0D, result.getOffset(), 1.0E-13D, describe(result));
                assertTrue(f.player.trigHandler.isVanillaMath(), "candidate table leaked into the next tick");
                velocity = new Vec3(delta.x * FRICTION, 0.0D, delta.z * FRICTION);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sprintJumpUsesOneTableForImpulseAndInput(boolean fast) throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(start, new Vec3(0, -0.08D * 0.98F, 0));
            f.player.xRot = 17.293F;
            f.command(PlayerCommandAction.START_SPRINTING);
            f.input(new ServerboundPlayerInput(true, false, false, false, true, false, true));
            float speed = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
            Vec3 input = forward(ClientVersion.V_26_3, f.player.xRot, speed, fast);
            float angle = f.player.xRot * ((float) Math.PI / 180F);
            float sin = fast ? fastSin(angle) : VanillaMath.sin(ClientVersion.V_26_3, angle);
            float cos = fast ? fastCos(angle) : VanillaMath.cos(ClientVersion.V_26_3, angle);
            Vec3 impulse = new Vec3(-sin * 0.2D, 0.42F, cos * 0.2D);
            var result = f.tick(start.add(input).add(impulse), false);
            assertFalse(result.hasEffectiveFlags(), describe(result));
            assertEquals(0.0D, result.getOffset(), 1.0E-13D, describe(result));
        }
    }

    @Test
    void fastMathCannotSupplyExtraInputSpeed() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(start, Vec3.ZERO);
            f.input(FORWARD);
            f.player.xRot = -33.713F;
            var result = f.tick(start.add(forward(ClientVersion.V_26_3, f.player.xRot, 0.2F, true)), true);
            assertTrue(result.hasEffectiveFlags(), describe(result));
        }
    }

    @Test
    void aHybridSprintJumpHasNoExactCandidate() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(start, Vec3.ZERO);
            f.player.xRot = 17.293F;
            f.command(PlayerCommandAction.START_SPRINTING);
            f.input(new ServerboundPlayerInput(true, false, false, false, true, false, true));
            float speed = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
            Vec3 input = forward(ClientVersion.V_26_3, f.player.xRot, speed, true);
            float angle = f.player.xRot * ((float) Math.PI / 180F);
            Vec3 vanillaJump = new Vec3(
                    -VanillaMath.sin(ClientVersion.V_26_3, angle) * 0.2D,
                    0.42F,
                    VanillaMath.cos(ClientVersion.V_26_3, angle) * 0.2D);
            var result = f.tick(start.add(input).add(vanillaJump), false);
            assertTrue(result.getOffset() > 1.0E-6D, "a fabricated combination became an exact movement");
        }
    }

    @Test
    void inverseDoesNotAssumeAUnitLengthTrigPair() {
        Vec3 input = new Vec3(0.3D, 0, 0.7D);
        float sin = 0.2F, cos = 0.4F;
        Vec3 solved = JavaMathRouter.inverse(input.x * cos - input.z * sin, input.z * cos + input.x * sin, sin, cos);
        assertEquals(0.0D, input.distanceTo(solved), 1.0E-15D);
        assertNull(JavaMathRouter.inverse(1, 1, 0, 0));
    }

    @Test
    void routerDiscardsOnlySiblingsCollisionsCannotReach() {
        var context = context(new Vec3(1, 0, 1));
        var exact = candidate(true, new Vec3(1, 0, 1));
        var shortAxis = candidate(false, new Vec3(0.9, 0, 1.1));
        var clippable = candidate(false, new Vec3(1.1, 0, 1.1));
        var inputs = new ArrayList<>(List.of(shortAxis, clippable, exact));
        var routed = JavaMathRouter.route(context, inputs);
        assertEquals(2, routed.size());
        assertSame(clippable, routed.getFirst());
        assertSame(exact, routed.getLast());
        when(context.isWithheldPosition()).thenReturn(true);
        assertSame(inputs, JavaMathRouter.route(context, inputs));
    }

    @Test
    void tableScopeRestoresAfterAnException() throws Exception {
        try (var f = new JavaTickFixture()) {
            var fast = new JavaMovementMath(new PredVector(Vec3.ZERO), false);
            assertThrows(
                    IllegalStateException.class,
                    () -> JavaMovementMath.calculate(f.player, fast, () -> {
                        assertFalse(f.player.trigHandler.isVanillaMath());
                        throw new IllegalStateException("test");
                    }));
            assertTrue(f.player.trigHandler.isVanillaMath());
        }
    }

    private static SimulationContext context(Vec3 target) {
        var context = mock(SimulationContext.class);
        var world = mock(WorldData.class);
        when(context.getWorldData()).thenReturn(world);
        when(world.getClimbingAtStart()).thenReturn(DesyncStatus.FALSE);
        when(context.getVersion()).thenReturn(ClientVersion.V_26_3);
        when(context.getTarget()).thenReturn(target);
        when(context.getStart()).thenReturn(new Vec3(0.5, FLOOR_Y, 0.5));
        Vec3 end = context.getStart().add(target);
        when(context.getEnd()).thenReturn(end);
        return context;
    }

    private static PredVector candidate(boolean vanilla, Vec3 input) {
        return new JavaMovementMath(new PredVector(Vec3.ZERO), vanilla).withTravelInput(input);
    }

    private static Vec3 forward(ClientVersion version, float yaw, float speed, boolean fast) {
        float angle = yaw * ((float) Math.PI / 180F);
        float sin = fast ? fastSin(angle) : VanillaMath.sin(version, angle);
        float cos = fast ? fastCos(angle) : VanillaMath.cos(version, angle);
        double amount = (double) 0.98F * speed;
        return new Vec3(-amount * sin, 0, amount * cos);
    }

    private static float fastSin(float angle) {
        return FAST[(int) (angle * 651.8986F) & 4095];
    }

    private static float fastCos(float angle) {
        return FAST[(int) (angle * 651.8986F + 1024.0F) & 4095];
    }
}
