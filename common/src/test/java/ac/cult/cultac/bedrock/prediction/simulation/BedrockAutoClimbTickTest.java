package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockSnapshotResolver;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbableContact;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
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

/** The climb a wall hit starts, replayed from a client falling against a wall beside a scaffolding column. */
public final class BedrockAutoClimbTickTest {
    private static final Vec3d FEET = new Vec3d(352.6274719238281D, 83.0244140625D, -64.62248992919922D);
    private static final Vec3d VELOCITY = new Vec3d(0.15149036049842834D, -0.2276884764432907D, -0.3022023141384125D);

    // Falling at -0.227688 a tick into a stone block at x 353, the client stops with its box flush
    // against it and one column of that box reaching the scaffolding at x 353, z -66. It reports
    // -0.301535 afterwards, an ordinary fall: the block the centre of its feet is in is air.
    @Test
    public void aWallHitBesideScaffoldingKeepsFalling() {
        List<PlacedBlockCollision> blocks = wall();
        for (int y = 82; y <= 84; y++) {
            blocks.add(PlacedBlockCollision.manual(
                    new BlockPosition(353, y, -66),
                    "minecraft:scaffolding[bottom=false,distance=0,waterlogged=false]",
                    "minecraft:scaffolding",
                    List.of(),
                    Set.of(BlockContactBehavior.CLIMBABLE, BlockContactBehavior.SCAFFOLDING)));
        }
        var result = tick(blocks);
        assertTrue(result.predictedState().collisionFlags().horizontalCollision());
        assertTrue(result.predictedState().climbableContact().scaffolding());
        assertFalse(result.predictedState().climbableContact().ladder());
        assertEquals(-0.227688D, moved(result).y(), 1.0E-5D);
        assertEquals(-0.301535D, result.predictedVelocity().y(), 1.0E-5D);
    }

    // The same wall hit with the centre of the feet in a ladder climbs at 0.2 a tick.
    @Test
    public void aWallHitInsideALadderClimbs() {
        List<PlacedBlockCollision> blocks = wall();
        blocks.add(PlacedBlockCollision.manual(
                new BlockPosition(352, 82, -65),
                "minecraft:ladder[facing=west,waterlogged=false]",
                "minecraft:ladder",
                List.of(),
                Set.of(BlockContactBehavior.CLIMBABLE, BlockContactBehavior.LADDER)));
        var result = tick(blocks);
        assertTrue(result.predictedState().collisionFlags().horizontalCollision());
        assertTrue(result.predictedState().climbableContact().ladder());
        assertEquals(0.2D, result.predictedVelocity().y(), 1.0E-6D);
    }

    // A touch player holding the block ascend control rises 0.15 a tick against a stone wall, its box in a
    // scaffolding column at x -49 and a vine column at x -50. On the tick the centre of its feet crosses from
    // the scaffolding into the vine it still moves the 0.15 of the scaffolding and reports 0.2 afterwards: the
    // wall climb starts from the vine, whatever scaffolding the box reaches.
    @Test
    public void aWallHitInAVineClimbsWhileTheBoxReachesScaffolding() {
        BedrockMovementContext world = vineBesideScaffolding();
        Vec3d feet = new Vec3d(-48.99607849121094D, 123.05221557617188D, 3.6999995708465576D);
        var contact =
                BedrockClimbableContact.fromBlockWorld(world.worldState().blockCollisionWorld(), feet, 0.6D, 1.8D);
        assertTrue(contact.scaffolding());
        assertFalse(contact.ladder());
        var state = BedrockMovementState.fromPhysicalFeet(
                        feet,
                        new Vec3d(-0.02391263097524643D, 0.06860000640153885D, 0.0D),
                        BedrockInputFrame.idle(0L),
                        BedrockCollisionFlags.AIR)
                .withClimbableContact(contact);
        BedrockInputFrame frame = new BedrockInputFrame(
                1L,
                -1.8117676F,
                -16.76677F,
                true,
                false,
                true,
                Set.of("ASCEND_BLOCK", "SPRINTING", "SPRINT_DOWN", "JUMP_CURRENT_RAW", "INPUT_MODE_TOUCH"));

        var result = tick(world, state, frame, new Vec3d(-0.05174095D, 0.0D, 0.99866056D));

        assertTrue(result.predictedState().collisionFlags().horizontalCollision());
        assertTrue(result.predictedState().climbableContact().scaffolding());
        assertTrue(result.predictedState().climbableContact().ladder());
        assertEquals(0.15D, moved(result).y(), 2.0E-6D);
        assertEquals(0.0D, moved(result).z(), 1.0E-6D);
        assertEquals(0.2D, result.predictedVelocity().y(), 1.0E-6D);
    }

