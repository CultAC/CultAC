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
 * A player sneaks on a boat a piston pushes. The client holds it back from the boat's edge where it shows the boat,
 * which may be off Cult's box for it by the boat's buffer.
 */
public final class BedrockSneakEdgeMovingActorTest {
    private static final Vec3d FEET = new Vec3d(336.1938781738281, 82.5999984741211, -38.61836242675781);
    private static final Vec3d MOVE = new Vec3d(-0.06329052150249481, -0.07840000092983246, 0.00812547281384468);
    // The client stopped along X at the boat's edge and kept its move along Z.
    private static final Vec3d HELD = new Vec3d(336.1938781738281, 82.5999984741211, -38.61023712158203);
    private static final PlayerDimensionsState SNEAKING = new PlayerDimensionsState(0.6F, 1.49F);
    // Cult's box for the boat still holds the whole move up; its edge is 0.076 short of where the client held.
    private static final double BOAT_MIN_X = 336.38D;

    @Test
    public void theEdgeOfABoatShownElsewhereHoldsTheSneakingPlayerBack() {
        boolean whole = false;
        boolean held = false;
        for (var candidate : candidates(0.3F)) {
            var result = candidate.movementResult();
            Vec3d end = result.predictedPosition();
            if (Math.abs(end.x() - FEET.x() - MOVE.x()) < 1.0E-5 && end.y() == FEET.y()) whole = true;
            if (end.subtract(HELD).length() > 1.0E-7) continue;
            // An axis held back to nothing loses its speed; the other keeps it.
            assertEquals(0.0D, result.predictedVelocity().x(), 0.0D);
            assertEquals(0.004436509D, result.predictedVelocity().z(), 1.0E-8);
            assertTrue(result.predictedState().collisionFlags().onGround());
            held = true;
        }
        assertTrue("the whole move over the boat where Cult shows it must stay a candidate", whole);
        assertTrue("the boat's edge within its buffer must hold the move back", held);
    }

    @Test
    public void theEdgeCannotBeShownFartherThanTheBoatsBuffer() {
        for (var candidate : candidates(0.05F)) {
            assertTrue(candidate.movementResult().predictedPosition().subtract(HELD).length() > 0.01D);
        }
        assertEquals(1, candidates(0.0F).size());
    }

    private static List<BedrockSimulation.Candidate> candidates(float buffer) {
        var shapes = new ArrayList<PlacedBlockCollision>();
        for (int x = 333; x <= 341; x++)
            for (int z = -44; z <= -34; z++)
                shapes.add(PlacedBlockCollision.manual(
                        new BlockPosition(x, 81, z),
                        "minecraft:stone",
                        "minecraft:stone",
                        List.of(new WorldCollisionBox(x, 81, z, x + 1, 82, z + 1))));
        shapes.add(BedrockDepenetrationLimit.SOLID_ACTOR.solidActor(
                new WorldCollisionBox(BOAT_MIN_X, 82.0D, -39.1D, BOAT_MIN_X + 1.6D, 82.0D + 0.6F, -37.5D), buffer));
        var context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(shapes)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                SNEAKING);
        var frame = new BedrockInputFrame(976, 30.9F, 46.0F, false, true, false);
        var previous = BedrockMovementState.fromPhysicalFeet(
                FEET, MOVE, new BedrockInputFrame(975, 30.9F, 46.0F, false, true, false), BedrockCollisionFlags.ON_GROUND);
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
