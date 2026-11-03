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
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Cult Log of a flagged tick is built only when a check that shows its identifier can flag. */
class SuperDebugIdentifierTest {
    private static final Vec3 START = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);
    private static final Vec3 RESTING = new Vec3(0.0, -0.0784000015258789, 0.0);
    private static final Vec3 TOO_FAR = new Vec3(0.9, JavaTickFixture.FLOOR_Y, 0.5);

    @Test
    void aFlaggedTickStoresItsLogUnderTheIdentifierItsFlagShows() throws Exception {
        try (JavaTickFixture f = new JavaTickFixture()) {
            f.place(START, RESTING);
            PredictionResult result = f.tick(TOO_FAR, true);

            assertTrue(result.getFlagSeverity() > 0.0, JavaTickFixture.describe(result));
            assertNotEquals(0, result.getIdentifier());
            assertTrue(f.player.checkManager.getCheck(OffsetHandler.class).violations > 0);
            String log = SuperDebug.getFlag(result.getIdentifier());
            assertNotNull(log);
            assertTrue(log.startsWith("Cult Version: "), log);
            assertTrue(log.contains("==================== flags =================="), log);
        }
    }

    @Test
    void aPlayerExemptFromEveryCheckThatShowsTheIdentifierGetsNoLog() throws Exception {
        List<String> judged;
        try (JavaTickFixture f = new JavaTickFixture()) {
            f.place(START, RESTING);
            judged = flags(f.tick(TOO_FAR, true));
        }
        try (JavaTickFixture f = new JavaTickFixture()) {
            for (Check check : f.player.checkManager.getAllChecks()) exempt(check);
            f.place(START, RESTING);
            int before = SimulationProcessor.nextDebugIdentifier();
            PredictionResult result = f.tick(TOO_FAR, true);
            int after = SimulationProcessor.nextDebugIdentifier();

            assertEquals(judged, flags(result), "the prediction is judged the same");
            assertEquals(0, result.getIdentifier());
            assertEquals(before % SuperDebug.FLAG_SIZE + 1, after, "no identifier, so no log, was built");
            assertEquals(0.0, f.player.checkManager.getCheck(OffsetHandler.class).violations);
        }
    }

    @Test
    void anExemptionFromSimulationAloneKeepsTheLogForTheOtherFlaggedChecks() throws Exception {
        try (JavaTickFixture f = new JavaTickFixture()) {
            exempt(f.player.checkManager.getCheck(OffsetHandler.class));
            f.place(START, RESTING);
            PredictionResult result = f.tick(TOO_FAR, true);

            assertFalse(result.getFlags().isEmpty(), JavaTickFixture.describe(result));
            assertNotEquals(0, result.getIdentifier());
            assertNotNull(SuperDebug.getFlag(result.getIdentifier()));
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