    // Climbing that vine with no control held, its box still reaching the scaffolding column, the client rises
    // 0.2 a tick and reports 0.2 after each: the climb goes on tick after tick.
    @Test
    public void aWallClimbInAVineGoesOnWhileTheBoxReachesScaffolding() {
        BedrockMovementContext world = vineBesideScaffolding();
        Vec3d feet = new Vec3d(-49.252782821655273D, 120.19231414794922D, 3.7000007629394531D);
        var contact =
                BedrockClimbableContact.fromBlockWorld(world.worldState().blockCollisionWorld(), feet, 0.6D, 1.8D);
        assertTrue(contact.scaffolding());
        assertTrue(contact.ladder());
        var state = BedrockMovementState.fromPhysicalFeet(
                        feet,
                        new Vec3d(0.07430530339479446D, 0.20000000298023224D, 0.0D),
                        BedrockInputFrame.idle(0L),
                        BedrockCollisionFlags.AIR)
                .withClimbableContact(contact);
        BedrockInputFrame frame =
                new BedrockInputFrame(1L, 5.438217F, 8.432114F, false, false, false, Set.of("INPUT_MODE_TOUCH"));

        var result = tick(world, state, frame, new Vec3d(0.60397583D, 0.0D, 0.7970027D));

        assertTrue(result.predictedState().collisionFlags().horizontalCollision());
        assertEquals(0.2D, moved(result).y(), 4.0E-6D);
        assertEquals(0.0D, moved(result).z(), 1.0E-6D);
        assertEquals(0.2D, result.predictedVelocity().y(), 1.0E-6D);
    }

