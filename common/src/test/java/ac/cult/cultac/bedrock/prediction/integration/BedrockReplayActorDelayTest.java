package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.assertEquals;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.*;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockDepenetrationLimit;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.*;
import java.util.List;
import org.junit.Test;

/**
 * A boosted glide toward a happy ghast that flies to meet it, replayed when the boost ended (capture 2026-11-14,
 * flag at tick 1042, a client before 1.26.60). The client showed the ghast where it was sent about six ticks before,
 * more than 2 blocks from the player's last replayed tick, so that tick did not meet it and the replay ended where
 * the live ticks had. At the ghast's newest box the same tick stops against it, 0.56 short. The tick after the replay
 * tells the two apart.
 */
public final class BedrockReplayActorDelayTest {
    private static final PlayerDimensionsState GLIDE = new PlayerDimensionsState(0.6F, 0.6F);
    private static final float EYE = 1.62001F;

    // tick, x, eye y, z, yaw, pitch, vx, vy, vz of the client's ticks; the last is the tick after the replay.
    private static final float[][] LIVE = {
        {1033, 296.3237F, 101.240425F, -92.92651F, -28.655975F, 11.485367F, -0.31406158F, -0.1321886F, 0.5368251F},
        {1034, 296.78253F, 100.95493F, -92.08411F, -44.780853F, 15.038315F, 0.45884037F, -0.28549528F, 0.8424047F},
        {1035, 297.8173F, 100.5856F, -91.45389F, -72.93109F, 16.13153F, 1.0347542F, -0.36933088F, 0.6302179F},
        {1036, 299.1688F, 100.20658F, -91.22646F, -93.428825F, 14.218399F, 1.3515146F, -0.37902266F, 0.22742185F},
        {1037, 300.6667F, 99.858986F, -91.27846F, -99.7148F, 11.758682F, 1.4978813F, -0.3475936F, -0.051996533F},
        {1038, 302.23553F, 99.52931F, -91.455925F, -99.7148F, 11.485367F, 1.56885F, -0.32967362F, -0.17746368F},
        {1039, 303.83914F, 99.20757F, -91.68983F, -99.7148F, 11.485367F, 1.6036177F, -0.3217361F, -0.2338986F},
        {1040, 305.46228F, 98.905266F, -91.95405F, -99.9881F, 10.392166F, 1.6231433F, -0.30230787F, -0.26421884F},
        {1041, 307.1158F, 98.691345F, -92.15422F, -95.06865F, 4.9261017F, 1.6535223F, -0.2139178F, -0.20016944F},
        {1042, 307.943F, 98.484985F, -92.321556F, -86.59625F, 1.3731537F, 0.0F, -0.20635872F, -0.16733538F}
    };
    // The ghast's newest box at the replay: centre x, bottom, centre z.
    private static final double[] GHAST_AT_REPLAY = {308.85352, 93.73404, -90.599625};

    private static final BedrockReplayTick.Actors MET = (recorded, state, world) -> world(true);
    private static final BedrockReplayTick.Actors MISSED = (recorded, state, world) -> world;

    @Test
    public void theReplayIsTheOneTheNextTickStartedFrom() {
        float[] last = LIVE[LIVE.length - 2], next = LIVE[LIVE.length - 1];
        var position = new Vec3d(next[1], next[2] - EYE, next[3]);
        var velocity = new Vec3d(next[6], next[7], next[8]);

        // Met at its newest box, the ghast stops the last replayed tick short.
        var met = marked().replay((state, world) -> world, MET).getFirst().state();
        assertEquals(last[1] - 0.5643, met.physicalFeetPosition().x(), 1.0E-3);
        assertEquals(0.0, met.velocity().x(), 0.0);

        for (var variants : List.of(List.of(MET, MISSED), List.of(MISSED, MET))) {
            var history = marked();
            var end = history.replay((state, world) -> world, variants, position, velocity)
                    .getFirst()
                    .state();
            assertEquals(last[1], end.physicalFeetPosition().x(), 1.0E-4);
            assertEquals(last[2] - EYE, end.physicalFeetPosition().y(), 1.0E-4);
            assertEquals(last[3], end.physicalFeetPosition().z(), 1.0E-4);
            assertEquals(last[6], end.velocity().x(), 1.0E-4);
            assertEquals(end, history.current().getFirst().state());
        }
    }

    @Test
    public void theFirstReplayIsKeptWhenTheNextTickFitsThemAlike() {
        float[] next = LIVE[LIVE.length - 1];
        var history = marked();
        var expected = marked().replay((state, world) -> world, MISSED);
        assertEquals(
                expected,
                history.replay(
                        (state, world) -> world,
                        List.of(MISSED, MISSED),
                        new Vec3d(next[1], next[2] - EYE, next[3]),
                        new Vec3d(next[6], next[7], next[8])));
    }

    /** The live ticks before the replay as a history whose replay starts after its first frame. */
    private static BedrockActorHistory marked() {
        var history = new BedrockActorHistory();
        for (int i = 0; i < LIVE.length - 1; i++) {
            var observed = at(i);
            history.record(new BedrockActorHistory.Frame(
                    (long) LIVE[i][0],
                    input(at(Math.max(0, i - 1)), i),
                    List.of(new BedrockProfileState.Entry(observed, BedrockMobJumpComponentState.DEFAULT)),
                    List.of(new BedrockProfileState.Entry(observed, BedrockMobJumpComponentState.DEFAULT)),
                    null,
                    Vec3d.ZERO,
                    Vec3d.ZERO,
                    observed.physicalFeetPosition(),
                    observed.velocity(),
                    List.of()));
        }
        history.mark((long) LIVE[0][0]);
        return history;
    }

    private static BedrockMovementState at(int row) {
        float[] r = LIVE[row];
        return BedrockMovementState.fromPhysicalFeet(
                        new Vec3d(r[1], r[2] - EYE, r[3]),
                        new Vec3d(r[6], r[7], r[8]),
                        BedrockInputFrame.idle((long) r[0]),
                        BedrockCollisionFlags.AIR)
                .withGliding(true)
                .withPlayerDimensions(GLIDE, true);
    }

    private static BedrockSimulation.Input input(BedrockMovementState start, int row) {
        float[] r = LIVE[row];
        var frame = new BedrockInputFrame((long) r[0], r[4], r[5], false, false, true);
        return new BedrockSimulation.Input(
                start,
                frame,
                frame.intent(),
                world(false),
                true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                BedrockMobJumpComponentState.DEFAULT,
                true,
                false,
                Vec3d.ZERO,
                true);
    }

    private static BedrockWorldSnapshot world(boolean ghast) {
        double x = GHAST_AT_REPLAY[0], y = GHAST_AT_REPLAY[1], z = GHAST_AT_REPLAY[2];
        var blocks = new BlockCollisionWorld(
                ghast
                        ? List.of(BedrockDepenetrationLimit.HAPPY_GHAST.solidActor(
                                new WorldCollisionBox(x - 2.002, y, z - 2.002, x + 2.002, y + 4.004, z + 2.002),
                                0.0F))
                        : List.of());
        return BedrockWorldSnapshot.fromContext(new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, blocks),
                new EquipmentState(0, 0, 0, false, true),
                EntityContactState.NONE,
                new MovementModifierState(true, false, false, false, 0.05, false, false, false, false, 0.35, 0),
                GLIDE));
    }
}
