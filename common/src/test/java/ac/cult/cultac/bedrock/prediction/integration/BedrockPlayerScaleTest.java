package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.*;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockCollisionSweep;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockActorDimensions;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.*;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import java.util.List;
import java.util.UUID;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A capture of a player whose scale attribute changes shows only the scale in the player's own entity data. The
 * client then stops with its centre 0.3 times that scale from a wall: 0.6 away at scale 2.0, 0.075 at 0.25.
 */
public final class BedrockPlayerScaleTest {
    private static final PlayerDimensionsState DEFINITION = new PlayerDimensionsState(0.6F, 1.8F);

    @BeforeClass
    public static void bootstrap() {
        OfflineCultTestBootstrap.installConfig();
    }

    @Test
    public void doubledPlayerStopsWhereTheClientStopped() {
        var player = player();
        // The client reported x=-20.340717 moving -x, then x=-20.4 with no x velocity: the wall face is x=-21.
        var state = player.applyConfirmedBoundingBoxSize(standing(-20.340717, 121.17676, -23.51652), idle(), 1.0F);
        assertEquals(new PlayerDimensionsState(1.2F, 3.6F), player.scaledCollisionDefinition(2.0F));
        state = player.applyConfirmedBoundingBoxSize(state, state.inputFrame(), 2.0F);

        assertEquals(new PlayerDimensionsState(1.2F, 3.6F), state.playerDimensions());
        assertEquals(-20.340717F - 0.6F, (float) state.collisionBox().minX(), 0);
        assertEquals(-20.340717F + 0.6F, (float) state.collisionBox().maxX(), 0);
        assertEquals(121.17676F + 3.6F, (float) state.collisionBox().maxY(), 0);
        var moved = BedrockCollisionSweep.sweep(
                state.physicalFeetPosition(),
                new Vec3d(-0.079281, 0, -0.17363),
                wall(-22, -21),
                state.playerDimensions());
        assertEquals(-20.4F, (float) moved.position().x(), 1.0E-6F);
        assertEquals(-23.51652F - 0.17363F, (float) moved.position().z(), 1.0E-6F);
    }

    @Test
    public void quarterPlayerStopsWhereTheClientStopped() {
        var player = player();
        // The client reported x=-2.2475193 moving +x, then x=-2.0749998 with no x velocity: the wall face is x=-2.
        var state = player.applyConfirmedBoundingBoxSize(standing(-2.2475193, 121.25221, -25.346739), idle(), 1.0F);
        state = player.applyConfirmedBoundingBoxSize(state, state.inputFrame(), 0.25F);

        assertEquals(new PlayerDimensionsState(0.6F * 0.25F, 1.8F * 0.25F), state.playerDimensions());
        var moved = BedrockCollisionSweep.sweep(
                state.physicalFeetPosition(),
                new Vec3d(0.22781555, 0, 0.19980954),
                wall(-2, -1),
                state.playerDimensions());
        assertEquals(-2.0749998F, (float) moved.position().x(), 1.0E-6F);
    }

    @Test
    public void aStateThatNeverHeldADefinitionKeepsTheSizeItsContextGaveIt() {
        var player = player();
        var state = player.applyConfirmedBoundingBoxSize(standing(0, 80, 0), idle(), 1.5F);
        assertEquals(new PlayerDimensionsState(0.6F * 1.5F, 1.8F * 1.5F), state.collisionDefinition());
        assertEquals(PlayerDimensionsState.DEFAULT, state.playerDimensions());

        // Before the client confirms its collision definition there is nothing to scale.
        assertNull(new BedrockPlayerState(new UUID(0, 2)).scaledCollisionDefinition(1.5F));
    }

    @Test
    public void theStandingSneakingAndLowBoxesFollowTheScale() {
        var half = BedrockActorDimensions.scaledDefinition(DEFINITION, 0.5F);
        var standing = BedrockActorDimensions.withCollisionDefinition(
                standing(0, 80, 0).withCollisionDefinition(DEFINITION), half);
        assertEquals(new PlayerDimensionsState(0.3F, 0.9F), standing.playerDimensions());

        var low = BedrockActorDimensions.withCollisionDefinition(
                standing(0, 80, 0)
                        .withCollisionDefinition(DEFINITION)
                        .withPlayerDimensions(
                                BedrockBoundingBoxMode.HORIZONTAL, new PlayerDimensionsState(0.6F, 0.6F), false),
                half);
        assertEquals(new PlayerDimensionsState(0.3F, 0.3F), low.playerDimensions());
        assertEquals(BedrockBoundingBoxMode.HORIZONTAL, low.boundingBoxMode());

        var sneaking = BedrockActorDimensions.withCollisionDefinition(
                standing(0, 80, 0)
                        .withCollisionDefinition(DEFINITION)
                        .withPlayerDimensions(
                                BedrockBoundingBoxMode.SNEAKING, new PlayerDimensionsState(0.6F, 1.49F), false),
                half);
        assertEquals(new PlayerDimensionsState(0.3F, 1.49F * 0.5F), sneaking.playerDimensions());
    }

