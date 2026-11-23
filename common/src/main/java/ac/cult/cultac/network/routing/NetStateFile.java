package ac.cult.cultac.network.routing;

import static ac.cult.cultac.manager.config.BaseConfigManager.CHECKS_OFF_KEY;
import static ac.cult.cultac.manager.config.BaseConfigManager.DORMANT_KEY;
import static ac.cult.cultac.manager.config.BaseConfigManager.OBSERVE_ONLY_KEY;
import static ac.cult.cultac.manager.config.BaseConfigManager.PAUSE_PUNISHMENTS_KEY;

import ac.cult.cultac.checks.info.CheckCatalog;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.manager.config.update.ConfigPatcher;
import ac.cult.cultac.manager.config.update.ConfigPatcher.NodePosition;
import ac.cult.cultac.manager.config.update.ConfigPatcher.PendingChange;
import ac.cult.cultac.network.routing.NetSettings.Record;
import ac.cult.cultac.utils.anticheat.LogUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * The network's shared settings on disk: {@value NetSettings#FILE} in the data folder, {@code {records:[{k, v, t, at,
 * by, id}], written:{dormant, observe-only, pause-punishments, checks-off}, writing:{...}}}, {@code written} holding
 * the config.yml values CultAC last wrote and {@code writing}, present only while CultAC writes config.yml, the values
 * it is writing, replaced in one move; and those values in config.yml itself. {@code checks-off} holds stable keys in
 * the file and the names checks have in this build in config.yml, which may name a check by any name.
 */
final class NetStateFile {
    private NetStateFile() {}

