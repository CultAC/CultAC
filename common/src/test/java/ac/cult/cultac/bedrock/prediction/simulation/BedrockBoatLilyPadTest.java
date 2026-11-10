package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockBoatMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.simulation.postmove.BedrockBoatLilyPads;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatProperties;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatState;
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
 * Captures of a boat paddled over water towards a lily pad at (18, 120, 70). On the tick the boat's box comes
 * within half a block of the pad's cell the client reports half the velocity of its move, with gravity and buoyancy
 * applied to the halved y. The reported position is 0.375 above the boat's feet.
 */
public final class BedrockBoatLilyPadTest {
    private static final BlockPosition PAD = new BlockPosition(18, 120, 70);

    @Test
    public void padIsReachedOnceTheBoxComesWithinHalfABlockOfItsCell() {
        // Rising towards the pad's row: out of reach at a reported y of 119.307434, in reach at 119.377625.
        assertEquals(List.of(), reached(pads(PAD), 17.566694, 119.307434, 71.320305));
        assertEquals(List.of(PAD), reached(pads(PAD), 17.575281, 119.377625, 71.670074));
        assertEquals(List.of(PAD), reached(pads(PAD), 17.558268, 119.35306, 70.97722));
        // Approaching along z: in reach at 72.092 and 72.162, not at 72.3875.
        assertEquals(List.of(PAD), reached(pads(PAD), 17.575281, 119.377625, 72.092));
        assertEquals(List.of(PAD), reached(pads(PAD), 17.575281, 119.377625, 72.162));
        assertEquals(List.of(), reached(pads(PAD), 17.575281, 119.377625, 72.3875));
    }

    @Test
    public void padCellsAreCountedInTheSessionFrame() {
        var frame = new BedrockCoordinateFrame(20000, 20080, 3);
        var shifted = new BlockPosition(20018, 120, 20150);
        var world = new BlockCollisionWorld(List.of(pad(shifted)), frame);

        assertEquals(
                List.of(shifted),
                BedrockBoatLilyPads.reached(
                        world, BedrockBoatProperties.DIMENSIONS, new Vec3d(20017.575281, 119.002625, 20151.670074)));
    }

    @Test
    public void reachedPadHalvesTheVelocityBeforeGravityAndBuoyancy() {
        BedrockWorldSnapshot water = snapshot(List.of(PAD));
        // The boat's move ended at (17.575281, 119.377625, 71.670074) with this velocity.
        Vec3d moved = new Vec3d(0.008586454F, 0.07018889F, 0.34977338F);
        Vec3d feet = new Vec3d(17.575281F, 119.377625F - BedrockBoatProperties.ORIGIN_HEIGHT, 71.670074F);
        BedrockMovementState boat = boat(feet, moved);
        Vec3d halved = new Vec3d(0.004293227F, 0.035094445F, 0.17488669F);

        // Gravity and buoyancy take the halved y to 0.04663479.
        assertEquals(
                0.04663479F,
                (float) BedrockBoatMovement.afterMove(boat, water.blockCollisionWorld(), feet, halved)
                        .y(),
                1.0E-7F);

        // The tick itself: the same move ends with half the x and z velocity when the pad is in the world.
        BedrockMovementState before = boat.withPhysicalFeetPosition(feet.subtract(moved), 0);
        BedrockMovementResult open = tick(before, snapshot(List.of()));
        BedrockMovementResult slowed = tick(before, water);
        assertEquals(open.predictedPosition(), slowed.predictedPosition());
        assertEquals((float) open.predictedVelocity().x() * 0.5F, (float) slowed.predictedVelocity().x(), 0);
        assertEquals((float) open.predictedVelocity().z() * 0.5F, (float) slowed.predictedVelocity().z(), 0);
    }

    @Test
    public void twoPadsQuarterTheVelocity() {
        var second = new BlockPosition(16, 120, 70);
        BedrockMovementState boat = boat(new Vec3d(17.5, 119.0, 71.3), new Vec3d(0.01, 0.07, 0.35));

        Vec3d open = tick(boat, snapshot(List.of())).predictedVelocity();
        Vec3d slowed = tick(boat, snapshot(List.of(PAD, second))).predictedVelocity();

        assertEquals((float) open.x() * 0.25F, (float) slowed.x(), 0);
        assertEquals((float) open.z() * 0.25F, (float) slowed.z(), 0);
    }

    private static List<BlockPosition> reached(BlockCollisionWorld world, double x, double reportedY, double z) {
        return BedrockBoatLilyPads.reached(
                world,
                BedrockBoatProperties.DIMENSIONS,
                new Vec3d(x, (float) reportedY - BedrockBoatProperties.ORIGIN_HEIGHT, z));
    }

    private static BlockCollisionWorld pads(BlockPosition... positions) {
        var blocks = new ArrayList<PlacedBlockCollision>();
        for (BlockPosition position : positions) blocks.add(pad(position));
        return new BlockCollisionWorld(blocks);
    }

    private static PlacedBlockCollision pad(BlockPosition position) {
        return PlacedBlockCollision.manual(position, "minecraft:lily_pad", "minecraft:lily_pad", List.of());
    }

    private static BedrockMovementState boat(Vec3d feet, Vec3d velocity) {
        return BedrockMovementState.fromPhysicalFeet(
                        feet, velocity, BedrockInputFrame.idle(1L), BedrockCollisionFlags.AIR)
                .withBoat(new BedrockBoatState(
                        null,
                        BedrockBoatProperties.initial(12),
                        0,
                        0,
                        0,
                        BedrockBoatState.Paddle.INITIAL,
                        BedrockBoatState.Paddle.INITIAL,
                        BedrockBoatState.PaddleInput.ANALOG))
                .withPhysicalFeetPosition(feet, 0)
                .withPlayerDimensions(BedrockBoatProperties.DIMENSIONS, true);
    }

    private static BedrockMovementResult tick(BedrockMovementState state, BedrockWorldSnapshot world) {
        BedrockInputFrame frame = BedrockInputFrame.idle(state.clientTick() + 1L);
        return BedrockForwardTick.simulate(new BedrockSimulation.Input(
                        state,
                        frame,
                        frame.intent(),
                        world,
                        true,
                        BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                        BedrockMobJumpComponentState.DEFAULT,
                        true,
                        false,
                        Vec3d.ZERO))
                .getFirst()
                .movementResult();
    }

    /** Water from y 117 to 119 under the boat, and the given lily pads. */
    private static BedrockWorldSnapshot snapshot(List<BlockPosition> pads) {
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        for (int x = 14; x <= 21; x++) {
            for (int z = 68; z <= 74; z++) {
                for (int y = 117; y <= 119; y++) {
                    blocks.add(PlacedBlockCollision.manual(
                            new BlockPosition(x, y, z), "minecraft:water[level=0]", "minecraft:water", List.of()));
                }
            }
        }
        for (BlockPosition position : pads) blocks.add(pad(position));
        return BedrockWorldSnapshot.fromContext(new BedrockMovementContext(
                BedrockEffectState.NONE,
                new AttributeState(0, 0, 0, 0, 0),
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                BedrockBoatProperties.DIMENSIONS));
    }
}
