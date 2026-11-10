package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;

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
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockSnapshotResolver;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * A capture of a client walking +z out of a block column at (17, 19) whose x neighbours are solid. The
 * velocities are the ones it reported at the end of each tick.
 */
public final class BedrockEndTickBlockPushTest {
    private static final double FEET_Y = 120.50797D;
    private static final float PUSH = 0.1F;

    // Box still 0.059 inside the column, post-drag velocity z 0.096058: reported z exactly 0.1, x untouched.
    @Test
    public void slowerAxisIsRaisedToThePushSpeed() {
        Vec3d pushed = push(walled(), new Vec3d(17.539060D, FEET_Y, 20.240938D), new Vec3d(0.001859D, 0.0D, 0.096058D));

        assertEquals(new Vec3d(0.001859D, 0.0D, PUSH), pushed);
    }

    // Box 0.179 inside the column with velocity z 0.100480, already faster than the push: reported unchanged.
    @Test
    public void fasterAxisIsLeftAlone() {
        Vec3d velocity = new Vec3d(0.001839D, 0.0D, 0.100480D);

        assertEquals(velocity, push(walled(), new Vec3d(17.536736D, FEET_Y, 20.120865D), velocity));
    }

    // The next tick ends clear of the column with velocity z 0 after its collision: reported 0.
    @Test
    public void boxClearOfEveryShapeIsNotPushed() {
        Vec3d velocity = new Vec3d(0.001875D, 0.0D, 0.0D);

        assertEquals(velocity, push(walled(), new Vec3d(17.541405D, FEET_Y, 20.419594D), velocity));
    }

    // A velocity against the push is only brought to the push speed, not past it.
    @Test
    public void opposingVelocityIsClampedToThePushSpeed() {
        Vec3d pushed = push(walled(), new Vec3d(17.539060D, FEET_Y, 20.240938D), new Vec3d(0.0D, 0.0D, -0.04D));

        assertEquals((float) -0.04D + PUSH, pushed.z(), 0.0D);
    }

    // With the x corridors free the push points from the column's center to the box's, each axis by its share.
    @Test
    public void openColumnPushesAlongTheNormalisedOffsetFromItsCenter() {
        Vec3d feet = new Vec3d(17.539060D, FEET_Y, 20.240938D);
        float x = (float) feet.x() - 17.5F;
        float z = (float) feet.z() - 19.5F;
        float length = (float) Math.sqrt(x * x + z * z);

        Vec3d pushed = push(column(), feet, new Vec3d(0.001859D, 0.0D, 0.096058D));

        assertEquals(x / length * PUSH, pushed.x(), 1.0E-6D);
        assertEquals(z / length * PUSH, pushed.z(), 1.0E-6D);
    }

    // A box centered on the column has no offset to follow: it leaves diagonally towards +x and +z.
    @Test
    public void boxCenteredOnTheColumnIsPushedTowardsPositiveXAndZ() {
        Vec3d pushed = push(column(), new Vec3d(17.5D, FEET_Y, 19.5D), new Vec3d(0.0D, -0.0784D, 0.0D));

        assertEquals(0.07071068D, pushed.x(), 1.0E-7D);
        assertEquals(-0.0784D, pushed.y(), 0.0D);
        assertEquals(0.07071068D, pushed.z(), 1.0E-7D);
    }

    // A swim pushed out of a wall at z 20066 ends with its float box one float step (0.00195) inside the wall's
    // block, its reported position 0.39 in front of it. The client raises the post-drag velocity z -0.068914 to the
    // push speed; the same box ending flush with the wall is not pushed.
    @Test
    public void boxOneFloatStepInsideAWallIsPushed() {
        var world = new BlockCollisionWorld(List.of(cube(20055, 119, 20066)));
        var feet = new Vec3d(20055.528120040894D, 119.16706085205078D, 20065.610872268677D);
        var velocity = new Vec3d(0.052746D, 0.009384D, -0.068914D);

        Vec3d pushed = pushBox(world, feet, velocity, 20065.220703125D, 20066.001953125D);
        assertEquals(velocity.x(), pushed.x(), 0.0D);
        assertEquals(-PUSH, pushed.z(), 1.0E-3D);

        assertEquals(velocity, pushBox(world, feet, velocity, 20065.21875D, 20066.0D));
    }

    private static Vec3d pushBox(BlockCollisionWorld world, Vec3d feet, Vec3d velocity, double minZ, double maxZ) {
        var state = BedrockMovementState.fromPhysicalFeet(
                        feet, velocity, BedrockInputFrame.idle(0L), BedrockCollisionFlags.AIR)
                .withCollisionBox(new WorldCollisionBox(
                        20055.138671875D, feet.y(), minZ, 20055.91796875D, feet.y() + 2.34D, maxZ));
        return BedrockEndTickBlockPush.apply(travelTick(world), state).velocity();
    }

    private static Vec3d push(BlockCollisionWorld world, Vec3d feet, Vec3d velocity) {
        var state = BedrockMovementState.fromPhysicalFeet(
                feet, velocity, BedrockInputFrame.idle(0L), BedrockCollisionFlags.ON_GROUND);
        return BedrockEndTickBlockPush.apply(travelTick(world), state).velocity();
    }

    /** Any tick that travelled in the world: the push reads its shapes and dimensions from it. */
    private static BedrockMovementResult travelTick(BlockCollisionWorld world) {
        var context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, world),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
        var state = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(17.5D, 200.0D, 30.5D), Vec3d.ZERO, BedrockInputFrame.idle(0L), BedrockCollisionFlags.AIR);
        var frame = BedrockInputFrame.idle(1L);
        var snapshot = BedrockSnapshotResolver.forState(BedrockWorldSnapshot.fromContext(context), state, frame);
        return BedrockSimulation.candidates(new BedrockSimulation.Input(
                        state,
                        frame,
                        frame.intent(),
                        snapshot,
                        false,
                        0.0D,
                        BedrockMobJumpComponentState.DEFAULT,
                        true,
                        false))
                .getFirst()
                .movementResult();
    }

    private static BlockCollisionWorld column() {
        return new BlockCollisionWorld(List.of(cube(17, 120, 19), cube(17, 121, 19)));
    }

    private static BlockCollisionWorld walled() {
        var blocks = new ArrayList<PlacedBlockCollision>(column().blocks());
        for (int y = 120; y <= 121; y++) {
            blocks.add(cube(16, y, 19));
            blocks.add(cube(18, y, 19));
        }
        return new BlockCollisionWorld(blocks);
    }

    private static PlacedBlockCollision cube(int x, int y, int z) {
        return PlacedBlockCollision.manual(
                new BlockPosition(x, y, z),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(x, y, z, x + 1, y + 1, z + 1)));
    }
}
