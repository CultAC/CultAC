package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.*;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.*;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class BedrockGeyserMovementTest {
    @Test
    public void launchChangesCarriedVelocityWithoutMovingTheBoxAgain() {
        var result = tick(column(1), new Vec3d(0.5, 4, 0.5), 100, false);
        var selected = result.predictedState()
                .withVelocityAndCollisionFlags(
                        new Vec3d(-0.015927782282233238, 0.0030162709299474955, -0.24348658323287964),
                        result.predictedState().collisionFlags());
        var end = BedrockForwardTick.finish(result, selected);
        assertEquals(selected.physicalFeetPosition(), end.physicalFeetPosition());
        assertEquals(selected.collisionBox(), end.collisionBox());
        assertEquals(selected.velocity().x(), end.velocity().x(), 0);
        assertEquals(selected.velocity().z(), end.velocity().z(), 0);
        assertEquals((float) selected.velocity().y() + 0.2F, end.velocity().y(), 0);
    }

    @Test
    public void contactArmsFollowingTicksAndTheEffectSurvivesLeavingTheColumn() {
        var first = tick(column(1), new Vec3d(0.5, 4, 0.5), 0, false);
        var end = BedrockForwardTick.finish(first, first.predictedState());
        assertEquals(first.predictedState().velocity(), end.velocity());
        assertEquals(100, end.geyserBoostTicks());
        var away = tick(BlockCollisionWorld.EMPTY, new Vec3d(2, 4, 0.5), 100, false);
        var expired = BedrockForwardTick.finish(away, away.predictedState());
        assertEquals(99, expired.geyserBoostTicks());
        assertEquals(away.predictedState().velocity(), expired.velocity());
    }

    @Test
    public void eachWaterDepthUsesItsOwnHeightAndPreAdditionCap() {
        float[] caps = {0.4F, 0.5F, 0.6F, 0.7F};
        for (int depth = 1; depth <= 4; depth++) {
            var world = column(depth);
            var below = tick(world, new Vec3d(0.5, depth * 6 + 0.5, 0.5), 100, false);
            float low = Math.nextDown(caps[depth - 1]);
            assertEquals(low + 0.2F, apply(below, low).velocity().y(), 0);
            assertEquals(
                    caps[depth - 1], apply(below, caps[depth - 1]).velocity().y(), 0);
            var above = tick(world, new Vec3d(0.5, depth * 6 + 1, 0.5), 100, false);
            assertEquals(0, apply(above, 0).velocity().y(), 0);
        }
    }

    @Test
    public void aBoxOverlapArmsTheEffectButLaunchNeedsTheCenterAboveTheSource() {
        var edge = tick(column(1), new Vec3d(1.1, 4, 0.5), 100, false);
        var end = apply(edge, 0);
        assertEquals(0, end.velocity().y(), 0);
        assertEquals(100, end.geyserBoostTicks());
    }

    @Test
    public void flyingDoesNotLaunchAndAStoredEffectCannotLaunchWithoutAValidColumn() {
        var flying = tick(column(1), new Vec3d(0.5, 4, 0.5), 100, true);
        assertEquals(0, apply(flying, 0).velocity().y(), 0);
        var empty = tick(BlockCollisionWorld.EMPTY, new Vec3d(0.5, 4, 0.5), 100, false);
        assertEquals(0, apply(empty, 0).velocity().y(), 0);
    }

    @Test
    public void obstructionDormancyFlowingWaterAndAFifthWaterBlockPreventLaunch() {
        var obstructed = new ArrayList<>(column(1).blocks());
        obstructed.add(block(0, 3, 0, "minecraft:stone", Map.of(), true));
        assertNoLaunch(new BlockCollisionWorld(obstructed));
        var dormant = new ArrayList<>(column(1).blocks());
        dormant.set(0, block(0, 0, 0, "minecraft:potent_sulfur", Map.of("potent_sulfur_state", "dormant"), true));
        assertNoLaunch(new BlockCollisionWorld(dormant));
        var flowing = new ArrayList<>(column(1).blocks());
        flowing.set(1, block(0, 1, 0, "minecraft:flowing_water", Map.of("liquid_depth", 1), false));
        assertNoLaunch(new BlockCollisionWorld(flowing));
        assertNoLaunch(column(5));
    }

    @Test
    public void actorDependentShapesUseTheirIndependentColumnShape() {
        var blocks = new ArrayList<>(column(1).blocks());
        blocks.add(block(0, 3, 0, "minecraft:scaffolding", Map.of("geyserPassable", true), true));
        var result = tick(new BlockCollisionWorld(blocks), new Vec3d(0.5, 5.5, 0.5), 100, false);
        assertEquals(0.2F, apply(result, 0).velocity().y(), 0);
    }

    @Test
    public void anOutletMayContainFlowingLiquidButCannotContainAnotherSource() {
        var blocks = new ArrayList<>(column(1).blocks());
        blocks.add(block(0, 2, 0, "minecraft:flowing_water", Map.of("liquid_depth", 1), false));
        var result = tick(new BlockCollisionWorld(blocks), new Vec3d(0.5, 4, 0.5), 100, false);
        assertEquals(0.2F, apply(result, 0).velocity().y(), 0);
        blocks.set(2, block(0, 2, 0, "minecraft:lava", Map.of("liquid_depth", 0), false));
        assertNoLaunch(new BlockCollisionWorld(blocks));
    }

    @Test
    public void anUnrefreshedEffectExpiresAndCopyingOrTeleportingPreservesIt() {
        var tick = tick(BlockCollisionWorld.EMPTY, new Vec3d(0.5, 4, 0.5), 1, false);
        var end = BedrockForwardTick.finish(tick, tick.predictedState());
        assertEquals(0, end.geyserBoostTicks());
        var before = tick.previousState();
        assertEquals(
                1,
                before.withPlayerDimensions(PlayerDimensionsState.DEFAULT, true)
                        .afterServerTeleport(new Vec3d(3, 4, 5), Vec3d.ZERO)
                        .geyserBoostTicks());
        assertEquals(
                1, before.withoutActorMovementTick(BedrockInputFrame.idle(10)).geyserBoostTicks());
    }

    @Test
    public void rebasedCoordinatesUseTheLocalBoxCenter() {
        int origin = 20_000_000;
        var world = new BlockCollisionWorld(List.of(
                        block(
                                origin + 1,
                                0,
                                0,
                                "minecraft:potent_sulfur",
                                Map.of("potent_sulfur_state", "continuous"),
                                true),
                        block(origin + 1, 1, 0, "minecraft:water", Map.of("liquid_depth", 0), false)))
                .withCoordinateFrame(new ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame(origin, 0, 1));
        assertEquals(
                0.2F,
                apply(tick(world, new Vec3d(origin + 1.1, 4, 0.5), 100, false), 0)
                        .velocity()
                        .y(),
                0);
    }

    private static void assertNoLaunch(BlockCollisionWorld world) {
        assertEquals(
                0,
                apply(tick(world, new Vec3d(0.5, 5.5, 0.5), 100, false), 0)
                        .velocity()
                        .y(),
                0);
    }

    private static BedrockMovementState apply(BedrockMovementResult result, float velocity) {
        return BedrockForwardTick.finish(
                result,
                result.predictedState()
                        .withVelocityAndCollisionFlags(
                                new Vec3d(0, velocity, 0),
                                result.predictedState().collisionFlags()));
    }

    private static BedrockMovementResult tick(BlockCollisionWorld world, Vec3d feet, int remaining, boolean flying) {
        var modifier =
                new MovementModifierState(false, flying, flying, false, 0.05, false, false, false, false, 0.35, 0);
        var context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, world),
                EquipmentState.NONE,
                EntityContactState.NONE,
                modifier,
                PlayerDimensionsState.DEFAULT);
        var state = BedrockMovementState.fromPhysicalFeet(
                        feet,
                        Vec3d.ZERO,
                        BedrockInputFrame.idle(0),
                        BedrockCollisionFlags.AIR,
                        Medium.AIR,
                        world.coordinateFrame())
                .withGeyserBoostTicks(remaining);
        var frame = BedrockInputFrame.idle(1);
        return BedrockForwardTick.simulate(new BedrockSimulation.Input(
                        state,
                        frame,
                        frame.intent(),
                        BedrockWorldSnapshot.fromContext(context),
                        false,
                        0,
                        BedrockMobJumpComponentState.DEFAULT,
                        true,
                        false,
                        Vec3d.ZERO))
                .getFirst()
                .movementResult();
    }

    private static BlockCollisionWorld column(int depth) {
        var blocks = new ArrayList<PlacedBlockCollision>();
        blocks.add(block(0, 0, 0, "minecraft:potent_sulfur", Map.of("potent_sulfur_state", "continuous"), true));
        for (int y = 1; y <= depth; y++)
            blocks.add(block(0, y, 0, "minecraft:water", Map.of("liquid_depth", 0), false));
        return new BlockCollisionWorld(blocks);
    }

    private static PlacedBlockCollision block(
            int x, int y, int z, String name, Map<String, Object> properties, boolean solid) {
        return PlacedBlockCollision.manual(
                new BlockPosition(x, y, z),
                name,
                name,
                properties,
                solid ? List.of(new WorldCollisionBox(x, y, z, x + 1, y + 1, z + 1)) : List.of());
    }
}
