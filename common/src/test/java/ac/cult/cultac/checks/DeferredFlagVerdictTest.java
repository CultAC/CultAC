package ac.cult.cultac.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.checks.verdict.FlagArbiter;
import ac.cult.cultac.checks.verdict.FlagOutcome;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;
import ac.grim.grimac.api.event.events.FlagEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

public class DeferredFlagVerdictTest {
    private static final Vec3 SAFE = new Vec3(4, 64, 2);

    @Test
    public void flagWithoutArbiterAppliesAtOnce() {
        withPlayer((player, check) -> {
            assertTrue(check.flagWithSetback("offset"));

            assertEquals(1, check.getViolations());
            assertTrue(check.getLastViolationTime() > 0);
            assertSetbackTo(player, SAFE);
        });
    }

    @Test
    public void flagBelowSetbackVlSendsNoSetback() {
        withPlayer(1, (player, check) -> {
            assertTrue(check.flagWithSetback("offset"));

            assertEquals(1, check.getViolations());
            assertNull(player.getSetbackTeleportUtil().getRequiredSetBack());
        });
    }

    @Test
    public void deferredFlagSetsBackBeforeItsVerdict() {
        withPlayer((player, check) -> {
            RecordingArbiter arbiter = install(player);

            assertTrue(check.flagWithSetback("offset"));

            assertEquals(0, check.getViolations());
            assertEquals(0, check.getLastViolationTime());
            assertEquals(1, arbiter.prospective.getFirst());
            assertEquals("offset", arbiter.verbose.getFirst());
            assertSetbackTo(player, SAFE);
        });
    }

    @Test
    public void committedVerdictAppliesTheViolation() {
        withPlayer((player, check) -> {
            RecordingArbiter arbiter = install(player);
            check.flagWithSetback("offset");

            arbiter.outcomes.getFirst().commit();

            assertEquals(1, check.getViolations());
            assertTrue(check.getLastViolationTime() > 0);
        });
    }

    @Test
    public void discardedVerdictKeepsTheSetback() {
        withPlayer((player, check) -> {
            RecordingArbiter arbiter = install(player);
            check.flagWithSetback("offset");

            arbiter.outcomes.getFirst().discard();

            assertEquals(0, check.getViolations());
            assertEquals(0, check.getLastViolationTime());
            assertFalse(check.shouldSetback());
            assertSetbackTo(player, SAFE);
        });
    }

    @Test
    public void declinedDeferralCommitsAtOnce() {
        withPlayer((player, check) -> {
            RecordingArbiter arbiter = install(player);
            arbiter.accept = false;

            assertTrue(check.flagWithSetback("offset"));

            assertEquals(1, check.getViolations());
            assertSetbackTo(player, SAFE);
        });
    }

    @Test
    public void pendingFlagsCountTowardProspectiveViolations() {
        withPlayer((player, check) -> {
            RecordingArbiter arbiter = install(player);

            check.flag("a");
            check.flag("b");

            assertEquals(List.of(1.0, 2.0), arbiter.prospective);
            arbiter.outcomes.get(0).commit();
            check.flag("c");
            assertEquals(3.0, arbiter.prospective.get(2));
        });
    }

    @Test
    public void pendingFlagsCountTowardTheSetbackVl() {
        withPlayer(1, (player, check) -> {
            RecordingArbiter arbiter = install(player);

            assertTrue(check.flagWithSetback("a"));
            assertNull(player.getSetbackTeleportUtil().getRequiredSetBack());
            assertTrue(check.flagWithSetback("b"));
            assertSetbackTo(player, SAFE);

            arbiter.outcomes.get(0).discard();
            arbiter.outcomes.get(1).discard();
            assertFalse(check.shouldSetback());
        });
    }

    @Test
    public void cancelledFlagIsNotDeferred() {
        withPlayer((player, check) -> {
            RecordingArbiter arbiter = install(player);
            FlagEvent.Channel flags = CultAPI.INSTANCE.getEventBus().get(FlagEvent.class);
            FlagEvent.SupplierHandler cancelAll = (user, flagged, verbose, cancelled) -> true;

            flags.onFlagSupplier(CultAPI.INSTANCE.getGrimPlugin(), cancelAll, 0, false);
            try {
                assertFalse(check.flagWithSetback("a"));
            } finally {
                flags.unsubscribe(cancelAll);
            }

            assertTrue(arbiter.outcomes.isEmpty());
            assertFalse(check.shouldSetback());
            assertNull(player.getSetbackTeleportUtil().getRequiredSetBack());
        });
    }

    private static void assertSetbackTo(CultPlayer player, Vec3 position) {
        var required = player.getSetbackTeleportUtil().getRequiredSetBack();
        assertNotNull(required);
        assertFalse(required.isPlugin());
        assertTrue(player.getSetbackTeleportUtil().isPendingSetback());
        Vec3 location = required.getTeleportData().getLocation();
        assertEquals(position.x, location.x, 1.0E-9);
        assertEquals(position.z, location.z, 1.0E-9);
    }

    private static RecordingArbiter install(CultPlayer player) {
        RecordingArbiter arbiter = new RecordingArbiter();
        player.setFlagArbiter(arbiter);
        return arbiter;
    }

    private interface Body {
        void run(CultPlayer player, Check check);
    }

    private static void withPlayer(Body body) {
        withPlayer(0, body);
    }

    private static void withPlayer(double setbackVl, Body body) {
        OfflinePlayers.withSafePlayer(SAFE, player -> {
            Check check = new Check(
                    player,
                    CheckInfo.builder()
                            .name("VerdictProbe")
                            .stableKey("verdict_probe")
                            .setback(setbackVl)
                            .build());
            body.run(player, check);
        });
    }

    private static final class RecordingArbiter implements FlagArbiter {
        final List<FlagOutcome> outcomes = new ArrayList<>();
        final List<Double> prospective = new ArrayList<>();
        final List<String> verbose = new ArrayList<>();
        boolean accept = true;

        @Override
        public boolean defer(
                @NotNull Check check,
                @NotNull Supplier<String> verbose,
                double prospectiveViolations,
                @NotNull FlagOutcome outcome) {
            if (!accept) return false;
            this.outcomes.add(outcome);
            this.prospective.add(prospectiveViolations);
            this.verbose.add(verbose.get());
            return true;
        }
    }
}
