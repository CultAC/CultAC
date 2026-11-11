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
import ac.cult.cultac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementAttributeState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
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

public final class BedrockBlockSlowdownTimingTest {
    private static final Vec3d FORWARD = new Vec3d(0.0D, 0.0D, 1.0D);
    private static final BlockPosition BODY = new BlockPosition(0, 1, 0);

    @Test
    public void cobwebPlacedOnTheActorSlowsItFromTheNextTick() {
        // A falling player with forward held. The client moved freely on the tick the cobweb arrived
        // and by (0.05, 0.25) of its carried velocity on the next one.
        BedrockMovementState player = airborne(
                new Vec3d(0.5D, 2.02441D, 0.71789D), new Vec3d(0.0D, -0.227692D, 0.160589D - 0.0196D));
        BedrockWorldSnapshot open = world("minecraft:stone", null, null);
        BedrockWorldSnapshot webbed = world("minecraft:stone", "minecraft:web", BlockContactBehavior.COBWEB);

        BedrockMovementResult before = tick(player, open);
        assertMove(-0.227692D, 0.160589D, before, 2.0E-6D);

        BedrockMovementResult arrival = tick(next(before), webbed);
        assertMove(-0.30153D, 0.16574D, arrival, 1.0E-5D);
        assertEquals(BlockMovementSlowdownState.COBWEB, arrival.predictedState().pendingBlockMovementSlowdownState());

        BedrockMovementResult slowed = tick(next(arrival), webbed);
        assertMove(-0.0187D, 0.0426D, slowed, 1.0E-5D);
        assertEquals(0.0D, next(slowed).velocity().z(), 0.0D);
    }

    @Test
    public void removedCobwebStillSlowsOneMoreTick() {
        // The client kept its slowed move on the tick the cobweb was removed, then fell from rest.
        BedrockMovementState player = airborne(new Vec3d(0.5D, 1.72095D, 0.44833D), new Vec3d(0.0D, -0.0784D, 0.0D));
        BedrockWorldSnapshot open = world("minecraft:stone", null, null);
        BedrockWorldSnapshot webbed = world("minecraft:stone", "minecraft:web", BlockContactBehavior.COBWEB);

        // A state that has not ticked reads the cobweb around its box.
        BedrockMovementResult inside = tick(player, webbed);
        assertMove(-0.00392D, 0.0049D, inside, 2.0E-6D);

        BedrockMovementResult removal = tick(next(inside), open);
        assertMove(-0.00392D, 0.0049D, removal, 2.0E-6D);
        assertEquals(BlockMovementSlowdownState.NONE, removal.predictedState().pendingBlockMovementSlowdownState());

        BedrockMovementResult free = tick(next(removal), open);
        assertMove(-0.0784D, 0.0196D, free, 2.0E-6D);
    }

    @Test
    public void teleportOutOfACobwebKeepsItsSlowdownForTheNextMove() {
        // A player inside a cobweb is teleported out of it at rest. The tick that takes the teleport runs no
        // move, and the client's first move after it was still slowed by the web, 0.0196 to 0.0049 forward.
        BedrockWorldSnapshot webbed = world("minecraft:stone", "minecraft:web", BlockContactBehavior.COBWEB);
        BedrockMovementResult inside = tick(airborne(new Vec3d(0.5D, 1.25D, 0.5D), Vec3d.ZERO), webbed);
        assertEquals(BlockMovementSlowdownState.COBWEB, inside.predictedState().pendingBlockMovementSlowdownState());

        assertFirstMoveAfterTeleportSlowed(inside, new Vec3d(0.5D, 1.25D, -0.5D), webbed);
    }

    @Test
    public void teleportIntoACobwebSlowsTheNextMove() {
        // A walking player is teleported into a cobweb at rest; the client's first move after it was slowed.
        BedrockWorldSnapshot webbed = world("minecraft:stone", "minecraft:web", BlockContactBehavior.COBWEB);
        BedrockMovementResult outside = tick(airborne(new Vec3d(0.5D, 1.25D, -0.5D), Vec3d.ZERO), webbed);
        assertEquals(BlockMovementSlowdownState.NONE, outside.predictedState().pendingBlockMovementSlowdownState());

        assertFirstMoveAfterTeleportSlowed(outside, new Vec3d(0.5D, 1.25D, 0.5D), webbed);
    }

