package ac.grim.grimac.utils.nmsutil;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class WatchableIndexUtil {
    public static final MetadataAccessor<Byte> ENTITY_SHARED_FLAGS = accessor("DATA_SHARED_FLAGS_ID", 0, "net.minecraft.world.entity.Entity");
    public static final MetadataAccessor<Boolean> ENTITY_NO_GRAVITY = accessor("DATA_NO_GRAVITY", 5, "net.minecraft.world.entity.Entity");
    public static final MetadataAccessor<?> ENTITY_POSE = accessor("DATA_POSE", 6, "net.minecraft.world.entity.Entity");
    public static final MetadataAccessor<Integer> ENTITY_TICKS_FROZEN = accessor("DATA_TICKS_FROZEN", 7, "net.minecraft.world.entity.Entity");
    public static final MetadataAccessor<Byte> LIVING_ENTITY_FLAGS = accessor("DATA_LIVING_ENTITY_FLAGS", 8, "net.minecraft.world.entity.LivingEntity");
    public static final MetadataAccessor<?> LIVING_HEALTH = accessor("DATA_HEALTH_ID", 9, "net.minecraft.world.entity.LivingEntity");
    public static final MetadataAccessor<?> LIVING_SLEEPING_POS = accessor("SLEEPING_POS_ID", 14, "net.minecraft.world.entity.LivingEntity");
    public static final MetadataAccessor<Byte> MOB_FLAGS = accessor("DATA_MOB_FLAGS_ID", 15, "net.minecraft.world.entity.Mob");
    public static final MetadataAccessor<Boolean> AGEABLE_BABY = accessor("DATA_BABY_ID", 16, "net.minecraft.world.entity.AgeableMob");
    public static final MetadataAccessor<Integer> SLIME_SIZE = accessor("ID_SIZE", 16, "net.minecraft.world.entity.monster.Slime");
    public static final MetadataAccessor<Integer> PHANTOM_SIZE = accessor("ID_SIZE", 16, "net.minecraft.world.entity.monster.Phantom");
    public static final MetadataAccessor<?> SHULKER_ATTACH_FACE = accessor("DATA_ATTACH_FACE_ID", 16, "net.minecraft.world.entity.monster.Shulker");
    public static final MetadataAccessor<Byte> SHULKER_PEEK = accessor("DATA_PEEK_ID", 17, "net.minecraft.world.entity.monster.Shulker");
    public static final MetadataAccessor<Boolean> PIG_SADDLE = accessor("DATA_SADDLE_ID", 17, "net.minecraft.world.entity.animal.pig.Pig", "net.minecraft.world.entity.animal.Pig");
    public static final MetadataAccessor<Integer> PIG_BOOST_TIME = accessor("DATA_BOOST_TIME", 18, "net.minecraft.world.entity.animal.pig.Pig", "net.minecraft.world.entity.animal.Pig");
    public static final MetadataAccessor<Integer> STRIDER_BOOST_TIME = accessor("DATA_BOOST_TIME", 18, "net.minecraft.world.entity.monster.Strider");
    public static final MetadataAccessor<Boolean> STRIDER_SUFFOCATING = accessor("DATA_SUFFOCATING", 19, "net.minecraft.world.entity.monster.Strider");
    public static final MetadataAccessor<Boolean> STRIDER_SADDLE = accessor("DATA_SADDLE_ID", 19, "net.minecraft.world.entity.monster.Strider");
    public static final MetadataAccessor<Byte> HORSE_FLAGS = accessor("DATA_ID_FLAGS", 18, "net.minecraft.world.entity.animal.equine.AbstractHorse", "net.minecraft.world.entity.animal.horse.AbstractHorse");
    public static final MetadataAccessor<Boolean> CAMEL_DASH = accessor("DASH", 19, "net.minecraft.world.entity.animal.camel.Camel", "net.minecraft.world.entity.animal.camel.Camel");
    public static final MetadataAccessor<Boolean> HAPPY_GHAST_STAYS_STILL = accessor("STAYS_STILL", 18, "net.minecraft.world.entity.animal.happyghast.HappyGhast", "net.minecraft.world.entity.animal.HappyGhast");
    public static final MetadataAccessor<Boolean> NAUTILUS_DASH = accessor("DASH", 19, "net.minecraft.world.entity.animal.nautilus.AbstractNautilus");
    public static final MetadataAccessor<?> FIREWORK_ATTACHED_TO_TARGET = accessor("DATA_ATTACHED_TO_TARGET", 9, "net.minecraft.world.entity.projectile.FireworkRocketEntity");
    public static final MetadataAccessor<Integer> FISHING_HOOKED_ENTITY = accessor("DATA_HOOKED_ENTITY", 8, "net.minecraft.world.entity.projectile.FishingHook");

    private static final List<MetadataAccessor<?>> RESOLVED_ACCESSORS = List.of(
            ENTITY_SHARED_FLAGS,
            ENTITY_NO_GRAVITY,
            ENTITY_POSE,
            ENTITY_TICKS_FROZEN,
            LIVING_ENTITY_FLAGS,
            LIVING_HEALTH,
            LIVING_SLEEPING_POS,
            MOB_FLAGS,
            AGEABLE_BABY,
            SLIME_SIZE,
            PHANTOM_SIZE,
            SHULKER_ATTACH_FACE,
            SHULKER_PEEK,
            PIG_SADDLE,
            PIG_BOOST_TIME,
            STRIDER_BOOST_TIME,
            STRIDER_SUFFOCATING,
            STRIDER_SADDLE,
            HORSE_FLAGS,
            CAMEL_DASH,
            HAPPY_GHAST_STAYS_STILL,
            NAUTILUS_DASH,
            FIREWORK_ATTACHED_TO_TARGET,
            FISHING_HOOKED_ENTITY
    );

    private WatchableIndexUtil() {
    }

    public static List<MetadataAccessor<?>> resolvedAccessors() {
        return RESOLVED_ACCESSORS;
    }

    public static SynchedEntityData.DataValue<?> getIndex(List<SynchedEntityData.DataValue<?>> objects, MetadataAccessor<?> accessor) {
        return getIndex(objects, accessor.id());
    }

    public static SynchedEntityData.DataValue<?> getIndex(List<SynchedEntityData.DataValue<?>> objects, int index) {
        for (SynchedEntityData.DataValue<?> object : objects) {
            if (object.id() == index) return object;
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> MetadataAccessor<T> accessor(String fieldName, int fallbackId, String... ownerNames) {
        Class<?> owner = resolveClass(ownerNames);
        if (owner == null) {
            return new MetadataAccessor<>(String.join("|", ownerNames) + "." + fieldName, fallbackId, false);
        }
        Field field = findField(owner, fieldName);
        if (field == null || !EntityDataAccessor.class.isAssignableFrom(field.getType())) {
            return new MetadataAccessor<>(owner.getName() + "." + fieldName, fallbackId, false);
        }

        try {
            field.setAccessible(true);
            EntityDataAccessor<T> accessor = (EntityDataAccessor<T>) field.get(null);
            return new MetadataAccessor<>(owner.getName() + "." + fieldName, accessor.id(), true);
        } catch (IllegalAccessException | RuntimeException ignored) {
            return new MetadataAccessor<>(owner.getName() + "." + fieldName, fallbackId, false);
        }
    }

    private static Class<?> resolveClass(String... names) {
        for (String name : names) {
            try {
                return Class.forName(name, false, WatchableIndexUtil.class.getClassLoader());
            } catch (ClassNotFoundException ignored) {
                // Try the package used by the other supported server generation.
            }
        }
        return null;
    }

    private static Field findField(Class<?> owner, String fieldName) {
        return Arrays.stream(owner.getDeclaredFields())
                .filter(field -> Objects.equals(field.getName(), fieldName))
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .findFirst()
                .orElse(null);
    }

    public record MetadataAccessor<T>(String name, int id, boolean resolved) {
    }
}
