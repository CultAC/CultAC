package ac.cult.cultac.parity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned four-path/three-profile scenario catalog validation. */
public final class ScenarioCatalog {
    public static final Set<String> REQUIRED_PATHS = Set.of(
            "legitimate",
            "definite-trigger",
            "boundary",
            "reset-recovery"
    );
    public static final Set<String> REQUIRED_PROFILES = Set.of(
            "zero-latency",
            "fixed-symmetric",
            "seeded-asymmetric-jitter"
    );

    private ScenarioCatalog() {
    }

    public record Validation(
            boolean valid,
            int schemaVersion,
            int checkCount,
            int scenarioCount,
            int expectedTrials,
            List<String> errors
    ) {
        public Validation {
            errors = List.copyOf(errors);
        }
    }

    public static JsonObject read(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("scenario catalog is missing: " + path);
        }
        try {
            return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("invalid scenario catalog JSON: " + path, exception);
        }
    }

    public static Validation validate(JsonObject catalog, Set<String> sharedKeys) {
        List<String> errors = new ArrayList<>();
        int schemaVersion = integer(catalog, "schemaVersion", errors);
        if (schemaVersion != 1) {
            errors.add("scenario catalog schemaVersion must be 1");
        }

        Set<String> profiles = new LinkedHashSet<>();
        JsonArray profileArray = array(catalog, "profiles", errors);
        if (profileArray != null) {
            for (JsonElement element : profileArray) {
                JsonObject profile = object(element, "profile", errors);
                if (profile == null) continue;
                String id = string(profile, "id");
                if (!profiles.add(id)) {
                    errors.add("duplicate network profile: " + id);
                }
                if (string(profile, "seed").isBlank()) {
                    errors.add("network profile must declare a seed: " + id);
                }
                if (array(profile, "schedule", errors) == null) {
                    errors.add("network profile has no schedule: " + id);
                } else {
                    validateSchedule(id, profile.getAsJsonArray("schedule"), errors);
                }
            }
        }
        Set<String> missingProfiles = new HashSet<>(REQUIRED_PROFILES);
        missingProfiles.removeAll(profiles);
        if (!missingProfiles.isEmpty()) {
            errors.add("missing required network profiles: " + missingProfiles);
        }
        Set<String> unexpectedProfiles = new HashSet<>(profiles);
        unexpectedProfiles.removeAll(REQUIRED_PROFILES);
        if (!unexpectedProfiles.isEmpty()) {
            errors.add("unexpected network profiles: " + unexpectedProfiles);
        }

        JsonArray checks = array(catalog, "checks", errors);
        Set<String> catalogKeys = new LinkedHashSet<>();
        Set<String> scenarioIds = new HashSet<>();
        int scenarioCount = 0;
        if (checks != null) {
            for (JsonElement element : checks) {
                JsonObject check = object(element, "check", errors);
                if (check == null) continue;
                String stableKey = string(check, "stableKey");
                if (stableKey.isBlank()) {
                    errors.add("scenario catalog check has blank stableKey");
                    continue;
                }
                if (!catalogKeys.add(stableKey)) {
                    errors.add("duplicate scenario catalog stableKey: " + stableKey);
                }
                JsonArray scenarios = array(check, "scenarios", errors);
                Set<String> paths = new LinkedHashSet<>();
                if (scenarios != null) {
                    for (JsonElement scenarioElement : scenarios) {
                        JsonObject scenario = object(scenarioElement, "scenario", errors);
                        if (scenario == null) continue;
                        scenarioCount++;
                        String path = string(scenario, "path");
                        if (!paths.add(path)) {
                            errors.add("duplicate path " + path + " for " + stableKey);
                        }
                        if (!REQUIRED_PATHS.contains(path)) {
                            errors.add("unexpected path " + path + " for " + stableKey);
                        }
                        for (String required : List.of("id", "actionTape", "validityGate", "expectedOutcome")) {
                            if (string(scenario, required).isBlank()) {
                                errors.add("scenario " + stableKey + "/" + path + " is missing " + required);
                            }
                        }
                        String scenarioId = string(scenario, "id");
                        if (!scenarioId.isBlank() && !scenarioIds.add(scenarioId)) {
                            errors.add("duplicate scenario id: " + scenarioId);
                        }
                        String gate = string(scenario, "validityGate");
                        if (!hasGate(gate, "scenario-valid")) {
                            errors.add("scenario " + stableKey + "/" + path
                                    + " has no client-side scenario-validity gate");
                        }
                        if (!hasGate(gate, "check-invoked")) {
                            errors.add("scenario " + stableKey + "/" + path
                                    + " has no target check-invoked gate");
                        }
                        validatePathContract(stableKey, path, scenario, gate, errors);
                    }
                }
                Set<String> missingPaths = new HashSet<>(REQUIRED_PATHS);
                missingPaths.removeAll(paths);
                if (!missingPaths.isEmpty()) {
                    errors.add("missing paths for " + stableKey + ": " + missingPaths);
                }
                if (scenarios == null || scenarios.size() != REQUIRED_PATHS.size()) {
                    errors.add("check " + stableKey + " must have exactly four path scenarios");
                }
            }
        }

        Set<String> missingChecks = new HashSet<>(sharedKeys);
        missingChecks.removeAll(catalogKeys);
        if (!missingChecks.isEmpty()) {
            errors.add("shared checks without scenarios: " + missingChecks);
        }
        Set<String> unexpectedChecks = new HashSet<>(catalogKeys);
        unexpectedChecks.removeAll(sharedKeys);
        if (!unexpectedChecks.isEmpty()) {
            errors.add("scenario catalog contains non-shared checks: " + unexpectedChecks);
        }
        int expectedTrials = sharedKeys.size() * REQUIRED_PATHS.size() * REQUIRED_PROFILES.size();
        return new Validation(
                errors.isEmpty(),
                schemaVersion,
                catalogKeys.size(),
                scenarioCount,
                expectedTrials,
                List.copyOf(errors)
        );
    }

    private static JsonArray array(JsonObject object, String name, List<String> errors) {
        JsonElement value = object == null ? null : object.get(name);
        if (value == null || !value.isJsonArray()) {
            errors.add("catalog field must be an array: " + name);
            return null;
        }
        return value.getAsJsonArray();
    }

    private static JsonObject object(JsonElement value, String kind, List<String> errors) {
        if (value == null || !value.isJsonObject()) {
            errors.add("catalog entry is not an object: " + kind);
            return null;
        }
        return value.getAsJsonObject();
    }

    private static int integer(JsonObject object, String name, List<String> errors) {
        try {
            return object.get(name).getAsInt();
        } catch (RuntimeException exception) {
            errors.add("catalog field must be an integer: " + name);
            return -1;
        }
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static boolean hasGate(String value, String required) {
        for (String token : value.split(";")) {
            if (token.trim().equalsIgnoreCase(required)) {
                return true;
            }
        }
        return false;
    }

    private static void validateSchedule(String profileId, JsonArray schedule, List<String> errors) {
        Map<String, long[]> expected = new HashMap<>();
        expected.put("zero-latency", new long[]{0, 0, 0, 0});
        expected.put("fixed-symmetric", new long[]{80, 0, 80, 0});
        expected.put("seeded-asymmetric-jitter", new long[]{37, 13, 83, 17});
        long[] expectedValues = expected.get(profileId);
        if (expectedValues == null || schedule.size() != 2) {
            errors.add("network profile must declare exactly inbound and outbound schedule entries: " + profileId);
            return;
        }
        Set<String> directions = new HashSet<>();
        for (JsonElement element : schedule) {
            JsonObject entry = object(element, "network schedule", errors);
            if (entry == null) continue;
            String direction = string(entry, "direction").toLowerCase();
            if (!directions.add(direction) || (!direction.equals("inbound") && !direction.equals("outbound"))) {
                errors.add("network profile has invalid or duplicate direction " + direction + ": " + profileId);
                continue;
            }
            try {
                long delay = entry.get("delayMillis").getAsLong();
                long jitter = entry.get("jitterMillis").getAsLong();
                int offset = direction.equals("inbound") ? 0 : 2;
                if (delay != expectedValues[offset] || jitter != expectedValues[offset + 1]) {
                    errors.add("network profile schedule does not match pinned " + profileId
                            + " values for " + direction);
                }
                if (delay < 0 || jitter < 0) {
                    errors.add("network profile schedule cannot be negative: " + profileId);
                }
            } catch (RuntimeException exception) {
                errors.add("network profile schedule has non-numeric delay/jitter: " + profileId);
            }
        }
        if (!directions.equals(Set.of("inbound", "outbound"))) {
            errors.add("network profile must contain one inbound and one outbound entry: " + profileId);
        }
    }

    private static void validatePathContract(
            String stableKey,
            String path,
            JsonObject scenario,
            String gate,
            List<String> errors
    ) {
        String expectedOutcome = string(scenario, "expectedOutcome");
        switch (path) {
            case "legitimate" -> {
                if (!expectedOutcome.equals("legitimate-control")) {
                    errors.add("legitimate scenario has the wrong expectedOutcome for " + stableKey);
                }
                for (String required : List.of("no-flag", "no-correction", "no-setback")) {
                    if (!hasGate(gate, required)) {
                        errors.add("legitimate scenario " + stableKey
                                + " must include gate " + required);
                    }
                }
            }
            case "definite-trigger" -> {
                if (!expectedOutcome.equals("definite-trigger")) {
                    errors.add("definite-trigger scenario has the wrong expectedOutcome for " + stableKey);
                }
                if (!hasGate(gate, "must-flag")) {
                    errors.add("definite-trigger scenario must include must-flag for " + stableKey);
                }
            }
            case "boundary" -> {
                if (!expectedOutcome.equals("boundary-comparison")) {
                    errors.add("boundary scenario has the wrong expectedOutcome for " + stableKey);
                }
                JsonObject boundary = scenario.has("boundary") && scenario.get("boundary").isJsonObject()
                        ? scenario.getAsJsonObject("boundary") : null;
                if (boundary == null || string(boundary, "below").isBlank()
                        || string(boundary, "above").isBlank()
                        || string(boundary, "assertion").isBlank()) {
                    errors.add("boundary scenario must declare below, above, and assertion for " + stableKey);
                }
            }
            case "reset-recovery" -> {
                if (!expectedOutcome.equals("reset-recovery")) {
                    errors.add("reset-recovery scenario has the wrong expectedOutcome for " + stableKey);
                }
                if (!hasGate(gate, "recovery-clean")) {
                    errors.add("reset-recovery scenario must include recovery-clean for " + stableKey);
                }
                if (string(scenario, "resetAssertion").isBlank()) {
                    errors.add("reset-recovery scenario must declare resetAssertion for " + stableKey);
                }
            }
            default -> {
                // The generic path checks above report unknown paths.
            }
        }
    }
}
