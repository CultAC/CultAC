package ac.cult.cultac.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoublePredicate;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Registry shared by parsing, matching, display and completion. */
public final class PlayerSearchFilters {
    private record Key(Function<NetworkPlayer, List<String>> values, boolean numeric) {}

    private static final Map<String, Key> KEYS = new LinkedHashMap<>();
    private static final Pattern START = Pattern.compile("(?:^|\\s+)([a-zA-Z]+)=");
    private static final Pattern RANGE = Pattern.compile("(\\d+(?:\\.\\d+)?)-(\\d+(?:\\.\\d+)?)");

    static {
        text("brand", p -> List.of(p.brand() == null ? "unknown" : p.brand()));
        text("server", p -> List.of(p.server() == null ? p.instance() : p.server()));
        text("name", p -> List.of(p.name()));
        text("uuid", p -> List.of(p.uuid().toString()));
        text("proxy", p -> List.of(p.instance()));
        text("mod", p -> p.details().getOrDefault("mod", List.of()));
        text("channel", p -> p.details().getOrDefault("channel", List.of()));
        text("version", p -> p.details().getOrDefault("version", List.of()));
        text("platform", p -> p.details().getOrDefault("platform", List.of()));
        text("fastmath", p -> p.details().getOrDefault("fastmath", List.of()));
        number("sens");
        number("vsens");
        number("ping");
    }

    private PlayerSearchFilters() {}

    public static void text(String name, Function<NetworkPlayer, List<String>> values) {
        KEYS.put(name, new Key(values, false));
    }

    private static void number(String name) {
        KEYS.put(
                name,
                new Key(
                        p -> (name.equals("sens") || name.equals("vsens"))
                                        && p.details()
                                                .getOrDefault("platform", List.of())
                                                .contains("bedrock")
                                ? List.of()
                                : p.details().getOrDefault(name, List.of()),
                        true));
    }

    public record Filter(
            String key, String query, Function<NetworkPlayer, List<String>> values, DoublePredicate numeric) {
        public List<String> matched(NetworkPlayer player) {
            return values.apply(player).stream()
                    .filter(value -> {
                        if (numeric == null)
                            return value.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
                        try {
                            return numeric.test(Double.parseDouble(value));
                        } catch (NumberFormatException malformed) {
                            return false;
                        }
                    })
                    .toList();
        }
    }

    public static List<Filter> parse(String input) {
        if (input.length() > 2048) throw new IllegalArgumentException();
        var matcher = START.matcher(input.strip());
        List<String> keys = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        while (matcher.find()) {
            if (keys.isEmpty() && matcher.start() != 0) throw new IllegalArgumentException();
            keys.add(matcher.group(1).toLowerCase(Locale.ROOT));
            starts.add(matcher.start());
            ends.add(matcher.end());
        }
        if (keys.isEmpty() || keys.size() > 16) throw new IllegalArgumentException();
        String typed = input.strip();
        List<Filter> filters = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            String query = typed.substring(ends.get(i), i + 1 < keys.size() ? starts.get(i + 1) : typed.length())
                    .strip();
            Key key = KEYS.get(keys.get(i));
            if (key == null || query.isEmpty()) throw new IllegalArgumentException();
            filters.add(new Filter(keys.get(i), query, key.values(), key.numeric() ? numeric(query) : null));
        }
        return List.copyOf(filters);
    }

    static DoublePredicate numeric(String query) {
        var range = RANGE.matcher(query);
        if (range.matches()) {
            double low = value(range.group(1)), high = value(range.group(2));
            if (low > high) throw new IllegalArgumentException();
            return actual -> Double.isFinite(actual) && actual >= low && actual <= high;
        }
        char operator = query.charAt(0);
        double expected = value(operator == '>' || operator == '<' ? query.substring(1) : query);
        return actual -> Double.isFinite(actual)
                && switch (operator) {
                    case '>' -> actual > expected;
                    case '<' -> actual < expected;
                    default -> Math.abs(actual - expected) <= 0.5;
                };
    }

    private static double value(String text) {
        double value = Double.parseDouble(text);
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException();
        return value;
    }

    public static List<String> suggestions(Collection<NetworkPlayer> players, String typed) {
        int space = typed.lastIndexOf(' ');
        String prefix = space < 0 ? "" : typed.substring(0, space + 1);
        String current = typed.substring(space + 1);
        List<String> suggestions = new ArrayList<>();
        KEYS.forEach((name, key) -> {
            String start = name + "=";
            if (start.startsWith(current.toLowerCase(Locale.ROOT))) suggestions.add(prefix + start);
            for (NetworkPlayer player : players) {
                for (String value : key.values().apply(player)) {
                    String suggestion = start + value;
                    if (suggestion.toLowerCase(Locale.ROOT).startsWith(current.toLowerCase(Locale.ROOT)))
                        suggestions.add(prefix + suggestion);
                }
            }
        });
        for (String known : List.of("platform=java", "platform=bedrock", "fastmath=true", "fastmath=false")) {
            if (known.startsWith(current.toLowerCase(Locale.ROOT))) suggestions.add(prefix + known);
        }
        return suggestions.stream()
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}
