package ac.cult.cultac.bedrock.prediction.state;

import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import net.minecraft.world.entity.EntityType;

/** Native predicted equines; camels and llamas use different control paths. */
public final class BedrockHorseProperties {
    private BedrockHorseProperties() { }

    public static boolean supports(EntityType<?> type) {
        return type == EntityTypesCompat.HORSE || type == EntityTypesCompat.DONKEY
                || type == EntityTypesCompat.MULE || type == EntityTypesCompat.SKELETON_HORSE
                || type == EntityTypesCompat.ZOMBIE_HORSE;
    }

    public static float riderSeatHeight(EntityType<?> type, float height) {
        // Geyser EntityUtils#getMountedHeightOffset, then the player's height offset.
        float mounted = height * 0.75F;
        if (type == EntityTypesCompat.DONKEY || type == EntityTypesCompat.MULE) mounted -= 0.25F;
        else if (type == EntityTypesCompat.SKELETON_HORSE) mounted -= 0.1875F;
        return (mounted - 0.35F) + 1.62001F;
    }

}
