package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionWorldBuilder;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockSnapshotResolver;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import ac.cult.cultac.bedrock.protocol.BedrockMoveVector;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.utils.collisions.BedrockClientBlockShapeMappings;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BedrockHoneyJumpTest {
    private static final BlockPosition SUPPORT = new BlockPosition(0, 81, 0);
    private static final BlockPosition COVER = new BlockPosition(0, 82, 0);
    private static final double EPSILON = 1.0E-7D;

    @BeforeClass
    public static void initializeBundledBlockMetadata() {
        OfflineCultTestBootstrap.installConfig();
    }

    @Test
    public void flag40HoneyJumpMatchesPacketAndRejectsNormalJumpHeight() {
        Vec3d feet = new Vec3d(314.44970703125D, 82.0D, -90.5108871459961D);
        BedrockInputFrame previousFrame = new BedrockInputFrame(5285L, -27.50261F, 63.202057F,
                false, false, true, Set.of("SPRINTING", "SPRINT_DOWN"));
        BedrockMovementState previous = BedrockMovementState.fromPhysicalFeet(feet,
                new Vec3d(0.02220112830400467D, -0.07840000092983246D, 0.00686628045514226D),
                previousFrame, BedrockCollisionFlags.ON_GROUND);
        BedrockInputFrame frame = new BedrockInputFrame(5286L, previousFrame.yaw(), previousFrame.pitch(),
                true, false, true, Set.of("JUMPING", "SPRINTING", "START_JUMPING", "JUMP_PRESSED_RAW",
                        "JUMP_CURRENT_RAW", "SPRINT_DOWN", "WANT_UP"));
        List<BedrockMovementResult> results = simulate(previous, frame, BedrockEffectState.NONE,
                Map.of(new BlockPosition(314, 81, -91), Blocks.HONEY_BLOCK.defaultBlockState()));
        Vec3 observed = new Vec3(314.6155090332031D, 82.25199890136719D, -90.31046295166016D);

        for (BedrockMovementResult result : results) {
            assertTrue(result.groundJumpApplied());
            assertEquals(0.25200000405311584D, result.collisionInputVelocity().y(), 0.0D);
            assertEquals(observed.y, result.predictedPosition().y(), 0.0D);
            assertEquals(0.16856001317501068D, result.predictedVelocity().y(), EPSILON);
            // A normal-height jump from honey must still fail the unchanged validation.
            assertTrue(observationOffset(result,
                    new Vec3(observed.x, 82.41999816894531D, observed.z)) > 0.167D);
        }
        assertTrue(results.stream().mapToDouble(result -> observationOffset(result, observed))
                .min().orElseThrow() <= 0.001D);
    }

    @Test
    public void carpetUsesTheHoneyBlockBelowForJumpRestriction() {
        for (BlockState carpet : List.of(BuiltInRegistries.BLOCK
                .getValue(Identifier.parse("minecraft:white_carpet")).defaultBlockState(),
                Blocks.MOSS_CARPET.defaultBlockState())) {
            assertImpulse(0.25200000405311584D, 82.0625D, BedrockEffectState.NONE,
                    Map.of(SUPPORT, Blocks.HONEY_BLOCK.defaultBlockState(), COVER, carpet));
        }
    }

    @Test
    public void slabAboveHoneyKeepsNormalJumpHeight() {
        assertImpulse(0.42F, 82.5D, BedrockEffectState.NONE,
                Map.of(SUPPORT, Blocks.HONEY_BLOCK.defaultBlockState(),
                        COVER, Blocks.STONE_SLAB.defaultBlockState()));
    }

    @Test
    public void neighboringHoneyDoesNotRestrictStoneJump() {
        assertImpulse(0.42F, 82.0D, BedrockEffectState.NONE,
                Map.of(SUPPORT, Blocks.STONE.defaultBlockState(),
                        new BlockPosition(1, 81, 0), Blocks.HONEY_BLOCK.defaultBlockState()));
    }

    @Test
    public void chainPropertyDoesNotRestrictJumping() {
        // Honey and chain share 0x2000000000; only honey has the jump-restriction bit.
        assertImpulse(0.42F, 82.0D, BedrockEffectState.NONE,
                Map.of(SUPPORT, Blocks.IRON_CHAIN.defaultBlockState()));
    }

    @Test
    public void honeyScalesJumpBoostBeforeMovement() {
        assertImpulse(0.3720000088214874D, 82.0D, BedrockEffectState.NONE.withJumpBoostLevel(2),
                Map.of(SUPPORT, Blocks.HONEY_BLOCK.defaultBlockState()));
    }

    @Test
    public void airborneHoneyContactDoesNotCreateAGroundJump() {
        BedrockMovementState previous = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(0.5D, 82.25D, 0.5D), new Vec3d(0.0D, -0.05D, 0.0D),
                BedrockInputFrame.idle(0L), BedrockCollisionFlags.AIR);
        for (BedrockMovementResult result : simulate(previous, jumpFrame(), BedrockEffectState.NONE,
                Map.of(SUPPORT, Blocks.HONEY_BLOCK.defaultBlockState()))) {
            assertFalse(result.groundJumpApplied());
            assertEquals(-0.05D, result.collisionInputVelocity().y(), EPSILON);
        }
    }

    private static void assertImpulse(double impulse, double feetY, BedrockEffectState effects,
                                      Map<BlockPosition, BlockState> blocks) {
        BedrockMovementState previous = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(0.5D, feetY, 0.5D), new Vec3d(0.0D, -0.0784F, 0.0D),
                BedrockInputFrame.idle(0L), BedrockCollisionFlags.ON_GROUND);
        for (BedrockMovementResult result : simulate(previous, jumpFrame(), effects, blocks)) {
            assertTrue(result.groundJumpApplied());
            assertEquals(impulse, result.collisionInputVelocity().y(), EPSILON);
            assertEquals((double) (float) ((float) feetY + (float) impulse),
                    result.predictedPosition().y(), 0.0D);
        }
    }

    private static BedrockInputFrame jumpFrame() {
        return new BedrockInputFrame(1L, 0.0F, 0.0F, true, false, false,
                Set.of("JUMPING", "START_JUMPING", "JUMP_PRESSED_RAW", "JUMP_CURRENT_RAW", "WANT_UP"));
    }

    private static List<BedrockMovementResult> simulate(BedrockMovementState previous,
            BedrockInputFrame frame, BedrockEffectState effects, Map<BlockPosition, BlockState> blocks) {
        var world = new BedrockCollisionWorldBuilder(BedrockClientBlockShapeMappings.catalog()).build(blocks);
        var context = new BedrockMovementContext(effects, AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, world), EquipmentState.NONE,
                EntityContactState.NONE, MovementModifierState.NONE, PlayerDimensionsState.DEFAULT);
        var snapshot = BedrockSnapshotResolver.forState(BedrockWorldSnapshot.fromContext(context), previous, frame);
        return BedrockSimulation.candidates(new BedrockSimulation.Input(previous, frame, frame.intent(),
                snapshot, false, BedrockSimulation.DEFAULT_MAX_AUTO_STEP, BedrockMobJumpComponentState.DEFAULT))
                .stream().map(BedrockSimulation.Candidate::movementResult).toList();
    }

    private static double observationOffset(BedrockMovementResult result, Vec3 observed) {
        return BedrockMovementObservationFactory.fromValidationSelection(result, observed,
                result.predictedPosition(), result.predictedPosition(),
                new BedrockMoveVector(0.70710677F, 0.70710677F)).validationOffset();
    }
}
