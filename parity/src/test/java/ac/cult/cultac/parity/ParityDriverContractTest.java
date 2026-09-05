package ac.cult.cultac.parity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParityDriverContractTest {
    private static final String AUDITED_BASELINE =
            "73a835d04f9bfbc76dc1d41c24ea73f3aec2280c";

    @Test
    void driverPinsTheAuditedBaselineAndFingerprintsUntrackedContents() throws Exception {
        Path driver = Path.of("scripts/run-cult-parity.sh");
        if (!Files.isRegularFile(driver)) {
            driver = Path.of("../scripts/run-cult-parity.sh");
        }

        String source = Files.readString(driver);
        assertTrue(source.contains("audited_baseline=\"" + AUDITED_BASELINE + "\""));
        assertTrue(source.contains("baseline_ref=\"$audited_baseline\""));
        assertTrue(source.contains("baseline_ref\" != \"$audited_baseline"));
        assertTrue(source.contains("rev-parse --verify \"${baseline_ref}^{commit}\""));
        assertTrue(source.contains("git ls-files --others --exclude-standard -z"));
        assertTrue(source.contains("git hash-object --no-filters"));
        assertTrue(source.contains("current-untracked-files.manifest"));
        assertTrue(source.contains("current-untracked-files.sha256"));
    }

    @Test
    void javaEntryPointRequiresExactAuditedBaselineMetadata() {
        assertDoesNotThrow(() -> CultParityMain.requireAuditedBaseline(
                AUDITED_BASELINE, AUDITED_BASELINE
        ));
        assertThrows(IllegalArgumentException.class, () ->
                CultParityMain.requireAuditedBaseline("HEAD", AUDITED_BASELINE));
        assertThrows(IllegalArgumentException.class, () ->
                CultParityMain.requireAuditedBaseline(AUDITED_BASELINE, ""));
    }

    @Test
    void driverRejectsAnyBaselineOtherThanTheAuditedCommitBeforeBuilding() throws Exception {
        Path driver = repositoryPath("scripts/run-cult-parity.sh");
        Path artifacts = Files.createTempDirectory("cult-parity-contract-");
        Process process = new ProcessBuilder(
                "bash",
                driver.toString(),
                "--baseline-ref", "HEAD",
                "--checks", "all",
                "--artifact-root", artifacts.toString()
        ).redirectErrorStream(true).start();

        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(2, process.exitValue(), output);
        assertTrue(output.contains("baseline is locked to audited commit"), output);
    }

    @Test
    void driverRejectsUnignoredArtifactRootInsideRepositoryBeforeCreatingIt() throws Exception {
        Path driver = repositoryPath("scripts/run-cult-parity.sh");
        Path repository = driver.getParent().getParent();
        Path artifacts = repository.resolve(
                "parity-provenance-contract-artifacts-" + System.nanoTime()
        );
        assertFalse(Files.exists(artifacts));

        Process process = new ProcessBuilder(
                "bash",
                driver.toString(),
                "--baseline-ref", AUDITED_BASELINE,
                "--checks", "all",
                "--artifact-root", artifacts.toString()
        ).redirectErrorStream(true).start();

        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(2, process.exitValue(), output);
        assertTrue(output.contains("inside the repository must be git-ignored"), output);
        assertFalse(Files.exists(artifacts));
    }

    @Test
    void twoZeroWorkflowsAndDedicatedHarnessTestsCoverTheParityBranch() throws Exception {
        String bedrock = Files.readString(repositoryPath(".github/workflows/bedrock-packet-replay.yml"));
        String mcp = Files.readString(repositoryPath(".github/workflows/mcp-client-smoketest.yml"));
        String harness = Files.readString(repositoryPath(".github/workflows/cult-parity-harness.yml"));

        assertTrue(bedrock.contains("- \"2.0\""));
        assertTrue(mcp.contains("- \"2.0\""));
        assertTrue(harness.contains("- \"2.0\""));
        assertTrue(harness.contains("./gradlew :parity:test"));
        assertTrue(harness.contains("fetch-depth: 0"));
    }

    private static Path repositoryPath(String relative) {
        Path path = Path.of(relative).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            path = Path.of("..").resolve(relative).toAbsolutePath().normalize();
        }
        return path;
    }
}
