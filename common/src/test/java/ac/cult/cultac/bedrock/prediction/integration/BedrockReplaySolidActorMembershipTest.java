package ac.cult.cultac.bedrock.prediction.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.*;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockDepenetrationLimit;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.*;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * A boosted glide past a happy ghast, replayed when the boost ended (capture 2026-11-12, flag at tick 2485). The
 * live tick 2478 started more than 2 blocks from the ghast and did not meet it; tick 2479 met it and stopped against
 * it. The replay meets the ghast at its newest box only on the ticks whose live tick met it.
 */
public final class BedrockReplaySolidActorMembershipTest {
    private static final PlayerDimensionsState GLIDE = new PlayerDimensionsState(0.6F, 0.6F);
    private static final float EYE = 1.62001F;
    private static final BedrockSolidActors.Met GHAST = new BedrockSolidActors.Met(Set.of(), new long[] {1});

    // tick, x, eye y, z, yaw, pitch, vx, vy, vz of the client's ticks before the replay.
    private static final float[][] LIVE = {
        {2477, 332.2662F, 89.78771F, -79.68048F, -164.14911F, 2.0804138F, 0.49027014F, -0.08809612F, -1.6030022F},
        {2478, 332.72F, 89.7155F, -81.2932F, -165.31842F, 1.6906433F, 0.45378813F, -0.07221211F, -1.6127264F},
        {2479, 333.13702F, 89.657814F, -82.20479F, -166.61766F, 1.1709595F, 0.4170145F, -0.05768784F, 0.0F},
        {2480, 333.50394F, 89.612206F, -83.05113F, -167.9169F, 0.78118896F, 0.36690643F, -0.045606066F, -0.84634286F},
        {2481, 333.84973F, 89.57193F, -84.2993F, -168.17676F, 0.78118896F, 0.34578297F, -0.04027789F, -1.2481583F},
        {2482, 334.18985F, 89.534004F, -85.74429F, -168.17676F, 0.78118896F, 0.34010348F, -0.03792812F, -1.4449931F},
        {2483, 334.52933F, 89.497116F, -87.28604F, -168.17676F, 0.78118896F, 0.33947155F, -0.036891848F, -1.5417526F},
        {2484, 334.86948F, 89.46068F, -88.87543F, -168.17676F, 0.78118896F, 0.34013587F, -0.03643484F, -1.589378F},
        {2485, 335.19052F, 89.424446F, -87.88549F, -168.04683F, 0.78118896F, 0.34109777F, -0.036233295F, -1.5885676F}
    };
    // The ghast's newest box at the replay: centre x, bottom, centre z.
    private static final double[] GHAST_AT_REPLAY = {331.38297, 86.06953, -83.523415};

    @Test
    public void aReplayedTickMeetsOnlyTheSolidActorsItsLiveTickMet() {
        var end = replayFrom2477(tick -> tick == 2478 ? BedrockSolidActors.Met.NONE : GHAST);
        float[] client = LIVE[LIVE.length - 1];
        assertEquals(client[1], end.physicalFeetPosition().x(), 1.0E-4);
        assertEquals(client[2] - EYE, end.physicalFeetPosition().y(), 1.0E-4);
        assertEquals(client[3], end.physicalFeetPosition().z(), 1.0E-4);
        assertEquals(client[6], end.velocity().x(), 1.0E-4);
        assertEquals(client[8], end.velocity().z(), 1.0E-4);
    }

    @Test
    public void meetingTheGhastOnATickWhoseLiveTickDidNotMissesTheClient() {
        var end = replayFrom2477(tick -> GHAST);
        float[] client = LIVE[LIVE.length - 1];
        assertTrue(Math.abs(end.physicalFeetPosition().x() - client[1]) > 0.02);
    }

    private interface Membership {
        BedrockSolidActors.Met at(long tick);
    }

    private static BedrockMovementState replayFrom2477(Membership membership) {
        var states = List.of(new BedrockProfileState.Entry(at(0), BedrockMobJumpComponentState.DEFAULT));
        for (int i = 1; i < LIVE.length; i++) {
            long tick = (long) LIVE[i][0];
            var input = input(at(i - 1), i);
            var observed = at(i);
            var frame = new BedrockActorHistory.Frame(
                    tick,
                    input,
                    List.of(new BedrockProfileState.Entry(observed, BedrockMobJumpComponentState.DEFAULT)),
                    List.of(new BedrockProfileState.Entry(observed, BedrockMobJumpComponentState.DEFAULT)),
                    null,
                    Vec3d.ZERO,
                    Vec3d.ZERO,
                    observed.physicalFeetPosition(),
                    observed.velocity(),
                    List.of(),
                    null,
                    null,
                    membership.at(tick));
            states = BedrockReplayTick.advance(
                    frame,
                    states,
                    List.of(),
                    (state, world) -> world,
                    (recorded, state, world) -> recorded.solidActors().isEmpty() ? world : world(true));
        }
        return states.getFirst().state();
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
