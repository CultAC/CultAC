package ac.cult.cultac.utils.common.arguments;

import ac.cult.cultac.platform.api.Platform;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;

public record SystemArgumentFactory(
        Map<String, String> arguments,
        Map<Class<?>, Function<String, ?>> parsers,
        Consumer<ArgumentOptions.Builder<?>> optionModifier) {

    public static class Builder {

        public static Builder of(final String prefix) {
            return new Builder(prefix);
        }

        public Builder optionModifier(Consumer<ArgumentOptions.Builder<?>> modifier) {
            this.optionModifier = modifier;
            return this;
        }

        private final String prefix;
        private final Map<Class<?>, Function<String, ?>> parseBuilder;
        private Consumer<ArgumentOptions.Builder<?>> optionModifier = null;

        private Builder(final String prefix) {
            this.prefix = prefix;
            this.parseBuilder = new HashMap<>();
            // Register default parsers
            registerDefaultParsers();
        }

        protected void registerDefaultParsers() {
            registerParser(Boolean.class, Boolean::parseBoolean)
                    .registerParser(Integer.class, Integer::parseInt)
                    .registerParser(String.class, String::valueOf)
                    .registerParser(Platform.class, Platform::getByName);
        }

        public <T> Builder registerParser(Class<T> type, Function<String, T> parser) {
            parseBuilder.put(type, parser);
            return this;
        }

        public SystemArgumentFactory build() {
            // Parse arguments from JVM arguments
            final String findPrefix = "-d" + prefix.toLowerCase();
            Map<String, String> builder = getSystemPropertiesMap(findPrefix);

            return new SystemArgumentFactory(Map.copyOf(builder), Map.copyOf(parseBuilder), optionModifier);
        }

        protected @NotNull Map<String, String> getSystemPropertiesMap(String findPrefix) {
            Map<String, String> builder = new HashMap<>();
            for (String line : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (line.toLowerCase().startsWith(findPrefix)) {
                    int index = line.indexOf('=');
                    if (index > 0 && index < line.length() - 1) {
                        String key = line.substring(2, index); // remove -D
                        String value = line.substring(index + 1);
                        builder.put(key.toLowerCase(), value);
                    }
                }
            }
            return builder;
        }
    }

    private <T> SystemArgument<T> createDefaultSupplier(ArgumentOptions<T> options) {
        T value = options.getModifier().apply(options.getDefaultSupplier().get());
        if (value == null && !options.isNullable())
            throw new IllegalArgumentException(
                    "Default value cannot be null for startup argument \"" + options.getKey() + "\"");
        if ((value != null && !options.getVerifier().test(value)))
            throw new IllegalArgumentException(
                    "Invalid default value for startup argument \"" + options.getKey() + "\"");
        return new SystemArgument<>(value);
    }

    public <T> SystemArgument<T> create(ArgumentOptions.Builder<T> builder) {
        if (optionModifier != null) optionModifier.accept(builder);
        final ArgumentOptions<T> options = builder.build();
        final String value = arguments.get(options.getKey().toLowerCase());
        if (value == null) return createDefaultSupplier(options);
        try {
            @SuppressWarnings("unchecked")
            Function<String, T> parser = (Function<String, T>) parsers.get(options.getClazz());
            if (parser == null) return createDefaultSupplier(options);
            T parsed = options.getModifier().apply(parser.apply(value));
            if (parsed == null || !options.getVerifier().test(parsed)) return createDefaultSupplier(options);
            return new SystemArgument<>(parsed);
        } catch (Exception e) {
            return createDefaultSupplier(options);
        }
    }
}
