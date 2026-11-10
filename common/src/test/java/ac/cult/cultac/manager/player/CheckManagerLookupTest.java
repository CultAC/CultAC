package ac.cult.cultac.manager.player;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.prediction.checks.NoSlow;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.InvalidStep;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.NoFallPseudo;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.Speed;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.Strafe;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.VerticalOffset;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaAutoJump;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Each held listener is the instance the map lookup returns, before and after reloads and check toggles. */
class CheckManagerLookupTest {
    @Test
    void heldListenersAreTheLookedUpInstances() {
        withPlayer(player -> {
            CheckManager manager = player.checkManager;
            assertHeld(manager);
            manager.reload();
            for (Check check : manager.getAllChecks()) check.setEnabled(false);
            assertHeld(manager);
            for (Check check : manager.getAllChecks()) check.setEnabled(true);
            assertHeld(manager);
        });
    }

    @Test
    void playersHoldTheirOwnListeners() {
        withPlayer(first -> withPlayer(second -> {
            assertSame(first.checkManager.getListener(NoSlow.class), first.checkManager.getNoSlow());
            assertSame(second.checkManager.getListener(NoSlow.class), second.checkManager.getNoSlow());
            assertSame(second.checkManager.getCheck(Speed.class), second.checkManager.getSpeed());
            if (first.checkManager.getNoSlow() == second.checkManager.getNoSlow()) throw new AssertionError("shared");
        }));
    }

    private static void assertHeld(CheckManager manager) {
        assertSame(manager.getListener(NoSlow.class), present(manager.getNoSlow()));
        assertSame(manager.getListener(JavaAutoJump.class), present(manager.getJavaAutoJump()));
        assertSame(manager.getListener(NoFallPseudo.class), present(manager.getNoFallPseudo()));
        assertSame(manager.getListener(InvalidStep.class), present(manager.getInvalidStep()));
        assertSame(manager.getListener(VerticalOffset.class), present(manager.getVerticalOffset()));
        assertSame(manager.getCheck(Speed.class), present(manager.getSpeed()));
        assertSame(manager.getCheck(Strafe.class), present(manager.getStrafe()));
        assertSame(manager.getListener(Speed.class), manager.getSpeed());
        assertSame(manager.getListener(Strafe.class), manager.getStrafe());
    }

    private static <T> T present(T value) {
        assertNotNull(value);
        return value;
    }

    private static void withPlayer(Consumer<CultPlayer> body) {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = new CultPlayer(
                TestUsers.create(new User.Profile(UUID.randomUUID(), ".Lookup_Test"), new EmbeddedChannel()));
        try {
            body.accept(player);
        } finally {
            Object channel = player.user.getChannel();
            player.onRemove();
            if (channel instanceof EmbeddedChannel embedded) embedded.finishAndReleaseAll();
        }
    }
}