    /**
     * The shared state {@code file} holds; empty when there is none or it does not read, which is logged. A value it was
     * writing that {@code config} holds counts as written.
     */
    static Map<String, Object> read(@Nullable Path file, @Nullable Path config) {
        if (file == null || !Files.isRegularFile(file)) return NetSettings.state(List.of(), Map.of());
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            Map<String, Object> written = new HashMap<>(values(json.get("written")));
            Map<String, Object> writing = values(json.get("writing"));
            if (!writing.isEmpty() && config != null && Files.isRegularFile(config)) {
                Map<String, Object> held = readConfig(config);
                for (Map.Entry<String, Object> value : writing.entrySet()) {
                    if (value.getValue().equals(held.get(value.getKey())))
                        written.put(value.getKey(), value.getValue());
                }
            }
            List<Record> records =
                    NetSettings.merged(List.of(), NetSettings.records(json.get("records")), new HashSet<>());
            return NetSettings.state(records, written);
        } catch (IOException | RuntimeException unreadable) {
            LogUtil.warn("Ignoring " + file + ", which does not read (" + unreadable.getMessage()
                    + "); the other instances send the network's shared settings again");
            return NetSettings.state(List.of(), Map.of());
        }
    }

    private static Map<String, Object> values(@Nullable JsonElement json) {
        Map<String, Object> values = new HashMap<>();
        if (json instanceof JsonObject object) {
            for (Map.Entry<String, JsonElement> value : object.entrySet()) {
                if (value.getValue() instanceof JsonPrimitive flag && flag.isBoolean())
                    values.put(value.getKey(), flag.getAsBoolean());
                else if (value.getValue() instanceof JsonArray listed) {
                    List<String> names = new ArrayList<>();
                    for (JsonElement name : listed) {
                        if (name instanceof JsonPrimitive text && text.isString()) names.add(text.getAsString());
                    }
                    values.put(value.getKey(), names);
                }
            }
        }
        return NetSettings.written(values);
    }

    /** Replaces {@code file} with {@code records}, {@code written} and {@code writing}, empty for none, in one move. */
    static void write(Path file, List<Record> records, Map<String, Object> written, Map<String, Object> writing)
            throws IOException {
        JsonObject json = new JsonObject();
        json.add("records", NetSettings.json(records));
        json.add("written", json(written));
        if (!writing.isEmpty()) json.add("writing", json(writing));
        Path target = file.toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), ".network-state-", ".tmp");
        try {
            Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8);
            replace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void replace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static JsonObject json(Map<String, Object> values) {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, Object> value : new TreeMap<>(values).entrySet()) {
            if (value.getValue() instanceof Boolean flag) json.addProperty(value.getKey(), flag);
            else if (value.getValue() instanceof List<?> listed) {
                JsonArray names = new JsonArray();
                for (Object name : listed) names.add(String.valueOf(name));
                json.add(value.getKey(), names);
            }
        }
        return json;
    }

    /**
     * config.yml's {@code dormant}, {@code observe-only}, {@code pause-punishments} and {@code checks-off}, a missing one
     * at its default, {@code checks-off} by stable key.
     */
    static Map<String, Object> readConfig(Path config) throws IOException {
        Object loaded = new Yaml().load(Files.readString(config, StandardCharsets.UTF_8));
        Map<?, ?> values = loaded instanceof Map<?, ?> map ? map : Map.of();
        return NetSettings.config(
                Boolean.TRUE.equals(values.get(DORMANT_KEY)),
                Boolean.TRUE.equals(values.get(OBSERVE_ONLY_KEY)),
                Boolean.TRUE.equals(values.get(PAUSE_PUNISHMENTS_KEY)),
                values.get(CHECKS_OFF_KEY) instanceof List<?> listed
                        ? BaseConfigManager.checkKeys(listed, CHECKS_OFF_KEY)
                        : List.of());
    }

    /**
     * Sets {@code values}, top-level config.yml keys, in {@code config}, keeping the rest of its text, under the host's
     * config.yml lock; {@code checks-off} lists stable keys and is written as the checks' names in this build.
     */
    static void writeConfig(Path config, Map<String, Object> values, Object lock) throws IOException {
        synchronized (lock) {
            Map<String, Object> writes = new TreeMap<>();
            for (Map.Entry<String, Object> value : values.entrySet()) {
                writes.put(
                        value.getKey(),
                        value.getKey().equals(CHECKS_OFF_KEY) && value.getValue() instanceof List<?> keys
                                ? names(keys)
                                : value.getValue());
            }
            Path target = config.toAbsolutePath();
            String text = Files.readString(target, StandardCharsets.UTF_8);
            String flowed = writeFlowLists(text, writes);
            if (writes.isEmpty() && flowed.equals(text)) return;
            Path staged = Files.createTempFile(target.getParent(), ".cult-config-", ".tmp");
            try {
                Files.writeString(staged, flowed, StandardCharsets.UTF_8);
                if (!writes.isEmpty()) {
                    ConfigPatcher patcher = new ConfigPatcher(staged.toFile());
                    for (Map.Entry<String, Object> value : writes.entrySet()) {
                        NodePosition position = patcher.getNodePosition(value.getKey());
                        if (position == null) patcher.addMissingValue(value.getKey(), value.getValue());
                        else patcher.applyChange(new PendingChange(position, value.getValue()));
                    }
                    patcher.save();
                }
                replace(staged, target);
            } finally {
                Files.deleteIfExists(staged);
            }
        }
    }

    /**
     * {@code text} with each list of {@code writes} whose top-level key holds a one-line flow list there rewritten as a
     * flow list in its place, keeping the line's trailing comment; each such list is removed from {@code writes}.
     */
    private static String writeFlowLists(String text, Map<String, Object> writes) {
        String[] lines = text.split("\n", -1);
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);
        options.setSplitLines(false);
        Yaml yaml = new Yaml(options);
        boolean changed = false;
        for (var write = writes.entrySet().iterator(); write.hasNext(); ) {
            var value = write.next();
            if (!(value.getValue() instanceof List<?> list)) continue;
            Pattern flow = Pattern.compile("^" + Pattern.quote(value.getKey()) + ":(\\s*)\\[[^\\]#]*](\\s*#.*)?(\r?)$");
            for (int i = 0; i < lines.length; i++) {
                Matcher matcher = flow.matcher(lines[i]);
                if (!matcher.matches()) continue;
                String comment = matcher.group(2) == null ? "" : matcher.group(2);
                lines[i] = value.getKey() + ":" + matcher.group(1)
                        + yaml.dump(list).strip() + comment + matcher.group(3);
                write.remove();
                changed = true;
                break;
            }
        }
        return changed ? String.join("\n", lines) : text;
    }

    /** The names in this build of the checks of {@code keys}, stable keys; a key of no check here stays as it is. */
    private static List<String> names(List<?> keys) {
        List<String> names = new ArrayList<>();
        for (Object key : keys) {
            CheckCatalog.Entry check = CheckCatalog.byStableKey(String.valueOf(key));
            names.add(check == null ? String.valueOf(key) : check.name());
        }
        return names;
    }
}
