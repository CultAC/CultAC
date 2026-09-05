package ac.cult.cultac.parity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Fail-closed command line entry point for the pinned Cult parity run.
 *
 * <p>The live runner is deliberately a process contract instead of a class
 * path dependency. This keeps the baseline jar untouched and lets the same
 * harness drive the checked-in MCP/Paper workspace or a CI-owned runner. A
 * live command must create one result.json and both packet/semantic JSONL
 * traces in the supplied trial directory.</p>
 */
public final class CultParityMain {
    static final String AUDITED_BASELINE_COMMIT =
            "73a835d04f9bfbc76dc1d41c24ea73f3aec2280c";
    private static final String DEFAULT_MAPPING_FILE = "parity/config/check-equivalence-mappings.tsv";
    private static final String DEFAULT_CLASSIFICATION_FILE = "parity/config/check-classifications.tsv";
    private static final long COMMAND_TIMEOUT_MINUTES = 20;

    private CultParityMain() {
    }

    public static void main(String[] arguments) throws Exception {
        Configuration configuration = Configuration.parse(arguments);
        Report report = run(configuration);
        System.out.println(ParityJson.CODEC.toJson(report.json()));
        if (!report.pass()) {
            throw new IllegalStateException(
                            "cultParity failed: " + report.summary()
                            + ". See " + report.configuration().artifactRoot().resolve("final-report.json")
            );
        }
    }

