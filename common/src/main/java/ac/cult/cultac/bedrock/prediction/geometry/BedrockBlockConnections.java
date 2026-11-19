package ac.cult.cultac.bedrock.prediction.geometry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * How a Bedrock block takes part in the connections of fences and of panes and bars, for a client whose fences and
 * panes carry no connection state and derive them from the blocks beside them.
 *
 * <p>A block's connection rule names the connector types it accepts (fence, nether brick fence, wall, pane or bars) and
 * the faces it connects on. A block with no rule at all connects to every connector. A fence or pane connects on a side
 * when the block there has no rule, or when its rule accepts the fence's own type on the face that points back.
 */
public final class BedrockBlockConnections {
    /** A block with no connection rule: every connector connects to it. */
    public static final int NO_RULE = -1;

    public static final int NO_CONNECTOR = -1;
    public static final int FENCE = 0;
    public static final int NETHER_BRICK_FENCE = 1;
    public static final int WALL = 2;
    public static final int PANE = 3;

    // Faces as the client numbers them.
    static final int DOWN = 0;
    static final int UP = 1;
    static final int NORTH = 2;
    static final int SOUTH = 3;
    static final int WEST = 4;
    static final int EAST = 5;

    private static final int ALL_TYPES = 0xF;
    private static final int HORIZONTAL_FACES = 1 << NORTH | 1 << SOUTH | 1 << WEST | 1 << EAST;
    private static final int ALL_FACES = 0x3F;
    private static final Map<String, Entry> RULES = load("bedrock/cultac-bedrock-connection-rules-26.40.tsv");

    private BedrockBlockConnections() {}

    private record Entry(String kind, int accepts) {}

    /**
     * The connection rule of the Bedrock block {@code name} in {@code states}: the accepted connector types in the low
     * four bits and the faces it connects on above them, or {@link #NO_RULE}.
     */
    public static int rule(String name, Map<String, ?> states) {
        Entry entry = RULES.get(name);
        if (entry == null || entry.kind().equals("none")) return NO_RULE;
        return switch (entry.kind()) {
            case "rule" -> encode(entry.accepts(), HORIZONTAL_FACES);
            case "hook:stair" -> {
                int direction = intState(states, "weirdo_direction");
                yield direction < 0 || direction > 3
                        ? encode(0, ALL_FACES)
                        : encode(ALL_TYPES, 1 << (EAST - direction));
            }
            case "hook:fence_gate" -> {
                int direction = intState(states, "minecraft:cardinal_direction", "direction");
                yield encode(
                        1 << FENCE | 1 << NETHER_BRICK_FENCE | 1 << WALL,
                        direction == 0 || direction == 2 ? 1 << WEST | 1 << EAST : 1 << NORTH | 1 << SOUTH);
            }
            case "hook:half_slab" -> encode(0, ALL_FACES);
            case "hook:top_snow" -> encode(intState(states, "height") == 7 ? ALL_TYPES : 0, ALL_FACES);
            case "hook:shelf" -> {
                int direction = intState(states, "minecraft:cardinal_direction");
                // South, west, north, east face their back: north, east, south, west.
                int[] back = {NORTH, EAST, SOUTH, WEST};
                yield encode(ALL_TYPES, direction < 0 || direction > 3 ? ALL_FACES : 1 << back[direction]);
            }
            case "hook:piston_arm" -> {
                int facing = intState(states, "facing_direction");
                int face = facing <= UP ? facing : facing ^ 1;
                yield encode(ALL_TYPES, facing < 0 || facing > 5 ? ALL_FACES : 1 << face);
            }
            case "hook:trapdoor" -> {
                if (!booleanState(states, "open_bit")) yield encode(0, ALL_FACES);
                int direction = intState(states, "direction");
                int[] faces = {WEST, EAST, NORTH, SOUTH};
                yield encode(ALL_TYPES, direction < 0 || direction > 3 ? ALL_FACES : 1 << faces[direction]);
            }
            default -> throw new IllegalStateException("Unknown connection rule kind " + entry.kind());
        };
    }

    /** The connector type of the Bedrock block {@code name}, or {@link #NO_CONNECTOR}. */
    public static int connectorType(String name) {
        if (name.equals("minecraft:nether_brick_fence")) return NETHER_BRICK_FENCE;
        Entry entry = RULES.get(name);
        if (entry == null || !entry.kind().equals("rule")) return NO_CONNECTOR;
        if (entry.accepts() == 1 << FENCE) return FENCE;
        if (entry.accepts() == (1 << WALL | 1 << PANE)) return name.endsWith("_wall") ? WALL : PANE;
        return NO_CONNECTOR;
    }

    /** Whether a connector of {@code type} connects to a block with {@code rule} whose face {@code face} it touches. */
    public static boolean connects(int type, int rule, int face) {
        if (rule == NO_RULE) return true;
        return type != NO_CONNECTOR && (rule & 1 << type) != 0 && (rule >> 4 & 1 << face) != 0;
    }

    private static int encode(int accepts, int faces) {
        return accepts & 0xF | (faces & 0x3F) << 4;
    }

    private static int intState(Map<String, ?> states, String... keys) {
        for (String key : keys) {
            Object value = states.get(key);
            if (value instanceof Number number) return number.intValue();
            if (value instanceof String text) {
                return switch (text) {
                    case "south" -> 0;
                    case "west" -> 1;
                    case "north" -> 2;
                    case "east" -> 3;
                    default -> -1;
                };
            }
        }
        return -1;
    }

    private static boolean booleanState(Map<String, ?> states, String key) {
        Object value = states.get(key);
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        return false;
    }

    private static Map<String, Entry> load(String resource) {
        Map<String, Entry> rules = new HashMap<>();
        try (InputStream in = BedrockBlockConnections.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing " + resource);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] fields = line.split("\t", -1);
                int accepts = fields.length > 2 && !fields[2].isEmpty() ? Integer.decode(fields[2]) : 0;
                rules.put(fields[0], new Entry(fields[1], accepts));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.copyOf(rules);
    }
}
