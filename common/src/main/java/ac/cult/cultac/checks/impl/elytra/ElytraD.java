package ac.cult.cultac.checks.impl.elytra;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.inventory.ClientGlider;
import ac.cult.cultac.utils.inventory.ItemTypes;
import ac.cult.cultac.utils.inventory.ItemUtil;

@CheckData(
        name = "ElytraD",
        stableKey = "cult.elytra.no_elytra",
        description = "Started gliding without an elytra",
        experimental = true)
public class ElytraD extends Check implements PostPredictionListener {
    private boolean setback;

    public ElytraD(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        if (packet.action() == PlayerCommandAction.START_FLYING_WITH_ELYTRA && !canGlide() && flag()) {
            setback = true;
            if (shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
                player.resyncPose();
            }
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (setback) {
            setbackIfAboveSetbackVL();
            setback = false;
        }
    }

    private boolean canGlide() {
        // don't check the client/server version, this is relevant for all
        final SimItemStack chestPlate = player.getInventory().getChestplate();
        if (chestPlate.getItem() == ItemTypes.ELYTRA
                && ItemUtil.getDamageValue(chestPlate) < ItemUtil.getMaxDamage(chestPlate) - 1) return true;

        return isGlider(player.getInventory().getHelmet(), EquipmentSlot.HEAD)
                || isGlider(player.getInventory().getChestplate(), EquipmentSlot.CHEST)
                || isGlider(player.getInventory().getLeggings(), EquipmentSlot.LEGS)
                || isGlider(player.getInventory().getBoots(), EquipmentSlot.FEET)
                || isGlider(player.getInventory().getOffHand(), EquipmentSlot.OFFHAND);
    }

    private static boolean isGlider(SimItemStack stack, EquipmentSlot slot) {
        return ClientGlider.canUse(stack, slot);
    }
}
