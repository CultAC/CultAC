package ac.cult.cultac.checks.impl.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class KeepSprintSetbackTest {
    private static final double UNSLOWED_OFFSET = 0.4;

    @Test
    void firstFlagSetsBack() {
        run(rig -> {
            rig.attackTick(UNSLOWED_OFFSET);
            assertEquals(1, rig.keepSprint.violations);
            verify(rig.setbacks, times(1)).executeViolationSetback(any(Check.class));
        });
    }

    @Test
    void slowedTickNeverSetsBack() {
        run(rig -> {
            rig.attackTick(0);
            assertEquals(0, rig.keepSprint.violations);
            verify(rig.setbacks, never()).executeViolationSetback(any(Check.class));
        });
    }

    private static void run(Consumer<Rig> scenario) {
        OfflineCultTestBootstrap.installConfig();
        var channel = new EmbeddedChannel();
        var user = TestUsers.create(new User.Profile(UUID.randomUUID(), ".KeepSprint_Test"), channel);
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
        final KeepSprint keepSprint;

        Rig(CultPlayer original) {
            player = spy(original);
            player.gamemode = GameMode.SURVIVAL;
            player.noSetbackPermission = false;
            doReturn(setbacks).when(player).getSetbackTeleportUtil();
            doReturn(true).when(setbacks).executeViolationSetback(any(Check.class));
            keepSprint = new KeepSprint(player);
            keepSprint.reload();
            keepSprint.violations = 0;
        }

        /** One client tick after an attack that certainly applied the slowdown; the damped candidate has this offset. */
        void attackTick(double offset) {
            var context = mock(SimulationContext.class);
            when(context.getMinAttackSlow()).thenReturn(1);
            var start = mock(PredVector.class);
            when(start.getAttackSlows()).thenReturn(1);
            var result = mock(PredictionResult.class);
            when(result.getSimulationContext()).thenReturn(context);
            when(result.getInitialStartingVel()).thenReturn(start);
            when(result.getFlagSeverity()).thenReturn(offset);
            keepSprint.handleResult(player, result, null);
            keepSprint.onPredictionComplete(new PredictionComplete(result));
        }
    }
}
