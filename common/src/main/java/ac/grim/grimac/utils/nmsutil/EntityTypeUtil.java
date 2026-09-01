package ac.grim.grimac.utils.nmsutil;

import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.Animal;
import org.bukkit.craftbukkit.entity.CraftEntityType;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class EntityTypeUtil {
    private static final Map<EntityType<?>, Class<? extends Entity>> NMS_ENTITY_CLASSES = resolveNmsEntityClasses();
    private static final Method ENTITY_TYPE_GET_KEY = resolveGetKey();

    private EntityTypeUtil() {
    }

    public static EntityKey getKey(EntityType<?> handle) {
        if (handle == null) {
            return new EntityKey("minecraft", "unknown");
        }
        try {
            return EntityKey.parse(ENTITY_TYPE_GET_KEY.invoke(null, handle).toString());
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to access EntityType#getKey", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("EntityType#getKey failed", exception.getCause());
        }
    }

    public static boolean isType(EntityType<?> type, String path) {
        EntityKey key = getKey(type);
        return "minecraft".equals(key.getNamespace()) && path.equals(key.getPath());
    }

    public static boolean isHappyGhast(EntityType<?> type) {
        return isType(type, "happy_ghast");
    }

    public static boolean isLiving(EntityType<?> type) {
        Class<? extends Entity> nmsClass = nmsEntityClass(type);
        if (nmsClass != null) {
            return LivingEntity.class.isAssignableFrom(nmsClass);
        }

        Class<? extends org.bukkit.entity.Entity> entityClass = bukkitEntityClass(type);
        if (entityClass != null) {
            return org.bukkit.entity.LivingEntity.class.isAssignableFrom(entityClass);
        }
        return type != null && type.getCategory() != MobCategory.MISC;
    }

    public static boolean isAnimal(EntityType<?> type) {
        Class<? extends Entity> nmsClass = nmsEntityClass(type);
        if (nmsClass != null) {
            return Animal.class.isAssignableFrom(nmsClass);
        }

        Class<? extends org.bukkit.entity.Entity> entityClass = bukkitEntityClass(type);
        if (entityClass != null) {
            return org.bukkit.entity.Animals.class.isAssignableFrom(entityClass)
                    || org.bukkit.entity.AbstractHorse.class.isAssignableFrom(entityClass)
                    || org.bukkit.entity.Strider.class.isAssignableFrom(entityClass);
        }
        return type != null && (type.getCategory() == MobCategory.CREATURE || type.getCategory() == MobCategory.AXOLOTLS);
    }

    public static boolean isAgeable(EntityType<?> type) {
        Class<? extends Entity> nmsClass = nmsEntityClass(type);
        if (nmsClass != null) {
            return AgeableMob.class.isAssignableFrom(nmsClass);
        }

        Class<? extends org.bukkit.entity.Entity> entityClass = bukkitEntityClass(type);
        if (entityClass != null) {
            return org.bukkit.entity.Ageable.class.isAssignableFrom(entityClass);
        }
        return isAnimal(type);
    }

    public static boolean isHorseFamily(EntityType<?> type) {
        Class<? extends Entity> nmsClass = nmsEntityClass(type);
        if (isSubclassNamed(nmsClass,
                "net.minecraft.world.entity.animal.horse.AbstractHorse",
                "net.minecraft.world.entity.animal.equine.AbstractHorse")) {
            return true;
        }

        Class<? extends org.bukkit.entity.Entity> entityClass = bukkitEntityClass(type);
        return entityClass != null && org.bukkit.entity.AbstractHorse.class.isAssignableFrom(entityClass);
    }

    public static boolean isChestedHorseFamily(EntityType<?> type) {
        Class<? extends Entity> nmsClass = nmsEntityClass(type);
        if (isSubclassNamed(nmsClass,
                "net.minecraft.world.entity.animal.horse.AbstractChestedHorse",
                "net.minecraft.world.entity.animal.equine.AbstractChestedHorse")) {
            return true;
        }

        Class<? extends org.bukkit.entity.Entity> entityClass = bukkitEntityClass(type);
        return entityClass != null && org.bukkit.entity.ChestedHorse.class.isAssignableFrom(entityClass);
    }

    private static boolean isSubclassNamed(Class<?> type, String... superclassNames) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            String currentName = current.getName();
            for (String superclassName : superclassNames) {
                if (superclassName.equals(currentName)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isBoat(EntityType<?> type) {
        Class<? extends Entity> nmsClass = nmsEntityClass(type);
        if (isSubclassNamed(nmsClass,
                "net.minecraft.world.entity.vehicle.AbstractBoat",
                "net.minecraft.world.entity.vehicle.boat.AbstractBoat")) {
            return true;
        }

        Class<? extends org.bukkit.entity.Entity> entityClass = bukkitEntityClass(type);
        if (entityClass != null && org.bukkit.entity.Boat.class.isAssignableFrom(entityClass)) {
            return true;
        }

        return isVanillaTypeEndingWith(type, "_boat", "_chest_boat", "_raft", "_chest_raft");
    }

    public static boolean canFloatWhileRidden(EntityType<?> type) {
        return type == EntityTypesCompat.HORSE
                || type == EntityTypesCompat.ZOMBIE_HORSE
                || type == EntityTypesCompat.MULE
                || type == EntityTypesCompat.DONKEY
                || type == EntityTypesCompat.CAMEL
                || isType(type, "camel_husk");
    }

    public static boolean isMinecart(EntityType<?> type) {
        Class<? extends Entity> nmsClass = nmsEntityClass(type);
        if (isSubclassNamed(nmsClass,
                "net.minecraft.world.entity.vehicle.AbstractMinecart",
                "net.minecraft.world.entity.vehicle.minecart.AbstractMinecart")) {
            return true;
        }

        Class<? extends org.bukkit.entity.Entity> entityClass = bukkitEntityClass(type);
        if (entityClass != null && org.bukkit.entity.Minecart.class.isAssignableFrom(entityClass)) {
            return true;
        }

        return isVanillaTypeEndingWith(type, "_minecart");
    }

    private static boolean isVanillaTypeEndingWith(EntityType<?> type, String... suffixes) {
        if (type == null) {
            return false;
        }

        EntityKey name = getKey(type);
        if (!"minecraft".equals(name.getNamespace())) {
            return false;
        }

        String key = name.getPath();
        for (String suffix : suffixes) {
            if (key.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static Class<? extends Entity> nmsEntityClass(EntityType<?> type) {
        return type == null ? null : NMS_ENTITY_CLASSES.get(type);
    }

    private static Map<EntityType<?>, Class<? extends Entity>> resolveNmsEntityClasses() {
        Map<EntityType<?>, Class<? extends Entity>> classes = new ConcurrentHashMap<>();
        // 26.2 moved the constants to EntityTypes; iterate whichever class declares them.
        for (Field field : EntityTypesCompat.HOLDER.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || !EntityType.class.isAssignableFrom(field.getType())) {
                continue;
            }

            Class<? extends Entity> entityClass = entityClassFromField(field);
            if (entityClass == null) {
                continue;
            }

            try {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof EntityType<?> type) {
                    classes.put(type, entityClass);
                }
            } catch (IllegalAccessException ignored) {
                // Leave this type to Bukkit/category fallback.
            }
        }
        return Map.copyOf(classes);
    }

    private static Method resolveGetKey() {
        try {
            return EntityType.class.getMethod("getKey", EntityType.class);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("EntityType#getKey is unavailable", exception);
        }
    }

    public record EntityKey(String namespace, String path) {
        private static EntityKey parse(String value) {
            int separator = value.indexOf(':');
            return separator < 0
                    ? new EntityKey("minecraft", value)
                    : new EntityKey(value.substring(0, separator), value.substring(separator + 1));
        }

        public String getNamespace() {
            return namespace;
        }

        public String getPath() {
            return path;
        }

        @Override
        public String toString() {
            return namespace + ':' + path;
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Entity> entityClassFromField(Field field) {
        Type genericType = field.getGenericType();
        if (!(genericType instanceof ParameterizedType parameterizedType)) {
            return null;
        }

        Type typeArgument = parameterizedType.getActualTypeArguments()[0];
        if (!(typeArgument instanceof Class<?> clazz) || !Entity.class.isAssignableFrom(clazz)) {
            return null;
        }
        return (Class<? extends Entity>) clazz;
    }

    private static Class<? extends org.bukkit.entity.Entity> bukkitEntityClass(EntityType<?> type) {
        if (type == null) {
            return null;
        }

        try {
            org.bukkit.entity.EntityType bukkitType = CraftEntityType.minecraftToBukkit(type);
            return bukkitType == null ? null : bukkitType.getEntityClass();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
