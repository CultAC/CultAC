package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Through 26.2 LocalPlayer#tick reports before ClientLevel's later entities and Level#tickBlockEntities, which can still
 * change the velocity the next travel starts from (a piston shoving a slime block sets it). The verbose E line is the
 * client tick's end, so it follows those passes.
 */
class DebugEndOfTickTest {
    @Test
    void theEndOfTickLineFollowsThePassesAfterTheReport() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 at = new Vec3(0.5, FLOOR_Y + 2, 0.5);
            f.place(at, Vec3.ZERO);
            PlatformPlayer listener = mock(PlatformPlayer.class);
            when(listener.isOnline()).thenReturn(true);
            f.player.checkManager.getDebugHandler().toggleListener(listener, true);

            // The first tick of a fall: (0 - 0.08) * 0.98.
            f.move(at.add(0, (0 - 0.08) * 0.98F, 0), false);
            // What JavaBlockEntityPass leaves after the report: a slime block's shove into the player.
            Field starts = SimulationProcessor.class.getDeclaredField("validPlayerStartingVels");
            starts.setAccessible(true);
            starts.set(f.player.checkManager.getSimulationProcessor(), new HashSet<>(Set.of(new Vec3(-1, -0.0784, 0))));
            f.tickEnd();

            ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
            verify(listener, atLeastOnce()).sendMessage(lines.capture());
            List<String> end = lines.getAllValues().stream().filter(line -> line.contains("E: ")).toList();
            assertTrue(!end.isEmpty() && end.getLast().contains("-1.000000"), String.valueOf(lines.getAllValues()));
        }
    }
}
