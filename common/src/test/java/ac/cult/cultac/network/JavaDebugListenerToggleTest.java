package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * /cult debug runs on the command thread while predictions complete on the packet thread. A completion's pruning of
 * offline listeners, landing while a toggle is still registering, must leave the listener in the mode it asked for.
 */
class JavaDebugListenerToggleTest {
    @Test
    void aCompletionDuringAVerboseToggleKeepsTheListenerVerbose() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(0.5, 90, 0.5), Vec3.ZERO);
            double[] y = {90};
            List<String> received = new ArrayList<>();
            boolean[] completedDuringToggle = {false};
            PlatformPlayer listener = Mockito.mock(PlatformPlayer.class);
            Mockito.when(listener.isOnline()).thenReturn(true);
            Mockito.doAnswer(invocation -> {
                        String message = invocation.getArgument(0);
                        received.add(message);
                        // The packet thread completes a prediction while the toggle tells the listener.
                        if (message.contains("start (S) and end (E)") && !completedDuringToggle[0]) {
                            completedDuringToggle[0] = true;
                            fall(f, y);
                        }
                        return null;
                    })
                    .when(listener)
                    .sendMessage(Mockito.anyString());
            var debug = f.player.checkManager.getDebugHandler();
            // Another staff member already debugs this player, so each completion prunes the listeners.
            PlatformPlayer other = Mockito.mock(PlatformPlayer.class);
            Mockito.when(other.isOnline()).thenReturn(true);
            debug.toggleListener(other, false);
            debug.toggleListener(listener, true);
            assertTrue(completedDuringToggle[0]);
            received.clear();
            fall(f, y);
            assertTrue(received.stream().anyMatch(line -> line.contains("S: ")), "verbose lines: " + received);

            // Toggling it off stops every line, also with a completion in between.
            debug.toggleListener(listener, true);
            received.clear();
            fall(f, y);
            assertFalse(received.stream().anyMatch(line -> line.contains("P: ")), "after toggling off: " + received);
        }
    }

    private static void fall(JavaTickFixture f, double[] y) {
        y[0] -= 0.5;
        f.tick(new Vec3(0.5, y[0], 0.5), false);
    }
}
