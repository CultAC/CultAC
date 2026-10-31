package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockTickInput;
import ac.cult.cultac.bedrock.prediction.integration.BedrockMovementInputFactory.Input;
import ac.cult.cultac.bedrock.prediction.integration.ExternalMovementUncertainty.Snapshot;
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
import ac.cult.cultac.checks.impl.prediction.PredVector.StepCandidate;
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
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.CollideAxisData.CollideResult;
import ac.cult.cultac.utils.data.PistonPushes;
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
        Assert.assertEquals(List.of(PistonShulkerPush.class, CollisionModifier.class, StepTransform.class), classes);
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
                false,
                null);
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
                context(Vec3.ZERO, false, noPushes(), new Vec3(1.0, 1.0, 1.0));
        PredictionResult result = result(start, test.context(), Vec3.ZERO, neutralCollisions());
        ValidMovements valid = BedrockMovementEngine.INSTANCE.createValidMovements(start, player, result, false);
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
    public void pistonCombinesCurrentAndPreviousEnvelopesAndScalesPositionOnlyMetadata() {
        PredVector start = new PredVector(Vec3.ZERO);
        BedrockUncertaintyPipelineTest.TestContext current = context(
                new Vec3(0.15, 0.0, 0.0),
                false,
                pushes(pointEnvelope(0.2), new SimpleCollisionBox()),
                new Vec3(0.5, 1.0, 1.0));
        BedrockUncertaintyPipelineTest.TestContext previous = context(
                Vec3.ZERO, false, pushes(pointEnvelope(-0.1), new SimpleCollisionBox()), new Vec3(1.0, 1.0, 1.0));
        PredictionResult result = result(start, current.context(), current.target(), neutralCollisions());
        PredictionResult last = result(start, previous.context(), Vec3.ZERO, neutralCollisions());
        CultPlayer player = bedrockPlayer();
        ValidMovements valid = new ValidMovements(start, player, result, false, List.of());
        Snapshot snapshot = ExternalMovementUncertainty.capture(current.context(), last);
        Assert.assertEquals(-0.1, snapshot.combinedTargetEnvelope().minX, 0.0);
        Assert.assertEquals(0.2, snapshot.combinedTargetEnvelope().maxX, 0.0);
        MovementTrace trace = new PistonShulkerPush()
                .handleMovementTrace(
                        player, valid, result, current.context(), last, MovementTrace.start(start), current.target());
        Assert.assertEquals(0.0, trace.position().x, 0.0);
        Assert.assertEquals(0.15, trace.collisionBaseOffset().x, 0.0);
        Assert.assertEquals(0.075, trace.positionOnlyDelta().x, 0.0);
    }

    @Test
    public void collisionReappliesPistonBaseInOneLineageOperation() {
        PredVector start = new PredVector(Vec3.ZERO);
        BedrockUncertaintyPipelineTest.TestContext test = context(
                new Vec3(0.2, 0.0, 0.0),
                false,
                pushes(pointEnvelope(0.2), new SimpleCollisionBox()),
                new Vec3(1.0, 1.0, 1.0));
        PredictionResult result = result(start, test.context(), test.target(), neutralCollisions());
        ValidMovements valid = new ValidMovements(
                start, bedrockPlayer(), result, false, List.of(new PistonShulkerPush(), new CollisionModifier()));
        MovementTrace trace = valid.transformTraceTowards(test.target(), null);
        Vec3 beforeReapplication = trace.position().vectorBeforeFirstReason("external movement collision base");
        Assert.assertEquals(trace.collisionBaseOffset().x, trace.position().x - beforeReapplication.x, 0.0);
        Assert.assertEquals(0.2, trace.positionOnlyDelta().x, 0.0);
    }

    @Test
    public void collisionClipsOrdinaryVerticalMovementWithoutBedrockVerticalProjection() {
        PredVector start = new PredVector(new Vec3(0.0, -0.2, 0.0));
        CollideAxisData collision = neutralCollisions();
        collision.setYNeg(new CollideResult(true, -0.1));
        BedrockUncertaintyPipelineTest.TestContext test =
                context(new Vec3(0.0, -0.1, 0.0), false, noPushes(), UNIT_SCALE);
        PredictionResult result = result(start, test.context(), test.target(), collision);
        ValidMovements valid = new ValidMovements(start, null, result, false, List.of(new CollisionModifier()));
        PredVector transformed =
                valid.transformTraceTowards(test.target(), null).position();
        Assert.assertEquals(-0.1, transformed.y, 0.0);
        Assert.assertTrue(transformed.hasReason("collide -y"));
    }

    @Test
    public void sharedStepTransformUsesExactBedrockCandidateAndUnknownFallback() {
        PredVector exact = new BedrockUncertaintyPipelineTest.BedrockTestVector(new Vec3(0.02, 0.5, 0.01), null);
        PredVector start = new BedrockUncertaintyPipelineTest.BedrockTestVector(Vec3.ZERO, exact);
        BedrockUncertaintyPipelineTest.TestContext test =
                context(new Vec3(0.02, 0.5, 0.01), false, noPushes(), UNIT_SCALE);
        PredictionResult result = result(start, test.context(), test.target(), neutralCollisions());
        ValidMovements valid = new ValidMovements(start, bedrockPlayer(), result, true, List.of(new StepTransform()));
        Assert.assertSame(
                exact, valid.transformTraceTowards(test.target(), null).position());
        CollideAxisData unknown = neutralCollisions();
        unknown.setUnknown(List.of(new SimpleCollisionBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)));
        result.setCollideAxisData(unknown);
        PredVector bounded =
                valid.transformTraceTowards(new Vec3(0.02, 0.4, 0.01), null).position();
        Assert.assertEquals(0.4, bounded.y, 0.0);
        Assert.assertTrue(bounded.isBoundedStep());
    }

    @Test
    public void bedrockUnknownStepFallbackRisesFromThePacketStartAfterAPistonPush() {
        PredVector start = new BedrockUncertaintyPipelineTest.BedrockTestVector(Vec3.ZERO, null);
        BedrockUncertaintyPipelineTest.TestContext test =
                context(new Vec3(0.02, 0.9, 0.01), false, noPushes(), UNIT_SCALE);
        CollideAxisData unknown = neutralCollisions();
        unknown.setUnknown(List.of(new SimpleCollisionBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)));
        PredictionResult result = result(start, test.context(), test.target(), unknown);
        CultPlayer player = bedrockPlayer();
        ValidMovements valid = new ValidMovements(start, player, result, true, List.of());
        MovementTrace pushed = MovementTrace.start(start).addCollisionBaseOffset(new Vec3(0.0, 0.3, 0.0));
        PredVector bounded = new StepTransform()
                .handleMovementTrace(player, valid, result, test.context(), null, pushed, test.target())
                .position();
        Assert.assertEquals(0.5625, bounded.y, 0.0);
        Assert.assertTrue(bounded.isBoundedStep());
    }

    @Test
    public void insideBlockIsGatedHorizontalAndLeavesVerticalUnchanged() {
        PredVector start = new PredVector(new Vec3(0.03, -0.08, -0.02));
        InsideBlock handler = new InsideBlock();
        BedrockUncertaintyPipelineTest.TestContext inactiveContext = context(Vec3.ZERO, false, noPushes(), UNIT_SCALE);
        BedrockUncertaintyPipelineTest.TestContext activeContext = context(Vec3.ZERO, true, noPushes(), UNIT_SCALE);
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
                false,
                null);
        Vec3d reported = new Vec3d(-0.047259465, 0, 0.088127986);
        var selected = previous.withPhysicalFeetPosition(new Vec3d(0.9, 0, 0.5), 0.16);
        var pushed =
                ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick.finish(movement, selected, reported);
        Assert.assertEquals(1, pushed.size());
        Assert.assertEquals(selected.physicalFeetPosition(), pushed.getFirst().physicalFeetPosition());
        Assert.assertEquals(reported.x(), pushed.getFirst().velocity().x(), 1.0E-12);
        Assert.assertEquals(reported.z(), pushed.getFirst().velocity().z(), 1.0E-12);
        var unpushed = ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick.finish(movement, selected);
        Assert.assertEquals(
                unpushed.getFirst().velocity().y(), pushed.getFirst().velocity().y(), 0);
        var outside =
                ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick.finish(movement, previous, reported);
        Assert.assertEquals(0, outside.getFirst().velocity().x(), 0);
        Assert.assertEquals(0, outside.getFirst().velocity().z(), 0);
        var excessive = ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick.finish(
                        movement, selected, new Vec3d(-1, 1, 1))
                .getFirst();
        Assert.assertEquals(-0.1, excessive.velocity().x(), 0);
        Assert.assertEquals(0.1, excessive.velocity().z(), 0);
        Assert.assertEquals(
                unpushed.getFirst().velocity().y(), excessive.velocity().y(), 0);
        var observation = BedrockMovementObservationFactory.fromForwardSelection(
                movement,
                new Vec3(0.9, 0, 0.5),
                selected,
                new Vec3(reported.x(), unpushed.getFirst().velocity().y(), reported.z()),
                null);
        Assert.assertEquals(0, observation.velocityOffset(), 1.0E-12);
        var claimedOverlap = BedrockMovementObservationFactory.fromForwardSelection(
                movement,
                new Vec3(0.9, 0, 0.5),
                previous,
                new Vec3(reported.x(), outside.getFirst().velocity().y(), reported.z()),
                null);
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
            Vec3 target, boolean mightBeInBlock, PistonPushes pushes, Vec3 stuckSpeed) {
        WorldData world = (WorldData) Mockito.mock(WorldData.class);
        Mockito.when(world.isMightBeInBlock()).thenReturn(mightBeInBlock);
        Mockito.when(world.getPistonPushes()).thenReturn(pushes);
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

    private static PistonPushes noPushes() {
        return pushes(new SimpleCollisionBox(), new SimpleCollisionBox());
    }

    private static PistonPushes pushes(SimpleCollisionBox piston, SimpleCollisionBox shulker) {
        return new PistonPushes(
                piston.isEmpty() ? shulker.copy() : piston.copy().union(shulker), piston, shulker);
    }

    private static SimpleCollisionBox pointEnvelope(double x) {
        return new SimpleCollisionBox(x, 0.0, 0.0, x, 1.0E-9, 0.0);
    }

    private static final class BedrockTestVector extends PredVector {
        private final PredVector stepCandidate;

        private BedrockTestVector(Vec3 vector, PredVector stepCandidate) {
            super(vector);
            this.stepCandidate = stepCandidate;
        }

        public StepCandidate stepCandidate(Vec3 end, CollideAxisData collisionData) {
            return collisionData != null && !collisionData.getUnknown().isEmpty()
                    ? new StepCandidate(null, true)
                    : new StepCandidate(this.stepCandidate, false);
        }

        public double maxUpStep(CultPlayer player) {
            return 0.5625;
        }
    }

    private record TestContext(SimulationContext context, Vec3 target) {}
}