    // At scale 0.8 a client that starts sneaking while jumping under a ceiling at y 122 rises the full 0.3332 from
    // feet 120.42: its sneaking box is 1.192 high. A 1.49 high box would stop at the ceiling.
    @Test
    public void theSneakingBoxStartedAtAScaleIsThatScaleTimesTheSneakingHeight() {
        var player = player();
        var standing = player.applyConfirmedBoundingBoxSize(standing(27.2273F, 120.42F, -76.5F), idle(), 0.8F);
        var startSneaking = new BedrockInputFrame(
                22342L, 0.0F, 0.0F, true, false, false, java.util.Set.of("START_SNEAKING"));

        var dimensions = BedrockActorDimensions.committedMovementDimensions(
                standing, context(PlayerDimensionsState.DEFAULT, List.of()), startSneaking);
        assertEquals(new PlayerDimensionsState(0.6F * 0.8F, 1.49F * 0.8F), dimensions);
        var moved = BedrockCollisionSweep.sweep(
                standing.physicalFeetPosition(), new Vec3d(-0.239513, 0.3332, 0), ceiling(), dimensions);
        assertEquals(120.7532F, (float) moved.position().y(), 1.0E-5F);
    }

    // The scale grows from 0.8 to 1.3 while the client sneaks under that ceiling, feet 120.42: its sneaking box is
    // now 1.937 high and reaches into the ceiling block, whose face is x 27. Moving -0.00588 the client ends the
    // tick with x velocity 0.0946492: -0.0053508 after drag, raised by the 0.1 push out of the block.
    @Test
    public void aSneakingBoxGrownIntoABlockIsPushedOutAtTheEndOfTheTick() {
        var player = player();
        player.applyAcknowledgedPoseMetadata(null, null, true, null, null);
        var sneaking = standing(27.2273F, 120.42F, -76.5F)
                .withCollisionDefinition(BedrockActorDimensions.scaledDefinition(DEFINITION, 0.8F))
                .withPlayerDimensions(
                        BedrockBoundingBoxMode.SNEAKING, new PlayerDimensionsState(0.48F, 1.49F * 0.8F), false);
        var grown = player.applyConfirmedBoundingBoxSize(sneaking, idle(), 1.3F);
        assertEquals(new PlayerDimensionsState(0.6F * 1.3F, 1.49F * 1.3F), grown.playerDimensions());

        CultPlayer cult = mock(CultPlayer.class);
        cult.bedrockState = player;
        SimulationContext simulationContext = mock(SimulationContext.class);
        when(simulationContext.getScale()).thenReturn(1.3F);
        var contextDimensions =
                BedrockPlayerContext.from(cult, simulationContext, null, false).dimensions();
        assertEquals(grown.playerDimensions().height(), contextDimensions.height(), 0);
        assertEquals(grown.playerDimensions().width(), contextDimensions.width(), 1.0E-7);

        var frame = BedrockInputFrame.idle(22344L);
        var input = new BedrockSimulation.Input(
                grown,
                frame,
                frame.intent(),
                BedrockWorldSnapshot.fromContext(context(contextDimensions, ceilingBlocks())),
                true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                BedrockMobJumpComponentState.DEFAULT,
                true,
                false,
                new Vec3d(-0.3, 0, 0),
                false);
        var result = BedrockForwardTick.simulate(input).getFirst().movementResult();
        var end = BedrockForwardTick.finish(result, result.predictedState()).getFirst();

        assertEquals(-0.00588, result.predictedPosition().x() - 27.2273F, 1.0E-6);
        assertEquals(0.0946492, end.velocity().x(), 1.0E-6);
        assertEquals(0.0, end.velocity().z(), 1.0E-6);
    }

    @Test
    public void contextSizesANewStateAtThePlayersScale() {
        CultPlayer cult = mock(CultPlayer.class);
        cult.bedrockState = player();
        SimulationContext context = mock(SimulationContext.class);
        when(context.getScale()).thenReturn(2.0F);

        var dimensions = BedrockPlayerContext.from(cult, context, null, false).dimensions();
        assertEquals(1.2F, (float) dimensions.width(), 0);
        assertEquals(3.6F, (float) dimensions.height(), 0);
    }

