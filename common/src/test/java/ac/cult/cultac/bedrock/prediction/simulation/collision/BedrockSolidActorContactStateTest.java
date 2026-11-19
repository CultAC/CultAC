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

public final class BedrockSolidActorContactStateTest {
    private static final Vec3d FEET = new Vec3d(14.421016693115234, 160.0, 14.5);
    private static final Vec3d CLAIMED = new Vec3d(14.626916885375977, 160.0, 14.5);

    /** A moving boat can stop the move even when its nominal face is just past the requested end. */
    @Test
    public void bufferedContactOnsetClearsVelocityAndChargesTheActorPosition() {
        var candidates = candidates(15.74853801727295F, 0.05F);
        boolean contact = false;
        for (var candidate : candidates) {
            var result = candidate.movementResult();
            var range = result.solidActorOrderRange();
            if (!result.predictedState().collisionFlags().xCollision() || range == null) continue;
            var selected = range.closest(result.predictedPosition(), CLAIMED);
            if (selected.subtract(CLAIMED).length() > 1.0E-6) continue;
            assertEquals(0.0, result.predictedVelocity().x(), 0.0);
            assertTrue(result.predictedState().collisionFlags().onGround());
            double charge = range.buffered(result.predictedPosition(), CLAIMED);
            // The actor moved about0.0216, although the player's end differs by only0.00996.
            assertTrue(charge > 0.020 && charge < 0.025);
            contact = true;
        }
        assertTrue("a buffered actor position must produce the collided state, not only an endpoint range", contact);
    }

    private static List<BedrockSimulation.Candidate> candidates(float boatX, float buffer) {
        var shapes = new ArrayList<PlacedBlockCollision>();
        for (int x = 12; x <= 18; x++)
            for (int z = 12; z <= 16; z++) {
                shapes.add(PlacedBlockCollision.manual(
                        new BlockPosition(x, 159, z),
                        "minecraft:stone",
                        "minecraft:stone",
                        List.of(new WorldCollisionBox(x, 159, z, x + 1, 160, z + 1))));
            }
        shapes.add(BedrockDepenetrationLimit.SOLID_ACTOR.solidActor(
                new WorldCollisionBox(boatX - 0.8F, 160F, 13.7F, boatX + 0.8F, 160F + 0.6F, 15.3F), buffer));
        var context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(shapes)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
        var frame = new BedrockInputFrame(65562, -90.0F, 0.0F, false, false, false);
        var previous = BedrockMovementState.fromPhysicalFeet(
                FEET,
                new Vec3d(0.11785900592803955, -0.07840000092983246, -3.939839565703096E-9),
                new BedrockInputFrame(65561, -90F, 0F, false, false, false),
                BedrockCollisionFlags.ON_GROUND);
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
                new Vec3d(0, 0, 1)));
    }
}
