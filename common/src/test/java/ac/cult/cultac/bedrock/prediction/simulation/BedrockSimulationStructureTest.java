package ac.cult.cultac.bedrock.prediction.simulation;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
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
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementUpdate;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbableContact;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BedrockSimulationStructureTest {
    @Test
    public void idleInputBruteForcesWalkAndSprintTravelSpeed() {
        BedrockInputFrame frame = BedrockInputFrame.idle(1L);
        BedrockMovementState state = BedrockMovementState.fromPhysicalFeet(
            new Vec3d(1.0D, 2.0D, 3.0D), Vec3d.ZERO, frame, BedrockCollisionFlags.AIR
        );
        BedrockWorldSnapshot snapshot = BedrockSnapshotResolver.forState(
            BedrockWorldSnapshot.fromContext(airContext()), state, frame
        );

        var candidates = BedrockSimulation.candidates(new BedrockSimulation.Input(
            state,
            frame,
            frame.intent(),
            snapshot,
            false,
            BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
            BedrockMobJumpComponentState.DEFAULT
        ));

        assertEquals(2, candidates.size());
        var inputLimits = candidates.stream()
            .map(candidate -> candidate.movementResult().horizontalInputLimit())
            .sorted()
            .toList();
        assertEquals(0.02D, inputLimits.get(0), 1.0E-8D);
        assertEquals(0.026D, inputLimits.get(1), 1.0E-8D);
        assertTrue(candidates.stream().allMatch(candidate ->
            candidate.movementResult().previousState().equals(state)));
    }

    @Test
    public void unloadedCompensatedChunkSkipsActorMovementSystemsButConsumesAuthFrame() {
        BedrockInputFrame previousFrame = BedrockInputFrame.idle(2L);
        BedrockInputFrame currentFrame = BedrockInputFrame.idle(3L);
        Vec3d position = new Vec3d(298.6525D, 84.0D, -100.63724D);
        BedrockMovementState state = BedrockMovementState.fromPhysicalFeet(
            position, Vec3d.ZERO, previousFrame, BedrockCollisionFlags.ON_GROUND);
        BedrockWorldSnapshot snapshot = BedrockSnapshotResolver.forState(
            BedrockWorldSnapshot.fromContext(airContext()), state, currentFrame);

        var candidates = BedrockSimulation.candidates(new BedrockSimulation.Input(
            state,
            currentFrame,
            currentFrame.intent(),
            snapshot,
            true,
            BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
            BedrockMobJumpComponentState.DEFAULT,
            false));

        assertEquals(1, candidates.size());
        BedrockMovementState predicted = candidates.get(0).movementResult().predictedState();
        assertEquals(state.physicalFeetPosition(), predicted.physicalFeetPosition());
        assertEquals(Vec3d.ZERO, predicted.velocity());
        assertEquals(BedrockCollisionFlags.ON_GROUND, predicted.collisionFlags());
        assertEquals(currentFrame, predicted.inputFrame());
        assertEquals(state.simulationTick(), predicted.simulationTick());
        var carry = BedrockSimulation.deriveNextStates(
            candidates.getFirst().movementResult(), List.of(predicted), Vec3d.ZERO);
        assertEquals(1, carry.size());
        assertEquals(state.velocity(), carry.getFirst().state().velocity());
        assertEquals(state.simulationTick(), carry.getFirst().state().simulationTick());
    }

    @Test
    public void skippedActorTickPreservesAirborneVelocityThroughReconciliation() {
        var frame = BedrockInputFrame.idle(3L);
        var state = BedrockMovementState.fromPhysicalFeet(new Vec3d(0, 80, 0),
            new Vec3d(0.125, -0.25, 0.0625), BedrockInputFrame.idle(2L), BedrockCollisionFlags.AIR);
        var snapshot = BedrockSnapshotResolver.forState(
            BedrockWorldSnapshot.fromContext(airContext()), state, frame);
        var result = BedrockSimulation.candidates(new BedrockSimulation.Input(
            state, frame, frame.intent(), snapshot, true,
            BedrockSimulation.DEFAULT_MAX_AUTO_STEP, BedrockMobJumpComponentState.DEFAULT, false))
            .getFirst().movementResult();
        var carry = BedrockSimulation.deriveNextStates(result, List.of(result.predictedState()), Vec3d.ZERO);
        assertEquals(1, carry.size());
        assertEquals(state.physicalFeetPosition(), carry.getFirst().state().physicalFeetPosition());
        assertEquals(state.velocity(), carry.getFirst().state().velocity());
        assertEquals(state.collisionFlags(), carry.getFirst().state().collisionFlags());
    }

    @Test
    public void teleportSuppressionSurvivesSkippedActorTicksAndIsConsumedOnce() {
        var state = BedrockMovementState.fromPhysicalFeet(new Vec3d(0, 80, 0), Vec3d.ZERO,
            BedrockInputFrame.idle(0L), BedrockCollisionFlags.AIR);
        for (long tick = 1; tick <= 5; tick++) {
            var frame = BedrockInputFrame.idle(tick);
            var snapshot = BedrockSnapshotResolver.forState(
                BedrockWorldSnapshot.fromContext(airContext()), state, frame);
            var result = BedrockSimulation.candidates(new BedrockSimulation.Input(
                state, frame, frame.intent(), snapshot, true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP, BedrockMobJumpComponentState.DEFAULT,
                tick >= 4, tick == 1)).getFirst().movementResult();
            var carry = BedrockSimulation.deriveNextStates(result, List.of(result.predictedState()), Vec3d.ZERO);
            assertEquals(1, carry.size());
            state = carry.getFirst().state();
            assertEquals(tick < 4, state.hasTeleported());
            assertEquals(tick == 5, result.travelActive());
            assertEquals(tick < 4 ? 0 : tick - 3, state.simulationTick());
            assertEquals(tick == 5 ? -0.0784 : 0.0, state.velocity().y(), 0.00000001);
            assertEquals(80.0, state.physicalFeetPosition().y(), 0.0);
        }
    }

    @Test
    public void snapshotEnrichmentIsIdempotentForTheSameState() {
        BedrockInputFrame frame = BedrockInputFrame.idle(1L);
        BedrockMovementState state = BedrockMovementState.fromPhysicalFeet(
            Vec3d.ZERO, Vec3d.ZERO, frame, BedrockCollisionFlags.AIR
        );
        BedrockWorldSnapshot once = BedrockSnapshotResolver.forState(
            BedrockWorldSnapshot.fromContext(airContext()), state, frame
        );

        assertEquals(once, BedrockSnapshotResolver.forState(once, state, frame));
    }

    @Test
    public void groundedRiptideClientDeltaExcludesServerSideItemMove() {
        BedrockInputFrame previousFrame = BedrockInputFrame.idle(214L);
        BedrockMovementState initial = BedrockMovementState.fromPhysicalFeet(
            new Vec3d(255.5189208984375D, 82.0D, -91.70471954345703D),
            new Vec3d(-0.00684814453125D, -0.005D, 0.0021240234375D),
            previousFrame,
            BedrockCollisionFlags.ON_GROUND,
            Medium.WATER
        );
        BedrockMovementState charged = initial.advance(new BedrockMovementUpdate(
            initial.physicalFeetPosition(),
            initial.velocity(),
            previousFrame,
            initial.collisionFlags(),
            initial.boundingBoxMode(),
            initial.playerDimensions(),
            0.0F,
            0L,
            new BedrockMovementUpdate.Glide(false, false),
            false,
            0.0D,
            new BedrockMovementUpdate.Riptide(10L, false, 0L),
            new BedrockMovementUpdate.ItemUse(false, 0L)
        )).withMovementBranch(Medium.WATER);
        BedrockInputFrame release = new BedrockInputFrame(
            215L, -113.34771F, -5.7219696F, false, false, false,
            Set.of("RELEASE_USING_ITEM", "START_SPIN_ATTACK")
        );
        BlockCollisionWorld world = new BlockCollisionWorld(List.of(
            PlacedBlockCollision.manual(
                new BlockPosition(255, 82, -92),
                "minecraft:water[level=0]",
                "minecraft:water",
                Map.of("liquid_depth", 0),
                List.of()),
            PlacedBlockCollision.manual(
                new BlockPosition(255, 83, -92),
                "minecraft:water[level=0]",
                "minecraft:water",
                Map.of("liquid_depth", 0),
                List.of())
        ));
        BedrockMovementContext context = new BedrockMovementContext(
            BedrockEffectState.NONE,
            AttributeState.DEFAULT,
            new WorldContactState(Medium.WATER, FluidState.NONE, world),
            new EquipmentState(0, 0, 0, 1, false, false),
            EntityContactState.NONE,
            new MovementModifierState(
                true, true, false, 0.05D, false, true, false, false, 0.35D, 0L),
            PlayerDimensionsState.DEFAULT
        );
        BedrockWorldSnapshot snapshot = BedrockSnapshotResolver.forState(
            BedrockWorldSnapshot.fromContext(context), charged, release
        );

        var candidates = BedrockSimulation.candidates(new BedrockSimulation.Input(
            charged,
            release,
            release.intent(),
            snapshot,
            false,
            BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
            BedrockMobJumpComponentState.DEFAULT
        ));

        assertFalse(candidates.isEmpty());
        var deltas = candidates.stream().map(candidate -> {
            var result = candidate.movementResult();
            return result.predictedState().physicalFeetPosition()
                .subtract(result.previousState().physicalFeetPosition());
        }).toList();
        assertTrue(deltas.toString(), deltas.stream().allMatch(delta -> {
            return Math.abs(delta.x() - 1.363494873046875D) < 0.001D
                && Math.abs(delta.y() - 0.224456787109375D) < 0.001D
                && Math.abs(delta.z() + 0.5893402099609375D) < 0.001D;
        }));
    }

    @Test
    public void airborneSurfaceReleaseConsumesPreUpdateWaterComponent() {
        BedrockInputFrame previousFrame = new BedrockInputFrame(
            570L, 169.65863F, -1.5891876F, true, false, false,
            Set.of("JUMPING", "JUMP_CURRENT_RAW", "WANT_UP"));
        BedrockMovementState initial = BedrockMovementState.fromPhysicalFeet(
            new Vec3d(255.63169860839844D, 82.67180633544922D, -90.35993957519531D),
            new Vec3d(-2.8839111328125003E-4D, 0.105638427734375D, -0.001654815673828125D),
            previousFrame,
            BedrockCollisionFlags.VERTICAL_COLLISION,
            Medium.AIR);
        BedrockMovementState charged = initial.advance(new BedrockMovementUpdate(
            initial.physicalFeetPosition(),
            initial.velocity(),
            previousFrame,
            initial.collisionFlags(),
            initial.boundingBoxMode(),
            initial.playerDimensions(),
            0.0F,
            0L,
            new BedrockMovementUpdate.Glide(false, false),
            false,
            0.0D,
            new BedrockMovementUpdate.Riptide(10L, false, 0L),
            new BedrockMovementUpdate.ItemUse(false, 0L)
        )).withWasInWaterFlag(true).withMovementBranch(Medium.AIR);
        BedrockInputFrame release = new BedrockInputFrame(
            571L, 169.65863F, -1.5891876F, true, false, false,
            Set.of("WANT_UP", "JUMP_CURRENT_RAW", "RELEASE_USING_ITEM", "START_SPIN_ATTACK", "JUMPING"));
        BedrockMovementContext context = new BedrockMovementContext(
            BedrockEffectState.NONE,
            AttributeState.DEFAULT,
            new WorldContactState(Medium.AIR, FluidState.NONE, BlockCollisionWorld.EMPTY),
            new EquipmentState(0, 0, 0, 1, false, false),
            EntityContactState.NONE,
            new MovementModifierState(
                false, true, false, 0.05D, false, true, false, false, 0.35D, 0L),
            PlayerDimensionsState.DEFAULT);
        BedrockWorldSnapshot snapshot = BedrockSnapshotResolver.forState(
            BedrockWorldSnapshot.fromContext(context), charged, release);

        var candidates = BedrockSimulation.candidates(new BedrockSimulation.Input(
            charged,
            release,
            release.intent(),
            snapshot,
            false,
            BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
            BedrockMobJumpComponentState.DEFAULT));

        assertFalse(candidates.isEmpty());
        assertTrue(candidates.stream().anyMatch(candidate -> {
            Vec3d delta = candidate.movementResult().predictedState().physicalFeetPosition()
                .subtract(candidate.movementResult().previousState().physicalFeetPosition());
            return Math.abs(delta.x() + 0.2694854736328125D) < 0.001D
                && Math.abs(delta.y() - 0.14719390869140625D) < 0.001D
                && Math.abs(delta.z() + 1.4765243530273438D) < 0.001D;
        }));
    }

    @Test
    public void powderSnowDescendBranchPassesThroughItsCollisionShape() {
        BedrockInputFrame frame = new BedrockInputFrame(
            1L, 0.0F, 0.0F, false, true, false,
            Set.of("WANT_DOWN", "START_SNEAKING")
        );
        BedrockMovementState state = BedrockMovementState.fromPhysicalFeet(
            new Vec3d(0.5D, 1.0D, 0.5D), new Vec3d(0.18D, -0.0784D, -0.18D), frame,
            BedrockCollisionFlags.ON_GROUND
        ).withClimbableContact(new BedrockClimbableContact(false, false, true, false));
        BlockPosition position = new BlockPosition(0, 0, 0);
        WorldCollisionBox fullBlock = new WorldCollisionBox(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
        BlockCollisionWorld world = new BlockCollisionWorld(List.of(
            PlacedBlockCollision.manual(
                position,
                "minecraft:powder_snow",
                "minecraft:powder_snow",
                List.of(fullBlock),
                List.of(fullBlock),
                List.of(fullBlock),
                Set.of(PlacedBlockCollision.BlockContactBehavior.POWDER_SNOW)
            )
        ));
        BedrockWorldSnapshot snapshot = BedrockSnapshotResolver.forState(
            BedrockWorldSnapshot.fromContext(airContext(world, true)), state, frame
        );

        var candidates = BedrockSimulation.candidates(new BedrockSimulation.Input(
            state,
            frame,
            frame.intent(),
            snapshot,
            false,
            BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
            BedrockMobJumpComponentState.DEFAULT
        ));

        assertTrue(candidates.stream().anyMatch(candidate -> {
            var result = candidate.movementResult();
            double deltaY = result.predictedState().physicalFeetPosition().y()
                - result.previousState().physicalFeetPosition().y();
            double deltaX = result.predictedState().physicalFeetPosition().x()
                - result.previousState().physicalFeetPosition().x();
            double deltaZ = result.predictedState().physicalFeetPosition().z()
                - result.previousState().physicalFeetPosition().z();
            return Math.abs(deltaY + 0.15000000596046448D) < 1.0E-7D
                && Math.abs(deltaX) < 1.0E-12D
                && Math.abs(deltaZ) < 1.0E-12D
                && !result.predictedState().collisionFlags().verticalCollision();
        }));
    }

    @Test
    public void groupedUpdateAdvancesEveryOwnedComponent() {
        BedrockMovementState state = BedrockMovementState.fromPhysicalFeet(
            Vec3d.ZERO,
            Vec3d.ZERO,
            BedrockInputFrame.idle(0L),
            BedrockCollisionFlags.ON_GROUND
        );
        BedrockInputFrame frame = BedrockInputFrame.idle(1L);
        BedrockMovementState next = state.advance(new BedrockMovementUpdate(
            new Vec3d(0.25D, 0.5D, -0.25D),
            new Vec3d(0.1D, 0.2D, 0.3D),
            frame,
            BedrockCollisionFlags.AIR,
            state.boundingBoxMode(),
            state.playerDimensions(),
            2.5F,
            3L,
            new BedrockMovementUpdate.Glide(true, true),
            true,
            0.4D,
            new BedrockMovementUpdate.Riptide(4L, true, 5L),
            new BedrockMovementUpdate.ItemUse(true, 6L)
        ));

        assertEquals(1L, next.simulationTick());
        assertEquals(3L, next.powderSnowTicks());
        assertEquals(2.5F, next.fallDistance(), 0.0F);
        assertEquals(4L, next.riptideChargeTicks());
        assertTrue(next.riptideSpinActive());
        assertEquals(5L, next.riptideSpinTicks());
        assertEquals(6L, next.itemUseSlowdownTicks());
        assertTrue(next.gliding());
        assertTrue(next.glidingRequest());
        assertTrue(next.swimming());
        assertEquals(0.4D, next.swimAmount(), 0.0D);
        assertTrue(next.itemUseSlowdownActive());
        assertFalse(next.autoClimbTravel());
    }

    @Test
    public void teleportTickKeepsBubbleColumnEffectWithoutWaterTravel() {
        var block = PlacedBlockCollision.manual(new BlockPosition(0, 0, 0),
            "minecraft:bubble_column[drag=false]", "minecraft:bubble_column", Map.of("drag_down", false), List.of());
        var waterAbove = PlacedBlockCollision.manual(new BlockPosition(0, 1, 0),
            "minecraft:water[level=0]", "minecraft:water", Map.of("liquid_depth", 0), List.of());
        var result = teleportResult(new Vec3d(0.5, 0, 0.5), new Vec3d(0, -0.02, 0),
            new BlockCollisionWorld(List.of(block, waterAbove)), Set.of());
        assertEquals(result.previousState().physicalFeetPosition(), result.predictedPosition());
        assertEquals(0.04, result.predictedVelocity().y(), 0.000001);
        assertFalse(result.selectedWaterTravel());
        assertFalse(result.travelActive());
        var carry = BedrockSimulation.deriveNextStates(result, List.of(result.predictedState()), Vec3d.ZERO);
        assertEquals(1, carry.size());
        assertEquals(result.predictedVelocity(), carry.getFirst().state().velocity());
    }

    @Test
    public void teleportTickAppliesHoneyInsideOnceWithoutMoveFriction() {
        var block = PlacedBlockCollision.manual(new BlockPosition(0, 0, 0),
            "minecraft:honey_block", "minecraft:honey_block", Map.of(), List.of());
        var result = teleportResult(new Vec3d(0.5, 0, 0.5), new Vec3d(0.2, -0.2, 0.1),
            new BlockCollisionWorld(List.of(block)), Set.of());
        assertEquals(result.previousState().physicalFeetPosition(), result.predictedPosition());
        // Honey friction scales x/z by 0.4F and clamps y to at least -0.12F.
        assertEquals(0.08, result.predictedVelocity().x(), 0.000001);
        assertEquals(-0.12, result.predictedVelocity().y(), 0.000001);
        assertEquals(0.04, result.predictedVelocity().z(), 0.000001);
        var carry = BedrockSimulation.deriveNextStates(result, List.of(result.predictedState()), Vec3d.ZERO);
        assertEquals(result.predictedVelocity(), carry.getFirst().state().velocity());
    }

    @Test
    public void teleportTickSensesWebWithoutConsumingItsMoveSlowdown() {
        var box = new WorldCollisionBox(0, 0, 0, 1, 1, 1);
        var block = PlacedBlockCollision.manual(new BlockPosition(0, 0, 0),
            "minecraft:cobweb", "minecraft:web", List.of(), List.of(box), List.of(box),
            Set.of(PlacedBlockCollision.BlockContactBehavior.COBWEB));
        var result = teleportResult(new Vec3d(0.5, 0, 0.5), new Vec3d(0.125, -0.25, 0.5),
            new BlockCollisionWorld(List.of(block)), Set.of("UP", "SPRINTING"));
        assertEquals(result.previousState().physicalFeetPosition(), result.predictedPosition());
        assertEquals(result.previousState().velocity(), result.predictedVelocity());
        assertTrue(result.predictedState().pendingBlockMovementSlowdownState().active());
        assertEquals(0.0, result.horizontalInputLimit(), 0.0);
    }

    @Test
    public void consecutiveTeleportTicksAdvanceAndFinishRiptideSpin() {
        var frame = BedrockInputFrame.idle(1L);
        var state = BedrockMovementState.fromPhysicalFeet(new Vec3d(0, 64, 0), Vec3d.ZERO, frame, BedrockCollisionFlags.AIR);
        state = state.advance(new BedrockMovementUpdate(state.physicalFeetPosition(), Vec3d.ZERO, frame,
            BedrockCollisionFlags.AIR, state.boundingBoxMode(), state.playerDimensions(), 0F, 0L,
            new BedrockMovementUpdate.Glide(false, false), false, 0D,
            new BedrockMovementUpdate.Riptide(0L, true, 6L), new BedrockMovementUpdate.ItemUse(false, 0L)));
        for (long tick = 2; tick <= 15; tick++) {
            frame = BedrockInputFrame.idle(tick);
            var snapshot = BedrockSnapshotResolver.forState(BedrockWorldSnapshot.fromContext(airContext()), state, frame);
            var result = BedrockSimulation.candidates(new BedrockSimulation.Input(state, frame, frame.intent(), snapshot,
                false, 0, BedrockMobJumpComponentState.DEFAULT, true, true)).getFirst().movementResult();
            assertEquals(state.physicalFeetPosition(), result.predictedPosition());
            assertEquals(state.simulationTick() + 1, result.predictedState().simulationTick());
            state = result.predictedState();
            if (tick < 15) assertEquals(5 + tick, state.riptideSpinTicks());
        }
        assertFalse(state.riptideSpinActive());
    }

    private static ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult teleportResult(
            Vec3d position, Vec3d velocity, BlockCollisionWorld world, Set<String> inputs) {
        BedrockInputFrame frame = new BedrockInputFrame(42L, 0F, 0F, false, false, false, inputs);
        var state = BedrockMovementState.fromPhysicalFeet(position, velocity, BedrockInputFrame.idle(41L), BedrockCollisionFlags.AIR);
        var snapshot = BedrockSnapshotResolver.forState(BedrockWorldSnapshot.fromContext(airContext(world, false)), state, frame);
        var result = BedrockSimulation.candidates(new BedrockSimulation.Input(state, frame, frame.intent(), snapshot,
            true, BedrockSimulation.DEFAULT_MAX_AUTO_STEP, BedrockMobJumpComponentState.DEFAULT, true, true)).getFirst().movementResult();
        assertEquals(state.simulationTick() + 1, result.predictedState().simulationTick());
        assertEquals(42L, result.predictedState().inputFrame().clientTick());
        return result;
    }

    private static BedrockMovementContext airContext() {
        return airContext(new BlockCollisionWorld(List.of()), false);
    }

    private static BedrockMovementContext airContext(BlockCollisionWorld world, boolean leatherBoots) {
        return new BedrockMovementContext(
            BedrockEffectState.NONE,
            AttributeState.DEFAULT,
            new WorldContactState(Medium.AIR, FluidState.NONE, world),
            new EquipmentState(0, 0, 0, leatherBoots, false),
            EntityContactState.NONE,
            MovementModifierState.NONE,
            PlayerDimensionsState.DEFAULT
        );
    }
}
