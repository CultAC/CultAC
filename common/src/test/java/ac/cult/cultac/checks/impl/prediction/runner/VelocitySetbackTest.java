package ac.cult.cultac.checks.impl.prediction.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * AntiKB and AntiExplosion set the player back from their first recorded flag, and a flag that is not recorded never
 * sets back.
 */
class VelocitySetbackTest {
    private static final double IGNORED_OFFSET = 0.4;

    static Stream<Arguments> handlers() {
        return Stream.of(
                Arguments.of("Knockback", (Function<CultPlayer, PacketModHandler>) KnockbackHandler::new),
                Arguments.of("Explosion", (Function<CultPlayer, PacketModHandler>) ExplosionHandler::new));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("handlers")
    void firstFlagSetsBack(String configName, Function<CultPlayer, PacketModHandler> handler) {
        run(handler, (rig, check) -> {
            rig.velocityTick(check, IGNORED_OFFSET);
            assertEquals(1, check.violations);
            verify(rig.setbacks, times(1)).executeViolationSetback();
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("handlers")
    void exemptFlagDoesNotSetBack(String configName, Function<CultPlayer, PacketModHandler> handler) {
        run(handler, (rig, check) -> {
            check.violations = 1;
            doReturn(true).when(rig.player).hasPermission("cult.exempt." + Check.permissionId(configName));
            check.updatePermissions();
            rig.velocityTick(check, IGNORED_OFFSET);
            assertEquals(1, check.violations);
            verify(rig.setbacks, never()).executeViolationSetback();
        });
    }

    private static void run(
            Function<CultPlayer, PacketModHandler> handler, BiConsumer<Rig, PacketModHandler> scenario) {
        OfflineCultTestBootstrap.installConfig();
        var channel = new EmbeddedChannel();
        var user = TestUsers.create(new User.Profile(UUID.randomUUID(), ".Velocity_Test"), channel);
        var original = new CultPlayer(user);
        try {
            var rig = new Rig(original);
            var check = handler.apply(rig.player);
            check.reload();
            check.violations = 0;
            scenario.accept(rig, check);
        } finally {
            original.onRemove();
            channel.finishAndReleaseAll();
        }
    }

    private static final class Rig {
        final CultPlayer player;
        final SetbackTeleportUtil setbacks = mock(SetbackTeleportUtil.class);

        Rig(CultPlayer original) {
            player = spy(original);
            player.gamemode = GameMode.SURVIVAL;
            player.noSetbackPermission = false;
            doReturn(setbacks).when(player).getSetbackTeleportUtil();
            doReturn(true).when(setbacks).executeViolationSetback();
        }

        /** One client tick after a confirmed velocity whose best candidate has this offset. */
        void velocityTick(PacketModHandler check, double offset) {
            var velocity = new TransactionVel(new Vec3(0, 0.4, 0), 1, true, false);
            velocity.addOffset(offset);
            check.secondBread = velocity;
            var result = mock(PredictionResult.class);
            when(result.getInitialStartingVel()).thenReturn(mock(PredVector.class));
            check.onPredictionComplete(new PredictionComplete(result));
        }
    }
}
