package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;

/** Queries immutable generated facts using a model entity ID. */
public final class EntityTypeUtil {
    private static final EntityKey UNKNOWN = new EntityKey("minecraft", "unknown");
    private static final java.util.List<EntityKey> KEYS = EntityTypes.defaults().types().stream()
            .map(type -> EntityKey.parse(type.key()))
            .toList();

    private EntityTypeUtil() {}

    public static EntityKey getKey(int handle) {
        return handle < 0 ? UNKNOWN : KEYS.get(handle);
    }

    public static EntityTypes.Type modelType(int type) {
        return EntityTypes.defaults().byId(type);
    }

    public static boolean isType(int type, String path) {
        EntityKey key = getKey(type);
        return "minecraft".equals(key.getNamespace()) && path.equals(key.getPath());
    }

    private static boolean hasFamily(int type, int family) {
        return type >= 0 && modelType(type).has(family);
    }

    public static boolean isHappyGhast(int type) {
        return isType(type, "happy_ghast");
    }

    public static boolean isProjectile(int type) {
        return hasFamily(type, EntityTypes.PROJECTILE);
    }

    public static boolean isLiving(int type) {
        return hasFamily(type, EntityTypes.LIVING);
    }

    public static boolean isAnimal(int type) {
        return hasFamily(type, EntityTypes.ANIMAL);
    }

    public static boolean isAgeable(int type) {
        return hasFamily(type, EntityTypes.AGEABLE);
    }

    public static boolean isHorseFamily(int type) {
        return hasFamily(type, EntityTypes.HORSE);
    }

    public static boolean isChestedHorseFamily(int type) {
        return hasFamily(type, EntityTypes.CHESTED_HORSE);
    }

    public static boolean isBoat(int type) {
        return hasFamily(type, EntityTypes.BOAT);
    }

    public static boolean isMinecart(int type) {
        return hasFamily(type, EntityTypes.MINECART);
    }

    public static boolean canFloatWhileRidden(int type) {
        return type == EntityTypeIds.HORSE
                || type == EntityTypeIds.ZOMBIE_HORSE
                || type == EntityTypeIds.MULE
                || type == EntityTypeIds.DONKEY
                || type == EntityTypeIds.CAMEL
                || isType(type, "camel_husk");
    }

    public static boolean isCamelFamily(int type) {
        return type == EntityTypeIds.CAMEL || isType(type, "camel_husk");
    }

    public static boolean isNautilusFamily(int type) {
        return isType(type, "nautilus") || isType(type, "zombie_nautilus");
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
}
