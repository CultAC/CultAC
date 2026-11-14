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
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
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

    // A box one float step (0.00195 at z 20066) inside a wall's block only touches it: a box carried from a reported
    // position can sit a step past a face it was left flush with. Two steps inside is pushed out at the push speed.
    @Test
    public void boxMoreThanOneFloatStepInsideAWallIsPushed() {
        var world = new BlockCollisionWorld(List.of(cube(20055, 119, 20066)));
        var feet = new Vec3d(20055.528120040894D, 119.16706085205078D, 20065.610872268677D);
        var velocity = new Vec3d(0.052746D, 0.009384D, -0.068914D);

        assertEquals(velocity, pushBox(world, feet, velocity, 20065.21875D, 20066.0D));
        assertEquals(velocity, pushBox(world, feet, velocity, 20065.220703125D, 20066.001953125D));

        Vec3d pushed = pushBox(world, feet, velocity, 20065.22265625D, 20066.00390625D);
        assertEquals(velocity.x(), pushed.x(), 0.0D);
        assertEquals(-PUSH, pushed.z(), 1.0E-3D);
    }

    // Flag 117 (2026-11-13): a sprint jump against a one-high step at z 20152, in a frame whose origin is
    // (20064, 20176). The move was stopped at the step's face and the reported velocity kept z at 0; the carried box
    // sat one float step (1.9e-6) inside that face.
    @Test
    public void boxOneFloatStepInsideAStepFaceInARebasedFrameIsNotPushed() {
        var frame = new BedrockCoordinateFrame(20064, 20176, 3L);
        var world = new BlockCollisionWorld(List.of(cube(20002, 122, 20152), cube(20003, 122, 20152)), frame);
        var feet = new Vec3d(20003.00500869751D, 122.7531967163086D, 20151.70000076294D);
        var velocity = new Vec3d(0.10817278921604156D, 0.2481359988451004D, 0.0D);
        double step = Math.ulp(24.0F);
        var flush = new WorldCollisionBox(
                frame.roundX(feet.x() - 0.3D),
                feet.y(),
                frame.roundZ(feet.z() - 0.3D),
                frame.roundX(feet.x() + 0.3D),
                feet.y() + 1.8D,
                20152.0D);

        assertEquals(velocity, pushBox(world, feet, velocity, flush, frame));
        assertEquals(velocity, pushBox(world, feet, velocity, withMaxZ(flush, 20152.0D + step), frame));
        Vec3d pushed = pushBox(world, feet, velocity, withMaxZ(flush, 20152.0D + 2.0D * step), frame);
        assertEquals(-PUSH, pushed.z(), 1.0E-4D);
    }

    // A door closed on an actor standing in its doorway, in a frame whose origin is (20064, 20176): the reported
    // velocity kept x and z at 0. A block of another kind with the same shape pushes the same box out.
    @Test
    public void doorClosedOnTheBoxInItsCellDoesNotPush() {
        var frame = new BedrockCoordinateFrame(20064, 20176, 1L);
        var feet = new Vec3d(20008.564796447754D, 120.0D, 20199.093509674072D);
        var velocity = new Vec3d(0.0D, -0.07840000092983246D, 0.0D);
        var lower = new BlockPosition(20008, 120, 20198);
        var upper = new BlockPosition(20008, 121, 20198);

        var doors = new BlockCollisionWorld(
                List.of(
                        panel(lower, "minecraft:oak_door[half=lower,open=false]", "minecraft:wooden_door"),
                        panel(upper, "minecraft:oak_door[half=upper,open=false]", "minecraft:wooden_door")),
                frame);
        var closed = BedrockEndTickBlockPush.apply(travelTick(doors), standing(feet, velocity, frame));
        assertEquals(velocity, closed.velocity());
        assertEquals(List.of(northPanel(lower), northPanel(upper)), closed.oneWayBoxes());

        var walls = new BlockCollisionWorld(
                List.of(
                        panel(lower, "minecraft:stone", "minecraft:stone"),
                        panel(upper, "minecraft:stone", "minecraft:stone")),
                frame);
        var pushed = BedrockEndTickBlockPush.apply(travelTick(walls), standing(feet, velocity, frame));
        assertEquals(PUSH, Math.hypot(pushed.velocity().x(), pushed.velocity().z()), 1.0E-7D);
        assertEquals(List.of(), pushed.oneWayBoxes());
    }

    // A trapdoor closed onto the feet of an actor standing in its cell: twenty ticks reported no push.
    @Test
    public void trapdoorClosedOntoTheFeetDoesNotPush() {
        var frame = new BedrockCoordinateFrame(20000, 20160, 1L);
        var feet = new Vec3d(20011.51249D, 120.0D, 20195.60945D);
        var velocity = new Vec3d(0.0D, -0.07840000092983246D, 0.0D);
        var slab = new WorldCollisionBox(20011.0D, 120.0D, 20195.0D, 20012.0D, 120.1825D, 20196.0D);
        var world = new BlockCollisionWorld(
                List.of(PlacedBlockCollision.manual(
                        new BlockPosition(20011, 120, 20195),
                        "minecraft:oak_trapdoor",
                        "minecraft:trapdoor",
                        List.of(slab))),
                frame);

        assertEquals(
                velocity,
                BedrockEndTickBlockPush.apply(travelTick(world), standing(feet, velocity, frame))
                        .velocity());
    }

    // A fence gate's box does not excuse a block of another kind the box also overlaps.
    @Test
    public void blockBesideAOneWayBlockStillPushes() {
        var gate = PlacedBlockCollision.manual(
                new BlockPosition(17, 120, 20),
                "minecraft:oak_fence_gate[open=false]",
                "minecraft:fence_gate",
                List.of(new WorldCollisionBox(17.0D, 120.0D, 20.375D, 18.0D, 121.5D, 20.625D)));
        var blocks = new ArrayList<PlacedBlockCollision>(walled().blocks());
        blocks.add(gate);

        Vec3d pushed = push(
                new BlockCollisionWorld(blocks),
                new Vec3d(17.539060D, FEET_Y, 20.240938D),
                new Vec3d(0.001859D, 0.0D, 0.096058D));

        assertEquals(new Vec3d(0.001859D, 0.0D, PUSH), pushed);
    }

    // A kept box outlives its cell while the box still overlaps it, and goes once a tick starts clear of it.
    @Test
    public void keptBoxLastsWhileTheStartingBoxOverlapsIt() {
        var panel = northPanel(new BlockPosition(17, 120, 19));
        var world = new BlockCollisionWorld(List.of(cube(17, 119, 20)));
        var feet = new Vec3d(17.5D, 120.0D, 20.2995D);
        var idle = new Vec3d(0.0D, -0.0784D, 0.0D);
        var touching = standing(feet, idle, BedrockCoordinateFrame.IDENTITY).withOneWayBoxes(List.of(panel));
        var clear = standing(new Vec3d(17.5D, 120.0D, 20.3D), idle, BedrockCoordinateFrame.IDENTITY)
                .withOneWayBoxes(List.of(panel));

        assertEquals(List.of(panel), BedrockOneWayBoxes.afterTick(touching, touching.collisionBox(), world));
        assertEquals(List.of(), BedrockOneWayBoxes.afterTick(clear, clear.collisionBox(), world));
    }

    private static BedrockMovementState standing(Vec3d feet, Vec3d velocity, BedrockCoordinateFrame frame) {
        return BedrockMovementState.fromPhysicalFeet(
                        feet, velocity, BedrockInputFrame.idle(0L), BedrockCollisionFlags.ON_GROUND)
                .withCoordinateFrame(frame);
    }

    /** A block whose shape is a closed door's panel on the north-facing side of its cell. */
    private static PlacedBlockCollision panel(BlockPosition position, String javaState, String bedrockIdentifier) {
        return PlacedBlockCollision.manual(position, javaState, bedrockIdentifier, List.of(northPanel(position)));
    }

    private static WorldCollisionBox northPanel(BlockPosition position) {
        return new WorldCollisionBox(
                position.x(),
                position.y(),
                position.z() + 0.8175D,
                position.x() + 1.0D,
                position.y() + 1.0D,
                position.z() + 1.0D);
    }

    private static WorldCollisionBox withMaxZ(WorldCollisionBox box, double maxZ) {
        return new WorldCollisionBox(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), maxZ);
    }

    private static Vec3d pushBox(
            BlockCollisionWorld world,
            Vec3d feet,
            Vec3d velocity,
            WorldCollisionBox box,
            BedrockCoordinateFrame frame) {
        var state = BedrockMovementState.fromPhysicalFeet(
                        feet, velocity, BedrockInputFrame.idle(0L), BedrockCollisionFlags.AIR)
                .withCoordinateFrame(frame)
                .withCollisionBox(box);
        return BedrockEndTickBlockPush.apply(travelTick(world), state).velocity();
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
