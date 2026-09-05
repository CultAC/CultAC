package ac.cult.cultac.checks.impl.elytra;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.util.SpigotConversionUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import org.bukkit.Material;

@CheckData(name = "ElytraD", stableKey = "cult.elytra.no_elytra", description = "Started gliding without an elytra", experimental = true)
public class ElytraD extends Check implements PostPredictionListener {
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    private boolean setback;

    public ElytraD(CultPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9);
    }

    @CultPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerCommandPacket packet) {
        if (!isApplicable()) return;
        if (NmsPacketUtil.readPlayerCommand(packet).action() == NmsPacketUtil.PlayerCommandAction.START_FLYING_WITH_ELYTRA
                && !canGlide()
                && flag()) {
            setback = true;
            if (shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
                resyncPose();
            }
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!isApplicable()) return;
        if (setback) {
            setbackIfAboveSetbackVL();
            setback = false;
        }
    }

    private boolean canGlide() {
        // don't check the client/server version, this is relevant for all
        final org.bukkit.inventory.ItemStack chestPlate = player.getInventory().getChestplate();
        if (chestPlate.getType() == Material.ELYTRA && ItemUtil.getDamageValue(chestPlate) < chestPlate.getType().getMaxDurability() - 1)
            return true;

        // if the server or client doesn't support glider components return false
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2)
                || SERVER_VERSION.isOlderThan(ClientVersion.V_1_21_2)) return false;


        return isGlider(player.getInventory().getHelmet(), EquipmentSlot.HEAD)
                || isGlider(player.getInventory().getChestplate(), EquipmentSlot.CHEST)
                || isGlider(player.getInventory().getLeggings(), EquipmentSlot.LEGS)
                || isGlider(player.getInventory().getBoots(), EquipmentSlot.FEET)
                || isGlider(player.getInventory().getOffHand(), EquipmentSlot.OFFHAND);
    }

    private static boolean isGlider(org.bukkit.inventory.ItemStack stack, EquipmentSlot slot) {
        ItemStack nms = SpigotConversionUtil.toNmsItemStack(stack);
        if (!nms.has(DataComponents.GLIDER) || (nms.isDamageableItem() && nms.getDamageValue() >= (nms.getMaxDamage() - 1))) {
            return false;
        }

        Equippable equippable = nms.get(DataComponents.EQUIPPABLE);
        return equippable != null && equippable.slot() == slot;
    }

    private void resyncPose() {
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_14) && player.platformPlayer != null) {
            player.platformPlayer.setSneaking(!player.platformPlayer.isSneaking());
        }
    }
}
