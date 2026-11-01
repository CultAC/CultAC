package ac.cult.cultac.checks.impl.sprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * A setback teleport and the movements the client sends before accepting it are not simulated ticks: the teleport
 * places the player, and SimulationProcessor exempts movement while a setback blocks it. Flagging them re-sets the
 * player back on every teleport acceptance, so no movement tick is ever accepted.
 */
class SprintDTest {
    private static final double ABOVE_SETBACK = 10;

    @Test
    void sprintingWhileBlindFlagsAndSetsBack() {
        run(rig -> {
            rig.sprintD.violations = ABOVE_SETBACK;
            rig.complete(false, false);
            assertEquals(ABOVE_SETBACK + 1, rig.sprintD.violations);
            verify(rig.setbacks).executeViolationSetback();
        });
    }

    @Test
    void theSetbackTeleportIsNotFlagged() {
        run(rig -> {
            rig.sprintD.violations = ABOVE_SETBACK;
            rig.complete(true, false);
            assertEquals(ABOVE_SETBACK, rig.sprintD.violations);
            verify(rig.setbacks, never()).executeViolationSetback();
        });
    }

    @Test
    void movementBlockedByAPendingSetbackIsNotFlagged() {
        run(rig -> {
            rig.sprintD.violations = ABOVE_SETBACK;
            for (int i = 0; i < 20; i++) rig.complete(false, true);
            assertEquals(ABOVE_SETBACK, rig.sprintD.violations);
            verify(rig.setbacks, never()).executeViolationSetback();
        });
    }

    private static void run(Consumer<Rig> scenario) {
        OfflineCultTestBootstrap.installConfig();
        var channel = new EmbeddedChannel();
        var user = TestUsers.create(new User.Profile(UUID.randomUUID(), ".SprintD_Test"), channel);
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
        final SprintD sprintD;

        Rig(CultPlayer original) {
            player = spy(original);
            player.gamemode = GameMode.SURVIVAL;
            player.noSetbackPermission = false;
            player.setExperimentalChecks(true);
            doReturn(setbacks).when(player).getSetbackTeleportUtil();
            doReturn(true).when(setbacks).executeViolationSetback();
            // Sprinting began after the blindness, so SprintD applies.
            player.compensatedEntities.getSelf().addPotionEffect(MovementEffect.BLINDNESS, 0);
            player.isSprinting = true;
            sprintD = new SprintD(player);
            sprintD.reload();
            sprintD.violations = 0;
        }

        void complete(boolean teleport, boolean exempt) {
            var realities = new ArrayList<PredictionResult>();
            var result = new PredictionResult(
                    player,
                    new PredVector(Vec3.ZERO),
                    mock(SimulationContext.class),
                    Vec3.ZERO,
                    null,
                    realities,
                    realities);
            result.setTeleport(teleport);
            result.setExempt(exempt);
            sprintD.onPredictionComplete(new PredictionComplete(result));
        }
    }
}
