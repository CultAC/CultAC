package ac.grim.grimac.utils.nmsutil;

import net.minecraft.world.entity.EntityType;

import java.lang.reflect.Field;

/**
 * Cross-version access to vanilla entity type constants. 26.2 moved the constants
 * from {@link EntityType} to the new net.minecraft.world.entity.EntityTypes class,
 * so resolve them by name to keep one jar working on both sides of that move.
 */
@SuppressWarnings("rawtypes")
public final class EntityTypesCompat {
    /** The class that declares the entity type constants on this server. */
    static final Class<?> HOLDER = resolveHolder();

    public static final EntityType ARMOR_STAND = constant("ARMOR_STAND");
    public static final EntityType AXOLOTL = constant("AXOLOTL");
    public static final EntityType BAT = constant("BAT");
    public static final EntityType CAMEL = constant("CAMEL");
    public static final EntityType CHICKEN = constant("CHICKEN");
    public static final EntityType COD = constant("COD");
    public static final EntityType COW = constant("COW");
    public static final EntityType DOLPHIN = constant("DOLPHIN");
    public static final EntityType DONKEY = constant("DONKEY");
    public static final EntityType ENDERMITE = constant("ENDERMITE");
    public static final EntityType END_CRYSTAL = constant("END_CRYSTAL");
    public static final EntityType EVOKER = constant("EVOKER");
    public static final EntityType FIREWORK_ROCKET = constant("FIREWORK_ROCKET");
    public static final EntityType FISHING_BOBBER = constant("FISHING_BOBBER");
    public static final EntityType GOAT = constant("GOAT");
    public static final EntityType HAPPY_GHAST = constant("HAPPY_GHAST");
    public static final EntityType HOGLIN = constant("HOGLIN");
    public static final EntityType HORSE = constant("HORSE");
    public static final EntityType ILLUSIONER = constant("ILLUSIONER");
    public static final EntityType ITEM = constant("ITEM");
    public static final EntityType LLAMA = constant("LLAMA");
    public static final EntityType MAGMA_CUBE = constant("MAGMA_CUBE");
    public static final EntityType MOOSHROOM = constant("MOOSHROOM");
    public static final EntityType MULE = constant("MULE");
    public static final EntityType OAK_BOAT = constant("OAK_BOAT");
    public static final EntityType PARROT = constant("PARROT");
    public static final EntityType PHANTOM = constant("PHANTOM");
    public static final EntityType PIG = constant("PIG");
    public static final EntityType PIGLIN = constant("PIGLIN");
    public static final EntityType PILLAGER = constant("PILLAGER");
    public static final EntityType PLAYER = constant("PLAYER");
    public static final EntityType PUFFERFISH = constant("PUFFERFISH");
    public static final EntityType RAVAGER = constant("RAVAGER");
    public static final EntityType SALMON = constant("SALMON");
    public static final EntityType SHULKER = constant("SHULKER");
    public static final EntityType SILVERFISH = constant("SILVERFISH");
    public static final EntityType SKELETON = constant("SKELETON");
    public static final EntityType SKELETON_HORSE = constant("SKELETON_HORSE");
    public static final EntityType SLIME = constant("SLIME");
    public static final EntityType SPIDER = constant("SPIDER");
    public static final EntityType STRIDER = constant("STRIDER");
    public static final EntityType TADPOLE = constant("TADPOLE");
    public static final EntityType TROPICAL_FISH = constant("TROPICAL_FISH");
    public static final EntityType TURTLE = constant("TURTLE");
    public static final EntityType VINDICATOR = constant("VINDICATOR");
    public static final EntityType WITCH = constant("WITCH");
    public static final EntityType ZOGLIN = constant("ZOGLIN");
    public static final EntityType ZOMBIE = constant("ZOMBIE");
    public static final EntityType ZOMBIE_HORSE = constant("ZOMBIE_HORSE");
    public static final EntityType ZOMBIFIED_PIGLIN = constant("ZOMBIFIED_PIGLIN");

    private EntityTypesCompat() {
    }

    private static Class<?> resolveHolder() {
        try {
            return Class.forName("net.minecraft.world.entity.EntityTypes");
        } catch (ClassNotFoundException ignored) {
            return EntityType.class;
        }
    }

    private static EntityType constant(String name) {
        try {
            Field field = HOLDER.getField(name);
            return (EntityType) field.get(null);
        } catch (NoSuchFieldException | IllegalAccessException exception) {
            // Constants can be absent on supported servers (for example HAPPY_GHAST
            // only exists on 1.21.6+). Resolve to null instead of failing class
            // initialization for the entire anticheat on those versions.
            return null;
        }
    }
}
