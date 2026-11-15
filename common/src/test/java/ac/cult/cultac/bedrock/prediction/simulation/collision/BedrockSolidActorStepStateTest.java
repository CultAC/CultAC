package ac.cult.cultac.bedrock.prediction.simulation.collision;

import static org.junit.Assert.*;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.*;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * A moving boat the player walks into may stand a little off where Cult shows it. Inside the player's box it moves the
 * box out, at its face it is a wall the player steps onto, past the move's end it is not met: each is a state of the
 * tick whichever of them Cult shows, within the boat's budget.
 */
public final class BedrockSolidActorStepStateTest {
    // A sneaking player on a repeater walks into a boat a piston pushes, and steps onto it.
    private static final Vec3d FEET = new Vec3d(337.2910461425781, 82.125, -39.320655822753906);
    private static final Vec3d MOVE = new Vec3d(-0.006817660294473171, -0.07840000092983246, 0.045211583375930786);
    private static final Vec3d STEPPED = new Vec3d(337.28424072265625, 82.5999984741211, -39.27544403076172);
    private static final double STEPPED_SPEED_Z = 0.024685528;
    private static final double STEP = STEPPED.y() - FEET.y();
    private static final PlayerDimensionsState SNEAKING = new PlayerDimensionsState(0.6F, 1.49F);
    // The boat lies on the floor the repeater stands on, too tall to step onto from that floor.
    private static final double BOAT_Y = 82.0D;
    // The boat's side 0.0138 inside the player's box, 0.0314 ahead of it, and 0.005 past the end of the move.
    private static final double INSIDE = -39.03449935;
    private static final double AHEAD = -38.98928755;
    private static final double PAST = -38.9704;

    @Test
    public void aBoatShownInsideTheBoxMayStandAtItsFaceSoTheStepOntoItKeepsItsSpeed() {
        boolean movedOut = false;
        boolean wall = false;
        for (var candidate : candidates(BOAT_Y, INSIDE, 0.05F, MOVE)) {
            var result = candidate.movementResult();
            if (result.predictedState().collisionFlags().zCollision()) {
                // Moved out of the boat first, the step ends short and without its speed.
                assertEquals(0.0D, result.predictedVelocity().z(), 0.0D);
                movedOut = true;
                continue;
            }
            assertSteppedAtSpeed(result);
            double charge = result.solidActorOrderRange().buffered(result.predictedPosition(), STEPPED);
            assertTrue("the boat's move out of the box must be charged: " + charge, charge > 0.0138 && charge < 0.0139);
            wall = true;
        }
        assertTrue("the move out of the boat where Cult shows it must stay a candidate", movedOut);
        assertTrue("the boat clear of the box must be a candidate with the step's own speed", wall);
    }

    @Test
    public void aBoatCannotLeaveTheBoxFartherThanItsBudget() {
        var candidates = candidates(BOAT_Y, INSIDE, 0.01F, MOVE);
        assertEquals(1, candidates.size());
        assertTrue(candidates
                .getFirst()
                .movementResult()
                .predictedState()
                .collisionFlags()
                .zCollision());
    }

    @Test
    public void aBoatDeeperInTheBoxThanTheMoveLeavesIsNotMoved() {
        // 0.12 inside: past the depenetration limit, so the move never meets the boat.
        var candidates = candidates(BOAT_Y, -39.02065581 - 0.12, 0.4F, MOVE);
        assertEquals(1, candidates.size());
        assertFalse(candidates
                .getFirst()
                .movementResult()
                .predictedState()
                .collisionFlags()
                .zCollision());
    }

    @Test
    public void aBoatShownInsideTheBoxMayLeaveAPlayerMovingAwayItsSpeed() {
        Vec3d away = new Vec3d(0.0D, -0.07840000092983246, -0.05D);
        boolean shoved = false;
        boolean free = false;
        for (var candidate : candidates(BOAT_Y, INSIDE, 0.05F, away)) {
            var result = candidate.movementResult();
            if (result.predictedState().collisionFlags().zCollision()) {
                assertEquals(0.0D, result.predictedVelocity().z(), 0.0D);
                shoved = true;
            } else {
                assertEquals((float) (FEET.z() - 0.05D), (float) result.predictedPosition().z(), 0.0F);
                assertTrue(result.predictedVelocity().z() < 0.0D);
                free = true;
            }
        }
        assertTrue(shoved);
        assertTrue("the boat clear of the box must leave the move as requested", free);
    }

    @Test
    public void aBoatSteppedOntoMayBeShownInsideTheBoxSoTheStepEndsShortWithoutItsSpeed() {
        // The client shows the boat 0.01 inside the box: the step ends that much short.
        Vec3d claimed = new Vec3d(STEPPED.x(), STEPPED.y(), STEPPED.z() - 0.01D);
        boolean stepped = false;
        boolean movedOut = false;
        for (var candidate : candidates(BOAT_Y, AHEAD, 0.05F, MOVE)) {
            var result = candidate.movementResult();
            if (!result.predictedState().collisionFlags().zCollision()) {
                stepped |= result.predictedPosition().subtract(STEPPED).length() < 1.0E-7;
                continue;
            }
            assertEquals(STEP, result.predictedPosition().y() - FEET.y(), 1.0E-7);
            assertEquals(0.0D, result.predictedVelocity().z(), 0.0D);
            var range = result.solidActorOrderRange();
            assertNotNull(range);
            assertEquals(
                    0.0D,
                    range.closest(result.predictedPosition(), claimed)
                            .subtract(claimed)
                            .length(),
                    1.0E-7);
            // The gap to the boat and the depth it then stands inside the box.
            double charge = range.buffered(result.predictedPosition(), claimed);
            assertTrue("the boat's move into the box must be charged: " + charge, charge > 0.0413 && charge < 0.0415);
            // It cannot be shown past the box's other side of the contact.
            Vec3d beyond = new Vec3d(STEPPED.x(), STEPPED.y(), STEPPED.z() + 0.01D);
            assertTrue(range.closest(result.predictedPosition(), beyond).z() <= STEPPED.z());
            movedOut = true;
        }
        assertTrue("the step onto the boat where Cult shows it must stay a candidate", stepped);
        assertTrue("the boat inside the box must be a candidate that ends the step short", movedOut);
    }

