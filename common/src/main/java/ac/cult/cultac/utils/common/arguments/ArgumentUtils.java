package ac.cult.cultac.utils.common.arguments;

import ac.cult.cultac.platform.api.Platform;
import java.util.function.Predicate;

public class ArgumentUtils {

    public static <T extends Number> Predicate<T> validRange(T min, T max) {
        return number -> number.doubleValue() >= min.doubleValue() && number.doubleValue() <= max.doubleValue();
    }

    public static ArgumentOptions.Builder<Integer> range(String key, int defaultValue, int min, int max) {
        return ArgumentOptions.from(Integer.class, key, defaultValue)
                .verifier(validRange(min, max))
                .modifier(integer -> Math.min(Math.max(integer, min), max));
    }

    public static ArgumentOptions.Builder<String> string(String key, String defaultValue) {
        return ArgumentOptions.from(String.class, key, defaultValue);
    }

    public static ArgumentOptions.Builder<Boolean> string(String key, boolean defaultValue) {
        return ArgumentOptions.from(Boolean.class, key, defaultValue);
    }

    public static ArgumentOptions.Builder<Platform> platform(String key) {
        return ArgumentOptions.from(Platform.class, key);
    }
}
