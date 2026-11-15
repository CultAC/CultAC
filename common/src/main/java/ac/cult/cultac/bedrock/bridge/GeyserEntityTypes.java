package ac.cult.cultac.bedrock.bridge;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

/**
 * Whether Geyser spawns a Bedrock entity for a Java entity type. It drops the spawn of a type it has no definition
 * for, and with it every later packet of that entity, links included. Each Geyser generation keeps its definitions
 * elsewhere: {@code GeyserEntityType#definition()}, {@code Registries.JAVA_ENTITY_TYPES} keyed by
 * {@code GeyserEntityType}, or {@code Registries.ENTITY_DEFINITIONS} keyed by the protocol type; the one present is
 * read reflectively. A type this cannot look up is taken as spawned.
 */
final class GeyserEntityTypes {
    private interface Lookup {
        boolean defined(EntityType type) throws ReflectiveOperationException;
    }

    private static final Lookup LOOKUP = lookup();
    private static final Map<String, Boolean> TRANSLATED = new ConcurrentHashMap<>();

    private GeyserEntityTypes() {}

    static boolean translates(String javaKey) {
        return TRANSLATED.computeIfAbsent(javaKey, GeyserEntityTypes::defined);
    }

    private static boolean defined(String javaKey) {
        if (LOOKUP == null) return true;
        EntityType type;
        try {
            String path = javaKey.startsWith("minecraft:") ? javaKey.substring("minecraft:".length()) : javaKey;
            type = EntityType.valueOf(path.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return true;
        }
        try {
            return LOOKUP.defined(type);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return true;
        }
    }

    private static Lookup lookup() {
        try {
            Class<?> geyserType = Class.forName(
                    "org.geysermc.geyser.entity.GeyserEntityType", false, Registries.class.getClassLoader());
            Method of = geyserType.getMethod("of", EntityType.class);
            try {
                Method definition = geyserType.getMethod("definition");
                return type -> ((Optional<?>) definition.invoke(of.invoke(null, type))).isPresent();
            } catch (NoSuchMethodException absent) {
                Object registry = Registries.class.getField("JAVA_ENTITY_TYPES").get(null);
                Method get = registry.getClass().getMethod("get", Object.class);
                return type -> get.invoke(registry, of.invoke(null, type)) != null;
            }
        } catch (ReflectiveOperationException | LinkageError current) {
            try {
                Object registry = Registries.class.getField("ENTITY_DEFINITIONS").get(null);
                Method get = registry.getClass().getMethod("get", Object.class);
                return type -> get.invoke(registry, type) != null;
            } catch (ReflectiveOperationException | LinkageError legacy) {
                return null;
            }
        }
    }
}
