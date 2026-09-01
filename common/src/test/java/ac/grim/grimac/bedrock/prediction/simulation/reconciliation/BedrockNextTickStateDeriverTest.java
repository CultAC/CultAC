package ac.grim.grimac.bedrock.prediction.simulation.reconciliation;

import ac.grim.grimac.bedrock.prediction.api.BedrockMovementResult;
import ac.grim.grimac.bedrock.prediction.geometry.BlockPosition;
import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.grim.grimac.bedrock.prediction.input.BedrockInputFrame;
import ac.grim.grimac.bedrock.prediction.model.AttributeState;
import ac.grim.grimac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.grim.grimac.bedrock.prediction.model.BedrockEffectState;
import ac.grim.grimac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.grim.grimac.bedrock.prediction.model.EquipmentState;
import ac.grim.grimac.bedrock.prediction.model.Medium;
import ac.grim.grimac.bedrock.prediction.model.MovementModifierState;
import ac.grim.grimac.bedrock.prediction.model.PlayerDimensionsState;
import ac.grim.grimac.bedrock.prediction.simulation.BedrockSimulation;
import ac.grim.grimac.bedrock.prediction.simulation.collision.BedrockBlockCollisionResolver;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockAerialMovement;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockClimbMovement;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockLiquidVerticalMovement;
import ac.grim.grimac.bedrock.prediction.simulation.postmove.BedrockLiquidClimbOutMovement;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import ac.grim.grimac.bedrock.prediction.world.BedrockMovementContext;
import ac.grim.grimac.bedrock.prediction.world.BlockCollisionWorld;
import ac.grim.grimac.bedrock.prediction.world.EntityContactState;
import ac.grim.grimac.bedrock.prediction.world.FluidState;
import ac.grim.grimac.bedrock.prediction.world.HoneySlideState;
import ac.grim.grimac.bedrock.prediction.world.PlacedBlockCollision;
import ac.grim.grimac.bedrock.prediction.world.WorldContactState;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class BedrockNextTickStateDeriverTest {
    private static final Vec3d POSITION = new Vec3d(0.5D, 64.0D, 0.5D);
    private static final Vec3d ZERO = new Vec3d(0.0D, 0.0D, 0.0D);

    @Test
    public void airAcceptedDiffProducesSingleDeterministicBaseState() {
        BedrockMovementState source = state(POSITION, ZERO, BedrockCollisionFlags.AIR, Medium.AIR);
        Vec3d acceptedDiff = new Vec3d(0.1D, 0.2D, -0.05D);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        movementResult(source, source, context(Medium.AIR, BlockCollisionWorld.EMPTY)),
                        List.of(source),
                        acceptedDiff);

        assertEquals(1, states.size());
        assertSame(source, states.getFirst().lineageState());
        assertEquals(0.1D, states.getFirst().state().velocity().x(), 0.0D);
        assertEquals(BedrockNextTickStateDeriver.airDraggedVelocityWithGravity(acceptedDiff.y()),
                states.getFirst().state().velocity().y(), 0.0D);
        assertEquals(-0.05D, states.getFirst().state().velocity().z(), 0.0D);
        assertFalse(states.getFirst().state().collisionFlags().liquidClimbOut());
    }

    @Test
    public void lavaTravelDragsAcceptedDiffWhileActorGlidingRemainsActive() {
        Vec3d acceptedDiff = new Vec3d(
                -0.064849853515625D,
                -0.100860595703125D,
                0.1639251708984375D);
        BedrockMovementState source = state(
                POSITION.add(acceptedDiff),
                acceptedDiff,
                BedrockCollisionFlags.AIR,
                Medium.LAVA).withGliding(true);
        BedrockMovementState previous = state(
                POSITION,
                new Vec3d(-0.1190185546875D, -0.161712646484375D, 0.29013824462890625D),
                BedrockCollisionFlags.AIR,
                Medium.LAVA).withGliding(true);
        BedrockMovementResult result = movementResult(
                previous,
                source,
                source.physicalFeetPosition(),
                context(Medium.LAVA, BlockCollisionWorld.EMPTY),
                BedrockLiquidVerticalMovement.LAVA_FRICTION,
                false,
                false);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        result, List.of(source), acceptedDiff);

        assertEquals(1, states.size());
        BedrockMovementState carried = states.getFirst().state();
        assertTrue(carried.gliding());
        assertEquals(acceptedDiff.x() * BedrockLiquidVerticalMovement.LAVA_FRICTION,
                carried.velocity().x(), 0.0D);
        assertEquals(BedrockLiquidVerticalMovement.lavaNextTickVelocityY(acceptedDiff.y(), true),
                carried.velocity().y(), 0.0D);
        assertEquals(acceptedDiff.z() * BedrockLiquidVerticalMovement.LAVA_FRICTION,
                carried.velocity().z(), 0.0D);
    }

    @Test
    public void acceptedEndpointMovementSideXContactAloneDoesNotBranchHorizontalCarry() {
        double contactX = 1.0D - BedrockBlockCollisionResolver.PLAYER_RADIUS;
        Vec3d previousPosition = new Vec3d(0.5D, 64.0D, 0.5D);
        Vec3d acceptedPosition = new Vec3d(contactX, 64.0D, 0.5D);
        Vec3d acceptedDiff = acceptedPosition.subtract(previousPosition);
        BlockCollisionWorld world = blockWorld(fullBlock(1, 64, 0));
        BedrockMovementState previous = state(previousPosition, ZERO, BedrockCollisionFlags.AIR, Medium.AIR);
        BedrockMovementState acceptedSource = state(acceptedPosition, ZERO, BedrockCollisionFlags.AIR, Medium.AIR);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        movementResult(previous, acceptedSource, context(Medium.AIR, world)),
                        List.of(acceptedSource),
                        acceptedDiff);

        assertEquals(1, states.size());
        assertTrue(hasVelocityXZ(states, acceptedDiff.x(), 0.0D));
    }

    @Test
    public void selectedXCollisionBranchesOnlyXCarryVelocity() {
        Vec3d acceptedDiff = new Vec3d(-0.063651123046875D, -0.14136505126953125D, -0.13830650329589844D);
        BedrockMovementState source = state(POSITION, ZERO, BedrockCollisionFlags.AIR, Medium.AIR);
        BedrockMovementResult result = movementResult(
                source, source, context(Medium.AIR, BlockCollisionWorld.EMPTY));

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        result, List.of(source), acceptedDiff, true, false);

        assertEquals(2, states.size());
        assertTrue(hasVelocityXZ(states, acceptedDiff.x(), acceptedDiff.z()));
        assertTrue(hasVelocityXZ(states, 0.0D, acceptedDiff.z()));
        assertTrue(states.stream().noneMatch(state -> state.state().collisionFlags().horizontalCollision()));
        assertTrue(states.stream().noneMatch(state -> state.state().collisionFlags().xCollision()));
    }

    @Test
    public void selectedZCollisionCannotZeroXCarryVelocity() {
        Vec3d acceptedDiff = new Vec3d(0.12D, -0.08D, -0.2D);
        BedrockMovementState source = state(POSITION, ZERO, BedrockCollisionFlags.AIR, Medium.AIR);
        BedrockMovementResult result = movementResult(
                source, source, context(Medium.AIR, BlockCollisionWorld.EMPTY));

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        result, List.of(source), acceptedDiff, false, true);

        assertEquals(2, states.size());
        assertFalse(hasVelocityXZ(states, 0.0D, acceptedDiff.z()));
        assertTrue(hasVelocityXZ(states, acceptedDiff.x(), 0.0D));
    }

    @Test
    public void acceptedEndpointMovementSideZContactAloneDoesNotBranchHorizontalCarry() {
        double contactZ = 1.0D - BedrockBlockCollisionResolver.PLAYER_RADIUS;
        Vec3d previousPosition = new Vec3d(0.5D, 64.0D, 0.5D);
        Vec3d acceptedPosition = new Vec3d(0.5D, 64.0D, contactZ);
        Vec3d acceptedDiff = acceptedPosition.subtract(previousPosition);
        BlockCollisionWorld world = blockWorld(fullBlock(0, 64, 1));
        BedrockMovementState previous = state(previousPosition, ZERO, BedrockCollisionFlags.AIR, Medium.AIR);
        BedrockMovementState acceptedSource = state(acceptedPosition, ZERO, BedrockCollisionFlags.AIR, Medium.AIR);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        movementResult(previous, acceptedSource, context(Medium.AIR, world)),
                        List.of(acceptedSource),
                        acceptedDiff);

        assertEquals(1, states.size());
        assertTrue(hasVelocityXZ(states, 0.0D, acceptedDiff.z()));
    }

    @Test
    public void acceptedEndpointMovementSideXZContactAloneDoesNotBranchHorizontalCarry() {
        double contact = 1.0D - BedrockBlockCollisionResolver.PLAYER_RADIUS;
        Vec3d previousPosition = new Vec3d(0.5D, 64.0D, 0.5D);
        Vec3d acceptedPosition = new Vec3d(contact, 64.0D, contact);
        Vec3d acceptedDiff = acceptedPosition.subtract(previousPosition);
        BlockCollisionWorld world = blockWorld(
                fullBlock(1, 64, 0),
                fullBlock(0, 64, 1));
        BedrockMovementState previous = state(previousPosition, ZERO, BedrockCollisionFlags.AIR, Medium.AIR);
        BedrockMovementState acceptedSource = state(acceptedPosition, ZERO, BedrockCollisionFlags.AIR, Medium.AIR);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        movementResult(previous, acceptedSource, context(Medium.AIR, world)),
                        List.of(acceptedSource),
                        acceptedDiff);

        assertEquals(1, states.size());
        assertTrue(hasVelocityXZ(states, acceptedDiff.x(), acceptedDiff.z()));
    }

    @Test
    public void acceptedEndpointOppositeSideContactDoesNotBranchHorizontalCarry() {
        double contactX = BedrockBlockCollisionResolver.PLAYER_RADIUS;
        Vec3d previousPosition = new Vec3d(0.2D, 64.0D, 0.5D);
        Vec3d acceptedPosition = new Vec3d(contactX, 64.0D, 0.5D);
        Vec3d acceptedDiff = acceptedPosition.subtract(previousPosition);
        BlockCollisionWorld world = blockWorld(fullBlock(-1, 64, 0));
        BedrockMovementState previous = state(previousPosition, ZERO, BedrockCollisionFlags.AIR, Medium.AIR);
        BedrockMovementState acceptedSource = state(acceptedPosition, ZERO, BedrockCollisionFlags.AIR, Medium.AIR);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        movementResult(previous, acceptedSource, context(Medium.AIR, world)),
                        List.of(acceptedSource),
                        acceptedDiff);

        assertEquals(1, states.size());
        assertTrue(hasVelocityXZ(states, acceptedDiff.x(), 0.0D));
    }

    @Test
    public void selectedXCollisionBranchesHorizontalCarryLikeJavaVelocityCandidates() {
        Vec3d acceptedDiff = new Vec3d(0.25D, 0.0D, -0.1D);
        BedrockCollisionFlags selectedXCollision = new BedrockCollisionFlags(
                false,
                true,
                false,
                true,
                false,
                true,
                false);
        BedrockMovementState previous = state(POSITION, ZERO, BedrockCollisionFlags.AIR, Medium.AIR);
        BedrockMovementState acceptedSource = state(
                POSITION.add(acceptedDiff),
                ZERO,
                selectedXCollision,
                Medium.AIR);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        movementResult(previous, acceptedSource, context(Medium.AIR, BlockCollisionWorld.EMPTY)),
                        List.of(acceptedSource),
                        acceptedDiff);

        assertEquals(2, states.size());
        assertTrue(hasVelocityXZ(states, acceptedDiff.x(), acceptedDiff.z()));
        assertTrue(hasVelocityXZ(states, 0.0D, acceptedDiff.z()));
    }

    @Test
    public void acceptedGroundJumpDiffUsesResultHorizontalFriction() {
        Vec3d acceptedDiff = new Vec3d(0.1D, AttributeState.DEFAULT.jumpStrength(), -0.2D);
        BedrockInputFrame jumpingFrame = new BedrockInputFrame(1L, 0.0F, 0.0F, true, false, false);
        BedrockMovementState previous = state(POSITION, ZERO, jumpingFrame, BedrockCollisionFlags.AIR, Medium.AIR);
        BedrockMovementState acceptedSource = state(
                POSITION.add(acceptedDiff),
                acceptedDiff,
                jumpingFrame,
                BedrockCollisionFlags.AIR,
                Medium.AIR);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        movementResult(previous, acceptedSource, context(Medium.AIR, BlockCollisionWorld.EMPTY)),
                        List.of(acceptedSource),
                        acceptedDiff);

        assertEquals(1, states.size());
        assertEquals(acceptedDiff.x(), states.getFirst().state().velocity().x(), 1.0E-12D);
        assertEquals(acceptedDiff.z(), states.getFirst().state().velocity().z(), 1.0E-12D);
    }

    @Test
    public void acceptedJumpHeldStairCatchKeepsSelectedGroundFriction() {
        double selectedGroundFriction = 0.5460000216960907D;
        Vec3d previousPosition = new Vec3d(0.5D, 64.5D, 0.5D);
        Vec3d acceptedDiff = new Vec3d(0.1D, 0.0D, -0.2D);
        Vec3d acceptedPosition = previousPosition.add(acceptedDiff);
        Vec3d rawPredictedPosition = previousPosition.add(new Vec3d(0.1D, -0.07840000092983246D, -0.2D));
        BedrockInputFrame jumpingFrame = new BedrockInputFrame(1L, 0.0F, 0.0F, true, false, false);
        BedrockMovementState previous = state(
                previousPosition,
                ZERO,
                jumpingFrame,
                BedrockCollisionFlags.ON_GROUND,
                Medium.GROUND);
        BedrockMovementState predicted = state(
                acceptedPosition,
                new Vec3d(
                        acceptedDiff.x() * selectedGroundFriction,
                        -0.07840000092983246D,
                        acceptedDiff.z() * selectedGroundFriction),
                jumpingFrame,
                BedrockCollisionFlags.ON_GROUND,
                Medium.GROUND);
        BedrockMovementState acceptedSource = state(
                acceptedPosition,
                acceptedDiff,
                jumpingFrame,
                BedrockCollisionFlags.ON_GROUND,
                Medium.GROUND);
        BlockCollisionWorld stairSupport = blockWorld(PlacedBlockCollision.manual(
                new BlockPosition(0, 64, 0),
                "minecraft:oak_stairs",
                "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]",
                List.of(new WorldCollisionBox(
                        0.0D,
                        64.0D,
                        0.0D,
                        1.0D,
                        64.5D,
                        1.0D))));

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        movementResult(
                                previous,
                                predicted,
                                rawPredictedPosition,
                                context(Medium.GROUND, stairSupport),
                                selectedGroundFriction),
                        List.of(acceptedSource),
                        acceptedDiff);

        assertEquals(1, states.size());
        assertEquals(acceptedDiff.x() * selectedGroundFriction,
                states.getFirst().state().velocity().x(), 1.0E-12D);
        assertEquals(acceptedDiff.z() * selectedGroundFriction,
                states.getFirst().state().velocity().z(), 1.0E-12D);
    }

    @Test
    public void acceptedUpwardDiffClearsStaleGroundFlag() {
        Vec3d acceptedDiff = new Vec3d(0.12D, 0.2D, -0.04D);
        BedrockMovementState previous = state(POSITION, ZERO, BedrockCollisionFlags.ON_GROUND, Medium.AIR);
        BedrockMovementState predicted = state(
                POSITION.add(acceptedDiff),
                acceptedDiff,
                BedrockCollisionFlags.ON_GROUND,
                Medium.AIR);
        BedrockMovementState acceptedSource = state(
                POSITION.add(acceptedDiff),
                acceptedDiff,
                BedrockCollisionFlags.ON_GROUND,
                Medium.AIR);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        movementResult(previous, predicted, context(Medium.AIR, BlockCollisionWorld.EMPTY)),
                        List.of(acceptedSource),
                        acceptedDiff);

        assertEquals(1, states.size());
        assertFalse(states.getFirst().state().collisionFlags().onGround());
        assertEquals(Medium.AIR, states.getFirst().state().movementBranch());
    }

    @Test
    public void acceptedEndpointWaterRebuildsNextTickTravelBranch() {
        BlockCollisionWorld waterWorld = new BlockCollisionWorld(List.of(
                PlacedBlockCollision.manual(
                        new BlockPosition(0, 64, 0),
                        "minecraft:water[level=0]",
                        "minecraft:water",
                        List.of())));
        Vec3d acceptedPosition = POSITION;
        Vec3d predictedPosition = new Vec3d(POSITION.x(), POSITION.y(), POSITION.z() + 1.0D);
        BedrockMovementState previous = state(POSITION, ZERO, BedrockCollisionFlags.AIR, Medium.AIR);
        BedrockMovementState predicted = state(predictedPosition, ZERO, BedrockCollisionFlags.AIR, Medium.AIR);
        BedrockMovementState acceptedSource = state(acceptedPosition, ZERO, BedrockCollisionFlags.AIR, Medium.AIR);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        movementResult(previous, predicted, context(Medium.AIR, waterWorld)),
                        List.of(acceptedSource),
                        ZERO);

        assertEquals(1, states.size());
        assertTrue(states.getFirst().state().waterTravelFlag());
        assertEquals(Medium.WATER, states.getFirst().state().movementBranch());
    }

    @Test
    public void recordedLiquidClimbOutAddsOnlySwimHopAndBaseStates() {
        BedrockCollisionFlags flags = BedrockCollisionFlags.AIR.withLiquidClimbOut(true);
        BedrockMovementState source = state(POSITION, ZERO, flags, Medium.WATER);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        movementResult(source, source, context(Medium.WATER, BlockCollisionWorld.EMPTY)),
                        List.of(source),
                        ZERO);

        assertEquals(2, states.size());
        assertTrue(hasVelocityY(states, BedrockLiquidClimbOutMovement.CLIMB_OUT_VELOCITY_Y));
        assertTrue(states.stream().anyMatch(state -> state.state().collisionFlags().liquidClimbOut()));
        assertTrue(states.stream().anyMatch(state -> !state.state().collisionFlags().liquidClimbOut()));
    }

    @Test
    public void freshClimbableBoundaryProducesOneCycleContactCarry() {
        BlockCollisionWorld ladderWorld = new BlockCollisionWorld(List.of(
                PlacedBlockCollision.manual(
                        new BlockPosition(1, 64, 0),
                        "minecraft:ladder",
                        "minecraft:ladder",
                        List.of())));
        Vec3d previousPosition = POSITION;
        Vec3d acceptedPosition = new Vec3d(1.5D, 64.0D, 0.5D);
        Vec3d acceptedDiff = acceptedPosition.subtract(previousPosition);
        BedrockMovementState previous = state(previousPosition, ZERO, BedrockCollisionFlags.AIR, Medium.AIR);
        BedrockMovementState accepted = state(acceptedPosition, ZERO, BedrockCollisionFlags.AIR, Medium.AIR);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        movementResult(previous, accepted, context(Medium.AIR, ladderWorld)),
                        List.of(accepted),
                        acceptedDiff);

        assertEquals(2, states.size());
        assertTrue(states.stream().anyMatch(state -> state.state().climbableContact().climbing()));
        assertTrue(states.stream().anyMatch(state -> !state.state().climbableContact().climbing()));
    }

    @Test
    public void consumedClimbableCarryDoesNotPersistOrAddEndTickVelocity() {
        BlockCollisionWorld ladderWorld = new BlockCollisionWorld(List.of(
                PlacedBlockCollision.manual(
                        new BlockPosition(0, 64, 0),
                        "minecraft:ladder",
                        "minecraft:ladder",
                        List.of())));
        BedrockMovementState source = state(POSITION, ZERO, BedrockCollisionFlags.AIR, Medium.AIR);
        Vec3d acceptedDiff = new Vec3d(0.0D, 0.1D, 0.0D);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        movementResult(source, source, context(Medium.AIR, ladderWorld)),
                        List.of(source),
                        acceptedDiff);

        assertEquals(1, states.size());
        assertFalse(hasVelocityY(states, BedrockClimbMovement.LADDER_ASCEND_VELOCITY));
        assertEquals(BedrockNextTickStateDeriver.airDraggedVelocityWithGravity(acceptedDiff.y()),
                states.getFirst().state().velocity().y(), 0.0D);
        for (BedrockNextTickStateDeriver.DerivedState state : states) {
            assertSame(source, state.lineageState());
        }
    }

    @Test
    public void horizontalCollisionOnClimbableAddsOnlyNamedClimbableVelocityState() {
        BlockCollisionWorld ladderWorld = new BlockCollisionWorld(List.of(
                PlacedBlockCollision.manual(
                        new BlockPosition(0, 64, 0),
                        "minecraft:ladder",
                        "minecraft:ladder",
                        List.of())));
        BedrockCollisionFlags flags = new BedrockCollisionFlags(false, true, false);
        BedrockMovementState source = state(POSITION, ZERO, flags, Medium.AIR);
        Vec3d acceptedDiff = new Vec3d(0.0D, -0.2D, 0.0D);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        movementResult(source, source, context(Medium.AIR, ladderWorld)),
                        List.of(source),
                        acceptedDiff);

        assertEquals(2, states.size());
        assertTrue(hasVelocityY(states, BedrockClimbMovement.LADDER_ASCEND_VELOCITY));
        for (BedrockNextTickStateDeriver.DerivedState state : states) {
            assertSame(source, state.lineageState());
        }
    }

    @Test
    public void predictedAutoClimbAddsOnlyNamedClimbableVelocityState() {
        BedrockMovementState source = state(POSITION, ZERO, BedrockCollisionFlags.AIR, Medium.AIR);
        BedrockMovementState predicted = source.withAutoClimbTravel(true);
        Vec3d acceptedDiff = new Vec3d(0.0D, 0.1D, 0.0D);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        movementResult(source, predicted, context(Medium.AIR, BlockCollisionWorld.EMPTY)),
                        List.of(source),
                        acceptedDiff);

        assertEquals(2, states.size());
        assertTrue(hasVelocityY(states, BedrockClimbMovement.LADDER_ASCEND_VELOCITY));
        for (BedrockNextTickStateDeriver.DerivedState state : states) {
            assertSame(source, state.lineageState());
        }
    }

    @Test
    public void climbEndTickVelocityRebuildsHorizontalFromAcceptedDiffBeforeReplacingY() {
        Vec3d deterministicVelocity = new Vec3d(0.04D, 0.0D, -0.03D);
        BedrockMovementState source = state(POSITION, deterministicVelocity, BedrockCollisionFlags.AIR, Medium.AIR);
        BedrockMovementState predicted = source.withAutoClimbTravel(true);
        Vec3d acceptedDiff = new Vec3d(0.2D, 0.1D, -0.15D);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        movementResult(source, predicted, context(Medium.AIR, BlockCollisionWorld.EMPTY)),
                        List.of(source),
                        acceptedDiff);

        assertEquals(2, states.size());
        BedrockMovementState climb = states.stream()
                .map(BedrockNextTickStateDeriver.DerivedState::state)
                .filter(state -> state.velocity().y() == BedrockClimbMovement.LADDER_ASCEND_VELOCITY)
                .findFirst()
                .orElseThrow();
        assertEquals(acceptedDiff.x(), climb.velocity().x(), 1.0E-12D);
        assertEquals(acceptedDiff.z(), climb.velocity().z(), 1.0E-12D);
    }

    @Test
    public void scaffoldingDescendKeepsNormalGravityStateBesideCarryAlternative() {
        double descend = BedrockClimbMovement.SCAFFOLDING_DESCEND_VELOCITY;
        double carried = BedrockAerialMovement.airDraggedVelocityWithoutGravity(descend);
        BedrockMovementState previous = state(
                POSITION,
                new Vec3d(0.0D, carried, 0.0D),
                BedrockCollisionFlags.AIR,
                Medium.AIR);
        BedrockMovementState predicted = state(
                POSITION.add(new Vec3d(0.0D, descend, 0.0D)),
                ZERO,
                BedrockCollisionFlags.AIR,
                Medium.AIR);
        Vec3d rawPredictedPosition = POSITION.add(new Vec3d(0.0D, descend, 0.0D));
        BedrockMovementResult result = movementResult(
                previous,
                predicted,
                rawPredictedPosition,
                context(Medium.AIR, BlockCollisionWorld.EMPTY),
                1.0D);
        assertEquals(descend,
                result.rawPredictedPhysicalFeetPosition().y() - result.previousState().physicalFeetPosition().y(),
                1.0E-9D);
        assertEquals(carried, result.collisionInputVelocity().y(), 1.0E-9D);
        assertFalse(result.movementContext().inWater());
        assertFalse(result.movementContext().inLava());
        assertEquals(1, BedrockClimbEndTickVelocityBranches.velocityYBranches(result, previous).size());

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        result,
                        List.of(previous),
                        new Vec3d(0.0D, descend, 0.0D));

        assertEquals(2, states.size());
        assertTrue(hasVelocityY(states,
                BedrockNextTickStateDeriver.airDraggedVelocityWithGravity(descend)));
        assertTrue(hasVelocityY(states, carried));
    }

    @Test
    public void acceptedUnclippedDescentDoesNotReuseCandidateVerticalCollision() {
        Vec3d acceptedDiff = new Vec3d(0.0D, -0.15D, 0.0D);
        Vec3d acceptedPosition = POSITION.add(acceptedDiff);
        BedrockMovementState previous = state(
                POSITION,
                acceptedDiff,
                BedrockCollisionFlags.AIR,
                Medium.AIR);
        BedrockMovementState acceptedSource = state(
                acceptedPosition,
                ZERO,
                BedrockCollisionFlags.ON_GROUND,
                Medium.AIR);
        BlockCollisionWorld world = blockWorld(
                fullBlock(100, 60, 100, "minecraft:stone"));
        BedrockMovementResult result = movementResult(
                previous,
                acceptedSource,
                acceptedPosition,
                context(Medium.AIR, world),
                1.0D);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        result, List.of(acceptedSource), acceptedDiff);

        assertEquals(1, states.size());
        assertEquals(
                BedrockNextTickStateDeriver.airDraggedVelocityWithGravity(acceptedDiff.y()),
                states.getFirst().state().velocity().y(),
                0.0D);
    }

    @Test
    public void verticalCollisionWithoutObservableBounceBlockDoesNotCarryCandidateBounce() {
        double acceptedPacketDeltaY = -0.2D;
        double simulatedBounceVelocityY = 0.12D;
        BedrockMovementState previous = state(
                POSITION,
                new Vec3d(0.0D, -0.24D, 0.0D),
                BedrockCollisionFlags.AIR,
                Medium.AIR);
        BedrockMovementState predicted = state(
                new Vec3d(POSITION.x(), POSITION.y() + acceptedPacketDeltaY, POSITION.z()),
                new Vec3d(0.0D, simulatedBounceVelocityY, 0.0D),
                BedrockCollisionFlags.ON_GROUND,
                Medium.AIR);
        BedrockMovementState acceptedSource = state(
                predicted.physicalFeetPosition(),
                new Vec3d(0.0D, acceptedPacketDeltaY, 0.0D),
                BedrockCollisionFlags.ON_GROUND,
                Medium.AIR);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        movementResult(previous, predicted, context(Medium.AIR, BlockCollisionWorld.EMPTY)),
                        List.of(acceptedSource),
                        new Vec3d(0.0D, acceptedPacketDeltaY, 0.0D));

        assertEquals(1, states.size());
        assertEquals(
                BedrockNextTickStateDeriver.airDraggedVelocityWithGravity(0.0D),
                states.getFirst().state().velocity().y(),
                0.0D);
    }

    @Test
    public void glidingLandingKeepsCollisionResetUntilNormalGravityRunsNextTick() {
        Vec3d groundPosition = new Vec3d(100.5D, 64.0D, 100.5D);
        BlockCollisionWorld world = blockWorld(fullBlock(100, 63, 100, "minecraft:stone"));
        BedrockMovementContext context = context(Medium.AIR, world);

        BedrockMovementState glidingPrevious = state(
                new Vec3d(100.5D, 64.2D, 100.5D),
                new Vec3d(0.0D, -0.24D, 0.0D),
                BedrockCollisionFlags.AIR,
                Medium.AIR);
        BedrockMovementState landed = state(
                groundPosition,
                ZERO,
                BedrockCollisionFlags.ON_GROUND,
                Medium.GROUND);
        BedrockMovementResult glidingLanding = movementResult(
                glidingPrevious,
                landed,
                new Vec3d(groundPosition.x(), 63.96D, groundPosition.z()),
                context,
                1.0D,
                false,
                true);

        BedrockMovementState landingCarry = BedrockNextTickStateDeriver.withAcceptedDiff(
                glidingLanding,
                List.of(landed),
                groundPosition.subtract(glidingPrevious.physicalFeetPosition()))
                .getFirst().state();
        assertEquals(0.0D, landingCarry.velocity().y(), 0.0D);

        BedrockMovementState steppedLandingCarry = BedrockNextTickStateDeriver.withAcceptedDiff(
                glidingLanding,
                List.of(landed),
                groundPosition.subtract(glidingPrevious.physicalFeetPosition()),
                false,
                false,
                true)
                .getFirst().state();
        assertEquals(0.0D, steppedLandingCarry.velocity().y(), 0.0D);

        BedrockCollisionFlags groundedWithoutCurrentCollision =
                new BedrockCollisionFlags(true, false, false);
        BedrockMovementState stoppedGliding = state(
                groundPosition,
                ZERO,
                groundedWithoutCurrentCollision,
                Medium.GROUND);
        BedrockMovementResult stopGlidingTick = movementResult(
                landingCarry,
                stoppedGliding,
                groundPosition,
                context,
                1.0D,
                false,
                false);

        BedrockMovementState normalGravityCarry = BedrockNextTickStateDeriver.withAcceptedDiff(
                stopGlidingTick,
                List.of(stoppedGliding),
                ZERO)
                .getFirst().state();
        double gravityCarry = BedrockNextTickStateDeriver.airDraggedVelocityWithGravity(0.0D);
        assertEquals(gravityCarry, normalGravityCarry.velocity().y(), 0.0D);
        assertFalse(normalGravityCarry.collisionFlags().verticalCollision());

        BedrockMovementState nextGroundCollision = state(
                groundPosition,
                new Vec3d(0.0D, gravityCarry, 0.0D),
                BedrockCollisionFlags.ON_GROUND,
                Medium.GROUND);
        BedrockMovementResult groundedTick = movementResult(
                normalGravityCarry,
                nextGroundCollision,
                groundPosition.add(new Vec3d(0.0D, gravityCarry, 0.0D)),
                context,
                1.0D,
                false,
                false);

        BedrockMovementState groundedCarry = BedrockNextTickStateDeriver.withAcceptedDiff(
                groundedTick,
                List.of(nextGroundCollision),
                ZERO)
                .getFirst().state();
        assertEquals(gravityCarry, groundedCarry.velocity().y(), 0.0D);
        assertTrue(groundedCarry.collisionFlags().verticalCollision());
    }

    @Test
    public void acceptedPostMoveBounceVelocityWinsBeforeGenericVerticalCollisionReset() {
        BlockCollisionWorld world = new BlockCollisionWorld(List.of(
                PlacedBlockCollision.manual(
                        new BlockPosition(302, 82, -91),
                        "minecraft:bed",
                        "minecraft:bed[facing=north,occupied=false,part=foot]",
                        List.of(new ac.grim.grimac.bedrock.prediction.geometry.WorldCollisionBox(
                                302.0D,
                                82.0D,
                                -91.0D,
                                303.0D,
                                82.5625D,
                                -90.0D)))));
        Vec3d previousPosition = new Vec3d(302.8486328125D, 82.12128448486328D, -90.72260284423828D);
        Vec3d fallingVelocity = new Vec3d(0.0450445556640625D, -0.44482332468032837D, -0.020571365356445312D);
        Vec3d acceptedPosition = new Vec3d(302.8936767578125D, 82.5625D, -90.74317420959473D);
        Vec3d acceptedDiff = acceptedPosition.subtract(previousPosition);
        BedrockMovementState previous = state(
                previousPosition,
                fallingVelocity,
                BedrockCollisionFlags.AIR,
                Medium.AIR);
        BedrockMovementState predicted = state(
                acceptedPosition,
                new Vec3d(fallingVelocity.x(), 0.3203887939453125D, fallingVelocity.z()),
                BedrockCollisionFlags.ON_GROUND,
                Medium.AIR);
        BedrockMovementState acceptedSource = state(
                acceptedPosition,
                acceptedDiff,
                BedrockCollisionFlags.ON_GROUND,
                Medium.AIR);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        movementResult(
                                previous,
                                predicted,
                                previousPosition.add(fallingVelocity),
                                context(Medium.AIR, world),
                                1.0D,
                                false),
                        List.of(acceptedSource),
                        acceptedDiff);

        assertEquals(1, states.size());
        assertEquals(0.3203887939453125D, states.getFirst().state().velocity().y(), 1.0E-6D);
    }

    @Test
    public void bedReplayTick39AcceptedPostMoveBounceCarriesVelocity() {
        BlockCollisionWorld world = new BlockCollisionWorld(List.of(
                PlacedBlockCollision.manual(
                        new BlockPosition(303, 82, -93),
                        "minecraft:bed",
                        "minecraft:bed[facing=north,occupied=false,part=foot]",
                        List.of(new ac.grim.grimac.bedrock.prediction.geometry.WorldCollisionBox(
                                303.0D,
                                82.0D,
                                -93.0D,
                                304.0D,
                                82.5625D,
                                -92.0D)))));
        Vec3d previousPosition = new Vec3d(303.0367431640625D, 82.74946594238281D, -92.23131561279297D);
        Vec3d fallingVelocity = new Vec3d(-0.003685224335640669D, -0.19685401022434235D, -0.19502107799053192D);
        Vec3d acceptedPosition = new Vec3d(303.0330505371094D, 82.5625D, -92.42633819580078D);
        Vec3d acceptedDiff = acceptedPosition.subtract(previousPosition);
        BedrockMovementState previous = state(
                previousPosition,
                fallingVelocity,
                BedrockCollisionFlags.AIR,
                Medium.AIR);
        BedrockMovementState predicted = state(
                acceptedPosition,
                new Vec3d(fallingVelocity.x(), 0.1301727294921875D, fallingVelocity.z()),
                BedrockCollisionFlags.ON_GROUND,
                Medium.AIR);
        BedrockMovementState acceptedSource = state(
                acceptedPosition,
                acceptedDiff,
                BedrockCollisionFlags.ON_GROUND,
                Medium.AIR);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        movementResult(
                                previous,
                                predicted,
                                previousPosition.add(fallingVelocity),
                                context(Medium.AIR, world),
                                1.0D,
                                false),
                        List.of(acceptedSource),
                        acceptedDiff);

        assertEquals(1, states.size());
        assertEquals(0.1301727294921875D, states.getFirst().state().velocity().y(), 1.0E-6D);
    }

    @Test
    public void acceptedStoneSideOfSlimeBoundaryDoesNotRetainCandidateBounce() {
        BlockCollisionWorld world = stoneSlimeBoundaryWorld();
        Vec3d previousPosition = new Vec3d(297.4566345214844D, 82.0D, -92.2D);
        Vec3d acceptedPosition = new Vec3d(297.4566345214844D, 82.0D, -92.01184844970703D);
        Vec3d acceptedDiff = acceptedPosition.subtract(previousPosition);
        Vec3d fallingVelocity = new Vec3d(0.0D, -0.4D, 0.0D);
        BedrockMovementState previous = state(
                previousPosition, fallingVelocity, BedrockCollisionFlags.AIR, Medium.AIR);
        BedrockMovementState candidateBounce = state(
                acceptedPosition,
                new Vec3d(0.0D, 0.37840333580970764D, 0.0D),
                BedrockCollisionFlags.ON_GROUND,
                Medium.AIR);
        BedrockMovementState acceptedSource = state(
                acceptedPosition, acceptedDiff, BedrockCollisionFlags.ON_GROUND, Medium.AIR);
        BedrockMovementResult result = movementResult(
                previous,
                candidateBounce,
                previousPosition.add(new Vec3d(acceptedDiff.x(), fallingVelocity.y(), acceptedDiff.z())),
                context(Medium.AIR, world),
                1.0D,
                true);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(result, List.of(acceptedSource), acceptedDiff);

        assertEquals(1, states.size());
        assertEquals(
                BedrockNextTickStateDeriver.airDraggedVelocityWithGravity(0.0D),
                states.getFirst().state().velocity().y(),
                0.0D);
    }

    @Test
    public void acceptedSlimeSideOfBoundaryAppliesBounce() {
        BlockCollisionWorld world = stoneSlimeBoundaryWorld();
        Vec3d previousPosition = new Vec3d(297.4566345214844D, 82.0D, -91.8D);
        Vec3d acceptedPosition = new Vec3d(297.4566345214844D, 82.0D, -91.98815155029297D);
        Vec3d acceptedDiff = acceptedPosition.subtract(previousPosition);
        Vec3d fallingVelocity = new Vec3d(0.0D, -0.4D, 0.0D);
        BedrockMovementState previous = state(
                previousPosition, fallingVelocity, BedrockCollisionFlags.AIR, Medium.AIR);
        BedrockMovementState predicted = state(
                acceptedPosition, ZERO, BedrockCollisionFlags.ON_GROUND, Medium.AIR);
        BedrockMovementState acceptedSource = state(
                acceptedPosition, acceptedDiff, BedrockCollisionFlags.ON_GROUND, Medium.AIR);
        BedrockMovementResult result = movementResult(
                previous,
                predicted,
                previousPosition.add(new Vec3d(acceptedDiff.x(), fallingVelocity.y(), acceptedDiff.z())),
                context(Medium.AIR, world),
                1.0D,
                false);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(result, List.of(acceptedSource), acceptedDiff);

        assertEquals(1, states.size());
        assertTrue(states.getFirst().state().velocity().y() > 0.0D);
    }

    @Test
    public void glidingSlimeLandingCarriesRawRestitutionIntoStopGlidingTick() {
        double fallingVelocityY = -0.6792831420898438D;
        double collisionTravelY = -0.03989410400390625D;
        double rawRestitutionY = -(float) fallingVelocityY;
        Vec3d landingPosition = new Vec3d(241.5D, 82.0D, -77.5D);
        Vec3d previousPosition = landingPosition.subtract(new Vec3d(0.0D, collisionTravelY, 0.0D));
        Vec3d landingDiff = landingPosition.subtract(previousPosition);
        BlockCollisionWorld world = blockWorld(fullBlock(241, 81, -78, "minecraft:slime_block"));
        BedrockMovementContext context = context(Medium.AIR, world);
        BedrockMovementState previous = state(
                previousPosition,
                new Vec3d(0.0D, fallingVelocityY, 0.0D),
                BedrockCollisionFlags.AIR,
                Medium.AIR);
        BedrockMovementState landed = state(
                landingPosition,
                ZERO,
                BedrockCollisionFlags.ON_GROUND,
                Medium.GROUND);
        BedrockMovementResult glidingLanding = movementResult(
                previous,
                landed,
                previousPosition.add(new Vec3d(0.0D, fallingVelocityY, 0.0D)),
                context,
                1.0D,
                false,
                true);
        BedrockMovementState landingCarry = BedrockNextTickStateDeriver.withAcceptedDiff(
                glidingLanding,
                List.of(landed),
                landingDiff)
                .getFirst().state();
        assertEquals(rawRestitutionY, landingCarry.velocity().y(), 0.0D);

        Vec3d stoppedGlidingPosition = landingPosition.add(new Vec3d(0.0D, rawRestitutionY, 0.0D));
        BedrockMovementState stoppedGliding = state(
                stoppedGlidingPosition,
                new Vec3d(0.0D, rawRestitutionY, 0.0D),
                BedrockCollisionFlags.AIR,
                Medium.AIR);
        BedrockMovementResult stopGlidingTick = movementResult(
                landingCarry,
                stoppedGliding,
                stoppedGlidingPosition,
                context,
                1.0D,
                false,
                false);

        BedrockMovementState normalAirCarry = BedrockNextTickStateDeriver.withAcceptedDiff(
                stopGlidingTick,
                List.of(stoppedGliding),
                stoppedGlidingPosition.subtract(landingPosition))
                .getFirst().state();
        assertEquals(
                BedrockNextTickStateDeriver.airDraggedVelocityWithGravity(rawRestitutionY),
                normalAirCarry.velocity().y(),
                0.0D);
    }

    @Test
    public void selectedUnknownCollisionStepCommitsVerticalCollisionState() {
        Vec3d previousPosition = new Vec3d(310.19219970703125D, 82.18250274658203D, -114.26569366455078D);
        Vec3d acceptedDiff = new Vec3d(0.0323486328125D, 0.4174957275390625D, 0.01369476318359375D);
        Vec3d acceptedPosition = previousPosition.add(acceptedDiff);
        BedrockMovementState previous = state(
                previousPosition,
                new Vec3d(0.032342103335940925D, -0.07840000092983246D, 0.013696655817693682D),
                BedrockCollisionFlags.ON_GROUND,
                Medium.GROUND);
        BedrockMovementState predicted = state(
                previousPosition, ZERO, BedrockCollisionFlags.ON_GROUND, Medium.GROUND);
        BedrockMovementState acceptedSource = state(
                acceptedPosition, acceptedDiff, BedrockCollisionFlags.ON_GROUND, Medium.GROUND);
        BedrockMovementResult result = movementResult(
                previous,
                predicted,
                previousPosition.add(new Vec3d(acceptedDiff.x(), -0.07840000092983246D, acceptedDiff.z())),
                context(Medium.AIR, BlockCollisionWorld.EMPTY),
                1.0D,
                false);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(
                        result, List.of(acceptedSource), acceptedDiff, false, false, true);

        assertEquals(1, states.size());
        assertEquals(
                BedrockNextTickStateDeriver.airDraggedVelocityWithGravity(0.0D),
                states.getFirst().state().velocity().y(),
                0.0D);
        assertTrue(states.getFirst().state().collisionFlags().verticalCollision());
    }

    @Test
    public void flag62StoneSideDoesNotRetainAdjacentSlimeCandidateBounce() {
        BlockCollisionWorld world = blockWorld(
                fullBlock(245, 81, -78, "minecraft:stone"),
                fullBlock(245, 81, -79, "minecraft:slime_block"));
        Vec3d previousPosition = new Vec3d(245.44757080078125D, 82.0D, -77.9D);
        Vec3d acceptedPosition = new Vec3d(245.4807586669922D, 82.0D, -77.8614273071289D);
        Vec3d acceptedDiff = acceptedPosition.subtract(previousPosition);
        Vec3d fallingVelocity = new Vec3d(0.03318634033203125D, -0.36D, 0.12842697143554688D);
        BedrockMovementState previous = state(
                previousPosition, fallingVelocity, BedrockCollisionFlags.AIR, Medium.AIR);
        BedrockMovementState candidateBounce = state(
                acceptedPosition,
                new Vec3d(fallingVelocity.x(), 0.3428112268447876D, fallingVelocity.z()),
                BedrockCollisionFlags.ON_GROUND,
                Medium.AIR);
        BedrockMovementState acceptedSource = state(
                acceptedPosition, acceptedDiff, BedrockCollisionFlags.ON_GROUND, Medium.AIR);
        BedrockMovementResult result = movementResult(
                previous,
                candidateBounce,
                previousPosition.add(new Vec3d(acceptedDiff.x(), fallingVelocity.y(), acceptedDiff.z())),
                context(Medium.AIR, world),
                1.0D,
                true);

        List<BedrockNextTickStateDeriver.DerivedState> states =
                BedrockNextTickStateDeriver.withAcceptedDiff(result, List.of(acceptedSource), acceptedDiff);

        assertEquals(1, states.size());
        assertEquals(
                BedrockNextTickStateDeriver.airDraggedVelocityWithGravity(0.0D),
                states.getFirst().state().velocity().y(),
                0.0D);
    }

    private static BlockCollisionWorld stoneSlimeBoundaryWorld() {
        return blockWorld(
                fullBlock(297, 81, -93, "minecraft:stone"),
                fullBlock(297, 81, -92, "minecraft:slime_block"));
    }

    private static PlacedBlockCollision fullBlock(int x, int y, int z, String block) {
        return PlacedBlockCollision.manual(
                new BlockPosition(x, y, z),
                block,
                block,
                List.of(new WorldCollisionBox(
                        x, y, z, x + 1.0D, y + 1.0D, z + 1.0D)));
    }

    private static boolean hasVelocityY(
            List<BedrockNextTickStateDeriver.DerivedState> states,
            double velocityY
    ) {
        return states.stream().anyMatch(state -> Math.abs(state.state().velocity().y() - velocityY) <= 1.0E-12D);
    }

    private static boolean hasVelocityXZ(
            List<BedrockNextTickStateDeriver.DerivedState> states,
            double velocityX,
            double velocityZ
    ) {
        return states.stream().anyMatch(state ->
                Math.abs(state.state().velocity().x() - velocityX) <= 1.0E-12D
                        && Math.abs(state.state().velocity().z() - velocityZ) <= 1.0E-12D);
    }

    private static BlockCollisionWorld blockWorld(PlacedBlockCollision... blocks) {
        return new BlockCollisionWorld(List.of(blocks));
    }

    private static PlacedBlockCollision fullBlock(int x, int y, int z) {
        return PlacedBlockCollision.manual(
                new BlockPosition(x, y, z),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(
                        x,
                        y,
                        z,
                        x + 1.0D,
                        y + 1.0D,
                        z + 1.0D)));
    }

    private static BedrockMovementState state(
            Vec3d position,
            Vec3d velocity,
            BedrockCollisionFlags flags,
            Medium movementBranch
    ) {
        return BedrockMovementState.fromPhysicalFeet(
                position,
                velocity,
                BedrockInputFrame.idle(0L),
                flags,
                movementBranch);
    }

    private static BedrockMovementState state(
            Vec3d position,
            Vec3d velocity,
            BedrockInputFrame frame,
            BedrockCollisionFlags flags,
            Medium movementBranch
    ) {
        return BedrockMovementState.fromPhysicalFeet(
                position,
                velocity,
                frame,
                flags,
                movementBranch);
    }

    private static BedrockMovementResult movementResult(
            BedrockMovementState previous,
            BedrockMovementState predicted,
            BedrockMovementContext context
    ) {
        return movementResult(previous, predicted, context, false);
    }

    private static BedrockMovementResult movementResult(
            BedrockMovementState previous,
            BedrockMovementState predicted,
            BedrockMovementContext context,
            boolean standingBounceBounced
    ) {
        return movementResult(
                previous,
                predicted,
                predicted.physicalFeetPosition(),
                context,
                1.0D,
                standingBounceBounced);
    }

    private static BedrockMovementResult movementResult(
            BedrockMovementState previous,
            BedrockMovementState predicted,
            Vec3d rawPredictedPosition,
            BedrockMovementContext context,
            double horizontalFriction
    ) {
        return movementResult(previous, predicted, rawPredictedPosition, context, horizontalFriction, false);
    }

    private static BedrockMovementResult movementResult(
            BedrockMovementState previous,
            BedrockMovementState predicted,
            Vec3d rawPredictedPosition,
            BedrockMovementContext context,
            double horizontalFriction,
            boolean standingBounceBounced
    ) {
        return movementResult(
                previous,
                predicted,
                rawPredictedPosition,
                context,
                horizontalFriction,
                standingBounceBounced,
                false);
    }

    private static BedrockMovementResult movementResult(
            BedrockMovementState previous,
            BedrockMovementState predicted,
            Vec3d rawPredictedPosition,
            BedrockMovementContext context,
            double horizontalFriction,
            boolean standingBounceBounced,
            boolean selectedGlidingTravel
    ) {
        return new BedrockMovementResult(
                previous,
                context,
                context,
                predicted,
                rawPredictedPosition,
                previous.velocity(),
                false,
                false,
                standingBounceBounced,
                false,
                BlockMovementSlowdownState.NONE,
                HoneySlideState.NONE,
                selectedGlidingTravel,
                predicted.waterTravelFlag(),
                0.0D,
                horizontalFriction,
                false,
                false,
                false,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP);
    }

    private static BedrockMovementContext context(Medium medium, BlockCollisionWorld blockWorld) {
        return new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(medium, FluidState.NONE, blockWorld),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
    }
}
