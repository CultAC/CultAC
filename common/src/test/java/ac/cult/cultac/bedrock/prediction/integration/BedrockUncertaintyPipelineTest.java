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
import ac.cult.cultac.checks.impl.prediction.PredVector.StepCandidate;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CollisionModifier;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ExternalMovementUncertainty;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.Fireworks;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.InsideBlock;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.PistonShulkerPush;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.StepTransform;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHandler;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ExternalMovementUncertainty.Snapshot;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.PistonPushes;
import ac.cult.cultac.utils.data.CollideAxisData.CollideResult;
import ac.cult.cultac.utils.latency.CompensatedFireworks;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public final class BedrockUncertaintyPipelineTest {
   private static final Vec3 UNIT_SCALE = new Vec3(1.0, 1.0, 1.0);

   @Test
   public void handlerOrderUsesOnlySharedJavaHandlers() {
      List<Class<? extends UncertaintyHandler>> classes = BedrockMovementEngine.UNCERTAINTY_HANDLERS
         .stream()
         .<Class<? extends UncertaintyHandler>>map(UncertaintyHandler::getClass)
         .toList();
      Assert.assertEquals(List.of(PistonShulkerPush.class, CollisionModifier.class, StepTransform.class, Fireworks.class, InsideBlock.class), classes);
      Assert.assertThrows(UnsupportedOperationException.class, () -> BedrockMovementEngine.UNCERTAINTY_HANDLERS.add(new InsideBlock()));
   }

   @Test
   public void bedrockEngineUsesStandardRunnerAndPreservesNoOpTrace() {
      BedrockMovementState previous = BedrockMovementState.fromPhysicalFeet(Vec3d.ZERO, Vec3d.ZERO, BedrockInputFrame.idle(0L), BedrockCollisionFlags.AIR);
      BedrockMovementState predicted = BedrockMovementState.fromPhysicalFeet(
         new Vec3d(0.0, 0.1, 0.0), Vec3d.ZERO, BedrockInputFrame.idle(1L), BedrockCollisionFlags.AIR
      );
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
         0.0
      );
      BedrockInputFrame inputFrame = BedrockInputFrame.idle(0L);
      Input input = new Input(
         BedrockAuthInputFrame.builder(UUID.randomUUID()).build(),
         previous,
         new BedrockTickInput(inputFrame, Vec3d.ZERO, 0L),
         inputFrame.intent(),
         BedrockWorldSnapshot.fromContext(movementContext),
         0.0,
         BedrockMobJumpComponentState.DEFAULT
      );
      BedrockPredVector start = new BedrockPredVector(
         input, movementResult, BedrockMobJumpComponentState.DEFAULT, new PredVector(Vec3.ZERO), new Vec3(0.0, 0.1, 0.0)
      );
      CultPlayer player = playerWithFireworks(false);
      BedrockUncertaintyPipelineTest.TestContext test = context(Vec3.ZERO, false, noPushes(), new Vec3(1.0, 1.0, 1.0));
      PredictionResult result = result(start, test.context(), Vec3.ZERO, neutralCollisions());
      ValidMovements valid = BedrockMovementEngine.INSTANCE.createValidMovements(start, player, result, false);
      MovementTrace trace = valid.transformTraceTowards(Vec3.ZERO, null);
      Assert.assertEquals(ValidMovements.class, valid.getClass());
      Assert.assertSame(start, trace.position());
      Assert.assertEquals(Vec3.ZERO, trace.positionOnlyDelta());
      Assert.assertEquals(Vec3.ZERO, trace.collisionBaseOffset());
      Assert.assertEquals(-0.2, start.collisionIgnoredExtents(Vec3.ZERO).minY, 0.0);
   }

   @Test
   public void pistonCombinesCurrentAndPreviousEnvelopesAndScalesPositionOnlyMetadata() {
      PredVector start = new PredVector(Vec3.ZERO);
      BedrockUncertaintyPipelineTest.TestContext current = context(
         new Vec3(0.15, 0.0, 0.0), false, pushes(pointEnvelope(0.2), new SimpleCollisionBox()), new Vec3(0.5, 1.0, 1.0)
      );
      BedrockUncertaintyPipelineTest.TestContext previous = context(
         Vec3.ZERO, false, pushes(pointEnvelope(-0.1), new SimpleCollisionBox()), new Vec3(1.0, 1.0, 1.0)
      );
      PredictionResult result = result(start, current.context(), current.target(), neutralCollisions());
      PredictionResult last = result(start, previous.context(), Vec3.ZERO, neutralCollisions());
      ValidMovements valid = new ValidMovements(start, null, result, false, List.of());
      Snapshot snapshot = ExternalMovementUncertainty.capture(current.context(), last);
      Assert.assertEquals(-0.1, snapshot.combinedTargetEnvelope().minX, 0.0);
      Assert.assertEquals(0.2, snapshot.combinedTargetEnvelope().maxX, 0.0);
      MovementTrace trace = new PistonShulkerPush()
         .handleMovementTrace(null, valid, result, current.context(), last, MovementTrace.start(start), current.target());
      Assert.assertEquals(0.0, trace.position().x, 0.0);
      Assert.assertEquals(0.15, trace.collisionBaseOffset().x, 0.0);
      Assert.assertEquals(0.075, trace.positionOnlyDelta().x, 0.0);
   }

   @Test
   public void collisionReappliesPistonBaseInOneLineageOperation() {
      PredVector start = new PredVector(Vec3.ZERO);
      BedrockUncertaintyPipelineTest.TestContext test = context(
         new Vec3(0.2, 0.0, 0.0), false, pushes(pointEnvelope(0.2), new SimpleCollisionBox()), new Vec3(1.0, 1.0, 1.0)
      );
      PredictionResult result = result(start, test.context(), test.target(), neutralCollisions());
      ValidMovements valid = new ValidMovements(start, null, result, false, List.of(new PistonShulkerPush(), new CollisionModifier()));
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
      BedrockUncertaintyPipelineTest.TestContext test = context(new Vec3(0.0, -0.1, 0.0), false, noPushes(), UNIT_SCALE);
      PredictionResult result = result(start, test.context(), test.target(), collision);
      ValidMovements valid = new ValidMovements(start, null, result, false, List.of(new CollisionModifier()));
      PredVector transformed = valid.transformTraceTowards(test.target(), null).position();
      Assert.assertEquals(-0.1, transformed.y, 0.0);
      Assert.assertTrue(transformed.hasReason("collide -y"));
   }

   @Test
   public void sharedStepTransformUsesExactBedrockCandidateAndUnknownFallback() {
      PredVector exact = new BedrockUncertaintyPipelineTest.BedrockTestVector(new Vec3(0.02, 0.5, 0.01), null);
      PredVector start = new BedrockUncertaintyPipelineTest.BedrockTestVector(Vec3.ZERO, exact);
      BedrockUncertaintyPipelineTest.TestContext test = context(new Vec3(0.02, 0.5, 0.01), false, noPushes(), UNIT_SCALE);
      PredictionResult result = result(start, test.context(), test.target(), neutralCollisions());
      ValidMovements valid = new ValidMovements(start, null, result, true, List.of(new StepTransform()));
      Assert.assertSame(exact, valid.transformTraceTowards(test.target(), null).position());
      CollideAxisData unknown = neutralCollisions();
      unknown.setUnknown(List.of(new SimpleCollisionBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)));
      result.setCollideAxisData(unknown);
      PredVector bounded = valid.transformTraceTowards(new Vec3(0.02, 0.4, 0.01), null).position();
      Assert.assertEquals(0.4, bounded.y, 0.0);
      Assert.assertTrue(bounded.isBoundedStep());
   }

   @Test
   public void fireworkCompensationIsInactiveOrBoundedBySharedSphereAndKeepsLineage() {
      PredVector source = new PredVector(Vec3.ZERO);
      PredVector start = source.withX(0.2, "collision lineage");
      Fireworks handler = new Fireworks();
      PredVector inactive = handler.handleUncertainty(playerWithFireworks(false), null, null, null, null, start, new Vec3(3.0, 0.0, 0.0));
      Assert.assertSame(start, inactive);
      PredVector active = handler.handleUncertainty(playerWithFireworks(true), null, null, null, null, start, new Vec3(3.0, 0.0, 0.0));
      Assert.assertEquals(1.7, active.distanceTo(start), 1.0E-12);
      Assert.assertTrue(active.hasReason("collision lineage"));
   }

   @Test
   public void insideBlockIsGatedHorizontalAndLeavesVerticalUnchanged() {
      PredVector start = new PredVector(new Vec3(0.03, -0.08, -0.02));
      InsideBlock handler = new InsideBlock();
      BedrockUncertaintyPipelineTest.TestContext inactiveContext = context(Vec3.ZERO, false, noPushes(), UNIT_SCALE);
      BedrockUncertaintyPipelineTest.TestContext activeContext = context(Vec3.ZERO, true, noPushes(), UNIT_SCALE);
      Assert.assertSame(start, handler.handleUncertainty(null, null, null, inactiveContext.context(), null, start, new Vec3(1.0, 1.0, -1.0)));
      PredVector active = handler.handleUncertainty(null, null, null, activeContext.context(), null, start, new Vec3(1.0, 1.0, -1.0));
      Assert.assertEquals(0.1, active.x, 0.0);
      Assert.assertEquals(start.y, active.y, 0.0);
      Assert.assertEquals(-0.1, active.z, 0.0);
      Assert.assertTrue(active.hasReason("block pushing"));
   }

   @Test
   public void lilyPadEscapeUsesExistingHorizontalAllowance() {
      double x = 191.81735229492188;
      double z = -111.4725112915039;
      SimpleCollisionBox lilyPad = new SimpleCollisionBox(192.0625, 63.0, -111.9375, 192.9375, 63.09375, -111.0625);
      SimpleCollisionBox standing = new SimpleCollisionBox(x - 0.3, 62.0, z - 0.3, x + 0.3, 63.8, z + 0.3);
      SimpleCollisionBox swimming = new SimpleCollisionBox(x - 0.3, 62.0, z - 0.3, x + 0.3, 62.6, z + 0.3);
      Assert.assertTrue(standing.isIntersected(lilyPad));
      Assert.assertFalse(swimming.isIntersected(lilyPad));
      Vec3 observed = new Vec3(-0.0521240234375, 0.0, 0.00690460205078125);
      PredVector predicted = new PredVector(new Vec3(0.0, 0.0, 0.00704193115234375));
      TestContext previous = context(observed, standing.isIntersected(lilyPad), noPushes(), UNIT_SCALE);
      TestContext test = context(observed, swimming.isIntersected(lilyPad), noPushes(), UNIT_SCALE);
      PredictionResult last = result(predicted, previous.context(), observed, neutralCollisions());
      CultPlayer player = Mockito.mock(CultPlayer.class);
      Mockito.when(player.isBedrockMovement()).thenReturn(true);
      InsideBlock handler = new InsideBlock();
      PredVector allowed = handler.handleUncertainty(player, null, null, test.context(), last, predicted, observed);
      Assert.assertEquals(observed, new Vec3(allowed.x, allowed.y, allowed.z));
      Assert.assertTrue(allowed.hasReason("block pushing"));
      PredictionResult noOverlap = result(predicted, test.context(), observed, neutralCollisions());
      Assert.assertSame(predicted, handler.handleUncertainty(player, null, null, test.context(), noOverlap, predicted, observed));
      Mockito.when(player.isBedrockMovement()).thenReturn(false);
      Assert.assertSame(predicted, handler.handleUncertainty(player, null, null, test.context(), last, predicted, observed));
   }

   @Test
   public void endTickOverlapUsesAcceptedEndpointAndOnlyAffectsNextTick() {
      var block = new ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision(
         new ac.cult.cultac.bedrock.prediction.geometry.BlockPosition(1, 0, 0),
         "minecraft:stone", "minecraft:stone", java.util.Map.of(),
         List.of(new ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox(1, 0, 0, 2, 1, 1)));
      BedrockMovementContext movementContext = airContext().withBlockCollisionWorld(new BlockCollisionWorld(List.of(block)));
      BedrockMovementState previous = BedrockMovementState.fromPhysicalFeet(
         new Vec3d(0.5, 0, 0.5), Vec3d.ZERO, BedrockInputFrame.idle(0), BedrockCollisionFlags.AIR);
      BedrockMovementResult movement = new BedrockMovementResult(
         previous, movementContext, movementContext, previous, previous.physicalFeetPosition(), Vec3d.ZERO,
         false, false, false, false, BlockMovementSlowdownState.NONE, HoneySlideState.NONE,
         false, false, 0.0, 1.0, false, false, false, 0.0);
      TestContext tick = context(Vec3.ZERO, false, noPushes(), UNIT_SCALE);
      java.util.concurrent.atomic.AtomicBoolean overlap = new java.util.concurrent.atomic.AtomicBoolean();
      WorldData world = tick.context().getWorldData();
      Mockito.when(world.isMightBeInBlock()).thenAnswer(invocation -> overlap.get());
      Mockito.doAnswer(invocation -> { overlap.set(invocation.getArgument(0)); return null; })
         .when(world).setMightBeInBlock(Mockito.anyBoolean());
      PredVector start = new PredVector(Vec3.ZERO);
      PredictionResult result = result(start, tick.context(), Vec3.ZERO, neutralCollisions());
      BedrockMovementEngine.recordEndTickBlockPush(result, movement, new Vec3(0.4, 0, 0));
      Assert.assertTrue(overlap.get());
      CultPlayer player = Mockito.mock(CultPlayer.class);
      Mockito.when(player.isBedrockMovement()).thenReturn(true);
      InsideBlock handler = new InsideBlock();
      Vec3 pushed = new Vec3(-0.08, 0, 0);
      Assert.assertSame(start, handler.handleUncertainty(player, null, result, tick.context(), null, start, pushed));
      TestContext next = context(pushed, false, noPushes(), UNIT_SCALE);
      Assert.assertEquals(-0.08, handler.handleUncertainty(player, null, null, next.context(), result, start, pushed).x, 0);
      BedrockMovementEngine.recordEndTickBlockPush(result, movement, Vec3.ZERO);
      Assert.assertFalse(overlap.get());
   }

   private static CultPlayer playerWithFireworks(boolean active) {
      CultPlayer player = (CultPlayer)Mockito.mock(CultPlayer.class);

      try {
         Field field = CultPlayer.class.getDeclaredField("compensatedFireworks");
         field.setAccessible(true);
         field.set(player, new CompensatedFireworks(player));
      } catch (ReflectiveOperationException exception) {
         throw new IllegalStateException("failed to install fireworks", exception);
      }

      if (active) {
         player.compensatedFireworks.addNewFirework(1);
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
         PlayerDimensionsState.DEFAULT
      );
   }

   private static BedrockUncertaintyPipelineTest.TestContext context(Vec3 target, boolean mightBeInBlock, PistonPushes pushes, Vec3 stuckSpeed) {
      WorldData world = (WorldData)Mockito.mock(WorldData.class);
      Mockito.when(world.isMightBeInBlock()).thenReturn(mightBeInBlock);
      Mockito.when(world.getPistonPushes()).thenReturn(pushes);
      SimulationContext context = (SimulationContext)Mockito.mock(SimulationContext.class);
      Mockito.when(context.getWorldData()).thenReturn(world);
      Mockito.when(context.getTarget()).thenReturn(target);
      Mockito.when(context.getTargetScalarHoriz()).thenReturn(1.0);
      Mockito.when(context.getTargetScalarVert()).thenReturn(1.0);
      Mockito.when(context.getLastStuckSpeed()).thenReturn(stuckSpeed);
      return new BedrockUncertaintyPipelineTest.TestContext(context, target);
   }

   private static PredictionResult result(PredVector start, SimulationContext context, Vec3 target, CollideAxisData collision) {
      PredictionResult result = new PredictionResult(null, start, context, target, null, List.of(), List.of());
      result.setCollideAxisData(collision);
      return result;
   }

   private static CollideAxisData neutralCollisions() {
      return new CollideAxisData(
         new CollideResult(false, 0.0), new CollideResult(false, 0.0), new CollideResult(false, 0.0), new CollideResult(false, 0.0), List.of()
      );
   }

   private static PistonPushes noPushes() {
      return pushes(new SimpleCollisionBox(), new SimpleCollisionBox());
   }

   private static PistonPushes pushes(SimpleCollisionBox piston, SimpleCollisionBox shulker) {
      return new PistonPushes(piston.isEmpty() ? shulker.copy() : piston.copy().union(shulker), piston, shulker, Set.of());
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
         return collisionData != null && !collisionData.getUnknown().isEmpty() ? new StepCandidate(null, true) : new StepCandidate(this.stepCandidate, false);
      }

      public double maxUpStep(CultPlayer player) {
         return 0.5625;
      }
   }

   private record TestContext(SimulationContext context, Vec3 target) {
   }
}