    @Test
    public void aBoatCannotEnterTheBoxFartherThanItsBudget() {
        // The boat stands 0.0314 ahead of the box.
        for (var candidate : candidates(BOAT_Y, AHEAD, 0.03F, MOVE)) {
            assertFalse(candidate
                    .movementResult()
                    .predictedState()
                    .collisionFlags()
                    .zCollision());
        }
    }

    @Test
    public void aBoatJustPastTheMoveMayStandInItsWayAndBeSteppedOnto() {
        boolean unmet = false;
        boolean stepped = false;
        for (var candidate : candidates(BOAT_Y, PAST, 0.05F, MOVE)) {
            var result = candidate.movementResult();
            if (result.predictedPosition().y() == FEET.y()) {
                assertFalse(result.predictedState().collisionFlags().zCollision());
                unmet = true;
                continue;
            }
            assertSteppedAtSpeed(result);
            // The boat only has to come as far as the end of the move.
            double charge = result.solidActorOrderRange().buffered(result.predictedPosition(), STEPPED);
            assertTrue("the boat's move into the way must be charged: " + charge, charge > 0.005 && charge < 0.0051);
            stepped = true;
        }
        assertTrue("the move past the boat where Cult shows it must stay a candidate", unmet);
        assertTrue("the boat in the move's way must be a candidate that steps onto it", stepped);
    }

    @Test
    public void aBoatCannotComeIntoTheWayFartherThanItsBudget() {
        var candidates = candidates(BOAT_Y, PAST, 0.003F, MOVE);
        assertEquals(1, candidates.size());
        assertEquals(
                FEET.y(), candidates.getFirst().movementResult().predictedPosition().y(), 0.0D);
    }

    @Test
    public void aBoatTooTallToStepOntoStillStopsTheMoveItComesInTheWayOf() {
        boolean stopped = false;
        // Raised 0.1, its top is out of the step's reach.
        for (var candidate : candidates(BOAT_Y + 0.1D, PAST, 0.05F, MOVE)) {
            var result = candidate.movementResult();
            assertEquals(FEET.y(), result.predictedPosition().y(), 0.0D);
            if (!result.predictedState().collisionFlags().zCollision()) continue;
            assertEquals(0.0D, result.predictedVelocity().z(), 0.0D);
            stopped = true;
        }
        assertTrue("a boat that cannot be stepped onto must stop the move", stopped);
    }

    private static void assertSteppedAtSpeed(ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult result) {
        assertEquals(0.0D, result.predictedPosition().subtract(STEPPED).length(), 1.0E-7);
        assertEquals(STEPPED_SPEED_Z, result.predictedVelocity().z(), 1.0E-8);
        assertNotNull(result.solidActorOrderRange());
        // Every position of the boat in the move's way ends the step at the same place along the move.
        Vec3d short1 = new Vec3d(STEPPED.x(), STEPPED.y(), STEPPED.z() - 0.01D);
        assertEquals(
                STEPPED.z(),
                result.solidActorOrderRange()
                        .closest(result.predictedPosition(), short1)
                        .z(),
                0.0D);
    }

    private static List<BedrockSimulation.Candidate> candidates(double boatY, double side, float buffer, Vec3d move) {
        var shapes = new ArrayList<PlacedBlockCollision>();
        for (int x = 333; x <= 341; x++)
            for (int z = -44; z <= -34; z++)
                shapes.add(PlacedBlockCollision.manual(
                        new BlockPosition(x, 81, z),
                        "minecraft:stone",
                        "minecraft:stone",
                        List.of(new WorldCollisionBox(x, 81, z, x + 1, 82, z + 1))));
        shapes.add(PlacedBlockCollision.manual(
                new BlockPosition(336, 82, -40),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(336, 82, -40, 337, 82.125, -39))));
        shapes.add(BedrockDepenetrationLimit.SOLID_ACTOR.solidActor(
                new WorldCollisionBox(336.5125, boatY, side, 338.1125, boatY + 0.6F, side + 1.6), buffer));
        var context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(shapes)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                SNEAKING);
        var frame = BedrockInputFrame.idle(4237);
        var previous = BedrockMovementState.fromPhysicalFeet(
                FEET, move, BedrockInputFrame.idle(4236), BedrockCollisionFlags.ON_GROUND);
        return BedrockForwardTick.simulate(new BedrockSimulation.Input(
                previous,
                frame,
                frame.intent(),
                BedrockWorldSnapshot.fromContext(context),
                true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                BedrockMobJumpComponentState.DEFAULT,
                true,
                false,
                Vec3d.ZERO));
    }
}
