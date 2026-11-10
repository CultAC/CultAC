package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.prediction.OffsetHandler;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SuperDebug;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.checks.verdict.FlagOutcome;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A flagged tick's Cult Log is built only when an alert, verbose line or punishment command shows its ID. */
class SuperDebugIdentifierTest {
    private static final Vec3 START = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);
    private static final Vec3 RESTING = new Vec3(0.0, -0.0784000015258789, 0.0);
    private static final Vec3 TOO_FAR = new Vec3(0.9, JavaTickFixture.FLOOR_Y, 0.5);

    @Test
    void aFlagNobodySeesBuildsNoLog() throws Exception {
        try (JavaTickFixture f = new JavaTickFixture()) {
            f.place(START, RESTING);
            int before = SimulationProcessor.nextDebugIdentifier();
            PredictionResult result = f.tick(TOO_FAR, true);
            int after = SimulationProcessor.nextDebugIdentifier();

            assertTrue(result.getFlagSeverity() > 0.0, JavaTickFixture.describe(result));
            assertTrue(f.player.checkManager.getCheck(OffsetHandler.class).violations > 0);
            assertTrue(result.isLogOffered());
            assertEquals(0, result.getIdentifier());
            assertEquals(before % SuperDebug.FLAG_SIZE + 1, after, "no identifier, so no log, was built");
        }
    }

    @Test
    void aFlagStaffSeeIsLoggedUnderTheIdentifierTheyAreShown() throws Exception {
        try (JavaTickFixture f = new JavaTickFixture();
                AutoCloseable staff = CultLogViewers.watch(f.player)) {
            f.place(START, RESTING);
            PredictionResult result = f.tick(TOO_FAR, true);

            assertNotEquals(0, result.getIdentifier(), JavaTickFixture.describe(result));
            String log = SuperDebug.getFlag(result.getIdentifier());
            assertNotNull(log);
            assertTrue(log.startsWith("Cult Version: "), log);
            assertTrue(log.contains("==================== flags =================="), log);
        }
    }

    @Test
    void aFlagLeftToAVerdictIsLoggedWhileItsAlertCanStillShowTheIdentifier() throws Exception {
        try (JavaTickFixture f = new JavaTickFixture();
                AutoCloseable staff = CultLogViewers.watch(f.player)) {
            List<FlagOutcome> verdicts = new ArrayList<>();
            f.player.setFlagArbiter((check, verbose, violations, outcome) -> verdicts.add(outcome));
            f.place(START, RESTING);
            PredictionResult result = f.tick(TOO_FAR, true);

            assertFalse(verdicts.isEmpty(), "the flags wait for a verdict");
            assertNotEquals(0, result.getIdentifier(), JavaTickFixture.describe(result));
            assertNotNull(SuperDebug.getFlag(result.getIdentifier()));
            for (FlagOutcome verdict : verdicts) verdict.commit();
        }
    }

    @Test
    void anIdentifierFirstShownAfterThePredictionHasNoLog() throws Exception {
        try (JavaTickFixture f = new JavaTickFixture()) {
            f.place(START, RESTING);
            PredictionResult result = f.tick(TOO_FAR, true);
            int before = SimulationProcessor.nextDebugIdentifier();

            assertEquals(0, result.shownIdentifier(), "the state the log reads has moved on");
            assertEquals(before % SuperDebug.FLAG_SIZE + 1, SimulationProcessor.nextDebugIdentifier());
        }
    }

    @Test
    void aPlayerExemptFromEveryCheckThatShowsTheIdentifierGetsNoLog() throws Exception {
        List<String> judged;
        try (JavaTickFixture f = new JavaTickFixture()) {
            f.place(START, RESTING);
            judged = flags(f.tick(TOO_FAR, true));
        }
        try (JavaTickFixture f = new JavaTickFixture();
                AutoCloseable staff = CultLogViewers.watch(f.player)) {
            for (Check check : f.player.checkManager.getAllChecks()) exempt(check);
            f.place(START, RESTING);
            int before = SimulationProcessor.nextDebugIdentifier();
            PredictionResult result = f.tick(TOO_FAR, true);
            int after = SimulationProcessor.nextDebugIdentifier();

            assertEquals(judged, flags(result), "the prediction is judged the same");
            assertFalse(result.isLogOffered());
            assertEquals(0, result.getIdentifier());
            assertEquals(before % SuperDebug.FLAG_SIZE + 1, after, "no identifier, so no log, was built");
            assertEquals(0.0, f.player.checkManager.getCheck(OffsetHandler.class).violations);
        }
    }

    @Test
    void anExemptionFromSimulationAloneStillOffersTheLogToTheOtherFlaggedChecks() throws Exception {
        try (JavaTickFixture f = new JavaTickFixture()) {
            exempt(f.player.checkManager.getCheck(OffsetHandler.class));
            f.place(START, RESTING);
            PredictionResult result = f.tick(TOO_FAR, true);

            assertFalse(result.getFlags().isEmpty(), JavaTickFixture.describe(result));
            assertTrue(result.isLogOffered());
        }
    }

    private static void exempt(Check check) throws Exception {
        JavaTickFixture.setField(Check.class, check, "exemptPermission", true);
    }

    private static List<String> flags(PredictionResult result) {
        return result.getFlags().stream()
                .map(flag -> flag.getCheck().getCheckName() + " " + flag.getSeverity() + " "
                        + flag.getVerbose().getString())
                .toList();
    }
}