    // A player standing with the centre of its feet in a north-facing ladder sprint-jumps into it. The client
    // moves the jump's 0.42 up, stops at the ladder's face at z 20123.8125 and reports 0.2 afterwards: the wall
    // climb also starts on the tick a jump starts.
    @Test
    public void aJumpIntoALadderClimbs() {
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        blocks.add(PlacedBlockCollision.manual(
                new BlockPosition(20026, 119, 20123),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(20022.0D, 119.0D, 20119.0D, 20030.0D, 120.0D, 20124.0D))));
        blocks.add(PlacedBlockCollision.manual(
                new BlockPosition(20026, 120, 20124),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(20022.0D, 120.0D, 20124.0D, 20030.0D, 124.0D, 20125.0D))));
        for (int y = 120; y <= 123; y++) {
            blocks.add(PlacedBlockCollision.manual(
                    new BlockPosition(20026, y, 20123),
                    "minecraft:ladder[facing=north,waterlogged=false]",
                    "minecraft:ladder",
                    List.of(new WorldCollisionBox(20026.0D, y, 20123.8125D, 20027.0D, y + 1.0D, 20124.0D)),
                    Set.of(BlockContactBehavior.CLIMBABLE, BlockContactBehavior.LADDER)));
        }
        BedrockMovementContext world = context(blocks);
        Vec3d feet = new Vec3d(20025.941146850586D, 120.0D, 20123.46156311035D);
        var contact =
                BedrockClimbableContact.fromBlockWorld(world.worldState().blockCollisionWorld(), feet, 0.6D, 1.8D);
        assertTrue(contact.ladder());
        var state = BedrockMovementState.fromPhysicalFeet(
                        feet,
                        new Vec3d(0.05827680602669716D, -0.07840000092983246D, 0.039073508232831955D),
                        BedrockInputFrame.idle(0L),
                        BedrockCollisionFlags.ON_GROUND)
                .withClimbableContact(contact);
        BedrockInputFrame frame = new BedrockInputFrame(
                1L,
                -17.914429F,
                11.431259F,
                true,
                false,
                true,
                Set.of("WANT_UP", "JUMPING", "START_JUMPING", "JUMP_CURRENT_RAW", "SPRINTING", "SPRINT_DOWN"));

        var result = tick(world, state, frame, new Vec3d(0.0D, 0.0D, 1.0D));

        assertTrue(result.predictedState().collisionFlags().horizontalCollision());
        assertTrue(result.predictedState().climbableContact().ladder());
        assertEquals(0.42D, moved(result).y(), 1.0E-6D);
        assertEquals(20123.8125D, result.predictedPosition().z() + 0.3D, 1.0E-6D);
        assertEquals(0.2D, result.predictedVelocity().y(), 1.0E-6D);
    }

    // Falling clear of the wall with the centre of its feet in the vine and its box reaching the scaffolding
    // column, the client caps the fall at 0.2 a tick and reports the capped fall after gravity and drag.
    @Test
    public void aFallInAVineIsCappedWhileTheBoxReachesScaffolding() {
        BedrockMovementContext world = vineBesideScaffolding();
        Vec3d feet = new Vec3d(-49.272D, 122.5D, 3.311D);
        var contact =
                BedrockClimbableContact.fromBlockWorld(world.worldState().blockCollisionWorld(), feet, 0.6D, 1.8D);
        assertTrue(contact.scaffolding());
        assertTrue(contact.ladder());
        var state = BedrockMovementState.fromPhysicalFeet(
                        feet,
                        new Vec3d(0.0D, -0.274399995803833D, 0.0D),
                        BedrockInputFrame.idle(0L),
                        BedrockCollisionFlags.AIR)
                .withClimbableContact(contact);

        var result = tick(world, state, BedrockInputFrame.idle(1L), Vec3d.ZERO);

        assertFalse(result.predictedState().collisionFlags().horizontalCollision());
        assertEquals(-0.2D, moved(result).y(), 1.0E-6D);
        assertEquals(-0.2744D, result.predictedVelocity().y(), 1.0E-6D);
    }

    /** A vine column at x -50 and a scaffolding column at x -49, both against a stone wall at z 4. */
    private static BedrockMovementContext vineBesideScaffolding() {
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        for (int y = 119; y <= 126; y++) {
            blocks.add(PlacedBlockCollision.manual(
                    new BlockPosition(-50, y, 4),
                    "minecraft:stone",
                    "minecraft:stone",
                    List.of(new WorldCollisionBox(-52.0D, y, 4.0D, -48.0D, y + 1.0D, 5.0D))));
            blocks.add(PlacedBlockCollision.manual(
                    new BlockPosition(-50, y, 3),
                    "minecraft:vine[east=false,north=false,south=true,up=false,west=false]",
                    "minecraft:vine",
                    List.of(),
                    Set.of(BlockContactBehavior.CLIMBABLE)));
            blocks.add(PlacedBlockCollision.manual(
                    new BlockPosition(-49, y, 3),
                    "minecraft:scaffolding[bottom=false,distance=0,waterlogged=false]",
                    "minecraft:scaffolding",
                    List.of(),
                    Set.of(BlockContactBehavior.CLIMBABLE, BlockContactBehavior.SCAFFOLDING)));
        }
        return context(blocks);
    }

    private static Vec3d moved(BedrockMovementResult result) {
        return result.predictedPosition().subtract(result.previousState().physicalFeetPosition());
    }

    private static BedrockMovementResult tick(List<PlacedBlockCollision> blocks) {
        var state = BedrockMovementState.fromPhysicalFeet(
                FEET, VELOCITY, BedrockInputFrame.idle(0L), BedrockCollisionFlags.AIR);
        return tick(context(blocks), state, BedrockInputFrame.idle(1L), Vec3d.ZERO);
    }

    private static BedrockMovementResult tick(
            BedrockMovementContext world, BedrockMovementState state, BedrockInputFrame frame, Vec3d control) {
        var snapshot = BedrockSnapshotResolver.forState(BedrockWorldSnapshot.fromContext(world), state, frame);
        return BedrockSimulation.candidates(new BedrockSimulation.Input(
                        state,
                        frame,
                        frame.intent(),
                        snapshot,
                        true,
                        BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                        BedrockMobJumpComponentState.DEFAULT,
                        true,
                        false,
                        control))
                .getFirst()
                .movementResult();
    }

    private static BedrockMovementContext context(List<PlacedBlockCollision> blocks) {
        return new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
    }

    /** A stone floor and the stone block at x 353 the player runs into. */
    private static List<PlacedBlockCollision> wall() {
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        blocks.add(PlacedBlockCollision.manual(
                new BlockPosition(352, 81, -65),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(348.0D, 81.0D, -69.0D, 357.0D, 82.0D, -61.0D))));
        blocks.add(PlacedBlockCollision.manual(
                new BlockPosition(353, 83, -65),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(353.0D, 83.0D, -65.0D, 354.0D, 84.0D, -64.0D))));
        return blocks;
    }
}