    public static Report run(Configuration configuration) throws IOException {
        Objects.requireNonNull(configuration, "configuration");
        requireAuditedBaseline(configuration.baselineRef(), configuration.baselineCommit());
        Files.createDirectories(configuration.artifactRoot());
        Files.createDirectories(configuration.artifactRoot().resolve("inventory"));
        Files.createDirectories(configuration.artifactRoot().resolve("static"));
        Files.createDirectories(configuration.artifactRoot().resolve("comparisons"));

        List<String> setupErrors = new ArrayList<>();
        if (!configuration.checks().equals("all")) {
            setupErrors.add("final parity execution requires --checks all; selector was " + configuration.checks());
        }
        if (!Files.isRegularFile(configuration.baselineJar())) {
            setupErrors.add("baseline jar is missing: " + configuration.baselineJar());
        }
        if (!Files.isRegularFile(configuration.currentJar())) {
            setupErrors.add("current jar is missing: " + configuration.currentJar());
        }

        Map<String, CheckInventory.ClassMapping> mappings;
        try {
            mappings = CheckInventory.readMappings(configuration.mappingFile());
        } catch (IOException exception) {
            mappings = Map.of();
            setupErrors.add(exception.getMessage());
        }

        Map<String, CheckInventory.Classification> classifications;
        try {
            classifications = CheckInventory.readClassifications(configuration.classificationFile());
        } catch (IOException exception) {
            classifications = Map.of();
            setupErrors.add(exception.getMessage());
        }

        List<CheckInventory.SourceEntry> baselineSources = readSources(
                configuration.baselineSource(), "baseline", setupErrors
        );
        List<CheckInventory.SourceEntry> currentSources = readSources(
                configuration.currentSource(), "current", setupErrors
        );
        CheckInventory.RuntimeInventory baselineRuntime = CheckInventory.readRuntimeInventory(
                configuration.baselineInventory()
        );
        CheckInventory.RuntimeInventory currentRuntime = CheckInventory.readRuntimeInventory(
                configuration.currentInventory()
        );
        CheckInventory.Reconciliation reconciliation = CheckInventory.reconcile(
                baselineRuntime,
                currentRuntime,
                baselineSources,
                currentSources,
                mappings,
                classifications
        );
        writeJson(configuration.artifactRoot().resolve("frozen-shared-check-inventory.json"),
                inventoryJson(configuration, baselineRuntime, currentRuntime, reconciliation));

        List<String> inventoryErrors = new ArrayList<>();
        inventoryErrors.addAll(setupErrors);
        inventoryErrors.addAll(CheckInventory.validateReviewedInputs(
                mappings, classifications, baselineRuntime, currentRuntime,
                baselineSources, currentSources
        ));
        inventoryErrors.addAll(reconciliation.errors());
        inventoryErrors.addAll(reconciliation.ambiguous().stream()
                .map(entry -> "ambiguous shared-check inventory entry: " + entry.stableKey()
                        + " (" + entry.reason() + ")")
                .toList());

        List<StaticParity.Result> staticResults = new ArrayList<>();
        if (Files.isRegularFile(configuration.baselineJar())
                && Files.isRegularFile(configuration.currentJar())) {
            for (CheckInventory.Entry entry : reconciliation.shared()) {
                CheckInventory.ClassMapping mapping = mappings.get(entry.stableKey());
                String baselineClass = mapping == null
                        ? entry.runtimeClass()
                        : mapping.baselineClass();
                String currentClass = mapping == null
                        ? entry.runtimeClass()
                        : mapping.currentClass();
                try {
                    staticResults.add(StaticParity.compare(
                            entry.stableKey(),
                            baselineClass,
                            currentClass,
                            configuration.baselineJar(),
                            configuration.currentJar(),
                            mappings
                    ));
                } catch (RuntimeException | IOException exception) {
                    staticResults.add(new StaticParity.Result(
                            entry.stableKey(), baselineClass, currentClass, false,
                            "", "", List.of("static analyzer error: " + exception),
                            List.of(), List.of(), "static analyzer error: " + exception
                    ));
                }
            }
        }
        writeJsonLines(configuration.artifactRoot().resolve("static/static-equivalence.jsonl"),
                staticResults);

        ScenarioCatalog.Validation catalogValidation;
        try {
            catalogValidation = ScenarioCatalog.validate(
                    ScenarioCatalog.read(configuration.scenarioCatalog()),
                    reconciliation.shared().stream()
                            .map(CheckInventory.Entry::stableKey)
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
            );
        } catch (IOException | RuntimeException exception) {
            catalogValidation = new ScenarioCatalog.Validation(
                    false, -1, 0, 0, reconciliation.shared().size() * 12,
                    List.of("scenario catalog error: " + exception.getMessage())
            );
        }
        writeJson(configuration.artifactRoot().resolve("scenario-validation.json"), catalogValidation);

        List<TrialResult> trials = new ArrayList<>();
        if (catalogValidation.valid()) {
            JsonObject catalog = ScenarioCatalog.read(configuration.scenarioCatalog());
            trials.addAll(runTrials(configuration, catalog, reconciliation.shared(), mappings));
        }
        writeJsonLines(configuration.artifactRoot().resolve("comparisons/trials.jsonl"), trials);
        writePerCheckReports(configuration.artifactRoot().resolve("comparisons/check-reports"),
                reconciliation.shared(), staticResults, trials);
        writeJsonLines(configuration.artifactRoot().resolve("coverage-matrix.jsonl"),
                coverageMatrix(reconciliation.shared(), catalogValidation, trials));
        Report report = Report.build(
                configuration,
                reconciliation,
                staticResults,
                catalogValidation,
                trials,
                inventoryErrors
        );
        writeJson(configuration.artifactRoot().resolve("final-report.json"), report.json());
        writeJson(configuration.artifactRoot().resolve("provenance.json"), provenance(configuration));
        return report;
    }

    static void requireAuditedBaseline(String baselineRef, String baselineCommit) {
        if (!AUDITED_BASELINE_COMMIT.equals(baselineRef)
                || !AUDITED_BASELINE_COMMIT.equals(baselineCommit)) {
            throw new IllegalArgumentException(
                    "cultParity baseline ref and commit must both equal audited commit "
                            + AUDITED_BASELINE_COMMIT
            );
        }
    }

    private static List<CheckInventory.SourceEntry> readSources(
            Path root,
            String side,
            List<String> errors
    ) {
        try {
            List<CheckInventory.SourceEntry> result = CheckInventory.scanSource(root);
            if (result.isEmpty()) {
                errors.add(side + " source inventory is empty: " + root);
            }
            return result;
        } catch (IOException | RuntimeException exception) {
            errors.add(side + " source inventory failed: " + exception.getMessage());
            return List.of();
        }
    }

