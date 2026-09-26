package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.*;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.Test;
import static org.junit.Assert.*;

/** Captured glide whose tick-4635 firework boost is replayed into a wall beyond the recorded fetch boxes. */
public final class BedrockReplayCollisionShapesTest {
    // pitch, yaw, packet x, packet y, packet z, delta x, delta y, delta z
    private static final float[][] CAPTURE = {
        {6.090683F, 23.723557F, 248.31363F, 84.692986F, -76.66806F, -0.1689398F, -0.09374546F, 0.23701136F},
        {6.090683F, 23.723557F, 248.14746F, 84.59194F, -76.42127F, -0.16617122F, -0.10104449F, 0.2467865F},
        {6.090683F, 23.723541F, 247.98325F, 84.48445F, -76.164604F, -0.1642203F, -0.10749028F, 0.2566703F},
        {5.9607544F, 23.593628F, 247.82033F, 84.3713F, -75.89792F, -0.16291429F, -0.11315241F, 0.2666878F},
        {6.090683F, 22.554123F, 247.65878F, 84.25312F, -75.620834F, -0.1615422F, -0.118182786F, 0.27708077F},
    };
    private static final float[] RECEIPT =
        {6.2206116F, 20.215454F, 245.5546F, 83.884346F, -71.291985F, -0.57935864F, -0.17471023F, 1.1998738F};
    private static final long FIRST_TICK = 4635;

    @Test
    public void replayFetchesLiveShapesOutsideTheRecordedFetchBox() {
        var replayed = replay((state, snapshot) -> snapshot.withBlockCollisionWorld(wall()));
        var receipt = forward(replayed, FIRST_TICK + CAPTURE.length, RECEIPT, air(), true);
        assertCaptured(receipt.end().getFirst().state(), RECEIPT);
    }

    @Test
    public void recordedShapesAloneMissTheWallTheClientCollidedWith() {
        var replayed = replay((state, snapshot) -> snapshot);
        var receipt = forward(replayed, FIRST_TICK + CAPTURE.length, RECEIPT, air(), true);
        assertTrue(receipt.end().getFirst().state().physicalFeetPosition()
                .subtract(position(RECEIPT)).length() > 1.0);
    }

    @Test
    public void recordedShapesInsideTheRecordedFetchBoxOverrideTheLiveWorld() {
        var fetched = new WorldCollisionBox(0.0, 0.0, 0.0, 2.0, 2.0, 2.0);
        var removed = stone(0, 0, 0);
        var placedInside = stone(1, 1, 1);
        var placedOutside = stone(3, 0, 0);
        var straddling = stone(1, 0, 2);
        var world = BedrockReplayCollisionWorld.reuse(new BlockCollisionWorld(List.of(removed, stone(5, 0, 0))),
                new BlockCollisionWorld(List.of(placedInside, placedOutside, straddling)), fetched);
        assertEquals(List.of(removed, placedOutside, straddling), world.blocks());
    }

