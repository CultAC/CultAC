package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.utils.latency.CompensatedOpenWindow.Window;
import java.lang.reflect.Field;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link CompensatedOpenWindow#mustBeOpen()} answers as a walk that returns false at the first null window. */
class MustBeOpenEquivalenceTest {
    private static boolean walk(Set<Window> windows) {
        for (Window window : windows) {
            if (window == null) return false;
        }
        return true;
    }

    @Test
    @SuppressWarnings("unchecked")
    void answersAsTheWalk() throws Exception {
        CompensatedOpenWindow open = new CompensatedOpenWindow(null);
        Field field = CompensatedOpenWindow.class.getDeclaredField("possibleOpenWindows");
        field.setAccessible(true);
        Set<Window> windows = (Set<Window>) field.get(open);
        Random random = new Random(3);
        int open1 = 0, closed = 0;
        for (int round = 0; round < 20_000; round++) {
            int op = random.nextInt(10);
            if (op < 3) windows.add(null);
            else if (op < 6) windows.remove(null);
            else if (op < 9) windows.add(new Window(random.nextInt(4), random.nextInt(6), null));
            else windows.clear();
            boolean expected = walk(windows);
            assertEquals(expected, open.mustBeOpen(), windows.toString());
            if (expected) open1++;
            else closed++;
        }
        assertTrue(open1 > 1000 && closed > 1000, open1 + " " + closed);
    }
}
