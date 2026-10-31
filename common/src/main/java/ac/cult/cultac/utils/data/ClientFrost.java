package ac.cult.cultac.utils.data;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockProperties;

/**
 * LivingEntity#removeFrost then #tryAddFrost: the minecraft:powder_snow movement_speed modifier for a synced
 * DATA_TICKS_FROZEN count. From 1.17, which added powder snow, through 1.21.4, LivingEntity#aiStep runs them after
 * travel on the client too, for the local player and its vehicle alike. From 1.21.5 only the server runs them, so
 * the client keeps the modifier of the last attribute packet.
 */
public final class ClientFrost {
    /** Entity#getTicksRequiredToFreeze. */
    private static final int TICKS_REQUIRED_TO_FREEZE = 140;

    private ClientFrost() {}

    /** Whether the client replaces frost after each travel. */
    public static boolean replacedAfterTravel(CultPlayer player) {
        ClientVersion version = player.getClientVersion();
        return !player.isBedrockMovement()
                && version.isNewerThanOrEquals(ClientVersion.V_1_17)
                && version.isOlderThan(ClientVersion.V_1_21_5);
    }

    /**
     * Replaces the frost in {@code movementSpeed}, held by an entity that ended its move at {@code position} on
     * {@code support}, and returns whether the attribute's value changed. tryAddFrost adds none over an
     * Entity#getBlockStateOnLegacy of air.
     */
    public static boolean replace(
            CultPlayer player,
            ClientAttributeInstance movementSpeed,
            int ticksFrozen,
            Vec3 position,
            MainSupportingBlockData support) {
        AttributeModifier frost = null;
        if (ticksFrozen > 0
                && !DataTables.defaults()
                        .registry()
                        .facts(BlockProperties.getOnPos(player, support, position))
                        .has(StateFacts.AIR)) {
            // Entity#getPercentFrozen
            float percentFrozen = Math.min(ticksFrozen, TICKS_REQUIRED_TO_FREEZE) / (float) TICKS_REQUIRED_TO_FREEZE;
            frost = new AttributeModifier(
                    CompensatedEntities.SNOW_MODIFIER_ID,
                    -0.05F * percentFrozen,
                    AttributeModifier.Operation.ADD_VALUE);
        }
        double before = movementSpeed.value();
        movementSpeed.replaceModifier(CompensatedEntities.SNOW_MODIFIER_ID, frost);
        return movementSpeed.value() != before;
    }
}