    private static BedrockProfileState.Entry replay(
            BiFunction<BedrockMovementState, BedrockWorldSnapshot, BedrockWorldSnapshot> liveWorld) {
        var seed = CAPTURE[0];
        var state = BedrockMovementState.fromPhysicalFeet(position(seed), velocity(seed),
                BedrockInputFrame.idle(FIRST_TICK), BedrockCollisionFlags.AIR).withGliding(true);
        var entry = new BedrockProfileState.Entry(state, BedrockMobJumpComponentState.DEFAULT);
        var history = new BedrockActorHistory();
        history.record(new BedrockActorHistory.Frame(FIRST_TICK, request(entry, FIRST_TICK, seed, air(), false),
                List.of(entry), List.of(entry), null, Vec3d.ZERO, Vec3d.ZERO, position(seed), velocity(seed), List.of()));
        // Each tick's recorded shapes were sampled around the unboosted path and do not reach the wall.
        for (int i = 1; i < CAPTURE.length; i++) {
            var frame = forward(entry, FIRST_TICK + i, CAPTURE[i], air(), false);
            assertCaptured(frame.end().getFirst().state(), CAPTURE[i]);
            history.record(frame);
            entry = frame.end().getFirst();
        }
        var player = org.mockito.Mockito.mock(ac.cult.cultac.player.CultPlayer.class);
        player.bedrockState = new ac.cult.cultac.bedrock.player.BedrockPlayerState(new java.util.UUID(0, 1));
        var rewind = new BedrockMovementRewind(history);
        rewind.queue(FIRST_TICK, true, new BedrockReplayEvent.Boost(1_000_000));
        var current = new ac.cult.cultac.checks.impl.prediction.PredictionCommit(
                new BedrockNextTickStates(history.current()),
                BedrockNextTickVelocityDerivation.profileStateVelocities(history.current()));
        var commit = rewind.apply(player, current, liveWorld);
        return BedrockProfileState.profileEntries(commit.carry()).getFirst();
    }

    private static BedrockSimulation.Input request(BedrockProfileState.Entry entry, long tick, float[] observed,
            BedrockWorldSnapshot world, boolean boost) {
        var frame = new BedrockInputFrame(tick, observed[1], observed[0], false, false, true);
        return new BedrockSimulation.Input(entry.state(), frame, frame.intent(), world, true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP, entry.mobJumpComponent(), true, false, Vec3d.ZERO, boost);
    }

    private static BedrockActorHistory.Frame forward(BedrockProfileState.Entry entry, long tick, float[] observed,
            BedrockWorldSnapshot world, boolean boost) {
        var input = request(entry, tick, observed, world, boost);
        var candidate = BedrockForwardTick.simulate(input).getFirst();
        var result = candidate.movementResult();
        var entries = BedrockForwardTick.finish(result, result.predictedState()).stream()
                .map(state -> new BedrockProfileState.Entry(state, candidate.mobJumpComponent())).toList();
        return new BedrockActorHistory.Frame(tick, input, entries, entries, null, Vec3d.ZERO, Vec3d.ZERO,
                position(observed), velocity(observed), List.of(), result.collisionFetchBox());
    }

    private static void assertCaptured(BedrockMovementState state, float[] observed) {
        double positionError = state.physicalFeetPosition().subtract(position(observed)).length();
        double velocityError = state.velocity().subtract(velocity(observed)).length();
        assertTrue("position residual " + positionError, positionError <= 0.001);
        assertTrue("velocity residual " + velocityError, velocityError <= 0.001);
    }

    private static BlockCollisionWorld wall() {
        var blocks = new ArrayList<PlacedBlockCollision>();
        // The client's X move clears this column before Z on the following tick.
        for (int y = 82; y <= 83; y++) blocks.add(stone(247, y, -73));
        return new BlockCollisionWorld(blocks);
    }

    private static PlacedBlockCollision stone(int x, int y, int z) {
        return PlacedBlockCollision.manual(new BlockPosition(x, y, z), "minecraft:stone", "minecraft:stone",
                List.of(new WorldCollisionBox(x, y, z, x + 1, y + 1, z + 1)));
    }

    private static BedrockWorldSnapshot air() {
        return BedrockWorldSnapshot.fromContext(new BedrockMovementContext(
                BedrockEffectState.NONE, AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(List.of())),
                new EquipmentState(0, 0, 0, false, true), EntityContactState.NONE,
                new MovementModifierState(true, false, false, false, 0.05, false, false,
                        false, false, 0.35, 0), PlayerDimensionsState.DEFAULT));
    }

    private static Vec3d position(float[] row) {
        return new Vec3d(row[2], row[3] - 1.62F, row[4]);
    }

    private static Vec3d velocity(float[] row) {
        return new Vec3d(row[5], row[6], row[7]);
    }
}
