package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import net.minecraft.world.InteractionHand;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

public final class RiptideUtil {
    private static final int MIN_CHARGE_TICKS = 10;

    private RiptideUtil() {
    }

    public static int getRiptideLevel(ItemStack item) {
        if (item == null || item.getType() != Material.TRIDENT) {
            return 0;
        }
        return item.getEnchantmentLevel(Enchantment.RIPTIDE);
    }

    public static boolean isValidRiptideRelease(CultPlayer player, InteractionHand hand) {
        return isFullyCharged(player, hand) && canUseRiptideAtCurrentState(player);
    }

    public static String invalidReleaseReason(CultPlayer player, InteractionHand hand) {
        if (!isFullyCharged(player, hand)) {
            return "charge=" + getTrackedUseTicks(player, hand);
        }
        if (player.compensatedEntities.getSelf().inVehicle()) {
            return "passenger";
        }
        return "dry";
    }

    public static boolean isFullyCharged(CultPlayer player, InteractionHand hand) {
        return hasFullCharge(getTrackedUseTicks(player, hand));
    }

    public static boolean hasFullCharge(int useTicks) {
        return useTicks >= MIN_CHARGE_TICKS;
    }

    public static int getTrackedUseTicks(CultPlayer player, InteractionHand hand) {
        if (hand == null || hand != player.packetStateData.riptideUseHand) {
            return 0;
        }
        return Math.max(0, player.packetStateData.acceptedClientTick - player.packetStateData.riptideUseStartClientTick);
    }

    public static boolean canUseRiptideAtCurrentState(CultPlayer player) {
        if (player.compensatedEntities.getSelf().inVehicle()) {
            return false;
        }
        return isTouchingWater(player) || player.compensatedWorld.isRaining;
    }

    public static boolean isTouchingWater(CultPlayer player) {
        SimpleCollisionBox box = GetBoundingBox.getPlayerBoundingBox(player, player.x, player.y, player.z).copy();
        box.expand(-1.0E-7D);
        return Collisions.hasMaterial(player, box, data -> NmsBlockTags.isWater(data.getFirst()));
    }

    public static void clearTrackedUse(CultPlayer player) {
        player.packetStateData.riptideUseHand = null;
        player.packetStateData.riptideUseStartClientTick = Integer.MIN_VALUE;
    }
}
