package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockTickInput;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

/** A motion sent without a tick changes the live velocity only; a replay runs its frame again without it. */
public final class BedrockUntimedMotionReplayTest {
    // An elytra glide pressed against a wall with a rocket boost running, pitch 25 down: client positions (feet) and
    // reported velocities, ticks 3015 to 3024. Before 3016 and before 3021 the server sent the motion
    // (0, -0.637, 0) without a tick; the client took each on the following tick. A replay from before 3016 ran on
    // 3024: its position there is that of the ticks run again without either motion.
    private static final double EYE = 1.62001D;
    private static final float PITCH = 25.000366F;
    private static final float YAW = -1.5258789E-5F;
    private static final double[][] CLIENT = {
        {17.50102, 134.44539, -71.3, 1.4779321E-4, -0.66155636, 0.0},
        {17.501097, 133.79297, -71.3, 7.550481E-5, -0.65242213, 0.0},
        {17.501205, 133.1337, -71.3, 1.09196044E-4, -0.6592752, 0.0},
        {17.50133, 132.47134, -71.3, 1.2422971E-4, -0.6623573, 0.0},
        {17.501461, 131.8076, -71.3, 1.3093809E-4, -0.6637435, 0.0},
        {17.501595, 131.14323, -71.3, 1.3393158E-4, -0.6643669, 0.0},
        {17.50167, 130.4907, -71.3, 7.550567E-5, -0.6525319, 0.0},
        {17.50178, 129.83139, -71.3, 1.09196815E-4, -0.6593245, 0.0},
        {17.501904, 129.169, -71.3, 1.2423022E-4, -0.6623795, 0.0},
        {17.502258, 128.46422, -71.3, 1.363516E-4, -0.664874, 0.0},
    };
    private static final long FIRST = 3015;

    @Test
    public void aReplayRunsTheFramesOfUntimedMotionsWithoutThem() {
        var world = wall();
        var history = new BedrockActorHistory();
        var rewind = new BedrockMovementRewind(history);
        var entry = new BedrockProfileState.Entry(
                BedrockMovementState.fromPhysicalFeet(
                                feet(CLIENT[0]), velocity(CLIENT[0]), frame(FIRST - 1), BedrockCollisionFlags.AIR)
                        .withGliding(true),
                BedrockMobJumpComponentState.DEFAULT);
        // The frame the replay starts from, ending where the client reported it.
        rewind.record(null, result(FIRST, request(entry, FIRST, null, world), world, false), commit(entry));
        for (long tick = FIRST + 1; tick <= FIRST + 8; tick++) {
            Vec3d motion =
                    tick == 3016 ? new Vec3d(0, -0.63718486, 0) : tick == 3021 ? new Vec3d(0, -0.63742906, 0) : null;
            var request = request(entry, tick, motion, world);
            entry = tick(request);
            rewind.record(null, result(tick, request, world, motion != null), commit(entry));
            double[] client = CLIENT[(int) (tick - FIRST)];
            assertEquals(
                    "live tick " + tick,
                    client[1] - EYE,
                    entry.state().physicalFeetPosition().y(),
                    1.0E-3);
        }

        // The replay starts at the frame an earlier update marked and runs every frame since.
        history.mark(FIRST);
        var replayed = history.replay((state, snapshot) -> snapshot, (frame, state, snapshot) -> snapshot);
        var after = tick(request(replayed.getFirst(), FIRST + 9, null, world));
        double[] client = CLIENT[9];
        assertEquals(client[1] - EYE, after.state().physicalFeetPosition().y(), 1.0E-3);
        assertEquals(client[4], after.state().velocity().y(), 1.0E-3);
    }

    private static BedrockProfileState.Entry tick(BedrockSimulation.Input request) {
        var candidate = BedrockForwardTick.simulate(request).getFirst();
        var result = candidate.movementResult();
        return new BedrockProfileState.Entry(
                BedrockForwardTick.finish(result, result.predictedState()), candidate.mobJumpComponent());
    }

    private static BedrockSimulation.Input request(
            BedrockProfileState.Entry previous, long tick, Vec3d motion, BedrockWorldSnapshot world) {
        var state = previous.state();
        if (motion != null) state = state.withVelocityAndCollisionFlags(motion, state.collisionFlags());
        var input = frame(tick);
        return new BedrockSimulation.Input(
                state,
                input,
                input.intent(),
                world,
                true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                previous.mobJumpComponent(),
                true,
                false,
                Vec3d.ZERO,
                true);
    }

    /** What a live tick leaves for the rewind: its selected travel, a knockback when it began with a motion. */
    private static PredictionResult result(
            long tick, BedrockSimulation.Input request, BedrockWorldSnapshot world, boolean knockback) {
        var movement = BedrockForwardTick.simulate(request).getFirst().movementResult();
        var end = movement.predictedState().physicalFeetPosition();
        var auth = BedrockAuthInputFrame.builder(new UUID(0, 1))
                .protocolVersion(0)
                .clientTick(tick)
                .inputMode(1)
                .playMode(2)
                .deviceId(3)
                .position(new Vec3(end.x(), end.y(), end.z()))
                .packetPosition(new Vec3(end.x(), end.y() + EYE, end.z()))
                .delta(Vec3.ZERO)
                .rotation(PITCH, YAW, YAW)
                .moveVector(0, 0)
                .authorityMode("client-auth-input")
                .build();
        var vector = new BedrockPredVector(
                new BedrockMovementInputFactory.Input(
                        auth, request.previousState(), new BedrockTickInput(request.frame(), Vec3d.ZERO, 0L), world),
                request,
                movement,
                BedrockMobJumpComponentState.DEFAULT,
                new PredVector(Vec3.ZERO),
                Vec3.ZERO);
        if (knockback) vector.addPacketModifier(new TransactionVel(Vec3.ZERO, 1, true, false));
        var context = mock(SimulationContext.class);
        when(context.getBedrockInput()).thenReturn(auth);
        var result = mock(PredictionResult.class);
        when(result.getAcceptedClosestToTarget()).thenReturn(vector);
        when(result.getSimulationContext()).thenReturn(context);
        when(result.getPositionOnlyDelta()).thenReturn(Vec3.ZERO);
        return result;
    }

    private static PredictionCommit commit(BedrockProfileState.Entry entry) {
        var entries = List.of(entry);
        return new PredictionCommit(
                new BedrockNextTickStates(entries), BedrockNextTickVelocityDerivation.profileStateVelocities(entries));
    }

    private static BedrockInputFrame frame(long tick) {
        return new BedrockInputFrame(tick, YAW, PITCH, false, false, false);
    }

    private static Vec3d feet(double[] row) {
        return new Vec3d(row[0], row[1] - EYE, row[2]);
    }

    private static Vec3d velocity(double[] row) {
        return new Vec3d(row[3], row[4], row[5]);
    }

    private static BedrockWorldSnapshot wall() {
        var wall = PlacedBlockCollision.manual(
                new BlockPosition(0, 100, -71),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(0, 100, -71, 40, 160, -70)));
        return BedrockWorldSnapshot.fromContext(new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(List.of(wall))),
                // Wearing an elytra.
                new EquipmentState(0, 0, 0, false, true),
                EntityContactState.NONE,
                new MovementModifierState(true, false, false, false, 0.05, false, false, false, false, 0.35, 0),
                PlayerDimensionsState.DEFAULT));
    }
}
