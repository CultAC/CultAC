package ac.cult.cultac.bedrock.prediction.simulation.collision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.api.BedrockActorOrderRange;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class BedrockSolidActorContactBoundsTest {
    private static final Vec3d FEET = new Vec3d(0.5, 120.0, 0.5);
    private static final PlayerDimensionsState DIMENSIONS = PlayerDimensionsState.DEFAULT;
    private static final BedrockCoordinateFrame FRAME = BedrockCoordinateFrame.IDENTITY;

    @Test
    public void contactOnsetUsesTheSameBudgetInEveryDirection() {
        for (int axis = 0; axis < 3; axis++)
            for (int sign : new int[] {-1, 1}) {
                Vec3d request = axis(axis, sign);
                var moves = moves(request, FRAME, actor(axis, sign, 1.02F, 0.05F));
                assertEquals(2, moves.size());
                var contact = moves.get(1);
                assertTrue(collided(contact, axis));
                assertEquals(
                        component(moves(Vec3d.ZERO, FRAME).getFirst().velocity(), axis),
                        component(contact.velocity(), axis),
                        0.0D);
                assertEquals(axis == 1 && sign < 0, contact.onGround());
                var range = contact.solidActorOrderRange();
                assertNotNull(range);
                Vec3d claimed = contact.position().add(axis(axis, -sign * 0.02D));
                assertEquals(
                        0.0D,
                        range.closest(contact.position(), claimed)
                                .subtract(claimed)
                                .length(),
                        1.0E-6D);
                assertEquals(0.04D, range.buffered(contact.position(), claimed), 2.0E-5D);
                assertTrue(range.buffered(contact.position(), FEET) <= 0.05D + 1.0E-8D);
                assertTrue(component(range.closest(contact.position(), FEET).subtract(FEET), axis) * sign >= 0.0D);
            }
    }

    @Test
    public void releasingContactRetainsVelocityAndClearsSupport() {
        for (int axis = 0; axis < 3; axis++)
            for (int sign : new int[] {-1, 1}) {
                Vec3d request = axis(axis, sign);
                var moves = moves(request, FRAME, actor(axis, sign, 0.98F, 0.05F));
                assertEquals(2, moves.size());
                assertTrue(collided(moves.getFirst(), axis));
                var released = moves.get(1);
                assertFalse(collided(released, axis));
                assertFalse(released.onGround());
                assertEquals(
                        component(moves(request, FRAME).getFirst().velocity(), axis),
                        component(released.velocity(), axis),
                        0.0D);
                var range = released.solidActorOrderRange();
                assertNotNull(range);
                assertEquals(0.02D, range.buffered(released.position(), released.position()), 2.0E-5D);
                // A release cannot borrow a different endpoint while retaining the uncollided velocity.
                assertEquals(released.position(), range.closest(released.position(), FEET));
            }
    }

    @Test
    public void noPositionCandidateExistsWithoutBudgetOrWithinHistoricalReplay() {
        assertEquals(1, moves(axis(0, 1), FRAME, actor(0, 1, 1.02F, 0.0F)).size());
        var replayed = BedrockDepenetrationLimit.replayed(actor(0, 1, 1.02F, 0.05F));
        assertEquals(1, moves(axis(0, 1), FRAME, replayed).size());
    }

    @Test
    public void facesOutsideTheAvailableBudgetDoNotChangeContactState() {
        for (int sign : new int[] {-1, 1}) {
            assertEquals(
                    1,
                    moves(axis(0, sign), FRAME, actor(0, sign, 1.051F, 0.05F)).size());
            assertEquals(
                    1,
                    moves(axis(0, sign), FRAME, actor(0, sign, 0.949F, 0.05F)).size());
        }
    }

    @Test
    public void blocksRetainTheirCollisionWhenABufferedActorMovesPastThem() {
        var moves = moves(axis(0, 1), FRAME, block(0.99F), actor(0, 1, 0.98F, 0.05F));
        for (var move : moves) {
            assertTrue(move.xCollision());
            assertEquals(0.0D, move.velocity().x(), 0.0D);
            var range = move.solidActorOrderRange();
            if (range != null)
                assertTrue(range.closest(move.position(), FEET.add(axis(0, 2))).x() <= FEET.x() + 0.99001D);
        }
        assertEquals(1, moves.size());
    }

    @Test
    public void anActorCanTakeContactFromABlockOnlyWithinItsOwnPositionBudget() {
        var moves = moves(axis(0, 1), FRAME, block(0.5F), actor(0, 1, 0.53F, 0.05F));
        assertEquals(2, moves.size());
        var contact = moves.get(1);
        var range = contact.solidActorOrderRange();
        assertNotNull(range);
        Vec3d claimed = FEET.add(axis(0, 0.49D));
        assertEquals(
                0.0D,
                range.closest(contact.position(), claimed).subtract(claimed).length(),
                1.0E-6D);
        assertEquals(0.04D, range.buffered(contact.position(), claimed), 2.0E-6D);
        assertTrue(range.closest(contact.position(), FEET.add(axis(0, 2))).x() <= FEET.x() + 0.5D);
        assertEquals(
                1,
                moves(axis(0, 1), FRAME, block(0.5F), actor(0, 1, 0.56F, 0.05F)).size());
    }

    @Test
    public void allActorsMustReleaseWithinBudgetBeforeVelocityCanSurvive() {
        var moves = moves(axis(0, 1), FRAME, actor(0, 1, 0.98F, 0.05F), actor(0, 1, 0.94F, 0.05F));
        for (var move : moves) {
            assertTrue(move.xCollision());
            assertEquals(0.0D, move.velocity().x(), 0.0D);
        }
    }

    @Test
    public void independentContactsStillShareOneXyzBudget() {
        Vec3d request = new Vec3d(1.0D, -1.0D, 1.0D);
        var moves =
                moves(request, FRAME, actor(0, 1, 1.02F, 0.05F), actor(1, -1, 1.02F, 0.05F), actor(2, 1, 1.02F, 0.05F));
        var contact = moves.stream()
                .filter(move -> move.xCollision() && move.zCollision() && move.verticalCollision())
                .findFirst()
                .orElseThrow();
        var range = contact.solidActorOrderRange();
        Vec3d claimed = FEET.add(new Vec3d(0.98D, -0.98D, 0.98D));
        Vec3d selected = range.closest(contact.position(), claimed);
        assertTrue(selected.subtract(claimed).length() > 0.01D);
        assertEquals(0.05D, range.buffered(contact.position(), selected), 1.0E-7D);
        assertTrue(range.buffered(contact.position(), contact.position()) > 0.034D);
        // Three individually valid translations can also exceed the shared budget before any endpoint is chosen.
        var overBudget =
                moves(request, FRAME, actor(0, 1, 1.04F, 0.05F), actor(1, -1, 1.04F, 0.05F), actor(2, 1, 1.04F, 0.05F));
        assertFalse(overBudget.stream()
                .anyMatch(move -> move.xCollision() && move.zCollision() && move.verticalCollision()));
    }

    @Test
    public void shiftedCoordinatesPreserveTheLocalContactAndCharge() {
        var shifted = new BedrockCoordinateFrame(20336, 20096, 1);
        var shape = actor(0, 1, 1.02F, 0.05F);
        var movedShape = BedrockDepenetrationLimit.SOLID_ACTOR.solidActor(
                shape.collisionBoxes().getFirst().move(shifted.originX(), 0, shifted.originZ()), 0.05F);
        var local = moves(axis(0, 1), FRAME, shape).get(1);
        var world = moves(axis(0, 1), shifted, movedShape).get(1);
        assertEquals(local.position(), shifted.toLocal(world.position()));
        assertEquals(local.velocity(), world.velocity());
        assertEquals(
                local.solidActorOrderRange().buffered(local.position(), local.position()),
                world.solidActorOrderRange().buffered(world.position(), world.position()),
                0.0D);
    }

    private record End(
            Vec3d position,
            Vec3d velocity,
            boolean xCollision,
            boolean zCollision,
            boolean onGround,
            boolean verticalCollision,
            BedrockActorOrderRange solidActorOrderRange) {}

    private static List<End> moves(Vec3d request, BedrockCoordinateFrame coordinates, PlacedBlockCollision... shapes) {
        Vec3d feet = coordinates.toWorld(FEET);
        var current = BedrockMovementState.fromPhysicalFeet(
                feet, request, BedrockInputFrame.idle(0), BedrockCollisionFlags.AIR, Medium.AIR, coordinates);
        var world = new BlockCollisionWorld(List.of(shapes), coordinates);
        var context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, world),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                DIMENSIONS);
        var frame = BedrockInputFrame.idle(1);
        var candidates = BedrockForwardTick.simulate(new BedrockSimulation.Input(
                current,
                frame,
                frame.intent(),
                BedrockWorldSnapshot.fromContext(context),
                false,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                BedrockMobJumpComponentState.DEFAULT,
                true,
                false,
                Vec3d.ZERO));
        List<End> results = new ArrayList<>();
        for (var candidate : candidates) {
            var move = candidate.movementResult();
            var flags = move.predictedState().collisionFlags();
            results.add(new End(
                    move.predictedPosition(),
                    move.predictedVelocity(),
                    flags.xCollision(),
                    flags.zCollision(),
                    flags.onGround(),
                    flags.verticalCollision(),
                    move.solidActorOrderRange()));
        }
        assertTrue(results.size() <= 32);
        return results;
    }

    private static PlacedBlockCollision actor(int axis, int sign, float gap, float budget) {
        var player = BedrockCollisionSweep.playerBox(FEET, DIMENSIONS);
        double[] low = {-10.0D, 110.0D, -10.0D};
        double[] high = {10.0D, 130.0D, 10.0D};
        double min = axis == 0 ? player.minX() : axis == 1 ? player.minY() : player.minZ();
        double max = axis == 0 ? player.maxX() : axis == 1 ? player.maxY() : player.maxZ();
        if (sign > 0) {
            low[axis] = (float) ((float) max + gap);
            high[axis] = low[axis] + 4.0F;
        } else {
            high[axis] = (float) ((float) min - gap);
            low[axis] = high[axis] - 4.0F;
        }
        return BedrockDepenetrationLimit.HAPPY_GHAST.solidActor(
                new WorldCollisionBox(low[0], low[1], low[2], high[0], high[1], high[2]), budget);
    }

    private static PlacedBlockCollision block(float gap) {
        float face = 0.8F + gap;
        return PlacedBlockCollision.manual(
                new BlockPosition(1, 120, 0),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(face, 119, -1, face + 1, 123, 2)));
    }

    private static boolean collided(End result, int axis) {
        return axis == 0 ? result.xCollision() : axis == 1 ? result.verticalCollision() : result.zCollision();
    }

    private static Vec3d axis(int axis, double value) {
        return new Vec3d(axis == 0 ? value : 0, axis == 1 ? value : 0, axis == 2 ? value : 0);
    }

    private static double component(Vec3d value, int axis) {
        return axis == 0 ? value.x() : axis == 1 ? value.y() : value.z();
    }
}