    private static List<TrialResult> coverageMatrix(
            List<CheckInventory.Entry> shared,
            ScenarioCatalog.Validation catalog,
            List<TrialResult> trials
    ) {
        if (catalog.valid()) {
            return List.copyOf(trials);
        }
        List<TrialResult> result = new ArrayList<>();
        String reason = "scenario catalog invalid; live path was not eligible: " + catalog.errors();
        for (CheckInventory.Entry entry : shared) {
            for (String path : ScenarioCatalog.REQUIRED_PATHS.stream().sorted().toList()) {
                for (String profile : ScenarioCatalog.REQUIRED_PROFILES.stream().sorted().toList()) {
                    result.add(TrialResult.invalid(entry.stableKey(), path, profile, reason));
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<TrialResult> runTrials(
            Configuration configuration,
            JsonObject catalog,
            List<CheckInventory.Entry> shared,
            Map<String, CheckInventory.ClassMapping> mappings
    ) throws IOException {
        Map<String, JsonObject> profiles = new LinkedHashMap<>();
        for (JsonElement element : catalog.getAsJsonArray("profiles")) {
            JsonObject profile = element.getAsJsonObject();
            profiles.put(profile.get("id").getAsString(), profile);
        }
        Map<String, JsonObject> checks = new LinkedHashMap<>();
        for (JsonElement element : catalog.getAsJsonArray("checks")) {
            JsonObject check = element.getAsJsonObject();
            checks.put(check.get("stableKey").getAsString(), check);
        }

        List<TrialResult> results = new ArrayList<>();
        for (CheckInventory.Entry entry : shared) {
            JsonObject check = checks.get(entry.stableKey());
            if (check == null) {
                continue;
            }
            for (JsonElement scenarioElement : check.getAsJsonArray("scenarios")) {
                JsonObject scenario = scenarioElement.getAsJsonObject();
                String path = scenario.get("path").getAsString();
                for (String profileId : ScenarioCatalog.REQUIRED_PROFILES.stream().sorted().toList()) {
                    JsonObject profile = profiles.get(profileId);
                    String seed = profile.get("seed").getAsString();
                    results.add(runTrial(
                            configuration, entry, scenario, profile, path, seed, mappings
                    ));
                }
            }
        }
        return List.copyOf(results);
    }

    private static void writePerCheckReports(
            Path directory,
            List<CheckInventory.Entry> shared,
            List<StaticParity.Result> staticResults,
            List<TrialResult> trials
    ) throws IOException {
        Map<String, StaticParity.Result> staticByKey = staticResults.stream()
                .collect(java.util.stream.Collectors.toMap(
                        StaticParity.Result::stableKey,
                        value -> value,
                        (left, right) -> right,
                        LinkedHashMap::new
                ));
        Map<String, List<TrialResult>> trialsByKey = new LinkedHashMap<>();
        for (TrialResult trial : trials) {
            trialsByKey.computeIfAbsent(trial.stableKey(), ignored -> new ArrayList<>()).add(trial);
        }
        for (CheckInventory.Entry entry : shared) {
            JsonObject report = new JsonObject();
            report.addProperty("schemaVersion", 1);
            report.add("inventory", ParityJson.CODEC.toJsonTree(entry));
            StaticParity.Result staticResult = staticByKey.get(entry.stableKey());
            report.add("static", staticResult == null
                    ? new JsonObject()
                    : ParityJson.CODEC.toJsonTree(staticResult));
            report.add("trials", ParityJson.CODEC.toJsonTree(
                    trialsByKey.getOrDefault(entry.stableKey(), List.of())
            ));
            writeJson(directory.resolve(safe(entry.stableKey()) + ".json"), report);
        }
    }

    private static TrialResult runTrial(
            Configuration configuration,
            CheckInventory.Entry entry,
            JsonObject scenario,
            JsonObject profile,
            String path,
            String seed,
            Map<String, CheckInventory.ClassMapping> mappings
    ) {
        String profileId = profile.get("id").getAsString();
        String safeKey = safe(entry.stableKey());
        Path trialRoot = configuration.artifactRoot().resolve("comparisons")
                .resolve(safeKey).resolve(path).resolve(profileId);
        Path baselineRoot = trialRoot.resolve("baseline");
        Path currentRoot = trialRoot.resolve("current");
        try {
            Files.createDirectories(baselineRoot);
            Files.createDirectories(currentRoot);
        } catch (IOException exception) {
            return TrialResult.invalid(entry.stableKey(), path, profileId, seed,
                    "cannot create trial directory: " + exception.getMessage());
        }

        if (configuration.liveCommand().isBlank()) {
            return TrialResult.invalid(entry.stableKey(), path, profileId, seed,
                    "no --live-command supplied; live execution is required");
        }

        String expectedOutcome = scenario.get("expectedOutcome").getAsString();
        SideResult baseline = runSide(configuration, entry, scenario, profile, path, seed, "baseline", baselineRoot);
        SideResult current = runSide(configuration, entry, scenario, profile, path, seed, "current", currentRoot);
        if (!baseline.valid() || !current.valid()) {
            return invalidSideResult(
                    entry, path, profileId, seed, expectedOutcome, baseline, current, mappings
            );
        }
        try {
            TraceComparator.Result packet = TraceComparator.compare(
                    baseline.packetTrace(), current.packetTrace(), mappings
            );
            TraceComparator.Result semantic = TraceComparator.compare(
                    baseline.semanticTrace(), current.semanticTrace(), mappings
            );
            boolean expected = expectedOutcome.equals(baseline.outcome())
                    && expectedOutcome.equals(current.outcome());
            boolean equal = packet.equal() && semantic.equal() && expected;
            String reason = equal ? "" : firstReason(packet, semantic, expected);
            // A comparison is only meaningful when both jars consumed the
            // same normalized client/server stimuli.  Keep the complete
            // traces and first difference in the row, but classify this trial
            // as invalid rather than presenting a transport divergence as a
            // semantic parity result.
            boolean stimulusEqual = packet.packetStimuliEqual();
            return new TrialResult(
                    entry.stableKey(), path, profileId, seed, stimulusEqual, equal,
                    baseline.outcome(), current.outcome(), expected,
                    stimulusEqual, packet.equal(), semantic.equal(),
                    packet.hasUnexpectedFlagsOrCorrections() || semantic.hasUnexpectedFlagsOrCorrections(),
                    reason,
                    baseline.semanticTrace().toString(), current.semanticTrace().toString(),
                    baseline.packetTrace().toString(), current.packetTrace().toString(),
                    packet.differences(), semantic.differences(),
                    packet.firstDifference(), semantic.firstDifference()
            );
        } catch (IOException | RuntimeException exception) {
            return TrialResult.invalid(entry.stableKey(), path, profileId,
                    "trace comparison invalidity: " + exception.getMessage());
        }
    }

    private static SideResult runSide(
            Configuration configuration,
            CheckInventory.Entry entry,
            JsonObject scenario,
            JsonObject profile,
            String path,
            String seed,
            String side,
            Path output
    ) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("CULT_PARITY_MODE", "live");
        environment.put("CULT_PARITY_SIDE", side);
        environment.put("CULT_PARITY_CHECK", entry.stableKey());
        environment.put("CULT_PARITY_PATH", path);
        environment.put("CULT_PARITY_PROFILE", profile.get("id").getAsString());
        environment.put("CULT_PARITY_SEED", seed);
        environment.put("CULT_PARITY_JAR", (side.equals("baseline")
                ? configuration.baselineJar() : configuration.currentJar()).toString());
        environment.put("CULT_PARITY_ARTIFACT_DIR", output.toString());
        environment.put("CULT_PARITY_ACTION_TAPE", scenario.get("actionTape").getAsString());
        environment.put("CULT_PARITY_VALIDITY_GATE", scenario.get("validityGate").getAsString());
        environment.put("CULT_PARITY_EXPECTED_OUTCOME", scenario.get("expectedOutcome").getAsString());
        environment.put("CULT_PARITY_NETWORK_SCHEDULE", profile.get("schedule").toString());
        environment.put("CULT_PARITY_PORT", Integer.toString(trialPort(entry.stableKey(), path, profile, side)));

        try {
            ProcessBuilder processBuilder = new ProcessBuilder("bash", "-lc", configuration.liveCommand())
                    .directory(configuration.repositoryRoot().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(output.resolve("runner.log").toFile());
            processBuilder.environment().putAll(environment);
            Process process = processBuilder.start();
            boolean finished = process.waitFor(COMMAND_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                return new SideResult(false, "live command timed out", "", null, null);
            }
            if (process.exitValue() != 0) {
                return new SideResult(false, "live command exit=" + process.exitValue(), "", null, null);
            }
            Path resultFile = output.resolve("result.json");
            if (!Files.isRegularFile(resultFile)) {
                return new SideResult(false, "runner did not write result.json", "", null, null);
            }
            JsonObject result = ParityJson.CODEC.fromJson(Files.readString(resultFile), JsonObject.class);
            boolean valid = booleanValue(result, "valid");
            String reason = stringValue(result, "reason");
            String outcome = stringValue(result, "outcome");
            Path semantic = resolveTrace(output, result, "semanticTrace", "semantic-trace.jsonl");
            Path packet = resolveTrace(output, result, "packetTrace", "packet-trace.jsonl");
            if (!valid) {
                return new SideResult(false, reason.isBlank() ? "runner validity gate failed" : reason,
                        outcome, semantic, packet);
            }
            if (outcome.isBlank()) {
                return new SideResult(false, "runner did not report outcome", outcome, semantic, packet);
            }
            if (semantic == null || !Files.isRegularFile(semantic)) {
                return new SideResult(false, "semantic trace is missing", outcome, semantic, packet);
            }
            if (packet == null || !Files.isRegularFile(packet)) {
                return new SideResult(false, "packet trace is missing", outcome, semantic, packet);
            }
            return new SideResult(true, reason, outcome, semantic, packet);
        } catch (IOException exception) {
            return new SideResult(false, "cannot start live command: " + exception.getMessage(), "", null, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new SideResult(false, "live command interrupted", "", null, null);
        }
    }

    private static Path resolveTrace(Path output, JsonObject result, String field, String fallback) {
        String value = stringValue(result, field);
        Path path = value.isBlank() ? output.resolve(fallback) : Path.of(value);
        return path.isAbsolute() ? path : output.resolve(path);
    }

    private static int trialPort(String stableKey, String path, JsonObject profile, String side) {
        String identity = stableKey + "|" + path + "|" + profile.get("id").getAsString() + "|" + side;
        return 28000 + Math.floorMod(identity.hashCode(), 1000);
    }

    private static String firstReason(
            TraceComparator.Result packet,
            TraceComparator.Result semantic,
            boolean expected
    ) {
        if (!packet.equal()) return "packet stimulus or trace mismatch: " + packet.differences();
        if (!semantic.equal()) return "semantic trace mismatch: " + semantic.differences();
        if (!expected) return "reported outcome does not match catalog expectedOutcome";
        return "unexpected flag/correction/setback outcome";
    }

    private static TrialResult invalidSideResult(
            CheckInventory.Entry entry,
            String path,
            String profile,
            String seed,
            String expectedOutcome,
            SideResult baseline,
            SideResult current,
            Map<String, CheckInventory.ClassMapping> mappings
    ) {
        TraceComparator.Result packet = compareIfAvailable(
                baseline.packetTrace(), current.packetTrace(), mappings
        );
        TraceComparator.Result semantic = compareIfAvailable(
                baseline.semanticTrace(), current.semanticTrace(), mappings
        );
        boolean unexpected = hasUnexpected(baseline.semanticTrace())
                || hasUnexpected(baseline.packetTrace())
                || hasUnexpected(current.semanticTrace())
                || hasUnexpected(current.packetTrace());
        String reason = "scenario/harness invalidity: baseline=" + baseline.reason()
                + "; current=" + current.reason();
        List<String> packetDifferences = packet == null ? List.of() : packet.differences();
        List<String> semanticDifferences = semantic == null ? List.of() : semantic.differences();
        if (!packetDifferences.isEmpty()) reason += "; packet=" + packetDifferences;
        if (!semanticDifferences.isEmpty()) reason += "; semantic=" + semanticDifferences;
        return new TrialResult(
                entry.stableKey(), path, profile, seed, false, false,
                baseline.outcome(), current.outcome(),
                expectedOutcome.equals(baseline.outcome()) && expectedOutcome.equals(current.outcome()),
                packet != null && packet.packetStimuliEqual(),
                packet != null && packet.equal(),
                semantic != null && semantic.equal(),
                unexpected,
                reason,
                tracePath(baseline.semanticTrace()), tracePath(current.semanticTrace()),
                tracePath(baseline.packetTrace()), tracePath(current.packetTrace()),
                packetDifferences, semanticDifferences,
                packet == null ? null : packet.firstDifference(),
                semantic == null ? null : semantic.firstDifference()
        );
    }

    private static TraceComparator.Result compareIfAvailable(
            Path baseline,
            Path current,
            Map<String, CheckInventory.ClassMapping> mappings
    ) {
        if (baseline == null || current == null
                || !Files.isRegularFile(baseline) || !Files.isRegularFile(current)) {
            return null;
        }
        try {
            return TraceComparator.compare(baseline, current, mappings);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static boolean hasUnexpected(Path trace) {
        if (trace == null || !Files.isRegularFile(trace)) return false;
        try {
            return TraceComparator.hasUnexpectedFlagsOrCorrections(trace);
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static String tracePath(Path path) {
        return path == null ? "" : path.toString();
    }

    private static JsonObject inventoryJson(
            Configuration configuration,
            CheckInventory.RuntimeInventory baseline,
            CheckInventory.RuntimeInventory current,
            CheckInventory.Reconciliation reconciliation
    ) throws IOException {
        JsonObject object = new JsonObject();
        object.addProperty("schemaVersion", 1);
        object.addProperty("baselineCommit", configuration.baselineCommit());
        object.addProperty("currentCommit", configuration.currentCommit());
        object.addProperty("baselineJar", configuration.baselineJar().toString());
        object.addProperty("currentJar", configuration.currentJar().toString());
        object.addProperty("baselineJarSha256", sha256(configuration.baselineJar()));
        object.addProperty("currentJarSha256", sha256(configuration.currentJar()));
        object.add("baselineRuntime", ParityJson.CODEC.toJsonTree(baseline));
        object.add("currentRuntime", ParityJson.CODEC.toJsonTree(current));
        object.add("shared", ParityJson.CODEC.toJsonTree(reconciliation.shared()));
        object.add("baselineOnly", ParityJson.CODEC.toJsonTree(reconciliation.baselineOnly()));
        object.add("currentOnly", ParityJson.CODEC.toJsonTree(reconciliation.currentOnly()));
        object.add("ambiguous", ParityJson.CODEC.toJsonTree(reconciliation.ambiguous()));
        object.add("disabled", ParityJson.CODEC.toJsonTree(reconciliation.disabled()));
        object.add("errors", ParityJson.CODEC.toJsonTree(reconciliation.errors()));
        return object;
    }

    private static JsonObject provenance(Configuration configuration) throws IOException {
        JsonObject object = new JsonObject();
        object.addProperty("schemaVersion", 1);
        object.addProperty("baselineRef", configuration.baselineRef());
        object.addProperty("baselineCommit", configuration.baselineCommit());
        object.addProperty("currentCommit", configuration.currentCommit());
        object.addProperty("baselineJar", configuration.baselineJar().toString());
        object.addProperty("currentJar", configuration.currentJar().toString());
        object.addProperty("baselineJarSha256", sha256(configuration.baselineJar()));
        object.addProperty("currentJarSha256", sha256(configuration.currentJar()));
        object.addProperty("repositoryRoot", configuration.repositoryRoot().toString());
        object.addProperty("scenarioCatalog", configuration.scenarioCatalog().toString());
        object.addProperty("scenarioCatalogSha256", sha256(configuration.scenarioCatalog()));
        object.addProperty("mappingFile", configuration.mappingFile().toString());
        object.addProperty("classificationFile", configuration.classificationFile().toString());
        object.addProperty("liveCommandConfigured", !configuration.liveCommand().isBlank());
        object.addProperty("javaVersion", System.getProperty("java.version", ""));
        try {
            JsonObject catalog = ScenarioCatalog.read(configuration.scenarioCatalog());
            JsonElement profiles = catalog.get("profiles");
            if (profiles != null) {
                object.add("networkProfiles", profiles.deepCopy());
            }
        } catch (IOException | RuntimeException ignored) {
            // The validation report carries the catalog error; provenance
            // remains writable so the failed run is still auditable.
        }
        return object;
    }

    private static void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, ParityJson.CODEC.toJson(value) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void writeJsonLines(Path path, List<?> values) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        StringBuilder output = new StringBuilder();
        for (Object value : values) {
            output.append(ParityJson.JSONL_CODEC.toJson(value)).append(System.lineSeparator());
        }
        Files.writeString(path, output.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String sha256(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception exception) {
            throw new IOException("cannot calculate SHA-256 for " + path, exception);
        }
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String stringValue(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static boolean booleanValue(JsonObject object, String field) {
        try {
            return object.get(field).getAsBoolean();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public record Configuration(
            String checks,
            String baselineRef,
            String baselineCommit,
            String currentCommit,
            Path artifactRoot,
            Path baselineJar,
            Path currentJar,
            Path baselineSource,
            Path currentSource,
            Path baselineInventory,
            Path currentInventory,
            Path scenarioCatalog,
            Path mappingFile,
            Path classificationFile,
            Path repositoryRoot,
            String liveCommand
    ) {
        public static Configuration parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if (!argument.startsWith("--")) {
                    throw new IllegalArgumentException("unexpected argument: " + argument);
                }
                String key = argument.substring(2);
                if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("missing value for --" + key);
                }
                values.put(key, args[++index]);
            }
            String root = values.getOrDefault("repository-root", Path.of(".").toAbsolutePath().normalize().toString());
            Path repository = Path.of(root).toAbsolutePath().normalize();
            Path artifact = requiredPath(values, "artifact-root");
            Path baselineJar = optionalPath(values.get("baseline-jar"), artifact.resolve("jars/baseline.jar"));
            Path currentJar = optionalPath(values.get("current-jar"), artifact.resolve("jars/current.jar"));
            Path baselineSource = optionalPath(values.get("baseline-source"), repository.resolve("baseline-source"));
            Path currentSource = optionalPath(values.get("current-source"), repository.resolve("common/src/main/java"));
            return new Configuration(
                    values.getOrDefault("checks", ""),
                    values.getOrDefault("baseline-ref", ""),
                    values.getOrDefault("baseline-commit", ""),
                    values.getOrDefault("current-commit", ""),
                    artifact,
                    baselineJar,
                    currentJar,
                    baselineSource,
                    currentSource,
                    optionalPath(values.get("baseline-inventory"), artifact.resolve("inventory/baseline.jsonl")),
                    optionalPath(values.get("current-inventory"), artifact.resolve("inventory/current.jsonl")),
                    requiredPath(values, "scenario-catalog"),
                    optionalPath(values.get("mapping-file"), repository.resolve(DEFAULT_MAPPING_FILE)),
                    optionalPath(values.get("classification-file"), repository.resolve(DEFAULT_CLASSIFICATION_FILE)),
                    repository,
                    values.getOrDefault("live-command", "")
            );
        }

        private static Path requiredPath(Map<String, String> values, String name) {
            String value = values.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("missing required --" + name);
            }
            return Path.of(value).toAbsolutePath().normalize();
        }

        private static Path optionalPath(String value, Path fallback) {
            return value == null || value.isBlank()
                    ? fallback.toAbsolutePath().normalize()
                    : Path.of(value).toAbsolutePath().normalize();
        }
    }

    public record SideResult(
            boolean valid,
            String reason,
            String outcome,
            Path semanticTrace,
            Path packetTrace
    ) {
    }

    public record TrialResult(
            String stableKey,
            String path,
            String profile,
            String seed,
            boolean valid,
            boolean equal,
            String baselineOutcome,
            String currentOutcome,
            boolean expectedOutcome,
            boolean stimulusEqual,
            boolean packetEqual,
            boolean semanticEqual,
            boolean unexpectedFlagsOrCorrections,
            String reason,
            String baselineSemanticTrace,
            String currentSemanticTrace,
            String baselinePacketTrace,
            String currentPacketTrace,
            List<String> packetDifferences,
            List<String> semanticDifferences,
            TraceComparator.Difference packetFirstDifference,
            TraceComparator.Difference semanticFirstDifference
    ) {
        public TrialResult {
            packetDifferences = List.copyOf(packetDifferences == null ? List.of() : packetDifferences);
            semanticDifferences = List.copyOf(semanticDifferences == null ? List.of() : semanticDifferences);
        }

        public static TrialResult invalid(String stableKey, String path, String profile, String reason) {
            return invalid(stableKey, path, profile, "", reason);
        }

        public static TrialResult invalid(
                String stableKey, String path, String profile, String seed, String reason
        ) {
            return new TrialResult(stableKey, path, profile, seed, false, false,
                    "", "", false, false, false, false, false, reason, "", "", "", "",
                    List.of(), List.of(), null, null);
        }
    }

    public record Report(
            Configuration configuration,
            int sharedChecks,
            int staticallyProvenChecks,
            int expectedLiveComparisons,
            int validLiveComparisons,
            int uncoveredChecks,
            int invalidTrials,
            int staticMismatches,
            int liveTraceMismatches,
            int unexpectedFlagsOrCorrections,
            int unclassifiedDifferences,
            int baselineOnlyChecks,
            int currentOnlyChecks,
            int disabledEntries,
            int ambiguousEntries,
            boolean pass,
            String summary
    ) {
        public static Report build(
                Configuration configuration,
                CheckInventory.Reconciliation reconciliation,
                List<StaticParity.Result> staticResults,
                ScenarioCatalog.Validation catalog,
                List<TrialResult> trials,
                List<String> inventoryErrors
        ) {
            int shared = reconciliation.shared().size();
            int expected = shared * ScenarioCatalog.REQUIRED_PATHS.size()
                    * ScenarioCatalog.REQUIRED_PROFILES.size();
            int staticMismatches = (int) staticResults.stream().filter(result -> !result.equivalent()).count();
            int proven = (int) staticResults.stream().filter(StaticParity.Result::equivalent).count();
            int invalid = (int) trials.stream().filter(result -> !result.valid()).count();
            int liveMismatches = (int) trials.stream()
                    .filter(result -> result.valid() && !result.equal()).count();
            int validComparisons = (int) trials.stream()
                    .filter(result -> result.valid() && result.equal()).count();
            int unexpected = (int) trials.stream()
                    .filter(TrialResult::unexpectedFlagsOrCorrections).count();
            // Baseline/current-only entries are accounted for in the frozen
            // inventory, but are not uncovered shared checks.  Uncovered is
            // reserved for shared entries missing a complete catalog.
            int uncovered = catalog.valid() ? 0 : shared;
            int unclassified = inventoryErrors.size()
                    + (int) trials.stream().filter(result -> result.reason().contains("unclassified")).count();
            boolean pass = shared == proven
                    && shared == staticResults.size()
                    && expected == validComparisons
                    && expected == trials.size()
                    && uncovered == 0
                    && invalid == 0
                    && staticMismatches == 0
                    && liveMismatches == 0
                    && unexpected == 0
                    && unclassified == 0
                    && catalog.valid()
                    && reconciliation.errors().isEmpty()
                    && inventoryErrors.isEmpty();
            String summary = "sharedChecks=" + shared
                    + " staticallyProvenChecks=" + proven
                    + " expectedLiveComparisons=" + expected
                    + " validLiveComparisons=" + validComparisons
                    + " uncoveredChecks=" + uncovered
                    + " invalidTrials=" + invalid
                    + " staticMismatches=" + staticMismatches
                    + " liveTraceMismatches=" + liveMismatches
                    + " unexpectedFlagsOrCorrections=" + unexpected
                    + " unclassifiedDifferences=" + unclassified;
            return new Report(configuration, shared, proven, expected, validComparisons,
                    uncovered, invalid, staticMismatches, liveMismatches, unexpected,
                    unclassified, reconciliation.baselineOnly().size(), reconciliation.currentOnly().size(),
                    reconciliation.disabled().size(), reconciliation.ambiguous().size(), pass, summary);
        }

        public JsonObject json() {
            JsonObject object = new JsonObject();
            object.addProperty("schemaVersion", 1);
            object.addProperty("sharedChecks", sharedChecks);
            object.addProperty("staticallyProvenChecks", staticallyProvenChecks);
            object.addProperty("expectedLiveComparisons", expectedLiveComparisons);
            object.addProperty("validLiveComparisons", validLiveComparisons);
            object.addProperty("uncoveredChecks", uncoveredChecks);
            object.addProperty("invalidTrials", invalidTrials);
            object.addProperty("staticMismatches", staticMismatches);
            object.addProperty("liveTraceMismatches", liveTraceMismatches);
            object.addProperty("unexpectedFlagsOrCorrections", unexpectedFlagsOrCorrections);
            object.addProperty("unclassifiedDifferences", unclassifiedDifferences);
            object.addProperty("baselineOnlyChecks", baselineOnlyChecks);
            object.addProperty("currentOnlyChecks", currentOnlyChecks);
            object.addProperty("disabledEntries", disabledEntries);
            object.addProperty("ambiguousEntries", ambiguousEntries);
            object.addProperty("pass", pass);
            object.addProperty("summary", summary);
            return object;
        }
    }
}
