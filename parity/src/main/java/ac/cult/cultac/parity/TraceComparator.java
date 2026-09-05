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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/** Event-by-event comparator for packet and semantic-state JSONL traces. */
public final class TraceComparator {
    /** Values explicitly allowed to vary between isolated JVMs. */
    public static final Set<String> NORMALIZED_FIELDS = Set.of(
            "sequence",
            "timestamp",
            "wallClockMillis",
            "wallClockNanos",
            "threadId",
            "threadName",
            "classLoaderId",
            "connectionId",
            "localPort",
            "remotePort",
            "lastViolationTime",
            "timeJoined",
            "lastJoinedWorld",
            "creationTime",
            "lastActivityTime"
    );

    private TraceComparator() {
    }

    public record Difference(
            int eventIndex,
            String path,
            String baseline,
            String current,
            String reason
    ) {
    }

    public record Result(
            boolean equal,
            boolean packetStimuliEqual,
            boolean hasUnexpectedFlagsOrCorrections,
            int baselineEventCount,
            int currentEventCount,
            Difference firstDifference,
            List<String> baselineStimuli,
            List<String> currentStimuli,
            List<String> differences
    ) {
        public Result {
            baselineStimuli = List.copyOf(baselineStimuli);
            currentStimuli = List.copyOf(currentStimuli);
            differences = List.copyOf(differences);
        }
    }

    public static Result compare(
            Path baselineTrace,
            Path currentTrace,
            Map<String, CheckInventory.ClassMapping> mappings
    ) throws IOException {
        List<JsonObject> baseline = read(baselineTrace);
        List<JsonObject> current = read(currentTrace);
        List<String> baselineStimuli = stimuli(baseline, "baseline", mappings);
        List<String> currentStimuli = stimuli(current, "current", mappings);
        boolean packetEqual = baselineStimuli.equals(currentStimuli);
        List<String> differences = new ArrayList<>();
        Difference first = null;

        if (!packetEqual) {
            first = new Difference(
                    firstListDifferenceIndex(baselineStimuli, currentStimuli),
                    "stimuli",
                    listValue(baselineStimuli, firstListDifferenceIndex(baselineStimuli, currentStimuli)),
                    listValue(currentStimuli, firstListDifferenceIndex(baselineStimuli, currentStimuli)),
                    "normalized client/server stimulus diverged before semantic comparison"
            );
            differences.add(first.reason() + ": " + first.baseline() + " vs " + first.current());
        }

        int common = Math.min(baseline.size(), current.size());
        for (int index = 0; index < common; index++) {
            JsonElement left = normalize(baseline.get(index), "baseline", mappings);
            JsonElement right = normalize(current.get(index), "current", mappings);
            if (!left.equals(right)) {
                Difference difference = firstJsonDifference(left, right, index, "$");
                if (first == null) {
                    first = difference;
                }
                differences.add("event " + index + " " + difference.path() + ": "
                        + difference.baseline() + " vs " + difference.current());
                break;
            }
        }
        if (first == null && baseline.size() != current.size()) {
            int index = common;
            first = new Difference(
                    index,
                    "$",
                    String.valueOf(baseline.size()),
                    String.valueOf(current.size()),
                    "trace event counts differ"
            );
            differences.add(first.reason());
        }

        boolean unexpected = hasUnexpectedOutcome(baseline) || hasUnexpectedOutcome(current);
        return new Result(
                first == null,
                packetEqual,
                unexpected,
                baseline.size(),
                current.size(),
                first,
                baselineStimuli,
                currentStimuli,
                differences
        );
    }

