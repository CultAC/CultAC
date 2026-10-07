package ac.cult.cultac.bedrock.prediction.state;

import ac.cult.blocksim.entity.EntityTypeIds;

/** Native predicted equines; camels and llamas use different control paths. */
public final class BedrockHorseProperties {
    private BedrockHorseProperties() {}

    public static boolean supports(int type) {
        return type == EntityTypeIds.HORSE
                || type == EntityTypeIds.DONKEY
                || type == EntityTypeIds.MULE
                || type == EntityTypeIds.SKELETON_HORSE
                || type == EntityTypeIds.ZOMBIE_HORSE;
    }

    public static float riderSeatHeight(int type, float height) {
        // Geyser EntityUtils#getMountedHeightOffset, then the player's height offset.
        float mounted = height * 0.75F;
        if (type == EntityTypeIds.DONKEY || type == EntityTypeIds.MULE) mounted -= 0.25F;
        else if (type == EntityTypeIds.SKELETON_HORSE) mounted -= 0.1875F;
        return (mounted - 0.35F) + 1.62001F;
    }
}