    private static void assertFirstMoveAfterTeleportSlowed(
            BedrockMovementResult before, Vec3d destination, BedrockWorldSnapshot world) {
        BedrockMovementResult teleport = tick(next(before).afterServerTeleport(destination, Vec3d.ZERO), world);
        assertMove(0.0D, 0.0D, teleport, 0.0D);
        assertEquals(BlockMovementSlowdownState.COBWEB, teleport.predictedState().pendingBlockMovementSlowdownState());

        BedrockMovementResult slowed = tick(next(teleport), world);
        assertMove(0.0D, 0.0049D, slowed, 1.0E-6D);
    }

    @Test
    public void powderSnowLeavesTheFloorUnderTheFeetInControl() {
        // Powder snow placed on a player walking on slime: the client's moves were 0.03681 and 0.03668 a
        // tick, the slime floor's control times the snow's 0.9, while its speed dropped with the frost.
        BedrockWorldSnapshot slime =
                world("minecraft:slime", "minecraft:powder_snow", BlockContactBehavior.POWDER_SNOW);
        BedrockMovementState player = grounded().withMovementAttribute(speed(0.1F - 3 * 0.05F / 140));

        BedrockMovementResult first = tick(player, slime);
        assertMove(0.0D, 0.03681D, first, 1.0E-5D);

        BedrockMovementResult second = tick(next(first).withMovementAttribute(speed(0.1F - 4 * 0.05F / 140)), slime);
        assertMove(0.0D, 0.03668D, second, 1.0E-5D);
    }

    @Test
    public void powderSnowOnADefaultFloorKeepsDefaultControl() {
        BedrockWorldSnapshot stone =
                world("minecraft:stone", "minecraft:powder_snow", BlockContactBehavior.POWDER_SNOW);

        assertMove(0.0D, 0.098D * 0.9D, tick(grounded(), stone), 1.0E-6D);
    }

    private static void assertMove(double y, double z, BedrockMovementResult result, double delta) {
        Vec3d from = result.previousState().physicalFeetPosition();
        assertEquals(y, result.predictedPosition().y() - from.y(), delta);
        assertEquals(z, result.predictedPosition().z() - from.z(), delta);
    }

    private static BedrockMovementAttributeState speed(float value) {
        return new BedrockMovementAttributeState(value, 0, 1024, 0, 1024, 0.1F, List.of());
    }

    private static BedrockMovementState airborne(Vec3d position, Vec3d velocity) {
        return BedrockMovementState.fromPhysicalFeet(
                position, velocity, BedrockInputFrame.idle(1L), BedrockCollisionFlags.AIR);
    }

    private static BedrockMovementState grounded() {
        return BedrockMovementState.fromPhysicalFeet(
                new Vec3d(0.5D, 1.0D, 0.5D),
                new Vec3d(0.0D, -0.0784D, 0.0D),
                BedrockInputFrame.idle(1L),
                BedrockCollisionFlags.ON_GROUND);
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
                        FORWARD))
                .getFirst()
                .movementResult();
    }

    private static BedrockMovementState next(BedrockMovementResult result) {
        return BedrockForwardTick.finish(result, result.predictedState());
    }

    private static BedrockWorldSnapshot world(String floor, String body, BlockContactBehavior bodyBehavior) {
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        for (int z = -1; z <= 2; z++) {
            blocks.add(PlacedBlockCollision.manual(
                    new BlockPosition(0, 0, z),
                    floor,
                    floor,
                    List.of(new WorldCollisionBox(0.0D, 0.0D, z, 1.0D, 1.0D, z + 1.0D)),
                    floor.equals("minecraft:slime") ? Set.of(BlockContactBehavior.SLIME) : Set.of()));
        }
        if (body != null) {
            blocks.add(PlacedBlockCollision.manual(BODY, body, body, List.of(), Set.of(bodyBehavior)));
        }
        return BedrockWorldSnapshot.fromContext(new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT));
    }
}