    public static List<JsonObject> read(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("trace file is missing: " + path);
        }
        List<JsonObject> result = new ArrayList<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(path)) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            try {
                result.add(JsonParser.parseString(line).getAsJsonObject());
            } catch (RuntimeException exception) {
                throw new IOException("invalid JSONL trace at " + path + ":" + lineNumber, exception);
            }
        }
        return List.copyOf(result);
    }

    public static boolean hasUnexpectedFlagsOrCorrections(Path path) throws IOException {
        return hasUnexpectedFlagsOrCorrections(read(path));
    }

    public static boolean hasUnexpectedFlagsOrCorrections(List<JsonObject> events) {
        return hasUnexpectedOutcome(events);
    }

    /** Returns stable keys that have at least one instrumented invocation event. */
    public static Set<String> invokedStableKeys(List<JsonObject> events) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonObject event : events) {
            if (!string(event, "kind").equals("invoke")) {
                continue;
            }
            String stableKey = string(event, "stableKey");
            if (!stableKey.isBlank()) {
                result.add(stableKey);
            }
        }
        return Set.copyOf(result);
    }

    public static JsonElement normalize(
            JsonElement value,
            String side,
            Map<String, CheckInventory.ClassMapping> mappings
    ) {
        return normalize(value, side, mappings, true);
    }

    private static JsonElement normalize(
            JsonElement value,
            String side,
            Map<String, CheckInventory.ClassMapping> mappings,
            boolean rootObject
    ) {
        if (value == null || value.isJsonNull()) {
            return JsonParser.parseString("null");
        }
        if (value.isJsonArray()) {
            JsonArray output = new JsonArray();
            for (JsonElement child : value.getAsJsonArray()) {
                output.add(normalize(child, side, mappings, false));
            }
            return output;
        }
        if (value.isJsonObject()) {
            JsonObject output = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                if (isNormalizedField(entry.getKey(), rootObject)) {
                    continue;
                }
                JsonElement normalized = normalize(entry.getValue(), side, mappings, false);
                if (normalized.isJsonPrimitive() && normalized.getAsJsonPrimitive().isString()
                        && (entry.getKey().toLowerCase().contains("class")
                        || entry.getKey().equals("checkClass")
                        || entry.getKey().equals("runtimeClass"))) {
                    normalized = normalizeClassName(normalized.getAsString(), side, mappings);
                }
                output.add(entry.getKey(), normalized);
            }
            return output;
        }
        return value.deepCopy();
    }

    private static List<String> stimuli(
            List<JsonObject> events,
            String side,
            Map<String, CheckInventory.ClassMapping> mappings
    ) {
        List<String> result = new ArrayList<>();
        for (JsonObject event : events) {
            String kind = string(event, "kind");
            if (!Set.of("packet", "stimulus", "packet-in", "packet-out", "serverbound", "clientbound")
                    .contains(kind)) {
                continue;
            }
            result.add(normalize(event, side, mappings).toString());
        }
        return List.copyOf(result);
    }

    private static boolean hasUnexpectedOutcome(List<JsonObject> events) {
        for (JsonObject event : events) {
            String kind = string(event, "kind");
            if (Set.of("flag", "correction", "setback", "punishment", "packet-modification", "packetModification")
                    .contains(kind)) {
                Boolean unexpected = booleanValue(event, "unexpected");
                Boolean allowed = booleanValue(event, "allowed");
                Boolean expected = booleanValue(event, "expected");
                if (Boolean.TRUE.equals(unexpected)
                        || Boolean.FALSE.equals(allowed)
                        || Boolean.FALSE.equals(expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isNormalizedField(String name, boolean rootObject) {
        if (name.equals("sequence") || name.equals("timestamp")
                || name.equals("wallClockMillis") || name.equals("wallClockNanos")) {
            return rootObject;
        }
        return Set.of(
                "threadId", "threadName", "classLoaderId", "connectionId",
                "localPort", "remotePort", "lastViolationTime", "timeJoined",
                "lastJoinedWorld", "creationTime", "lastActivityTime"
        ).contains(name);
    }

    private static Difference firstJsonDifference(
            JsonElement left,
            JsonElement right,
            int eventIndex,
            String path
    ) {
        if (left == null || right == null || left.isJsonNull() || right.isJsonNull()) {
            return new Difference(eventIndex, path, String.valueOf(left), String.valueOf(right), "normalized event differs");
        }
        if (left.isJsonObject() && right.isJsonObject()) {
            Set<String> keys = new HashSet<>();
            keys.addAll(left.getAsJsonObject().keySet());
            keys.addAll(right.getAsJsonObject().keySet());
            for (String key : keys.stream().sorted().toList()) {
                JsonElement leftValue = left.getAsJsonObject().get(key);
                JsonElement rightValue = right.getAsJsonObject().get(key);
                if (leftValue == null || rightValue == null || !leftValue.equals(rightValue)) {
                    return firstJsonDifference(leftValue, rightValue, eventIndex, path + "." + key);
                }
            }
            return new Difference(eventIndex, path, left.toString(), right.toString(), "normalized event differs");
        }
        if (left.isJsonArray() && right.isJsonArray()) {
            int size = Math.max(left.getAsJsonArray().size(), right.getAsJsonArray().size());
            for (int index = 0; index < size; index++) {
                JsonElement leftValue = index < left.getAsJsonArray().size() ? left.getAsJsonArray().get(index) : null;
                JsonElement rightValue = index < right.getAsJsonArray().size() ? right.getAsJsonArray().get(index) : null;
                if (leftValue == null || rightValue == null || !leftValue.equals(rightValue)) {
                    return firstJsonDifference(leftValue, rightValue, eventIndex, path + "[" + index + "]");
                }
            }
            return new Difference(eventIndex, path, left.toString(), right.toString(), "normalized event differs");
        }
        return new Difference(eventIndex, path, left.toString(), right.toString(), "normalized event differs");
    }

    private static JsonElement normalizeClassName(
            String className,
            String side,
            Map<String, CheckInventory.ClassMapping> mappings
    ) {
        String normalized = className;
        for (Map.Entry<String, CheckInventory.ClassMapping> entry : mappings.entrySet()) {
            CheckInventory.ClassMapping mapping = entry.getValue();
            String source = side.equals("baseline") ? mapping.baselineClass() : mapping.currentClass();
            normalized = normalized.replace(source, "<shared:" + entry.getKey() + ">");
        }
        normalized = normalizeTransportClass(normalized);
        return JsonParser.parseString(stringJson(normalized));
    }

    /**
     * Keep the logical packet suffix visible.  The old implementation mapped
     * every PacketEvents/NMS class to one token, which could hide a genuinely
     * different packet stimulus.  A packet is transport-equivalent only when
     * its reviewed logical name is the same.
     */
    private static String normalizeTransportClass(String value) {
        if (value.startsWith("com.github.retrooper.packetevents.")) {
            return "<packet-events>" + value.substring("com.github.retrooper.packetevents".length());
        }
        if (value.startsWith("net.minecraft.network.protocol.")) {
            return "<nms-packet>" + value.substring("net.minecraft.network.protocol".length());
        }
        return value;
    }

    private static int firstListDifferenceIndex(List<String> left, List<String> right) {
        int limit = Math.min(left.size(), right.size());
        for (int index = 0; index < limit; index++) {
            if (!left.get(index).equals(right.get(index))) {
                return index;
            }
        }
        return limit;
    }

    private static String listValue(List<String> values, int index) {
        return index < values.size() ? values.get(index) : "<missing>";
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static Boolean booleanValue(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        try {
            return value.getAsBoolean();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String stringJson(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
