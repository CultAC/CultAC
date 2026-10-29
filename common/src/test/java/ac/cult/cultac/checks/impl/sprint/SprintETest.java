package ac.cult.cultac.checks.impl.sprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.ClientHorizontalCollision;
import ac.cult.cultac.checks.impl.prediction.ClientHorizontalCollisionTest;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.CollideAxisData.CollideResult;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * LocalPlayer#shouldStopRunSprinting only stops sprinting on a hard collision; running into a wall along the input is
 * minor and keeps the sprint.
 */
class SprintETest {
    @Test
    void sprintingAfterAMinorWallCollisionIsNotFlagged() {
        run(rig -> {
            Vec3 slide = new Vec3(-0.2, 0, 0);
            Vec3 attempted = slide.add(ClientHorizontalCollisionTest.input(85, 0, 1, 0.13));
            rig.complete(rig.move(85, slide, new Vec3(attempted.x, 0, 0)));
            rig.complete(rig.move(85, Vec3.ZERO, new Vec3(0, 0, 0.1)));
            assertEquals(0, rig.sprintE.violations);
            verify(rig.setbacks, never()).executeViolationSetback();
        });
    }

    @Test
    void sprintingAfterAHardWallCollisionIsFlagged() {
        run(rig -> {
            Vec3 slide = new Vec3(0.3, 0, 0);
            Vec3 attempted = slide.add(ClientHorizontalCollisionTest.input(30, 0, 1, 0.13));
            rig.complete(rig.move(30, slide, new Vec3(attempted.x, 0, 0)));
            rig.complete(rig.move(30, Vec3.ZERO, new Vec3(0, 0, 0.1)));
            assertEquals(1, rig.sprintE.violations);
        });
    }

    @Test
    void sprintingAfterAWallMinorFromAnAcceptedSiblingStartIsNotFlagged() {
        // 01a17ffe move 2156: W at yaw -1.2172644 sprint-falls into a +Z wall. The selected start has X at 0, which W
        // cannot turn into the reported -0.0018 X; the accepted start (-0.0023563, 0.371) reaches it with W exactly,
        // 1.6 degrees off the collided movement, so LocalPlayer#isHorizontalCollisionMinor keeps the sprint.
        run(rig -> {
            rig.player.packetStateData.knownInput = new KnownInput(true, false, false, false, false, false, true);
            float yaw = -1.2172644F;
            Vec3 target = new Vec3(-0.0018164858938689576, -0.15523200451660557, 0.24328455423164996);
            CollideAxisData wall = Rig.wallAtPositiveZ(target, target.z);
            List<PredictionResult> realities = new ArrayList<>();
            PredictionResult selected = rig.reality(
                    yaw, new Vec3(0.0, -0.1552320045166016, 0.37103305941942144), target, wall, realities);
            rig.reality(
                    yaw,
                    new Vec3(-0.002356318533208103, -0.15523200451660105, 0.37103305941942144),
                    target,
                    wall,
                    realities);
            rig.complete(selected);
            rig.complete(rig.move(yaw, Vec3.ZERO, new Vec3(0, 0, 0.1)));
            assertEquals(0, rig.sprintE.violations);
            assertEquals(DesyncStatus.FALSE, ClientHorizontalCollision.hard(selected));
        });
    }

    private static void run(Consumer<Rig> scenario) {
        OfflineCultTestBootstrap.installConfig();
        var channel = new EmbeddedChannel();
        var user = TestUsers.create(new User.Profile(UUID.randomUUID(), ".SprintE_Test"), channel);
        var original = new CultPlayer(user);
        try {
            scenario.accept(new Rig(original));
        } finally {
            original.onRemove();
            channel.finishAndReleaseAll();
        }
    }

    private static final class Rig {
        final CultPlayer player;
        final SetbackTeleportUtil setbacks = mock(SetbackTeleportUtil.class);
        final SprintE sprintE;

        Rig(CultPlayer original) {
            player = spy(original);
            player.gamemode = GameMode.SURVIVAL;
            player.noSetbackPermission = false;
            player.setExperimentalChecks(true);
            doReturn(setbacks).when(player).getSetbackTeleportUtil();
            player.isSprinting = true;
            sprintE = new SprintE(player);
            sprintE.reload();
            sprintE.violations = 0;
        }

        PredictionResult move(float yaw, Vec3 attemptedMomentum, Vec3 target) {
            PredictionResult result = ClientHorizontalCollisionTest.result(
                    yaw, attemptedMomentum, target, ClientHorizontalCollisionTest.wallAtPositiveZ());
            WorldData world = mock(WorldData.class);
            doReturn(DesyncStatus.FALSE).when(world).getInWater();
            doReturn(world).when(result.getSimulationContext()).getWorldData();
            return result;
        }

        /** An accepted reality of {@code realities} starting at {@code start}, travelling with 26.2's keys and yaw. */
        PredictionResult reality(
                float yaw, Vec3 start, Vec3 target, CollideAxisData collide, List<PredictionResult> realities) {
            doReturn(ClientVersion.V_26_2).when(player).getClientVersion();
            SimulationContext context = mock(SimulationContext.class);
            doReturn(yaw).when(context).getXRot();
            doReturn(0.02F * 1.3D).when(context).getHorizontalInputRadius(any(), any());
            WorldData world = mock(WorldData.class);
            doReturn(DesyncStatus.FALSE).when(world).getInWater();
            doReturn(world).when(context).getWorldData();
            ValidMovements valid = mock(ValidMovements.class);
            doReturn(new MovementTrace(new PredVector(start), Vec3.ZERO, Vec3.ZERO, start, start))
                    .when(valid)
                    .getClosestTrace();
            doReturn(new SimpleCollisionBox(start, start)).when(valid).getCollisionIgnoredMaxStartingVelExtents();
            PredictionResult result =
                    new PredictionResult(player, new PredVector(start), context, target, null, realities, realities);
            result.setValidMovements(valid);
            result.setCollideAxisData(collide);
            realities.add(result);
            return result;
        }

        void complete(PredictionResult result) {
            sprintE.onPredictionComplete(new PredictionComplete(result));
        }

        static CollideAxisData wallAtPositiveZ(Vec3 target, double wallZ) {
            return new CollideAxisData(
                    new CollideResult(false, target.x, 0),
                    new CollideResult(false, 0),
                    new CollideResult(false, target.y, 0),
                    new CollideResult(true, wallZ, 1),
                    List.of());
        }
    }
}
