package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockTickInput;
import ac.cult.cultac.bedrock.prediction.integration.BedrockMovementInputFactory.Input;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.HoneySlideState;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CollisionModifier;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.InsideBlock;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.StepTransform;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHandler;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.CollideAxisData.CollideResult;
import ac.cult.cultac.utils.latency.CompensatedFireworks;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public final class BedrockUncertaintyPipelineTest {
    private static final Vec3 UNIT_SCALE = new Vec3(1.0, 1.0, 1.0);

    @Test
    public void handlerOrderUsesOnlySharedJavaHandlers() {
        List<Class<? extends UncertaintyHandler>> classes = BedrockMovementEngine.UNCERTAINTY_HANDLERS.stream()
                .<Class<? extends UncertaintyHandler>>map(UncertaintyHandler::getClass)
                .toList();
        Assert.assertEquals(List.of(CollisionModifier.class, StepTransform.class), classes);
        Assert.assertThrows(
                UnsupportedOperationException.class,
                () -> BedrockMovementEngine.UNCERTAINTY_HANDLERS.add(new InsideBlock()));
    }

    @Test
    public void bedrockEngineUsesStandardRunnerAndPreservesNoOpTrace() {
        BedrockMovementState previous = BedrockMovementState.fromPhysicalFeet(
                Vec3d.ZERO, Vec3d.ZERO, BedrockInputFrame.idle(0L), BedrockCollisionFlags.AIR);
        BedrockMovementState predicted = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(0.0, 0.1, 0.0), Vec3d.ZERO, BedrockInputFrame.idle(1L), BedrockCollisionFlags.AIR);
        BedrockMovementContext movementContext = airContext();
        BedrockMovementResult movementResult = new BedrockMovementResult(
                previous,
                movementContext,
                movementContext,
                predicted,
                new Vec3d(0.0, -0.2, 0.0),
                Vec3d.ZERO,
                false,
                false,
                false,
                false,
                BlockMovementSlowdownState.NONE,
                HoneySlideState.NONE,
                false,
                false,
                0.0,
                1.0,
                false,
                false,
                false,
                0.0,
                true,
                false);
        BedrockInputFrame inputFrame = BedrockInputFrame.idle(0L);
        Input input = new Input(
                BedrockAuthInputFrame.builder(UUID.randomUUID()).build(),
                previous,
                new BedrockTickInput(inputFrame, Vec3d.ZERO, 0L),
                inputFrame.intent(),
                BedrockWorldSnapshot.fromContext(movementContext),
                0.0,
                BedrockMobJumpComponentState.DEFAULT);
        BedrockPredVector start = new BedrockPredVector(
                input,
                movementResult,
                BedrockMobJumpComponentState.DEFAULT,
                new PredVector(Vec3.ZERO),
                new Vec3(0.0, 0.1, 0.0));
        CultPlayer player = playerWithFireworks();
        BedrockUncertaintyPipelineTest.TestContext test =
                context(Vec3.ZERO, false, new Vec3(1.0, 1.0, 1.0));
        PredictionResult result = result(start, test.context(), Vec3.ZERO, neutralCollisions());
        ValidMovements valid = BedrockMovementEngine.INSTANCE.createValidMovements(start, player, result);
        MovementTrace trace = valid.transformTraceTowards(Vec3.ZERO, null);
        Assert.assertEquals(ValidMovements.class, valid.getClass());
        Assert.assertSame(start, trace.position());
        Assert.assertEquals(Vec3.ZERO, trace.positionOnlyDelta());
        Assert.assertEquals(Vec3.ZERO, trace.collisionBaseOffset());
        Assert.assertEquals(-0.2, start.collisionIgnoredExtents(Vec3.ZERO).minY, 0.0);

        player.debugUncertainty = new Vec3(0.0, 0.05, 0.0);
        valid.computeClosest(null);
        valid.applyDebugUncertainty();
        MovementTrace debug = valid.getClosestTrace();
        Assert.assertEquals(0.05, debug.position().y, 1.0E-12);
        Assert.assertSame(start, debug.preCollisionVelocity());
        Assert.assertSame(start, debug.position().firstInLineage(BedrockPredVector.class));
        Assert.assertEquals(-0.05, debug.positionOnlyDelta().y, 1.0E-12);
    }

    @Test
    public void collisionClipsOrdinaryVerticalMovementWithoutBedrockVerticalProjection() {
        PredVector start = new PredVector(new Vec3(0.0, -0.2, 0.0));
        CollideAxisData collision = neutralCollisions();
        collision.setYNeg(new CollideResult(true, -0.1));
        BedrockUncertaintyPipelineTest.TestContext test =
                context(new Vec3(0.0, -0.1, 0.0), false, UNIT_SCALE);
        PredictionResult result = result(start, test.context(), test.target(), collision);
        ValidMovements valid = new ValidMovements(start, null, result, List.of(new CollisionModifier()));
        PredVector transformed =
                valid.transformTraceTowards(test.target(), null).position();
        Assert.assertEquals(-0.1, transformed.y, 0.0);
        Assert.assertTrue(transformed.hasReason("collide -y"));
    }

    @Test
    public void stepTransformTakesTheEngineStepWhereverThePacketIs() {
        PredVector step = new PredVector(new Vec3(0.02, 0.5, 0.01));
        PredVector stepped = new BedrockUncertaintyPipelineTest.BedrockTestVector(Vec3.ZERO, step);
        PredVector flat = new BedrockUncertaintyPipelineTest.BedrockTestVector(Vec3.ZERO, null);
        for (Vec3 target : List.of(new Vec3(0.02, 0.5, 0.01), Vec3.ZERO, new Vec3(0.02, 0.4, 0.01))) {
            BedrockUncertaintyPipelineTest.TestContext test = context(target, false, UNIT_SCALE);
            PredictionResult result = result(stepped, test.context(), target, neutralCollisions());
            ValidMovements valid = new ValidMovements(stepped, bedrockPlayer(), result, List.of(new StepTransform()));
            Assert.assertSame(step, valid.transformTraceTowards(target, null).position());
            result = result(flat, test.context(), target, neutralCollisions());
            valid = new ValidMovements(flat, bedrockPlayer(), result, List.of(new StepTransform()));
            Assert.assertSame(flat, valid.transformTraceTowards(target, null).position());
        }
    }

    @Test
    public void insideBlockIsGatedHorizontalAndLeavesVerticalUnchanged() {
        PredVector start = new PredVector(new Vec3(0.03, -0.08, -0.02));
        InsideBlock handler = new InsideBlock();
        BedrockUncertaintyPipelineTest.TestContext inactiveContext = context(Vec3.ZERO, false, UNIT_SCALE);
        BedrockUncertaintyPipelineTest.TestContext activeContext = context(Vec3.ZERO, true, UNIT_SCALE);
        Assert.assertSame(
                start,
                handler.handleUncertainty(
                        null, null, null, inactiveContext.context(), null, start, new Vec3(1.0, 1.0, -1.0)));
        PredVector active = handler.handleUncertainty(
                null, null, null, activeContext.context(), null, start, new Vec3(1.0, 1.0, -1.0));
        Assert.assertEquals(0.1, active.x, 0.0);
        Assert.assertEquals(start.y, active.y, 0.0);
        Assert.assertEquals(-0.1, active.z, 0.0);
        Assert.assertTrue(active.hasReason("block pushing"));
    }

    @Test
    public void endTickOverlapChangesPacketVelocityWithoutChangingCurrentPosition() {
        var block = new ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision(
                new ac.cult.cultac.bedrock.prediction.geometry.BlockPosition(1, 0, 0),
                "minecraft:stone",
                "minecraft:stone",
                java.util.Map.of(),
                List.of(new ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox(1, 0, 0, 2, 1, 1)));
        BedrockMovementContext movementContext =
                airContext().withBlockCollisionWorld(new BlockCollisionWorld(List.of(block)));
        BedrockMovementState previous = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(0.5, 0, 0.5), Vec3d.ZERO, BedrockInputFrame.idle(0), BedrockCollisionFlags.AIR);
        BedrockMovementResult movement = new BedrockMovementResult(
                previous,
                movementContext,
                movementContext,
                previous,
                previous.physicalFeetPosition(),
                Vec3d.ZERO,
                false,
                false,
                false,
                false,
                BlockMovementSlowdownState.NONE,
                HoneySlideState.NONE,
                false,
                false,
                0.0,
                1.0,
                false,
                false,
                false,
                0.0,
                true,
                false);
        var selected = previous.withPhysicalFeetPosition(new Vec3d(0.9, 0, 0.5), 0.16);
        // The box overlaps the block from its west side, so the push raises the resting x velocity to 0.1 westward.
        var pushed = ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick.finish(movement, selected);
        Assert.assertEquals(1, pushed.size());
        Assert.assertEquals(selected.physicalFeetPosition(), pushed.getFirst().physicalFeetPosition());
        Assert.assertEquals(-0.1F, pushed.getFirst().velocity().x(), 0);
        Assert.assertEquals(0, pushed.getFirst().velocity().z(), 0);
        var outside = ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick.finish(movement, previous);
        Assert.assertEquals(0, outside.getFirst().velocity().x(), 0);
        Assert.assertEquals(0, outside.getFirst().velocity().z(), 0);
        Assert.assertEquals(
                outside.getFirst().velocity().y(), pushed.getFirst().velocity().y(), 0);
        Vec3 reported = new Vec3(-0.1F, pushed.getFirst().velocity().y(), 0);
        var observation = BedrockMovementObservationFactory.fromForwardSelection(
                movement, new Vec3(0.9, 0, 0.5), selected, reported, null);
        Assert.assertEquals(0, observation.velocityOffset(), 1.0E-12);
        var claimedOverlap = BedrockMovementObservationFactory.fromForwardSelection(
                movement, new Vec3(0.9, 0, 0.5), previous, reported, null);
        Assert.assertEquals(0.1, claimedOverlap.velocityOffset(), 1.0E-7);
    }

    private static CultPlayer bedrockPlayer() {
        CultPlayer player = Mockito.mock(CultPlayer.class);
        Mockito.when(player.isBedrockMovement()).thenReturn(true);
        return player;
    }

    private static CultPlayer playerWithFireworks() {
        CultPlayer player = (CultPlayer) Mockito.mock(CultPlayer.class);

        try {
            Field field = CultPlayer.class.getDeclaredField("compensatedFireworks");
            field.setAccessible(true);
            field.set(player, new CompensatedFireworks(player));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("failed to install fireworks", exception);
        }

        return player;
    }

    private static BedrockMovementContext airContext() {
        return new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, BlockCollisionWorld.EMPTY),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
    }

    private static BedrockUncertaintyPipelineTest.TestContext context(
            Vec3 target, boolean mightBeInBlock, Vec3 stuckSpeed) {
        WorldData world = (WorldData) Mockito.mock(WorldData.class);
        Mockito.when(world.isMightBeInBlock()).thenReturn(mightBeInBlock);
        SimulationContext context = (SimulationContext) Mockito.mock(SimulationContext.class);
        Mockito.when(context.getWorldData()).thenReturn(world);
        Mockito.when(context.getTarget()).thenReturn(target);
        Mockito.when(context.getTargetScalarHoriz()).thenReturn(1.0);
        Mockito.when(context.getTargetScalarVert()).thenReturn(1.0);
        Mockito.when(context.getLastStuckSpeed()).thenReturn(stuckSpeed);
        return new BedrockUncertaintyPipelineTest.TestContext(context, target);
    }

    private static PredictionResult result(
            PredVector start, SimulationContext context, Vec3 target, CollideAxisData collision) {
        PredictionResult result = new PredictionResult(null, start, context, target, null, List.of(), List.of());
        result.setCollideAxisData(collision);
        return result;
    }

    private static CollideAxisData neutralCollisions() {
        return new CollideAxisData(
                new CollideResult(false, 0.0),
                new CollideResult(false, 0.0),
                new CollideResult(false, 0.0),
                new CollideResult(false, 0.0),
                List.of());
    }

    private static final class BedrockTestVector extends PredVector {
        private final PredVector step;

        private BedrockTestVector(Vec3 vector, PredVector step) {
            super(vector);
            this.step = step;
        }

        @Override
        public PredVector steppedMove() {
            return step;
        }
    }

    private record TestContext(SimulationContext context, Vec3 target) {}
}
