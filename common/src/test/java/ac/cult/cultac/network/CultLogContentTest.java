package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.SuperDebug;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The Cult Log keeps a flagged tick's values and prints them, every context field among them, when it is read. */
class CultLogContentTest {
    private static final Vec3 START = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);
    private static final Vec3 RESTING = new Vec3(0.0, -0.0784000015258789, 0.0);
    private static final Vec3 TOO_FAR = new Vec3(0.9, JavaTickFixture.FLOOR_Y, 0.5);

    /** The engine handles and per-tick caches the log leaves out. */
    private static final Set<String> UNPRINTED_CONTEXT_FIELDS = Set.of(
            "packetMovement",
            "packetMovementEnd",
            "version",
            "trig",
            "entities",
            "entityCollisionBoxesForMovementTick",
            "hardCollidingEntityCollisionsForMovementTick");

    @Test
    void aLogReadsTheSameAfterThePlayerMovesOn() throws Exception {
        try (JavaTickFixture f = new JavaTickFixture()) {
            f.place(START, RESTING);
            PredictionResult flagged = f.tick(TOO_FAR, true);
            assertNotEquals(0, flagged.getIdentifier(), JavaTickFixture.describe(flagged));
            String log = SuperDebug.getFlag(flagged.getIdentifier());
            assertNotNull(log);

            f.tick(TOO_FAR.add(0.0, 1.0, 0.0), false);
            f.tick(START, true);

            assertEquals(log, SuperDebug.getFlag(flagged.getIdentifier()));
        }
    }

    @Test
    void theContextAndItsWorldDataPrintEveryField() throws Exception {
        try (JavaTickFixture f = new JavaTickFixture()) {
            f.place(START, RESTING);
            SimulationContext context = f.tick(TOO_FAR, true).getSimulationContext();
            WorldData world = context.getWorldData();
            assertNotNull(world);

            assertEquals(
                    fields(SimulationContext.class, UNPRINTED_CONTEXT_FIELDS),
                    labels(context.toString(), "SimulationContext("));
            assertEquals(fields(WorldData.class, Set.of()), labels(world.toString(), "WorldData("));
        }
    }

    private static List<String> fields(Class<?> type, Set<String> unprinted) {
        List<String> names = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !unprinted.contains(field.getName())) {
                names.add(field.getName());
            }
        }
        return names;
    }

    /** The {@code name=} labels directly inside {@code prefix}'s brackets, skipping those of nested values. */
    private static List<String> labels(String text, String prefix) {
        List<String> labels = new ArrayList<>();
        int depth = 1;
        int start = prefix.length();
        for (int i = prefix.length(); i < text.length() && depth > 0; i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (depth == 1 && c == '=' && start >= 0) {
                labels.add(text.substring(start, i));
                start = -1;
            } else if (depth == 1 && c == ',' && text.startsWith(", ", i)) {
                start = i + 2;
            }
        }
        return labels;
    }
}
