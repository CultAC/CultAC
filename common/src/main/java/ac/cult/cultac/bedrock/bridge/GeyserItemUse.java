package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.events.packets.blockplace.PlaceHandler;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.bukkit.block.BlockFace;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.util.BlockUtils;

final class GeyserItemUse {
    private GeyserItemUse() { }
    private static final BlockFace[] FACES = {BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST};

    static boolean allow(GeyserSession session, CultPlayer player, InventoryTransactionPacket packet) {
        if (packet.getTransactionType() != InventoryTransactionType.ITEM_USE || packet.getActionType() != 0) return true;
        if (!player.getSetbackTeleportUtil().isPendingSetback()) return true;
        var position = packet.getBlockPosition();
        BlockUtils.restoreCorrectBlock(session, position, packet.getHotbarSlot());
        if (packet.getBlockFace() >= 0 && packet.getBlockFace() < FACES.length) {
            var face = FACES[packet.getBlockFace()];
            BlockUtils.restoreCorrectBlock(session, position.add(face.getModX(), face.getModY(), face.getModZ()), packet.getHotbarSlot());
        }
        return false;
    }

    static void observe(GeyserSession session, CultPlayer player, InventoryTransactionPacket packet, int previousSequence) {
        switch (packet.getTransactionType()) {
            case ITEM_RELEASE -> { if (packet.getActionType() == 0) player.actionManager.releaseItem(); }
            case ITEM_USE_ON_ENTITY -> GeyserEntityInteractions.observe(session, player, packet);
            case ITEM_USE -> {
                int sequence = GeyserBlockActions.sequence(session);
                if (sequence == previousSequence) return; // Geyser rejected or consumed the action locally.
                player.lastBlockPlaceUseItem = System.currentTimeMillis();
                if (packet.getActionType() == 0 && packet.getBlockFace() >= 0 && packet.getBlockFace() < FACES.length) {
                    var pos = packet.getBlockPosition();
                    var cursor = packet.getClickPosition();
                    player.compensatedWorld.advanceClientPredictionSequence();
                    PlaceHandler.handleNativeUseItemOn(player, new NmsPacketUtil.UseItemOnData(InteractionHand.MAIN_HAND,
                            GeyserBedrockBridgeRuntime.worldBlock(session, pos), FACES[packet.getBlockFace()],
                            new Vec3(cursor.getX(), cursor.getY(), cursor.getZ()), false, sequence));
                }
                if (packet.getActionType() == 1 || sequence - previousSequence > 1) {
                    float yaw = session.getPlayerEntity().getJavaYaw();
                    float pitch = session.getPlayerEntity().getPitch();
                    player.compensatedWorld.advanceClientPredictionSequence();
                    PlaceHandler.handleNativeUseItem(player, new NmsPacketUtil.UseItemData(InteractionHand.MAIN_HAND, sequence, yaw, pitch));
                    player.actionManager.useItem(InteractionHand.MAIN_HAND);
                    player.getInventory().useItem(InteractionHand.MAIN_HAND, yaw, pitch);
                }
            }
            default -> { }
        }
    }
}