    @Test
    public void rewindReplaysTheScaleOnTheTickItWasReceived() {
        var history = new BedrockActorHistory();
        var state = BedrockMovementState.fromPhysicalFeet(
                        new Vec3d(0, 80, 0),
                        new Vec3d(0.1, -0.1, 0),
                        BedrockInputFrame.idle(0),
                        BedrockCollisionFlags.AIR)
                .withCollisionDefinition(DEFINITION)
                .withPlayerDimensions(DEFINITION, false);
        for (long tick = 1; tick <= 5; tick++) {
            var frame = forward(state, tick);
            history.record(frame);
            state = frame.end().getFirst().state();
        }
        var doubled = BedrockActorDimensions.scaledDefinition(DEFINITION, 2.0F);

        // An untimed scale is received after tick 5: the client's next tick runs with the new box.
        history.metadata(0, new BedrockReplayEvent.Metadata(null, null, null, null, null, null, null, doubled));
        assertEquals(doubled, history.current().getFirst().state().playerDimensions());
        for (long tick = 6; tick <= 7; tick++) {
            history.record(forward(history.current().getFirst().state(), tick));
        }
        assertEquals(doubled, history.frames().get(5).receivedMetadata().scaledDefinition());

        var replayed = history.apply(3, new BedrockReplayEvent.Motion(new Vec3d(0.2, 0, 0)), (old, world) -> world)
                .getFirst()
                .state();
        assertEquals(
                DEFINITION,
                history.frames().get(4).beforeEvents().getFirst().state().playerDimensions());
        assertEquals(
                doubled, history.frames().get(5).beforeEvents().getFirst().state().playerDimensions());
        assertEquals(doubled, replayed.playerDimensions());
        assertEquals(doubled, replayed.collisionDefinition());
    }

    /** Stone at y 122 over x 21 to 26 and z -78 to -72. */
    private static List<PlacedBlockCollision> ceilingBlocks() {
        var blocks = new java.util.ArrayList<PlacedBlockCollision>();
        for (int x = 21; x <= 26; x++) {
            for (int z = -78; z <= -72; z++) {
                blocks.add(PlacedBlockCollision.manual(
                        new BlockPosition(x, 122, z),
                        "minecraft:stone",
                        "minecraft:stone",
                        List.of(new WorldCollisionBox(x, 122, z, x + 1, 123, z + 1))));
            }
        }
        return blocks;
    }

    private static List<BlockCollision> ceiling() {
        return new BlockCollisionWorld(ceilingBlocks()).collisions();
    }

    private static BedrockMovementContext context(PlayerDimensionsState dimensions, List<PlacedBlockCollision> blocks) {
        return new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                dimensions);
    }

    private static BedrockPlayerState player() {
        var player = new BedrockPlayerState(new UUID(0, 1));
        player.confirmCollisionDefinition(DEFINITION);
        return player;
    }

    private static BedrockInputFrame idle() {
        return BedrockInputFrame.idle(1);
    }

    private static BedrockMovementState standing(double x, double y, double z) {
        return BedrockMovementState.fromPhysicalFeet(
                new Vec3d(x, y, z), Vec3d.ZERO, BedrockInputFrame.idle(0), BedrockCollisionFlags.AIR);
    }

    private static List<BlockCollision> wall(int minX, int maxX) {
        var block = PlacedBlockCollision.manual(
                new BlockPosition(minX, 121, -24),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(minX, 118, -30, maxX, 126, -18)));
        return new BlockCollisionWorld(List.of(block)).collisions();
    }

    private static BedrockActorHistory.Frame forward(BedrockMovementState state, long tick) {
        var frame = BedrockInputFrame.idle(tick);
        var world = BedrockWorldSnapshot.fromContext(new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(List.of())),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT));
        var input = new BedrockSimulation.Input(
                state,
                frame,
                frame.intent(),
                world,
                false,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                BedrockMobJumpComponentState.DEFAULT,
                true,
                false,
                Vec3d.ZERO,
                false);
        var candidate = BedrockForwardTick.simulate(input).getFirst();
        var result = candidate.movementResult();
        var entries = BedrockForwardTick.finish(result, result.predictedState()).stream()
                .map(next -> new BedrockProfileState.Entry(next, candidate.mobJumpComponent()))
                .toList();
        return new BedrockActorHistory.Frame(
                tick,
                input,
                entries,
                entries,
                null,
                Vec3d.ZERO,
                Vec3d.ZERO,
                result.predictedPosition(),
                result.predictedVelocity(),
                List.of());
    }
}
