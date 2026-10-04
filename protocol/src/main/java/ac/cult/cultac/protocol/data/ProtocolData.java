package ac.cult.cultac.protocol.data;

import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolResolutionException;
import ac.cult.cultac.protocol.ProtocolVersion;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Checked-in data generated from the exact vanilla version's reports. */
public final class ProtocolData {
    private static final Set<String> REQUIRED_REGISTRIES = Set.of(
            "minecraft:block",
            "minecraft:entity_type",
            "minecraft:attribute",
            "minecraft:mob_effect",
            "minecraft:menu");
    private final ProtocolVersion version;
    private final Map<ConnectionPhase, Map<PacketDirection, IdTable>> packets;
    private final Map<String, IdTable> registries;

    private ProtocolData(
            ProtocolVersion version,
            Map<ConnectionPhase, Map<PacketDirection, IdTable>> packets,
            Map<String, IdTable> registries) {
        this.version = version;
        this.packets = Map.copyOf(packets);
        this.registries = Map.copyOf(registries);
    }

    public static ProtocolData load(ProtocolVersion version) {
        String path = "/ac/cult/cultac/protocol/data/" + version.protocol() + "/index.tsv";
        try (InputStream input = ProtocolData.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new ProtocolResolutionException("Missing protocol data for " + version + ": " + path);
            }
            return readIndex(version, new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new ProtocolResolutionException(
                    "Cannot read protocol data for " + version + ": " + failure.getMessage());
        }
    }

    static ProtocolData readIndex(ProtocolVersion version, Reader input) throws IOException {
        BufferedReader reader = new BufferedReader(input);
        String expected = "cult-protocol\t1\t" + version.protocol() + "\t" + version.minecraftVersion();
        if (!expected.equals(reader.readLine())) {
            throw new ProtocolResolutionException("Wrong data schema/version; expected " + expected);
        }
        Map<ConnectionPhase, Map<PacketDirection, List<String>>> packetNames = new EnumMap<>(ConnectionPhase.class);
        Map<String, List<String>> registryNames = new HashMap<>();
        int lineNumber = 1;
        for (String line; (line = reader.readLine()) != null; ) {
            lineNumber++;
            String[] fields = line.split("\t", -1);
            try {
                switch (fields[0]) {
                    case "packet" -> {
                        requireFields(fields, 5);
                        ConnectionPhase phase = ConnectionPhase.valueOf(fields[1].toUpperCase(Locale.ROOT));
                        PacketDirection direction = PacketDirection.valueOf(fields[2].toUpperCase(Locale.ROOT));
                        List<String> names = packetNames
                                .computeIfAbsent(phase, key -> new EnumMap<>(PacketDirection.class))
                                .computeIfAbsent(direction, key -> new ArrayList<>());
                        append(names, fields[3], fields[4]);
                    }
                    case "registry" -> {
                        requireFields(fields, 4);
                        append(
                                registryNames.computeIfAbsent(fields[1], key -> new ArrayList<>()),
                                fields[2],
                                fields[3]);
                    }
                    default -> throw new IllegalArgumentException("Unknown index entry " + fields[0]);
                }
            } catch (IllegalArgumentException failure) {
                throw new ProtocolResolutionException(
                        "Invalid " + version + " index at line " + lineNumber + ": " + failure.getMessage());
            }
        }
        Map<ConnectionPhase, Map<PacketDirection, IdTable>> packets = new EnumMap<>(ConnectionPhase.class);
        for (ConnectionPhase phase : ConnectionPhase.values()) {
            Map<PacketDirection, IdTable> flows = new EnumMap<>(PacketDirection.class);
            for (PacketDirection direction : PacketDirection.values()) {
                List<String> names = packetNames.getOrDefault(phase, Map.of()).getOrDefault(direction, List.of());
                if (names.isEmpty()
                        && !(phase == ConnectionPhase.HANDSHAKE && direction == PacketDirection.CLIENTBOUND)) {
                    throw new ProtocolResolutionException(
                            "Missing packet table " + version + "/" + phase + "/" + direction);
                }
                flows.put(direction, new IdTable(phase + "/" + direction, names));
            }
            packets.put(phase, Map.copyOf(flows));
        }
        Map<String, IdTable> registries = new HashMap<>();
        registryNames.forEach((name, names) -> registries.put(name, new IdTable(name, names)));
        if (!registries.keySet().equals(REQUIRED_REGISTRIES)) {
            throw new ProtocolResolutionException("Missing or unexpected registries for " + version);
        }
        return new ProtocolData(version, packets, registries);
    }

    private static void requireFields(String[] fields, int length) {
        if (fields.length != length) {
            throw new IllegalArgumentException("Wrong field count");
        }
    }

    private static void append(List<String> names, String id, String name) {
        if (Integer.parseInt(id) != names.size()) {
            throw new IllegalArgumentException("Non-contiguous or duplicate ID " + id);
        }
        names.add(name);
    }

    public ProtocolVersion version() {
        return version;
    }

    public IdTable packets(ConnectionPhase phase, PacketDirection direction) {
        return packets.get(phase).get(direction);
    }

    public IdTable registry(String name) {
        IdTable registry = registries.get(name);
        if (registry == null) {
            throw new ProtocolResolutionException("Unknown static registry " + name + " on " + version);
        }
        return registry;
    }

    public Map<String, IdTable> registries() {
        return registries;
    }
}
