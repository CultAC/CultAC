package ac.cult.cultac.network.routing;

import static ac.cult.cultac.manager.config.BaseConfigManager.CHECKS_OFF_KEY;
import static ac.cult.cultac.manager.config.BaseConfigManager.DORMANT_KEY;
import static ac.cult.cultac.manager.config.BaseConfigManager.OBSERVE_ONLY_KEY;
import static ac.cult.cultac.manager.config.BaseConfigManager.PAUSE_PUNISHMENTS_KEY;

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
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

/**
 * The network's shared settings on disk: {@value NetSettings#FILE} in the data folder, {@code {records:[{k, v, t, at,
 * by, id}], written:{dormant, observe-only, pause-punishments, checks-off}}}, {@code written} holding the config.yml
 * values CultAC last wrote, replaced in one move; and those values in config.yml itself.
 */
final class NetStateFile {
    private NetStateFile() {}

    /** The shared state {@code file} holds; empty when there is none or it does not read, which is logged. */
    static Map<String, Object> read(@Nullable Path file) {
        if (file == null || !Files.isRegularFile(file)) return NetSettings.state(List.of(), Map.of());
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            Map<String, Object> written = new HashMap<>();
            if (json.get("written") instanceof JsonObject values) {
                for (Map.Entry<String, JsonElement> value : values.entrySet()) {
                    if (value.getValue() instanceof JsonPrimitive flag && flag.isBoolean())
                        written.put(value.getKey(), flag.getAsBoolean());
                    else if (value.getValue() instanceof JsonArray listed) {
                        List<String> names = new ArrayList<>();
                        for (JsonElement name : listed) {
                            if (name instanceof JsonPrimitive text && text.isString()) names.add(text.getAsString());
                        }
                        written.put(value.getKey(), names);
                    }
                }
            }
            List<Record> records =
                    NetSettings.merged(List.of(), NetSettings.records(json.get("records")), new HashSet<>());
            return NetSettings.state(records, NetSettings.written(written));
        } catch (IOException | RuntimeException unreadable) {
            LogUtil.warn("Ignoring " + file + ", which does not read (" + unreadable.getMessage()
                    + "); the other instances send the network's shared settings again");
            return NetSettings.state(List.of(), Map.of());
        }
    }

    /** Replaces {@code file} with {@code records} and {@code written} in one move. */
    static void write(Path file, List<Record> records, Map<String, Object> written) throws IOException {
        JsonObject json = new JsonObject();
        json.add("records", NetSettings.json(records));
        JsonObject values = new JsonObject();
        for (Map.Entry<String, Object> value : new TreeMap<>(written).entrySet()) {
            if (value.getValue() instanceof Boolean flag) values.addProperty(value.getKey(), flag);
            else if (value.getValue() instanceof List<?> listed) {
                JsonArray names = new JsonArray();
                for (Object name : listed) names.add(String.valueOf(name));
                values.add(value.getKey(), names);
            }
        }
        json.add("written", values);
        Path target = file.toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), ".network-state-", ".tmp");
        try {
            Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * config.yml's {@code dormant}, {@code observe-only}, {@code pause-punishments} and {@code checks-off}, a missing one
     * at its default.
     */
    static Map<String, Object> readConfig(Path config) throws IOException {
        Object loaded = new Yaml().load(Files.readString(config, StandardCharsets.UTF_8));
        Map<?, ?> values = loaded instanceof Map<?, ?> map ? map : Map.of();
        List<String> off = new ArrayList<>();
        if (values.get(CHECKS_OFF_KEY) instanceof List<?> listed) {
            for (Object check : listed) {
                if (check != null && !check.toString().isBlank())
                    off.add(check.toString().strip());
            }
        }
        return NetSettings.config(
                Boolean.TRUE.equals(values.get(DORMANT_KEY)),
                Boolean.TRUE.equals(values.get(OBSERVE_ONLY_KEY)),
                Boolean.TRUE.equals(values.get(PAUSE_PUNISHMENTS_KEY)),
                off);
    }

    /** Sets {@code values}, top-level config.yml keys, in {@code config}, keeping the rest of its text. */
    static void writeConfig(Path config, Map<String, Object> values) throws IOException {
        ConfigPatcher patcher = new ConfigPatcher(config.toFile());
        for (Map.Entry<String, Object> value : new TreeMap<>(values).entrySet()) {
            List<String> path = List.of(value.getKey());
            if (patcher.inFlowCollection(path)) {
                patcher.replaceInFlowCollection(path, value.getValue());
                continue;
            }
            NodePosition position = patcher.getNodePosition(path);
            if (position == null) patcher.addMissingValue(path, value.getValue());
            else patcher.applyChange(new PendingChange(position, value.getValue()));
        }
        patcher.save();
    }
}
