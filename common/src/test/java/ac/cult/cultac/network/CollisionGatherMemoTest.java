package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.checks.impl.prediction.checks.EngineCheck;
import ac.cult.cultac.checks.impl.prediction.checks.NoFall;
import ac.cult.cultac.checks.impl.prediction.checks.PredCheckRunner;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CollisionModifier;
import ac.cult.cultac.events.packets.PacketWorldBorder;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** The collision probe's block-gather memo never serves a gather while the border diameter moves on the wall clock. */
class CollisionGatherMemoTest {
    @Test
    void aBorderMovingOnTheWallClockKeepsTheGatherMemoClosed() throws Exception {
        assertEquals(Set.of("open, held"), states(border -> {}));
        long now = System.currentTimeMillis();
        // A clock lerp whose edges pass the player within a few blocks during the run.
        assertEquals(Set.of("closed"), states(border -> {
            setField(border, "tickBasedLerp", false);
            setField(border, "oldDiameter", 4.0D);
            setField(border, "newDiameter", 40.0D);
            setField(border, "startTime", now);
            setField(border, "endTime", now + 2_000L);
        }));
        // A still border with a zero lerp span whose start the clock is behind.
        assertEquals(Set.of("open, not held"), states(border -> {
            setField(border, "tickBasedLerp", false);
            setField(border, "oldDiameter", 40.0D);
            setField(border, "newDiameter", 40.0D);
            setField(border, "startTime", now + 3_600_000L);
            setField(border, "endTime", now + 3_600_000L);
        }));
    }

    /** The memo's state at every candidate's engine checks over a few seeded scenarios. */
    private static Set<String> states(Consumer<PacketWorldBorder> border) throws Exception {
        Field open = CollisionModifier.class.getDeclaredField("gatherMemoOpen");
        open.setAccessible(true);
        Field valid = CollisionModifier.class.getDeclaredField("gatherMemoValid");
        valid.setAccessible(true);
        Field probe = SimulationProcessor.class.getDeclaredField("collisionProbe");
        probe.setAccessible(true);
        Set<String> states = new TreeSet<>();
        for (long seed = 0; seed < 8; seed++) {
            try (var f = new JavaTickFixture()) {
                border.accept(f.player.checkManager.getWorldBorder());
                SimulationProcessor processor = f.player.checkManager.getSimulationProcessor();
                CollisionModifier memo = (CollisionModifier) probe.get(processor);
                List<EngineCheck> checks = checks(processor);
                for (int i = 0; i < checks.size(); i++) {
                    if (!(checks.get(i) instanceof NoFall)) continue;
                    EngineCheck noFall = checks.get(i);
                    checks.set(i, (player, result, lastResult) -> {
                        try {
                            if (!open.getBoolean(memo)) states.add("closed");
                            else states.add(valid.getBoolean(memo) ? "open, held" : "open, not held");
                        } catch (IllegalAccessException e) {
                            throw new IllegalStateException(e);
                        }
                        noFall.handleResult(player, result, lastResult);
                    });
                }
                PredictionScenarios.run(f, seed, result -> {});
            }
        }
        return states;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<EngineCheck> checks(SimulationProcessor processor) throws Exception {
        Field flagger = SimulationProcessor.class.getDeclaredField("flagger");
        flagger.setAccessible(true);
        Object runner = flagger.get(processor);
        Field checks = PredCheckRunner.class.getDeclaredField("checks");
        checks.setAccessible(true);
        return (List<EngineCheck>) checks.get(runner);
    }
}
