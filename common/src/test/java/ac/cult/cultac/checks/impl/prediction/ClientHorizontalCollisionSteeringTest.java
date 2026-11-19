package ac.cult.cultac.checks.impl.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaTravelInputs;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.checks.impl.sprint.SprintE;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.TrigHandler;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * LocalPlayer#applyInput keeps xxa/zza while LivingEntity#travelFallFlying adds no moveRelative input. The live 26.3
 * glide-piston replay at yaw 96 was shoved up 0.51, then slid along a wall while still sprinting: its movement
 * (-0.19778455135439427, -0.01, 0) is within 8 degrees of its forward keys, even though its travel acceleration is zero.
 */
class ClientHorizontalCollisionSteeringTest {
    private static final Vec3 DELTA = new Vec3(-0.19778455135439427, -0.017640000343322755, -0.0022357347962505027);
    private static final Vec3 MOVEMENT = new Vec3(DELTA.x, -0.010000000000005116, 0);

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_11", "V_26_2", "V_26_3"})
    void zeroGlideAccelerationDoesNotEraseTheSteeringKeys(ClientVersion version) throws Exception {
        try (var rig = new Rig(version)) {
            PredictionResult result = rig.move(96, true);
            assertEquals(DesyncStatus.FALSE, ClientHorizontalCollision.hard(result));
            // The next aiStep may report different keys before an older client's previous block pass is replayed.
            rig.player.packetStateData.knownInput = KnownInput.DEFAULT;
            assertEquals(DesyncStatus.FALSE, ClientHorizontalCollision.afterMove(result, DELTA, MOVEMENT));
            rig.sprintE.onPredictionComplete(new PredictionComplete(result));
            rig.sprintE.onPredictionComplete(new PredictionComplete(result));
            assertEquals(0, rig.sprintE.violations);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_11", "V_26_2", "V_26_3"})
    void glidingStillStopsSprintingAtAHardWallAngle(ClientVersion version) throws Exception {
        try (var rig = new Rig(version)) {
            PredictionResult result = rig.move(100, true);
            assertEquals(DesyncStatus.TRUE, ClientHorizontalCollision.hard(result));
            assertEquals(DesyncStatus.TRUE, ClientHorizontalCollision.afterMove(result, DELTA, MOVEMENT));
            rig.sprintE.onPredictionComplete(new PredictionComplete(result));
            rig.sprintE.onPredictionComplete(new PredictionComplete(result));
            assertEquals(1, rig.sprintE.violations);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_26_3"})
    void noKeysStillMakeTheCollisionHard(ClientVersion version) throws Exception {
        try (var rig = new Rig(version)) {
            assertEquals(DesyncStatus.TRUE, ClientHorizontalCollision.hard(rig.move(96, false)));
        }
    }

    private static final class Rig implements AutoCloseable {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final CultPlayer player;
        final SprintE sprintE;
        final ClientVersion version;

        Rig(ClientVersion version) throws Exception {
            OfflineCultTestBootstrap.installConfig();
            this.version = version;
            player = new CultPlayer(TestUsers.create(new User.Profile(UUID.randomUUID(), "Steering_Test"), channel));
            var field = CultPlayer.class.getDeclaredField("resolvedClientVersion");
            field.setAccessible(true);
            field.set(player, version);
            player.setExperimentalChecks(true);
            player.isSprinting = true;
            sprintE = new SprintE(player);
            sprintE.reload();
            sprintE.violations = 0;
        }

        PredictionResult move(float yaw, boolean forward) {
            player.packetStateData.knownInput = new KnownInput(forward, false, false, false, false, false, true);
            var context = mock(SimulationContext.class);
            var world = mock(WorldData.class);
            when(world.getClimbingAtStart()).thenReturn(DesyncStatus.FALSE);
            when(context.getWorldData()).thenReturn(world);
            when(context.getStart()).thenReturn(new Vec3(0.5, 64, 0.5));
            when(context.getVersion()).thenReturn(version);
            when(context.getXRot()).thenReturn(yaw);
            when(context.getTrig()).thenReturn(new TrigHandler(player));
            when(context.getPose()).thenReturn(Pose.FALL_FLYING);
            when(context.isGliding()).thenReturn(true);
            when(context.usesFallFlyingMovement()).thenReturn(true);
            when(context.getItemUse()).thenReturn(ClientItemUseState.Snapshot.NONE);
            var start = JavaTravelInputs.withInputs(player, context, List.of(new PredVector(DELTA)))
                    .getFirst();
            assertEquals(Vec3.ZERO, start.getTravelInput());
            var result = mock(PredictionResult.class);
            var valid = mock(ValidMovements.class);
            when(result.getPlayer()).thenReturn(player);
            when(result.getSimulationContext()).thenReturn(context);
            when(result.getInitialStartingVel()).thenReturn(start);
            when(result.getValidMovements()).thenReturn(valid);
            when(valid.getClosestTrace()).thenReturn(new MovementTrace(start, Vec3.ZERO, Vec3.ZERO, DELTA, DELTA));
            when(result.getCollideAxisData())
                    .thenReturn(JavaMoveCollision.of(DELTA, MOVEMENT, new Vec3(1, 1, 1), List.of()));
            return result;
        }

        @Override
        public void close() {
            player.onRemove();
            channel.finishAndReleaseAll();
        }
    }
}
