package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState.Entry;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockDepenetrationLimit;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * A replayed tick selects among the orders of its solid actors as a live tick does: where three boats leave the
 * player along x and the order gives its end a range, the replay carries the claimed end inside that range, at the
 * state the live selection carries, not the move's own end.
 */
public final class BedrockActorOrderRangeReplayTest {
    private static final Vec3d FEET = new Vec3d(41.5, 120.0, 25.5);
    private static final Vec3d GRAVITY = new Vec3d(0.0, -0.0784, 0.0);

    @Test
    public void aReplayedCrowdTickCarriesTheClaimedEndInsideTheRange() {
        var entry = new Entry(
                BedrockMovementState.fromPhysicalFeet(
                        FEET, GRAVITY, BedrockInputFrame.idle(0), BedrockCollisionFlags.ON_GROUND),
                BedrockMobJumpComponentState.DEFAULT);
        var input = BedrockInputFrame.idle(1);
        var request = new BedrockSimulation.Input(
                entry.state(),
                input,
                input.intent(),
                threeBoats(),
                true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                entry.mobJumpComponent(),
                true,
                false,
                Vec3d.ZERO);
        // The move the replay meets: its actors as a replayed tick meets them.
        var replayedRequest = new BedrockSimulation.Input(
                request.previousState(),
                request.frame(),
                request.intent(),
                BedrockReplayCollisionWorld.withReplayedActors(request.snapshot(), request.snapshot()),
                request.canStep(),
                request.maxUpStep(),
                request.mobJumpComponent(),
                request.actorMovementTick(),
                request.acceptedTeleport(),
                request.control(),
                request.glideBoost());
        var candidates = BedrockForwardTick.simulate(replayedRequest);
        assertEquals(1, candidates.size());
        var movement = candidates.getFirst().movementResult();
        var range = movement.solidActorOrderRange();
        assertNotNull(range);
        Vec3d own = movement.predictedPosition();
        double low = own.x() + range.minX();
        double high = own.x() + range.maxX();
        Vec3d claimed = new Vec3d((low + high) / 2.0, own.y(), own.z());
        assertTrue(claimed.x() > low && claimed.x() < high && claimed.x() != own.x());

        var frame = new BedrockActorHistory.Frame(
                1, request, List.of(entry), List.of(entry), null, Vec3d.ZERO, Vec3d.ZERO, claimed, null, List.of());
        var carried = BedrockReplayTick.advance(frame, List.of(entry), List.of(), (state, world) -> world)
                .getFirst()
                .state();

        assertEquals(0.0D, carried.physicalFeetPosition().subtract(claimed).length(), 1.0E-9D);
        assertEquals(movement.predictedState().velocity(), carried.velocity());
        // The live selection's carry: the move relocated to the accepted end, a move from the same start.
        Vec3d previous = movement.previousState().physicalFeetPosition();
        Vec3d accepted = claimed.subtract(previous);
        var live = BedrockForwardTick.finish(
                movement,
                BedrockValidationSelectedState.candidateBase(
                        movement,
                        new BedrockValidationSelection(
                                previous, null, BedrockVectorAdapter.toJava(accepted), previous.add(accepted), null)));
        assertEquals(live, carried);
    }

    /**
     * Two boats overlap the player from the west by 0.008 and 0.004 and one from the east by 0.006, each within the
     * depth a replayed tick still leaves: the order that meets the 0.004 boat last ends between the other two.
     */
    private static BedrockWorldSnapshot threeBoats() {
        var blocks = new ArrayList<PlacedBlockCollision>();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                int bx = (int) Math.floor(FEET.x()) + x;
                int bz = (int) Math.floor(FEET.z()) + z;
                blocks.add(PlacedBlockCollision.manual(
                        new BlockPosition(bx, 119, bz),
                        "minecraft:stone",
                        "minecraft:stone",
                        List.of(new WorldCollisionBox(bx, 119, bz, bx + 1, 120, bz + 1))));
            }
        }
        blocks.add(boat(41.2F + 0.008F - 0.8F));
        blocks.add(boat(41.2F + 0.004F - 0.8F));
        blocks.add(boat(41.8F - 0.006F + 0.8F));
        return BedrockWorldSnapshot.fromContext(new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT));
    }

    /** A boat 1.6 wide and 0.6 tall on the floor at y 120, centred on z 25.5. */
    private static PlacedBlockCollision boat(float x) {
        return BedrockDepenetrationLimit.SOLID_ACTOR.solidActor(
                new WorldCollisionBox(x - 0.8F, 120.0F, 25.5F - 0.8F, x + 0.8F, 120.6F, 25.5F + 0.8F));
    }
}
