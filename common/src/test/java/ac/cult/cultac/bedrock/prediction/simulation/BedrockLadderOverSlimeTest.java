package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
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
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbableContact;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision.BlockContactBehavior;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Pressed into a ladder while standing, the actor accelerates with the control of the block under its feet. On slime
 * the client walks the input at 0.6^3 / 0.8^3 of what it walks on stone.
 */
public final class BedrockLadderOverSlimeTest {
    // Feet against a south-facing ladder, whose 3/16 deep shape fills z 0..0.1875 of block 0 1 0.
    private static final Vec3d FEET = new Vec3d(0.5D, 1.0D, 0.4875D);

    @Test
    public void walkingAlongALadderOnSlimeTakesTheSlimeControl() {
        double onStone = walkAlongLadder("minecraft:stone", "minecraft:stone", Set.of());
        double onSlime =
                walkAlongLadder("minecraft:slime_block", "minecraft:slime", Set.of(BlockContactBehavior.SLIME));

        float defaultFriction = 0.6F;
        float slimeFriction = 0.8F;
        double ratio = (double) (defaultFriction * defaultFriction * defaultFriction)
                / (slimeFriction * slimeFriction * slimeFriction);
        assertEquals(onStone * ratio, onSlime, 1.0E-6D);
    }

    private static double walkAlongLadder(String javaFloor, String bedrockFloor, Set<BlockContactBehavior> behavior) {
        BedrockMovementContext world = ladderOver(javaFloor, bedrockFloor, behavior);
        // The last move ended standing on the floor and touching the ladder beside it.
        BedrockMovementState state = BedrockMovementState.fromPhysicalFeet(
                        FEET, Vec3d.ZERO, walk(1L), new BedrockCollisionFlags(true, false, true, true, false))
                .withClimbableContact(BedrockClimbableContact.fromBlockWorld(
                        world.worldState().blockCollisionWorld(), FEET, 0.6D, 1.8D));
        BedrockInputFrame frame = walk(2L);
        BedrockMovementResult result = BedrockForwardTick.simulate(new BedrockSimulation.Input(
                        state,
                        frame,
                        frame.intent(),
                        BedrockWorldSnapshot.fromContext(world),
                        true,
                        BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                        BedrockMobJumpComponentState.DEFAULT,
                        true,
                        false,
                        new Vec3d(0.0D, 0.0D, 1.0D)))
                .getFirst()
                .movementResult();
        assertTrue(state.climbableContact().climbing());
        assertEquals(FEET.z(), result.predictedPosition().z(), 1.0E-6D);
        return result.predictedPosition().x() - FEET.x();
    }

    // Yaw -90 walks forward along +x, parallel to the ladder.
    private static BedrockInputFrame walk(long tick) {
        return new BedrockInputFrame(tick, -90.0F, 0.0F, false, false, false, Set.of("UP"));
    }

    private static BedrockMovementContext ladderOver(
            String javaFloor, String bedrockFloor, Set<BlockContactBehavior> behavior) {
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        for (int x = -1; x <= 2; x++) {
            for (int z = -1; z <= 1; z++) {
                blocks.add(PlacedBlockCollision.manual(
                        new BlockPosition(x, 0, z),
                        javaFloor,
                        bedrockFloor,
                        List.of(new WorldCollisionBox(x, 0.0D, z, x + 1.0D, 1.0D, z + 1.0D)),
                        behavior));
            }
            blocks.add(PlacedBlockCollision.manual(
                    new BlockPosition(x, 1, 0),
                    "minecraft:ladder[facing=south]",
                    "minecraft:ladder",
                    List.of(new WorldCollisionBox(x, 1.0D, 0.0D, x + 1.0D, 2.0D, 0.1875D))));
        }
        return new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
    }
}
