package ac.cult.cultac.checks.impl.elytra;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.inventory.ItemUtil;

@CheckData(
        name = "ElytraD",
        stableKey = "cult.elytra.no_elytra",
        description = "Started gliding without an elytra",
        experimental = true)
public class ElytraD extends Check implements PostPredictionListener {
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(ProtocolVersion.V26_3.protocol());

    private boolean setback;

    public ElytraD(CultPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9);
    }

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        if (!isApplicable()) return;
        if (packet.action() == PlayerCommandAction.START_FLYING_WITH_ELYTRA && !canGlide() && flag()) {
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
        final ac.cult.blocksim.engine.SimItemStack chestPlate =
                player.getInventory().getChestplate();
        if (chestPlate.getItem() == ac.cult.cultac.utils.inventory.ItemTypes.ELYTRA
                && ItemUtil.getDamageValue(chestPlate) < ItemUtil.getMaxDamage(chestPlate) - 1) return true;

        // if the server or client doesn't support glider components return false
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2)
                || SERVER_VERSION.isOlderThan(ClientVersion.V_1_21_2)) return false;

        return isGlider(player.getInventory().getHelmet(), EquipmentSlot.HEAD)
                || isGlider(player.getInventory().getChestplate(), EquipmentSlot.CHEST)
                || isGlider(player.getInventory().getLeggings(), EquipmentSlot.LEGS)
                || isGlider(player.getInventory().getBoots(), EquipmentSlot.FEET)
                || isGlider(player.getInventory().getOffHand(), EquipmentSlot.OFFHAND);
    }

    private static boolean isGlider(ac.cult.blocksim.engine.SimItemStack stack, EquipmentSlot slot) {
        return ac.cult.cultac.utils.inventory.ClientGlider.canUse(stack, slot);
    }

    private void resyncPose() {
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_14) && player.platformPlayer != null) {
            player.platformPlayer.setSneaking(!player.platformPlayer.isSneaking());
        }
    }
}
