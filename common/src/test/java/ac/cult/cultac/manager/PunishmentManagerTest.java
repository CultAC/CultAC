package ac.cult.cultac.manager;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.Check;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class PunishmentManagerTest {
    @BeforeClass
    public static void installConfig() {
        OfflineCultTestBootstrap.installConfig();
    }

    @Test
    public void sameMillisecondFlagsCountForEveryCheck() throws Exception {
        AtomicLong now = new AtomicLong(1_000);
        PunishmentManager manager = new PunishmentManager(null, now::get);
        Check first = check("First");
        Check second = check("Second");
        PunishGroup group = addGroup(manager, List.of(first, second), List.of(), 1_000);

        manager.handleViolation(first);
        manager.handleViolation(first);
        manager.handleViolation(second);

        assertEquals(3, group.violations.size());
        assertEquals(2, violationCount(manager, group, first));
        assertEquals(1, violationCount(manager, group, second));
    }

    @Test
    public void violationsExpireOnlyAfterTheConfiguredWindow() throws Exception {
        AtomicLong now = new AtomicLong(1_000);
        PunishmentManager manager = new PunishmentManager(null, now::get);
        Check check = check("First");
        PunishGroup group = addGroup(manager, List.of(check), List.of(), 1_000);
        manager.handleViolation(check);
        manager.handleViolation(check);

        now.set(2_000);
        manager.handleViolation(check);
        assertEquals(3, group.violations.size());

        now.set(2_001);
        manager.handleViolation(check);
        assertEquals(2, group.violations.size());
    }

    @Test
    public void duplicateTimestampsReachThresholdAndIntervalBoundaries() throws Exception {
        AtomicLong now = new AtomicLong(1_000);
        PunishmentManager manager = new PunishmentManager(null, now::get);
        Check check = check("First");
        ParsedCommand command = new ParsedCommand(2, 2, "[log]");
        addGroup(manager, List.of(check), List.of(command), 1_000);
        AtomicInteger rendered = new AtomicInteger();

        flag(manager, check, rendered);
        assertEquals(0, rendered.get());
        flag(manager, check, rendered);
        assertEquals(1, rendered.get());
        flag(manager, check, rendered);
        assertEquals(1, rendered.get());
        flag(manager, check, rendered);
        assertEquals(2, rendered.get());

        now.set(2_001);
        flag(manager, check, rendered);
        assertEquals(2, rendered.get());
        flag(manager, check, rendered);
        assertEquals(3, rendered.get());
    }

    @Test
    public void zeroIntervalCommandStillExecutesOnlyOnce() throws Exception {
        AtomicLong now = new AtomicLong(1_000);
        PunishmentManager manager = new PunishmentManager(null, now::get);
        Check check = check("First");
        addGroup(manager, List.of(check), List.of(new ParsedCommand(2, 0, "[log]")), 1_000);
        AtomicInteger rendered = new AtomicInteger();

        flag(manager, check, rendered);
        flag(manager, check, rendered);
        flag(manager, check, rendered);
        assertEquals(1, rendered.get());
        now.set(2_001);
        flag(manager, check, rendered);
        flag(manager, check, rendered);
        assertEquals(1, rendered.get());
    }

    private static void flag(PunishmentManager manager, Check check, AtomicInteger rendered) {
        manager.handleViolation(check);
        manager.handleAlert(null, () -> {
            rendered.incrementAndGet();
            return "test";
        }, check);
    }

    private static Check check(String name) {
        Check check = mock(Check.class);
        when(check.getDisplayName()).thenReturn(name);
        when(check.getDescription()).thenReturn("");
        when(check.getStableKey()).thenReturn(name);
        when(check.isLastFlagStoredBinaryVerbose()).thenReturn(true);
        return check;
    }

    @SuppressWarnings("unchecked")
    private static PunishGroup addGroup(PunishmentManager manager, List<Check> checks,
                                        List<ParsedCommand> commands, int window) throws Exception {
        PunishGroup group = new PunishGroup(List.copyOf(checks), commands, window);
        Field field = PunishmentManager.class.getDeclaredField("groups");
        field.setAccessible(true);
        ((List<PunishGroup>) field.get(manager)).add(group);
        Field format = PunishmentManager.class.getDeclaredField("alertString");
        format.setAccessible(true);
        format.set(manager, "");
        return group;
    }

    private static int violationCount(PunishmentManager manager, PunishGroup group, Check check) throws Exception {
        Method method = PunishmentManager.class.getDeclaredMethod("getViolations", PunishGroup.class, Check.class);
        method.setAccessible(true);
        return (int) method.invoke(manager, group, check);
    }
}
