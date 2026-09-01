package ac.grim.grimac.bedrock.prediction.simulation;

import ac.grim.grimac.bedrock.prediction.api.BedrockMovementResult;
import ac.grim.grimac.bedrock.prediction.geometry.BlockPosition;
import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.grim.grimac.bedrock.prediction.input.BedrockInputFrame;
import ac.grim.grimac.bedrock.prediction.model.AttributeState;
import ac.grim.grimac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.grim.grimac.bedrock.prediction.model.BedrockEffectState;
import ac.grim.grimac.bedrock.prediction.model.EquipmentState;
import ac.grim.grimac.bedrock.prediction.model.Medium;
import ac.grim.grimac.bedrock.prediction.model.MovementModifierState;
import ac.grim.grimac.bedrock.prediction.model.PlayerDimensionsState;
import ac.grim.grimac.bedrock.prediction.simulation.collision.BedrockBlockCollisionResolver;
import ac.grim.grimac.bedrock.prediction.simulation.collision.BedrockCollisionProbe;
import ac.grim.grimac.bedrock.prediction.simulation.collision.BedrockCollisionProjectionResolver;
import ac.grim.grimac.bedrock.prediction.simulation.collision.BedrockCollisionSweep;
import ac.grim.grimac.bedrock.prediction.simulation.collision.BedrockEntityMove;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockTravelInput;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import ac.grim.grimac.bedrock.prediction.world.BedrockMovementContext;
import ac.grim.grimac.bedrock.prediction.world.BlockCollision;
import ac.grim.grimac.bedrock.prediction.world.BlockCollisionWorld;
import ac.grim.grimac.bedrock.prediction.world.EntityContactState;
import ac.grim.grimac.bedrock.prediction.world.FluidState;
import ac.grim.grimac.bedrock.prediction.world.PlacedBlockCollision;
import ac.grim.grimac.bedrock.prediction.world.WorldContactState;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BedrockBlockCollisionResolverTest {
    @Test
    public void yFirstCollisionKeepsEdgeFallForNextTick() {
        BlockCollisionWorld world = new BlockCollisionWorld(List.of(
            PlacedBlockCollision.manual(
                new BlockPosition(0, 0, 0),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D)))));

        Vec3d currentFeet = new Vec3d(0.69D, 1.0D, 0.5D);
        Vec3d velocity = new Vec3d(0.7D, -0.0784000015258789D, 0.0D);
        BedrockBlockCollisionResolver.Result result = resolve(
            currentFeet,
            currentFeet.add(velocity),
            velocity,
            world,
            PlayerDimensionsState.DEFAULT
        );

        assertEquals(1.39D, result.position().x(), 1.0E-6D);
        assertEquals(1.0D, result.position().y(), 1.0E-6D);
        assertTrue(result.onGround());
        assertTrue(result.verticalCollision());
    }

    @Test
    public void horizontalMoveWithoutVerticalCollisionDoesNotSynthesizeGround() {
        BlockCollisionWorld world = new BlockCollisionWorld(List.of(
            PlacedBlockCollision.manual(
                new BlockPosition(0, 0, 0),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D)))));

        Vec3d currentFeet = new Vec3d(0.7D, 1.0D, 0.5D);
        Vec3d velocity = new Vec3d(0.2D, 0.0D, 0.0D);
        BedrockBlockCollisionResolver.Result result = resolve(
            currentFeet,
            currentFeet.add(velocity),
            velocity,
            world,
            PlayerDimensionsState.DEFAULT
        );

        assertEquals(0.9D, result.position().x(), 1.0E-6D);
        assertEquals(1.0D, result.position().y(), 1.0E-6D);
        assertFalse(result.onGround());
        assertFalse(result.verticalCollision());
    }

    @Test
    public void geyserSneakingHeightMatchesObservedTopSlabClip() {
        WorldCollisionBox ceilingBox = new WorldCollisionBox(
                231.0D, 84.0D, -87.0D, 232.0D, 84.5D, -86.0D);
        PlacedBlockCollision ceilingBlock = PlacedBlockCollision.manual(
                new BlockPosition(231, 84, -87),
                "minecraft:smooth_stone_slab",
                "minecraft:smooth_stone_slab",
                List.of(ceilingBox));
        List<BlockCollision> ceiling = List.of(new BlockCollision(ceilingBlock, ceilingBox));
        Vec3d feet = new Vec3d(231.70713806152344D, 82.41999816894531D, -86.29044342041016D);
        Vec3d attempted = new Vec3d(0.0D, 0.333198219537735D, 0.0D);

        var nativeSneak = BedrockCollisionSweep.sweep(
                feet, attempted, ceiling, new PlayerDimensionsState(0.6D, (double) 1.49F));
        var geyserSneak = BedrockCollisionSweep.sweep(
                feet, attempted, ceiling, new PlayerDimensionsState(0.6D, 1.5D));

        assertEquals(0.09000396728515625D, nativeSneak.appliedDelta().y(), 0.0D);
        assertEquals(0.0800018310546875D, geyserSneak.appliedDelta().y(), 0.0D);
    }

    @Test
    public void upwardMoveDoesNotClipAgainstFaceTouchingSideBlock() {
        BlockCollisionWorld world = new BlockCollisionWorld(List.of(
            PlacedBlockCollision.manual(
                new BlockPosition(229, 84, -71),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(229.0D, 84.0D, -71.0D, 230.0D, 85.0D, -70.0D)))));

        Vec3d currentFeet = new Vec3d(229.5301055908203D, 83.33866882324219D, -71.30000305175781D);
        Vec3d requestedMove = new Vec3d(0.004367065429676131D, 0.37312229766845917D, 0.0D);
        BedrockBlockCollisionResolver.Result result = resolve(
            currentFeet,
            currentFeet.add(requestedMove),
            requestedMove,
            world,
            PlayerDimensionsState.DEFAULT
        );

        assertEquals(
            BedrockCollisionSweep.f(currentFeet.y() + BedrockCollisionSweep.f(requestedMove.y())),
            result.position().y(),
            1.0E-6D);
        assertFalse(result.verticalCollision());
    }

    @Test
    public void airborneFallDoesNotCreateStepRequestAcrossLowObstacle() {
        BlockCollisionWorld world = candleFieldWorld();

        Vec3d currentFeet = new Vec3d(271.00042724609375D, 82.375D, -80.42987823486328D);
        Vec3d requestedMove = new Vec3d(0.20489000000003443D, -0.07840000092983246D, 0.18455500000000313D);
        BedrockBlockCollisionResolver.Result result = resolve(
            currentFeet,
            currentFeet.add(requestedMove),
            requestedMove,
            world,
            PlayerDimensionsState.DEFAULT
        );

        assertEquals("y", currentFeet.y() + requestedMove.y(), result.position().y(), 1.0E-6D);
        assertFalse(result.steppedUp());
        assertFalse(result.verticalCollision());
        assertFalse(result.onGround());
    }

    @Test
    public void groundedHorizontalMoveKeepsSupportReachedDuringFall() {
        BlockCollisionWorld world = candleFieldWorld();

        Vec3d currentFeet = new Vec3d(270.7245178222656D, 82.375D, -79.9754409790039D);
        Vec3d requestedMove = new Vec3d(0.100830078125D, -0.07840000092983246D, 0.25046539306640625D);
        BedrockEntityMove.Result result = BedrockEntityMove.move(
            groundedState(currentFeet),
            BedrockInputFrame.idle(0L),
            world,
            PlayerDimensionsState.DEFAULT,
            currentFeet.add(requestedMove),
            requestedMove,
            requestedMove.y(),
            true
        );

        assertEquals(currentFeet.x() + requestedMove.x(), result.position().x(), 1.0E-6D);
        assertEquals(currentFeet.y(), result.position().y(), 1.0E-6D);
        assertEquals(currentFeet.z() + requestedMove.z(), result.position().z(), 1.0E-6D);
        assertTrue(result.verticalCollision());
        assertTrue(result.onGround());
    }

    @Test
    public void groundedCandleEdgeKeepsSupportReachedDuringFall() {
        BlockCollisionWorld world = candleFieldWorld();

        Vec3d currentFeet = new Vec3d(271.00054931640625D, 82.375D, -80.42967987060547D);
        Vec3d requestedMove = new Vec3d(0.20489501953125D, -0.07840000092983246D, 0.1845550537109375D);
        BedrockEntityMove.Result result = BedrockEntityMove.move(
            groundedState(currentFeet),
            BedrockInputFrame.idle(0L),
            world,
            PlayerDimensionsState.DEFAULT,
            currentFeet.add(requestedMove),
            requestedMove,
            requestedMove.y(),
            true
        );

        assertEquals(currentFeet.x() + requestedMove.x(), result.position().x(), 1.0E-6D);
        assertEquals(currentFeet.y(), result.position().y(), 1.0E-6D);
        assertEquals(currentFeet.z() + requestedMove.z(), result.position().z(), 1.0E-6D);
        assertTrue(result.verticalCollision());
        assertTrue(result.onGround());
    }

    @Test
    public void candlestepping3Tick11520AuthProbeKeepsStepRetryAxis() {
        BlockCollisionWorld world = candleFieldWorld();

        Vec3d currentFeet = new Vec3d(270.85772705078125D, 82.14136505126953D, -79.86250305175781D);
        BedrockMovementState current = state(
            currentFeet,
            new Vec3d(-0.07287109375D, -0.23053058981895447D, 0.13776496887207032D),
            new BedrockCollisionFlags(false, true, false, true, false, false, false, false));
        Vec3d attemptedMove = new Vec3d(-0.08935546875D, -0.23053058981895447D, 0.13776496887207032D);
        BedrockCollisionProbe.Result probe = BedrockCollisionProbe.probe(
            current,
            world,
            PlayerDimensionsState.DEFAULT,
            attemptedMove,
            true,
            BedrockSimulation.DEFAULT_MAX_AUTO_STEP);

        assertFalse(probe.toString(), probe.zCollision());
        assertEquals(attemptedMove.z(), probe.zResult(), 1.0E-6D);
    }

    @Test
    public void candlestepping3Tick11519EndpointContactIdentifiesZAxis() {
        BlockCollisionWorld world = candleFieldWorld();

        Vec3d acceptedEndpointFeet = new Vec3d(270.85772705078125D, 82.14136505126953D, -79.86250305175781D);
        BedrockCollisionProbe.HorizontalContactAxes axes = BedrockCollisionProbe.horizontalContactAxes(
            acceptedEndpointFeet,
            world,
            PlayerDimensionsState.DEFAULT);

        assertFalse(axes.toString(), axes.xContact());
        assertTrue(axes.toString(), axes.zContact());
    }

    @Test
    public void fallingCandleEdgeAutoStepCanReturnToOriginalHeight() {
        BlockCollisionWorld world = candleFieldWorld();
        Vec3d currentFeet = new Vec3d(
            271.1368408203125D, 82.14136505126953D, -80.17182922363281D);
        Vec3d requestedMove = new Vec3d(
            0.07952880859375D, -0.23053058981895447D, 0.1147613525390625D);
        BedrockMovementState current = state(currentFeet, requestedMove, BedrockCollisionFlags.AIR);

        BedrockEntityMove.CollisionMove move = BedrockEntityMove.collide(
            current,
            requestedMove,
            world,
            PlayerDimensionsState.DEFAULT,
            true,
            BedrockSimulation.DEFAULT_MAX_AUTO_STEP
        );

        assertEquals(82.0D, move.baseMove().position().y(), 1.0E-6D);
        assertTrue(move.toString(), move.stepRetryAllowed());
        assertTrue(move.toString(), move.steppedUp());
        assertEquals(currentFeet.y(), move.selectedMove().position().y(), 1.0E-6D);
        assertTrue(horizontalDistanceSquared(move.selectedMove().appliedDelta())
            > horizontalDistanceSquared(move.baseMove().appliedDelta()));
        for (BlockCollision obstacle : BedrockCollisionSweep.collisionObstacles(world)) {
            assertFalse(obstacle.toString(), move.selectedMove().finalBox().intersects(obstacle.box()));
        }
    }

    @Test
    public void candleTopMovementKeepsFullHorizontalDelta() {
        BlockCollisionWorld world = candleFieldWorld();
        Vec3d currentFeet = new Vec3d(
            271.25299072265625D, 82.375D, -80.83926391601562D);
        Vec3d requestedMove = new Vec3d(
            -0.02783203125D, -0.07840000092983246D, -0.26898956298828125D);
        BedrockMovementState current = groundedState(currentFeet);

        BedrockEntityMove.CollisionMove move = BedrockEntityMove.collide(
            current,
            requestedMove,
            world,
            PlayerDimensionsState.DEFAULT,
            true,
            BedrockSimulation.DEFAULT_MAX_AUTO_STEP
        );

        assertFalse(move.stepRetryAllowed());
        assertEquals(requestedMove.x(), move.selectedMove().appliedDelta().x(), 1.0E-6D);
        assertEquals(0.0D, move.selectedMove().appliedDelta().y(), 1.0E-6D);
        assertEquals(requestedMove.z(), move.selectedMove().appliedDelta().z(), 1.0E-6D);
    }

    @Test
    public void groundedCandleEdgeStepsFromStoneToCandle() {
        BlockCollisionWorld world = candleFieldWorld();

        Vec3d currentFeet = new Vec3d(270.8450012207031D, 82.0D, -79.93566131591797D);
        Vec3d requestedMove = new Vec3d(-0.20513916015625D, -0.07840000092983246D, 0.12717437744140625D);
        BedrockEntityMove.Result result = BedrockEntityMove.move(
            groundedState(currentFeet),
            BedrockInputFrame.idle(0L),
            world,
            PlayerDimensionsState.DEFAULT,
            currentFeet.add(requestedMove),
            requestedMove,
            requestedMove.y(),
            true
        );

        assertEquals(currentFeet.x() + requestedMove.x(), result.position().x(), 1.0E-6D);
        assertEquals(currentFeet.y() + 0.375D, result.position().y(), 1.0E-6D);
        assertEquals(currentFeet.z() + requestedMove.z(), result.position().z(), 1.0E-6D);
        assertTrue(result.toString(), result.steppedUp());
        assertTrue(result.onGround());
    }

    @Test
    public void fallingCandleOverlapResolvesThroughAutoStep() {
        BlockCollisionWorld world = candleFieldWorld();

        Vec3d currentFeet = new Vec3d(268.2824401855469D, 82.14136505126953D, -79.14517974853516D);
        Vec3d requestedMove = new Vec3d(-0.0714477708875171D, -0.23053058981895447D, -0.08712391487837494D);
        BedrockEntityMove.CollisionMove collisionMove = BedrockEntityMove.collide(
            airState(currentFeet),
            requestedMove,
            world,
            PlayerDimensionsState.DEFAULT,
            true,
            BedrockSimulation.DEFAULT_MAX_AUTO_STEP
        );
        BedrockEntityMove.Result result = BedrockEntityMove.move(
            airState(currentFeet),
            BedrockInputFrame.idle(0L),
            world,
            PlayerDimensionsState.DEFAULT,
            currentFeet.add(requestedMove),
            requestedMove,
            requestedMove.y(),
            true
        );

        assertTrue(collisionMove.toString() + " / " + result, result.steppedUp());
        assertEquals(82.375D, result.position().y(), 1.0E-6D);
        assertTrue(result.verticalCollision());
        assertTrue(result.onGround());
    }

    @Test
    public void cakePaneOverlapDepenetratesHorizontallyWithoutLifting() {
        BlockCollisionWorld world = collisionWorld(List.of(
            PlacedBlockCollision.manual(
                new BlockPosition(235, 83, -75),
                "minecraft:glass_pane",
                "minecraft:glass_pane[north=false,east=true,south=false,west=false,waterlogged=false]",
                List.of(new WorldCollisionBox(
                    235.4375D,
                    83.0D,
                    -74.5625D,
                    236.0D,
                    84.0D,
                    -74.4375D
                ))
            )
        ));
        Vec3d feet = new Vec3d(235.19252014160156D, 82.94999694824219D, -74.54266357421875D);
        Vec3d requestedMove = new Vec3d(
            -0.001708984375D,
            -0.07840000092983246D,
            -0.09865570068359375D
        );

        BedrockCollisionSweep.MoveResult result = BedrockCollisionSweep.sweep(
            feet,
            requestedMove,
            BedrockCollisionSweep.collisionObstacles(world),
            PlayerDimensionsState.DEFAULT
        );

        assertTrue(result.appliedDelta().x() < requestedMove.x());
        assertEquals(requestedMove.y(), result.appliedDelta().y(), 1.0E-6D);
        assertEquals(requestedMove.z(), result.appliedDelta().z(), 1.0E-6D);
        assertFalse(result.yCollision());
    }

    @Test
    public void tallIronBarOverlapDoesNotBecomeDownwardSupport() {
        BlockCollisionWorld world = collisionWorld(List.of(
            PlacedBlockCollision.manual(
                new BlockPosition(302, 83, -80),
                "minecraft:iron_bars",
                "minecraft:iron_bars[north=true,east=false,south=false,west=true,waterlogged=false]",
                List.of(
                    new WorldCollisionBox(302.4375D, 83.0D, -80.0D, 302.5625D, 84.0D, -79.4375D),
                    new WorldCollisionBox(302.0D, 83.0D, -79.5625D, 302.5625D, 84.0D, -79.4375D)
                )
            )
        ));
        Vec3d feet = new Vec3d(302.79998779296875D, 83.17675018310547D, -79.19999694824219D);
        Vec3d requestedMove = new Vec3d(0.0D, -0.07840000092983246D, 0.0D);

        BedrockCollisionSweep.MoveResult result = BedrockCollisionSweep.sweep(
            feet,
            requestedMove,
            BedrockCollisionSweep.collisionObstacles(world),
            new PlayerDimensionsState(0.6000000238418579D, 1.7999999523162842D)
        );

        assertEquals(requestedMove.y(), result.appliedDelta().y(), 1.0E-6D);
        assertFalse(result.yCollision());
    }

    @Test
    public void bedCollisionCandidateDefersBounceUntilSelection() {
        BlockCollisionWorld world = collisionWorld(List.of(
            PlacedBlockCollision.manual(
                new BlockPosition(302, 82, -91),
                "minecraft:bed",
                "minecraft:bed[facing=north,occupied=false,part=foot]",
                List.of(new WorldCollisionBox(
                    302.0D,
                    82.0D,
                    -91.0D,
                    303.0D,
                    82.5625D,
                    -90.0D
                ))
            )
        ));
        Vec3d currentFeet = new Vec3d(302.8486328125D, 82.12128448486328D, -90.72260284423828D);
        Vec3d requestedMove = new Vec3d(0.0450445556640625D, -0.44482332468032837D, -0.020571365356445312D);
        BedrockMovementState current = state(currentFeet, requestedMove, BedrockCollisionFlags.AIR);
        BedrockEntityMove.Result collisionResult = BedrockEntityMove.move(
            current,
            BedrockInputFrame.idle(0L),
            world,
            PlayerDimensionsState.DEFAULT,
            currentFeet.add(requestedMove),
            requestedMove,
            requestedMove.y(),
            true
        );
        BedrockMovementResult result = BedrockSimulation.move(
            current,
            BedrockInputFrame.idle(0L),
            airContext(world),
            BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE,
            true,
            BedrockSimulation.DEFAULT_MAX_AUTO_STEP
        );

        assertEquals(82.5625D, collisionResult.position().y(), 1.0E-6D);
        assertTrue(collisionResult.verticalCollision());
        assertFalse(collisionResult.standingBounceBounced());
        assertEquals(82.5625D, result.predictedPosition().y(), 1.0E-6D);
        assertEquals(-0.07840000092983246D, result.predictedVelocity().y(), 1.0E-6D);
        assertFalse(result.standingBounceBounced());
    }

    @Test
    public void bedReplayTick39CandidateDefersPostMoveBounceCarry() {
        BlockCollisionWorld world = collisionWorld(List.of(
            PlacedBlockCollision.manual(
                new BlockPosition(303, 82, -93),
                "minecraft:bed",
                "minecraft:bed[facing=north,occupied=false,part=foot]",
                List.of(new WorldCollisionBox(
                    303.0D,
                    82.0D,
                    -93.0D,
                    304.0D,
                    82.5625D,
                    -92.0D
                ))
            )
        ));
        Vec3d previousFeet = new Vec3d(303.0367431640625D, 82.74946594238281D, -92.23131561279297D);
        Vec3d previousVelocity = new Vec3d(-0.003685224335640669D, -0.19685401022434235D, -0.19502107799053192D);
        BedrockMovementState current = state(previousFeet, previousVelocity, BedrockCollisionFlags.AIR);

        BedrockMovementResult result = BedrockSimulation.move(
            current,
            BedrockInputFrame.idle(0L),
            airContext(world),
            BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE,
            true,
            BedrockSimulation.DEFAULT_MAX_AUTO_STEP
        );

        assertEquals(82.5625D, result.predictedPosition().y(), 1.0E-6D);
        assertEquals(-0.07840000092983246D, result.predictedVelocity().y(), 1.0E-6D);
        assertFalse(result.standingBounceBounced());
    }

    @Test
    public void bedReplayTick97StepsFromHorizontalOverlapWhileFalling() {
        BlockCollisionWorld world = collisionWorld(List.of(
            PlacedBlockCollision.manual(
                new BlockPosition(302, 82, -91),
                "minecraft:bed",
                "minecraft:bed[facing=north,occupied=false,part=foot]",
                List.of(new WorldCollisionBox(
                    302.0D,
                    82.0D,
                    -91.0D,
                    303.0D,
                    82.5625D,
                    -90.0D
                ))
            )
        ));
        Vec3d previousFeet = new Vec3d(302.79913330078125D, 82.12128448486328D, -90.69999694824219D);
        Vec3d requestedMove = new Vec3d(0.04949951171875D, -0.44482332468032837D, -0.02260589599609375D);
        BedrockMovementState current = state(
            previousFeet,
            requestedMove,
            new BedrockCollisionFlags(false, true, false, true, false, false, true));
        BedrockCollisionProbe.Result probe = BedrockCollisionProbe.probe(
            current,
            world,
            PlayerDimensionsState.DEFAULT,
            requestedMove,
            false,
            BedrockSimulation.DEFAULT_MAX_AUTO_STEP);

        BedrockEntityMove.Result result = BedrockEntityMove.move(
            current,
            BedrockInputFrame.idle(0L),
            world,
            PlayerDimensionsState.DEFAULT,
            previousFeet.add(requestedMove),
            requestedMove,
            requestedMove.y(),
            true
        );
        BedrockMovementResult movementResult = BedrockSimulation.move(
            current,
            BedrockInputFrame.idle(0L),
            airContext(world),
            BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE,
            false,
            BedrockSimulation.DEFAULT_MAX_AUTO_STEP
        );

        assertTrue(probe.yNegCollision());
        assertEquals(
            82.5625D,
            BedrockCollisionProjectionResolver.resolvedPosition(movementResult, requestedMove).orElseThrow().y(),
            1.0E-6D);
        assertEquals(82.5625D, result.position().y(), 1.0E-6D);
        assertTrue(result.verticalCollision());
        assertTrue(result.onGround());
    }

    @Test
    public void candleReplayStepCandidatesSweepDownAfterStepUp() {
        BlockCollisionWorld world = candleFieldWorld();

        Vec3d tick84Feet = new Vec3d(269.8832092285156D, 82.0D, -78.34992980957031D);
        Vec3d tick84Delta = new Vec3d(-0.02679443359375D, -0.07840000092983246D, 0.28271484375D);
        Vec3d tick84Step = BedrockCollisionProbe.project(
            groundedState(tick84Feet), world, PlayerDimensionsState.DEFAULT, tick84Delta, true, BedrockSimulation.DEFAULT_MAX_AUTO_STEP);
        assertEquals(tick84Delta.x(), tick84Step.x(), 1.0E-6D);
        assertEquals(0.0D, tick84Step.y(), 1.0E-6D);
        assertEquals(tick84Delta.z(), tick84Step.z(), 1.0E-6D);

        Vec3d tick161Feet = new Vec3d(268.13623046875D, 82.0D, -80.78960418701172D);
        Vec3d tick161Delta = new Vec3d(0.213165283203125D, -0.07840000092983246D, -0.14510345458984375D);
        Vec3d tick161Step = BedrockCollisionProbe.project(
            groundedState(tick161Feet), world, PlayerDimensionsState.DEFAULT, tick161Delta, true, BedrockSimulation.DEFAULT_MAX_AUTO_STEP);
        assertEquals(tick161Delta.x(), tick161Step.x(), 1.0E-6D);
        assertEquals(0.0D, tick161Step.y(), 1.0E-6D);
        assertEquals(tick161Delta.z(), tick161Step.z(), 1.0E-6D);
    }

    @Test
    public void autoStepUsesCollisionCandidateHeight() {
        BlockCollisionWorld world = collisionWorld(List.of(stepObstacle(1.0D, 1.5D)));
        Vec3d currentFeet = new Vec3d(0.5D, 1.0D, 0.5D);
        Vec3d requestedMove = new Vec3d(0.6D, 0.0D, 0.0D);

        BedrockEntityMove.CollisionMove result = BedrockEntityMove.collide(
            groundedState(currentFeet),
            requestedMove,
            world,
            PlayerDimensionsState.DEFAULT,
            true,
            0.5625D
        );

        assertTrue(result.steppedUp());
        assertEquals(1.1D, result.selectedMove().position().x(), 1.0E-5D);
        assertEquals(1.5D, result.selectedMove().position().y(), 1.0E-6D);
        assertTrue(result.selectedMove().position().x() > result.baseMove().position().x() + 0.1D);
    }

    @Test
    public void autoStepRejectsCandidateAboveMaxAutoStep() {
        BlockCollisionWorld world = collisionWorld(List.of(stepObstacle(1.0D, 1.75D)));
        Vec3d currentFeet = new Vec3d(0.5D, 1.0D, 0.5D);
        Vec3d requestedMove = new Vec3d(0.6D, 0.0D, 0.0D);

        BedrockEntityMove.CollisionMove result = BedrockEntityMove.collide(
            groundedState(currentFeet),
            requestedMove,
            world,
            PlayerDimensionsState.DEFAULT,
            true,
            0.5625D
        );

        assertFalse(result.steppedUp());
        assertEquals(result.baseMove().position(), result.selectedMove().position());
    }

    private static BlockCollisionWorld candleFieldWorld() {
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        for (int x = 267; x <= 274; x++) {
            for (int z = -84; z <= -77; z++) {
                blocks.add(PlacedBlockCollision.manual(
                    new BlockPosition(x, 81, z),
                    "minecraft:stone",
                    "minecraft:stone",
                    List.of(new WorldCollisionBox(x, 81.0D, z, x + 1.0D, 82.0D, z + 1.0D))));
            }
        }
        for (int x = 268; x <= 272; x++) {
            for (int z = -82; z <= -78; z++) {
                blocks.add(PlacedBlockCollision.manual(
                    new BlockPosition(x, 82, z),
                    "minecraft:pink_candle[candles=1,lit=false,waterlogged=false]",
                    "minecraft:pink_candle",
                    List.of(new WorldCollisionBox(
                        x + 0.4375D,
                        82.0D,
                        z + 0.4375D,
                        x + 0.5625D,
                        82.375D,
                        z + 0.5625D))));
            }
        }
        return new BlockCollisionWorld(blocks);
    }

    private static BedrockBlockCollisionResolver.Result resolve(
        Vec3d currentFeet,
        Vec3d requestedFeet,
        Vec3d velocity,
        BlockCollisionWorld world,
        PlayerDimensionsState dimensions
    ) {
        List<BlockCollision> obstacles = BedrockCollisionSweep.collisionObstacles(world);
        Vec3d requestedDelta = requestedFeet.subtract(currentFeet);
        BedrockCollisionSweep.MoveResult move = BedrockCollisionSweep.sweep(
            currentFeet, requestedDelta, obstacles, dimensions
        );
        return BedrockBlockCollisionResolver.fromMove(
            requestedDelta, velocity, obstacles, dimensions, move, move, false
        );
    }

    private static double horizontalDistanceSquared(Vec3d movement) {
        return movement.x() * movement.x() + movement.z() * movement.z();
    }

    private static PlacedBlockCollision stepObstacle(double minY, double maxY) {
        return PlacedBlockCollision.manual(
            new BlockPosition(0, (int) minY, 0),
            "minecraft:stone",
            "minecraft:stone",
            List.of(new WorldCollisionBox(
                0.8D,
                minY,
                0.2D,
                1.8D,
                maxY,
                0.8D
            ))
        );
    }

    private static BedrockMovementState groundedState(Vec3d feet) {
        return BedrockMovementState.fromPhysicalFeet(
            feet,
            Vec3d.ZERO,
            BedrockInputFrame.idle(0L),
            BedrockCollisionFlags.ON_GROUND
        );
    }

    private static BedrockMovementState airState(Vec3d feet) {
        return state(feet, Vec3d.ZERO, BedrockCollisionFlags.AIR);
    }

    private static BedrockMovementState state(Vec3d feet, Vec3d velocity, BedrockCollisionFlags flags) {
        return BedrockMovementState.fromPhysicalFeet(
            feet,
            velocity,
            BedrockInputFrame.idle(0L),
            flags
        );
    }

    private static BlockCollisionWorld collisionWorld(List<PlacedBlockCollision> blocks) {
        return new BlockCollisionWorld(blocks);
    }

    private static BedrockMovementContext airContext(BlockCollisionWorld blockWorld) {
        return new BedrockMovementContext(
            BedrockEffectState.NONE,
            AttributeState.DEFAULT,
            new WorldContactState(Medium.AIR, FluidState.NONE, blockWorld),
            EquipmentState.NONE,
            EntityContactState.NONE,
            MovementModifierState.NONE,
            PlayerDimensionsState.DEFAULT);
    }

}
