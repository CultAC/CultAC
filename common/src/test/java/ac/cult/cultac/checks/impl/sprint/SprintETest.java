package ac.cult.cultac.checks.impl.sprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.ClientHorizontalCollisionTest;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
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

        void complete(PredictionResult result) {
            sprintE.onPredictionComplete(new PredictionComplete(result));
        }
    }
}
