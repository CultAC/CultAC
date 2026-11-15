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
import java.util.Map;
import java.util.UUID;
import org.junit.Test;

/**
 * A motion sent without a tick changes the velocity the newest frame ended with. A replay that starts at that frame
 * keeps it; one that starts earlier runs that frame's end again without it.
 */
public final class BedrockUntimedMotionReplayTest {
    // Elytra glides pressed against a wall with a rocket boost running, pitch 25 down: client positions (eye) and
    // reported velocities. The server sent the motion (0, -0.637, 0) without a tick; the client took it on the tick
    // after it arrived.
    private static final double EYE = 1.62001D;
    private static final float PITCH = 25.000366F;
    private static final float YAW = -1.5258789E-5F;
    // Ticks 3014 to 3024; motions before 3016 and 3021. A boost start marked 3014's successor and the boost stop
    // replayed from there over both motions.
    private static final double[][] EARLIER = {
        {17.500874, 135.10695, -72.357376, 1.4588847E-4, -0.6574944, 1.5212429},
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
    // Ticks 4061 to 4064; a motion before 4063. The boost stop stamped 4062 arrived after tick 4063 and replayed
    // from 4062.
    private static final double[][] AT_ANCHOR = {
        {65.50145, 131.8075, 216.7, 1.3093825E-4, -0.66374844, 0.0},
        {65.50159, 131.14313, 216.7, 1.3393167E-4, -0.6643691, 0.0},
        {65.50166, 130.4906, 216.7, 7.550567E-5, -0.6525319, 0.0},
        {65.50177, 129.83128, 216.7, 1.09196815E-4, -0.6593245, 0.0},
    };

    @Test
    public void aReplayFromBeforeTheFrameAMotionArrivedAfterRunsWithoutIt() {
        assertReplay(
                EARLIER, 3014, Map.of(3016L, new Vec3d(0, -0.63718486, 0), 3021L, new Vec3d(0, -0.63742906, 0)), 3014);
    }

    @Test
    public void aReplayFromTheFrameAMotionArrivedAfterKeepsIt() {
        assertReplay(AT_ANCHOR, 4061, Map.of(4063L, new Vec3d(0, -0.63742906, 0)), 4062);
    }

    /**
     * Records the live ticks of {@code client} from {@code first}, each taking the motion keyed by its tick, replays
     * from {@code anchor} and checks the client's last tick from the replayed end.
     */
    private static void assertReplay(double[][] client, long first, Map<Long, Vec3d> motions, long anchor) {
        var world = wall(client[client.length - 1][2] + 0.3D);
        var history = new BedrockActorHistory();
        var rewind = new BedrockMovementRewind(history);
        var entry = new BedrockProfileState.Entry(
                BedrockMovementState.fromPhysicalFeet(
                                feet(client[0]), velocity(client[0]), frame(first - 1), BedrockCollisionFlags.AIR)
                        .withGliding(true),
                BedrockMobJumpComponentState.DEFAULT);
        // The first frame, ending where the client reported it.
        rewind.record(null, result(first, request(entry, first, null, world), world, false), commit(entry));
        long last = first + client.length - 1;
        for (long tick = first + 1; tick < last; tick++) {
            Vec3d motion = motions.get(tick);
            var request = request(entry, tick, motion, world);
            entry = tick(request);
            rewind.record(null, result(tick, request, world, motion != null), commit(entry));
            double[] row = client[(int) (tick - first)];
            assertEquals(
                    "live tick " + tick,
                    row[1] - EYE,
                    entry.state().physicalFeetPosition().y(),
                    1.0E-3);
        }

        history.mark(anchor);
        var replayed = history.replay((state, snapshot) -> snapshot, (frame, state, snapshot) -> snapshot);
        var after = tick(request(replayed.getFirst(), last, null, world));
        double[] row = client[client.length - 1];
        assertEquals(row[1] - EYE, after.state().physicalFeetPosition().y(), 1.0E-3);
        assertEquals(row[4], after.state().velocity().y(), 1.0E-3);
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

    private static BedrockWorldSnapshot wall(double z) {
        var wall = PlacedBlockCollision.manual(
                new BlockPosition(0, 100, (int) Math.floor(z)),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(0, 100, z, 80, 160, z + 1)));
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
