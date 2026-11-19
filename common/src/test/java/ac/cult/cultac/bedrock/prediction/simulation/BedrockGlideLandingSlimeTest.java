package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelInput;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockBlockMetadata;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class BedrockGlideLandingSlimeTest {
    private static final PlayerDimensionsState GLIDE_SIZE = new PlayerDimensionsState(0.6F, 0.6F);
    private static final Set<String> GLIDE_INPUT = Set.of("SPRINTING", "SPRINT_DOWN", "GLIDING", "ACTOR_POSE_SNAPSHOT");
    private static final String MOVING_PISTON = "minecraft:moving_piston[facing=up,type=normal]";

    @Test
    public void glideLandingOnAMovingBlockBouncesOffTheSlimeItCarries() {
        // Flag 15, auth tick 4314: a glide onto the slime a sticky piston carries through (325, 83, -51).
        BedrockMovementResult result =
                flag15Landing(Map.of(BedrockBlockMetadata.MOVING_BLOCK_CARRIED, "minecraft:slime_block"));

        assertTrue(result.selectedGlidingTravel());
        assertEquals(325.6887512207031D, result.predictedPosition().x(), 1.0E-5D);
        assertEquals(84.0D, result.predictedPosition().y(), 1.0E-6D);
        assertEquals(-50.77824783325195D, result.predictedPosition().z(), 1.0E-5D);
        // The client reported the whole fall back up: 0.6255798 against the 0.625579834 it fell with.
        assertEquals(0.625579834D, result.predictedState().velocity().y(), 1.0E-6D);
        assertEquals(0.4838406443595886D, result.predictedState().velocity().x(), 1.0E-6D);
        assertEquals(-0.5015338659286499D, result.predictedState().velocity().z(), 1.0E-6D);
    }

    @Test
    public void glideLandingOnAMovingBlockWithoutDataStops() {
        BedrockMovementResult result = flag15Landing(Map.of());

        assertEquals(84.0D, result.predictedPosition().y(), 1.0E-6D);
        assertEquals(0.0D, result.predictedState().velocity().y(), 0.0D);
    }

    @Test
    public void glideLandingOnSlimeTakesTheStandingSlowdown() {
        // A slow glide onto a resting slime: friction and drag stay off for the landing, the slime still slows it.
        BedrockMovementResult slime = slowLanding("minecraft:slime_block", "minecraft:slime");
        BedrockMovementResult stone = slowLanding("minecraft:stone", "minecraft:stone");

        assertTrue(slime.selectedGlidingTravel());
        assertTrue(slime.predictedState().collisionFlags().onGround());
        assertEquals(85.0D, slime.predictedPosition().y(), 1.0E-6D);
        assertEquals(0.0D, slime.predictedState().velocity().y(), 0.0D);
        Vec3d slowed = slime.predictedState().velocity();
        Vec3d kept = stone.predictedState().velocity();
        assertEquals((float) kept.x() * 0.4F, slowed.x(), 1.0E-9D);
        assertEquals((float) kept.z() * 0.4F, slowed.z(), 1.0E-9D);
    }

    private static BedrockMovementResult flag15Landing(Map<String, Object> movingBlockData) {
        BedrockMovementState previous = glider(
                new Vec3d(325.2049255371094D, 84.41639709472656D, -50.27671432495117D),
                new Vec3d(0.46916335821151733D, -0.6249925494194031D, -0.4814581573009491D),
                4311L);
        BedrockInputFrame frame =
                new BedrockInputFrame(4312L, -137.79028F, 43.668045F, false, false, true, GLIDE_INPUT);
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        BlockPosition moving = new BlockPosition(325, 83, -51);
        blocks.add(PlacedBlockCollision.manual(
                moving, MOVING_PISTON, "minecraft:moving_block", movingBlockData, List.of(cube(moving))));
        for (int x = 324; x <= 326; x++) {
            for (int z = -53; z <= -50; z++) {
                BlockPosition position = new BlockPosition(x, 83, z);
                if (position.equals(moving)) continue;
                blocks.add(PlacedBlockCollision.manual(
                        position, "minecraft:obsidian", "minecraft:obsidian", List.of(cube(position))));
            }
        }
        return move(previous, frame, blocks);
    }

    private static BedrockMovementResult slowLanding(String javaState, String bedrockIdentifier) {
        BedrockMovementState previous = glider(
                new Vec3d(325.2885437011719D, 85.03730773925781D, -50.31354904174805D),
                new Vec3d(0.0015258789D, -0.06D, -0.0016708374D),
                24219L);
        BedrockInputFrame frame = new BedrockInputFrame(24220L, -137.8F, 20.0F, false, false, true, GLIDE_INPUT);
        BlockPosition below = new BlockPosition(325, 84, -51);
        return move(
                previous,
                frame,
                List.of(PlacedBlockCollision.manual(below, javaState, bedrockIdentifier, List.of(cube(below)))));
    }

    private static BedrockMovementState glider(Vec3d feet, Vec3d velocity, long tick) {
        return BedrockMovementState.fromPhysicalFeet(
                        feet,
                        velocity,
                        new BedrockInputFrame(tick, -137.79028F, 43.668045F, false, false, true, GLIDE_INPUT),
                        BedrockCollisionFlags.AIR)
                .withGliding(true)
                .withPlayerDimensions(BedrockBoundingBoxMode.HORIZONTAL, GLIDE_SIZE, true);
    }

    private static BedrockMovementResult move(
            BedrockMovementState previous, BedrockInputFrame frame, List<PlacedBlockCollision> blocks) {
        BedrockMovementContext context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                new MovementModifierState(true, true, false, true, .05, false, false, false, false, .35, 0),
                GLIDE_SIZE);
        return BedrockSimulation.move(
                previous,
                frame,
                context,
                BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE,
                true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP);
    }

    private static WorldCollisionBox cube(BlockPosition position) {
        return new WorldCollisionBox(
                position.x(),
                position.y(),
                position.z(),
                position.x() + 1.0D,
                position.y() + 1.0D,
                position.z() + 1.0D);
    }
}
