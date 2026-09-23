package ac.cult.cultac.bedrock.prediction.simulation;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockBoundingBoxMode;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockSneakEdgeMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.simulation.travel.BedrockMoveRequest;
import ac.cult.cultac.bedrock.prediction.simulation.travel.BedrockResolvedMove;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class BedrockSneakEdgeMovementTest {
    private static final PlayerDimensionsState SNEAKING = new PlayerDimensionsState(0.6F, 1.5F);
    private static final BedrockCoordinateFrame ORIGIN = new BedrockCoordinateFrame(0, -3328, 1);
    private static final BedrockInputFrame FRAME = new BedrockInputFrame(
        3174L, -137.4837F, 60.146454F, false, true, false,
        Set.of("ACTOR_SNEAKING", "SNEAK_DOWN", "SNEAK_CURRENT_RAW", "SNEAKING", "ACTOR_POSE_SNAPSHOT"));

    @Test
    public void flag81StopsAtWestLedgeWhileContinuingAlongZ() {
        // Flag 81, auth tick 3178: logged carry and controls over a reconstructed
        // full-block west ledge. The debug log does not contain the complete world.
        Vec3d start = new Vec3d(2364.763671875D, 64.0D, -3524.8712005615234D);
        Vec3d carry = new Vec3d(-0.02364056184887886D, -0.07840000092983246D, 0.02560042217373848D);
        BedrockMovementState previous = state(start, carry, BedrockCollisionFlags.ON_GROUND, ORIGIN);
        BlockCollisionWorld world = world(new WorldCollisionBox(2365, 63, -3527, 2368, 64, -3521), ORIGIN);

        BedrockMovementResult result = simulate(previous, FRAME, world, new Vec3d(0, 0, -0.3F));

        assertEquals(start.x(), result.predictedPosition().x(), 0.0D);
        assertEquals(64.0D, result.predictedPosition().y(), 0.0D);
        assertEquals(-3524.823928833008D, result.predictedPosition().z(), 0.00001D);
        assertEquals(0.0D, result.predictedVelocity().x(), 0.0D);
        assertEquals(0.025809818878769875D, result.predictedVelocity().z(), 0.000001D);
        assertFalse("Sneak backoff is not a wall collision", result.predictedState().collisionFlags().horizontalCollision());
        assertTrue(result.predictedState().collisionFlags().onGround());
        assertEquals("Replaying the same tick must be deterministic", result,
            simulate(previous, FRAME, world, new Vec3d(0, 0, -0.3F)));
        for (BedrockMovementState next : BedrockForwardTick.finish(result, result.predictedState())) {
            assertEquals("End-of-tick carry must retain the zeroed axis", 0.0D, next.velocity().x(), 0.0D);
        }
    }

    @Test
    public void fullyStoppedEdgeDoesNotCarryHiddenHorizontalVelocity() {
        Vec3d start = new Vec3d(1.3F, 1, 0.5F);
        BedrockMovementState previous = state(start, new Vec3d(0.06F, -0.0784F, 0),
            BedrockCollisionFlags.ON_GROUND, BedrockCoordinateFrame.IDENTITY);
        BlockCollisionWorld world = world(new WorldCollisionBox(0, 0, 0, 1, 1, 1), BedrockCoordinateFrame.IDENTITY);
        // Already outside the shrunk support box: both backoff steps must stop.
        BedrockMovementResult result = simulate(previous, FRAME, world, Vec3d.ZERO);

        assertEquals(previous.physicalFeetPosition().x(), result.predictedPosition().x(), 0.0D);
        assertEquals(0.0D, result.predictedVelocity().x(), 0.0D);
        assertFalse(result.predictedState().collisionFlags().horizontalCollision());
    }

    @Test
    public void allFourEdgesStopOnlyTheOutwardAxis() {
        BlockCollisionWorld world = world(new WorldCollisionBox(0, 0, 0, 1, 1, 1), BedrockCoordinateFrame.IDENTITY);
        for (Vec3d direction : List.of(new Vec3d(1, 0, 0), new Vec3d(-1, 0, 0),
                new Vec3d(0, 0, 1), new Vec3d(0, 0, -1))) {
            Vec3d start = new Vec3d(0.5F + direction.x() * 0.76F, 1, 0.5F + direction.z() * 0.76F);
            Vec3d move = new Vec3d(direction.x() * 0.02F, -0.0784F, direction.z() * 0.02F);
            BedrockMovementState previous = state(start, move, BedrockCollisionFlags.ON_GROUND, BedrockCoordinateFrame.IDENTITY);
            BedrockMoveRequest result = adjust(previous, world, SNEAKING, 0.5625D, move);

            assertEquals(new Vec3d(0, move.y(), 0), result.move());
            assertEquals(new Vec3d(0, move.y(), 0), result.collisionInputVelocity());
        }
    }

    @Test
    public void partialBackoffKeepsMomentumForTheFollowingTick() {
        BlockCollisionWorld world = world(new WorldCollisionBox(0, 0, 0, 1, 1, 1), BedrockCoordinateFrame.IDENTITY);
        Vec3d move = new Vec3d(0.12F, -0.0784F, 0);
        BedrockMovementState previous = state(new Vec3d(1.20F, 1, 0.5F), move,
            BedrockCollisionFlags.ON_GROUND, BedrockCoordinateFrame.IDENTITY);

        BedrockMoveRequest result = adjust(previous, world, SNEAKING, 0.5625D, move);

        assertEquals((double) (0.12F - 0.05F), result.move().x(), 0.0D);
        assertEquals("Partial backoff must not erase velocity", move, result.collisionInputVelocity());
    }

    @Test
    public void diagonalCornerChecksCombinedSupportAfterEachAxis() {
        var xArm = world(new WorldCollisionBox(0, 0, 0, 1, 1, 2), BedrockCoordinateFrame.IDENTITY).blocks().getFirst();
        var zArm = world(new WorldCollisionBox(0, 0, 0, 2, 1, 1), BedrockCoordinateFrame.IDENTITY).blocks().getFirst();
        BlockCollisionWorld world = new BlockCollisionWorld(List.of(xArm, zArm));
        Vec3d move = new Vec3d(0.04F, -0.0784F, 0.04F);
        BedrockMovementState previous = state(new Vec3d(1.24F, 1, 1.24F), move,
            BedrockCollisionFlags.ON_GROUND, BedrockCoordinateFrame.IDENTITY);

        assertEquals(0.04F, adjust(previous, world, SNEAKING, 0.5625D, new Vec3d(0.04F, 0, 0)).move().x(), 0.0D);
        assertEquals(0.04F, adjust(previous, world, SNEAKING, 0.5625D, new Vec3d(0, 0, 0.04F)).move().z(), 0.0D);
        BedrockMoveRequest result = adjust(previous, world, SNEAKING, 0.5625D, move);
        assertEquals(new Vec3d(0, move.y(), 0), result.move());
        assertEquals(new Vec3d(0, move.y(), 0), result.collisionInputVelocity());
    }

    @Test
    public void airborneOrReleasedSneakDoesNotAcquireEdgeProtection() {
        BlockCollisionWorld world = world(new WorldCollisionBox(0, 0, 0, 1, 1, 1), BedrockCoordinateFrame.IDENTITY);
        Vec3d move = new Vec3d(0.1F, 0, 0);
        BedrockMovementState airborne = state(new Vec3d(1.26F, 1.1F, 0.5F), move,
            BedrockCollisionFlags.AIR, BedrockCoordinateFrame.IDENTITY).withMovementBranch(Medium.GROUND);
        BedrockMoveRequest request = request(airborne, move);
        assertSame(request, BedrockSneakEdgeMovement.applyBeforeCollision(
            airborne, true, SNEAKING, 0.5625D, request, world));
        BedrockMovementState grounded = state(new Vec3d(1.26F, 1, 0.5F), move,
            BedrockCollisionFlags.ON_GROUND, BedrockCoordinateFrame.IDENTITY);
        request = request(grounded, move);
        assertSame(request, BedrockSneakEdgeMovement.applyBeforeCollision(
            grounded, false, SNEAKING, 0.5625D, request, world));
    }

    @Test
    public void supportUsesAcknowledgedWidthAndHeight() {
        Vec3d move = new Vec3d(0.04F, 0, 0);
        BedrockMovementState previous = state(new Vec3d(1.08F, 1, 0.5F), move,
            BedrockCollisionFlags.ON_GROUND, BedrockCoordinateFrame.IDENTITY);
        BlockCollisionWorld floor = world(new WorldCollisionBox(0, 0, 0, 1, 1, 1), BedrockCoordinateFrame.IDENTITY);
        assertEquals(0.0D, adjust(previous, floor, new PlayerDimensionsState(0.2F, 1.5F), 0.5625D, move).move().x(), 0.0D);

        // The old default 1.8-high probe reaches this ceiling after translating down;
        // the actual 1.5-high box has no supporting intersection.
        BlockCollisionWorld ceiling = world(new WorldCollisionBox(0, 2, 0, 2, 3, 1), BedrockCoordinateFrame.IDENTITY);
        assertEquals(0.0D, adjust(previous, ceiling, SNEAKING, 0.5625D, move).move().x(), 0.0D);
        assertEquals(move.x(), adjust(previous, ceiling, PlayerDimensionsState.DEFAULT, 0.5625D, move).move().x(), 0.0D);
    }

    @Test
    public void stepDownUsesTheActorsStepHeightTimesOnePointZeroOne() {
        Vec3d move = new Vec3d(0.04F, 0, 0);
        BedrockMovementState previous = state(new Vec3d(0.5F, 1, 0.5F), move,
            BedrockCollisionFlags.ON_GROUND, BedrockCoordinateFrame.IDENTITY);
        BlockCollisionWorld lowerSupport = world(new WorldCollisionBox(0, 0, 0, 1, 0.749F, 1), BedrockCoordinateFrame.IDENTITY);

        assertEquals(move.x(), adjust(previous, lowerSupport, SNEAKING, 0.25D, move).move().x(), 0.0D);
        assertEquals(0.0D, adjust(previous, lowerSupport, SNEAKING, 0.2D, move).move().x(), 0.0D);
    }

    private static BedrockMoveRequest adjust(BedrockMovementState previous, BlockCollisionWorld world,
                                              PlayerDimensionsState dimensions, double step, Vec3d move) {
        return BedrockSneakEdgeMovement.applyBeforeCollision(previous, true, dimensions, step, request(previous, move), world);
    }

    private static BedrockMoveRequest request(BedrockMovementState previous, Vec3d move) {
        return new BedrockMoveRequest(new BedrockResolvedMove(false, move, move), previous.physicalFeetPosition().add(move));
    }

    private static BedrockMovementState state(Vec3d feet, Vec3d velocity, BedrockCollisionFlags flags,
                                               BedrockCoordinateFrame origin) {
        return BedrockMovementState.fromPhysicalFeet(feet, velocity, FRAME, flags,
                flags.onGround() ? Medium.GROUND : Medium.AIR, origin)
            .withPlayerDimensions(BedrockBoundingBoxMode.SNEAKING, SNEAKING, true);
    }

    private static BlockCollisionWorld world(WorldCollisionBox box, BedrockCoordinateFrame origin) {
        return new BlockCollisionWorld(List.of(PlacedBlockCollision.manual(
            new BlockPosition((int) Math.floor(box.minX()), (int) Math.floor(box.minY()), (int) Math.floor(box.minZ())),
            "minecraft:stone", "minecraft:stone", List.of(box))), origin);
    }

    private static BedrockMovementResult simulate(BedrockMovementState state, BedrockInputFrame frame,
                                                   BlockCollisionWorld world, Vec3d control) {
        BedrockMovementContext context = new BedrockMovementContext(BedrockEffectState.NONE, AttributeState.DEFAULT,
            new WorldContactState(Medium.AIR, FluidState.NONE, world), EquipmentState.NONE, EntityContactState.NONE,
            MovementModifierState.NONE, SNEAKING);
        return BedrockSimulation.movementTick(new BedrockSimulation.Input(state, frame, frame.intent(),
            BedrockWorldSnapshot.fromContext(context), true, BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
            BedrockMobJumpComponentState.DEFAULT), control).movementResult();
    }
}
