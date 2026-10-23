package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.protocol.ProtocolVersion;
import java.util.List;

/** Metadata indices in the observed wire layout, independent of model values. */
public final class WatchableIndexUtil {
    public static final int ENTITY_SHARED_FLAGS = 0;
    public static final int ENTITY_NO_GRAVITY = 5;
    public static final int ENTITY_POSE = 6;
    public static final int ENTITY_TICKS_FROZEN = 7;
    public static final int LIVING_ENTITY_FLAGS = 8;
    public static final int LIVING_HEALTH = 9;
    public static final int LIVING_SLEEPING_POS = 14;
    public static final int MOB_FLAGS = 15;
    public static final int AGEABLE_BABY = 16;
    // Zombie, its subclasses and Zoglin define DATA_BABY_ID after Mob's fields, as AgeableMob does.
    public static final int ZOMBIE_BABY = 16;
    // Piglin's DATA_BABY_ID follows AbstractPiglin's DATA_IMMUNE_TO_ZOMBIFICATION.
    public static final int PIGLIN_BABY = 17;
    public static final int PHANTOM_SIZE = 16;
    public static final int SHULKER_ATTACH_FACE = 16;
    public static final int SHULKER_PEEK = 17;
    public static final int FIREWORK_ATTACHED_TO_TARGET = 9;
    public static final int FISHING_HOOKED_ENTITY = 8;
    public static final int DISPLAY_POS_ROT_INTERPOLATION_DURATION = 10;

    private WatchableIndexUtil() {}

    /** Fields whose native inheritance or representation changed within protocols 768–777. */
    public record Layout(
            int pigBoostTime,
            int striderBoostTime,
            int horseFlags,
            int camelDash,
            int happyGhastStaysStill,
            int nautilusDash,
            int slimeSize,
            int pigSaddle,
            int striderSaddle,
            boolean saddleEquipment) {
        /** Camel defines LAST_POSE_CHANGE_TICK directly after DASH in every supported version. */
        public int camelLastPoseChangeTick() {
            return camelDash + 1;
        }
    }

    private static final Layout LEGACY = new Layout(18, 17, 17, 18, -1, -1, 16, 17, 19, false);
    private static final Layout EQUIPMENT = new Layout(17, 17, 17, 18, -1, -1, 16, -1, -1, true);
    private static final Layout HAPPY_GHAST = new Layout(17, 17, 17, 18, 18, -1, 16, -1, -1, true);
    private static final Layout NAUTILUS = new Layout(17, 17, 17, 18, 18, 19, 16, -1, -1, true);
    private static final Layout AGE_LOCKED = new Layout(18, 18, 18, 19, 19, 20, 16, -1, -1, true);
    private static final Layout CUBE_MOB = new Layout(18, 18, 18, 19, 19, 20, 18, -1, -1, true);

    public static Layout forModel(CultConnection connection) {
        return forModel(connection.getObservedProtocol());
    }

    public static Layout forModel(ProtocolVersion version) {
        return switch (version) {
            case V1_21_3, V1_21_4 -> LEGACY;
            case V1_21_5 -> EQUIPMENT;
            case V1_21_6, V1_21_7, V1_21_9 -> HAPPY_GHAST;
            case V1_21_11 -> NAUTILUS;
            case V26_1 -> AGE_LOCKED;
            case V26_2, V26_3 -> CUBE_MOB;
        };
    }
    /** AgeableMob adds AGE_LOCKED in 26.1 before TamableAnimal's flags. */
    public static int tameableFlags(ProtocolVersion version) {
        return version.atLeast(ProtocolVersion.V26_1) ? 18 : 17;
    }

    public static EntityMetadata.Entry getIndex(List<EntityMetadata.Entry> objects, int index) {
        for (int i = 0; i < objects.size(); i++) {
            EntityMetadata.Entry object = objects.get(i);
            if (object.id() == index) return object;
        }

        return null;
    }
}
