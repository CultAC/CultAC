package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;

public final class RiptideUtil {
    private static final int MIN_CHARGE_TICKS = 10;

    private RiptideUtil() {}

    public static int getRiptideLevel(SimItemStack item) {
        if (item == null || item.getItem() != ac.cult.cultac.utils.inventory.ItemTypes.TRIDENT) {
            return 0;
        }
        return ac.cult.cultac.utils.inventory.ItemUtil.enchantmentLevel(item, "minecraft:riptide");
    }

    public static boolean isValidRiptideRelease(CultPlayer player, Hand hand) {
        return isFullyCharged(player, hand) && canUseRiptideAtCurrentState(player);
    }

    public static String invalidReleaseReason(CultPlayer player, Hand hand) {
        if (!isFullyCharged(player, hand)) {
            return "charge=" + getTrackedUseTicks(player, hand);
        }
        if (player.compensatedEntities.getSelf().inVehicle()) {
            return "passenger";
        }
        return "dry";
    }

    public static boolean isFullyCharged(CultPlayer player, Hand hand) {
        return hasFullCharge(getTrackedUseTicks(player, hand));
    }

    public static boolean hasFullCharge(int useTicks) {
        return useTicks >= MIN_CHARGE_TICKS;
    }

    public static int getTrackedUseTicks(CultPlayer player, Hand hand) {
        if (hand == null || hand != player.packetStateData.riptideUseHand) {
            return 0;
        }
        return Math.max(
                0, player.packetStateData.acceptedClientTick - player.packetStateData.riptideUseStartClientTick);
    }

    public static boolean canUseRiptideAtCurrentState(CultPlayer player) {
        if (player.compensatedEntities.getSelf().inVehicle()) {
            return false;
        }
        return isTouchingWater(player) || player.compensatedWorld.isRaining;
    }

    public static boolean isTouchingWater(CultPlayer player) {
        SimpleCollisionBox box = GetBoundingBox.getPlayerBoundingBox(player, player.x, player.y, player.z)
                .copy();
        box.expand(-1.0E-7D);
        return Collisions.hasState(player, box, (state, pos) -> isWater(state));
    }

    private static boolean isWater(int state) {
        var fluid = ClientFluidQueries.modelFluid(state);
        return fluid.is("minecraft:water") || fluid.is("minecraft:flowing_water");
    }

    public static void clearTrackedUse(CultPlayer player) {
        player.packetStateData.riptideUseHand = null;
        player.packetStateData.riptideUseStartClientTick = Integer.MIN_VALUE;
    }
}
