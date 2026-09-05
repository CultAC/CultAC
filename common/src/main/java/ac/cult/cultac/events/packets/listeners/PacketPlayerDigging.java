package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.nmsutil.RiptideUtil;
import net.minecraft.core.BlockPos;
import org.bukkit.inventory.ItemStack;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;

public class PacketPlayerDigging {
    //LOW
    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent event, CultPlayer player, ServerboundUseItemPacket packet) {
        InteractionHand hand = NmsPacketUtil.readUseItem(packet).hand();
        ItemStack item = player.getInventory().getHandItem(hand);
        if (RiptideUtil.getRiptideLevel(item) > 0) {
            player.packetStateData.riptideUseHand = hand;
            player.packetStateData.riptideUseStartClientTick = player.packetStateData.acceptedClientTick;
        } else if (hand == player.packetStateData.riptideUseHand) {
            RiptideUtil.clearTrackedUse(player);
        }
    }

    @CultPacketHandler
    public void onPlayerAction(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerActionPacket packet) {
        if (packet.getAction() == Action.RELEASE_USE_ITEM) {
            InteractionHand hand = player.packetStateData.riptideUseHand;
            ItemStack item = hand == null ? null : player.getInventory().getHandItem(hand);
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
        Action action = packet.getAction();
        if (action == Action.START_DESTROY_BLOCK || action == Action.STOP_DESTROY_BLOCK || action == Action.ABORT_DESTROY_BLOCK) {
            BlockPos blockPosition = packet.getPos();
            NmsPacketUtil.PlayerActionData actionData = NmsPacketUtil.readPlayerAction(packet);
            BlockBreak blockBreak = new BlockBreak(player, blockPosition, actionData.blockFace(),
                    packet.getDirection().get3DDataValue(), action, packet.getSequence(),
                    player.compensatedWorld.getBlockStateAt(blockPosition));

            player.checkManager.onBlockBreak(blockBreak);

            if (blockBreak.isCancelled()) {
                event.setCancelled(true);
                player.onPacketCancel();
                player.getResyncHandler().resyncPosition(blockPosition.getX(), blockPosition.getY(), blockPosition.getZ(), packet.getSequence());
                return;
            }

            player.checkManager.queuePostFlyingBlockBreak(blockBreak);
        }
    }

    @CultPacketHandler
    public void onSetCarriedItem(PacketReceiveEvent event, CultPlayer player, ServerboundSetCarriedItemPacket packet) {
        int slotId = packet.getSlot();
        // Stop people from spamming the server with out of bounds exceptions
        if (slotId > 8 || slotId < 0) return; //TODO: flag?

        player.packetStateData.lastSlotSelected = slotId;
        if (player.packetStateData.riptideUseHand != InteractionHand.OFF_HAND) {
            RiptideUtil.clearTrackedUse(player);
        }
    }
}
