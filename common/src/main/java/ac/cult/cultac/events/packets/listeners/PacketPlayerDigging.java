package ac.cult.cultac.events.packets.listeners;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.nmsutil.RiptideUtil;

public class PacketPlayerDigging {
    // LOW
    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        Hand hand = packet.hand();
        SimItemStack item = player.getInventory().getHandItem(hand);
        if (RiptideUtil.getRiptideLevel(item) > 0) {
            player.packetStateData.riptideUseHand = hand;
            player.packetStateData.riptideUseStartClientTick = player.packetStateData.acceptedClientTick;
        } else if (hand == player.packetStateData.riptideUseHand) {
            RiptideUtil.clearTrackedUse(player);
        }
    }

    @CultPacketHandler
    public void onPlayerAction(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        if (packet.action() == PlayerAction.RELEASE_USE_ITEM) {
            Hand hand = player.packetStateData.riptideUseHand;
            SimItemStack item = hand == null ? null : player.getInventory().getHandItem(hand);
            int j = RiptideUtil.getRiptideLevel(item);

            if (j > 0 && RiptideUtil.isValidRiptideRelease(player, hand)) {
                player.packetStateData.riptideLevel = j;
                player.riptideSpinAttackTicks = 20;
            } else if (j > 0) {
                player.packetStateData.invalidRiptideRelease = true;
                player.packetStateData.invalidRiptideReleaseReason = RiptideUtil.invalidReleaseReason(player, hand);
            }
            RiptideUtil.clearTrackedUse(player);
        }

        // Cancellation prevents post-flying checks but not movement prediction or resync.
        PlayerAction action = packet.action();
        if (action == PlayerAction.START_DESTROY_BLOCK
                || action == PlayerAction.STOP_DESTROY_BLOCK
                || action == PlayerAction.ABORT_DESTROY_BLOCK) {
            BlockPos blockPosition = packet.position();
            BlockBreak blockBreak = new BlockBreak(
                    player,
                    blockPosition,
                    packet.direction(),
                    packet.direction().ordinal(),
                    action,
                    packet.sequence(),
                    player.compensatedWorld.getBlockStateIdAt(blockPosition));

            player.checkManager.onBlockBreak(blockBreak);

            if (blockBreak.isCancelled()) {
                event.setCancelled(true);
                player.onPacketCancel();
                player.getResyncHandler()
                        .resyncPosition(
                                blockPosition.getX(), blockPosition.getY(), blockPosition.getZ(), packet.sequence());
                return;
            }

            player.checkManager.queuePostFlyingBlockBreak(blockBreak);
        }
    }

    @CultPacketHandler
    public void onSetCarriedItem(
            PacketReceiveEvent<ServerboundSetCarriedItem> event, CultPlayer player, ServerboundSetCarriedItem packet) {
        selectHotbarSlot(player, packet.slot());
    }

    public static void selectHotbarSlot(CultPlayer player, int slotId) {
        // Stop people from spamming the server with out of bounds exceptions
        if (slotId > 8 || slotId < 0) return; // TODO: flag?

        player.packetStateData.lastSlotSelected = slotId;
        if (player.packetStateData.riptideUseHand != Hand.OFF_HAND) {
            RiptideUtil.clearTrackedUse(player);
        }
    }
}
