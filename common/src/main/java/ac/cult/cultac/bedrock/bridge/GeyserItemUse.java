package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.events.packets.blockplace.PlaceHandler;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.Vec3d;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.util.BlockUtils;

final class GeyserItemUse {
    private GeyserItemUse() {}

    static int sequence(GeyserSession session) {
        try {
            var field = org.geysermc.geyser.session.cache.WorldCache.class.getDeclaredField("currentSequence");
            field.setAccessible(true);
            return field.getInt(session.getWorldCache());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Geyser prediction sequence", failure);
        }
    }

    private static final Direction[] FACES = Direction.values();

    static boolean allow(GeyserSession session, CultPlayer player, InventoryTransactionPacket packet) {
        if (packet.getTransactionType() != InventoryTransactionType.ITEM_USE || packet.getActionType() != 0)
            return true;
        if (!player.getSetbackTeleportUtil().isPendingSetback()) return true;
        var position = packet.getBlockPosition();
        BlockUtils.restoreCorrectBlock(session, position, packet.getHotbarSlot());
        if (packet.getBlockFace() >= 0 && packet.getBlockFace() < FACES.length) {
            var face = FACES[packet.getBlockFace()];
            BlockUtils.restoreCorrectBlock(
                    session, position.add(face.getModX(), face.getModY(), face.getModZ()), packet.getHotbarSlot());
        }
        return false;
    }

    static void observe(
            GeyserSession session, CultPlayer player, InventoryTransactionPacket packet, int previousSequence) {
        switch (packet.getTransactionType()) {
            case ITEM_RELEASE -> {
                if (packet.getActionType() == 0) player.actionManager.releaseItem();
            }
            case ITEM_USE_ON_ENTITY -> GeyserEntityInteractions.observe(session, player, packet);
            case ITEM_USE -> {
                int sequence = sequence(session);
                if (sequence == previousSequence && packet.getActionType() != 0) return;
                player.lastBlockPlaceUseItem = System.currentTimeMillis();
                if (packet.getActionType() == 0 && packet.getBlockFace() >= 0 && packet.getBlockFace() < FACES.length) {
                    var pos = packet.getBlockPosition();
                    var cursor = packet.getClickPosition();
                    player.compensatedWorld.advanceClientPredictionSequence();
                    var worldPos = GeyserBedrockBridgeRuntime.worldBlock(session, pos);
                    PlaceHandler.handleQueuedUseItemOn(
                            player,
                            new ServerboundUseItemOn(
                                    Hand.MAIN_HAND,
                                    new BlockPos(worldPos.getX(), worldPos.getY(), worldPos.getZ()),
                                    FACES[packet.getBlockFace()],
                                    new Vec3d(cursor.getX(), cursor.getY(), cursor.getZ()),
                                    false,
                                    false,
                                    sequence));
                }
                if (packet.getActionType() == 1 || sequence - previousSequence > 1) {
                    float yaw = session.getPlayerEntity().getJavaYaw();
                    float pitch = session.getPlayerEntity().getPitch();
                    // Native equipment and active item use have their own observers below.
                    // Only the raycast block actions formerly handled by UseItemHandler
                    // belong in the host block-action runtime (buckets and water plants).
                    var held = player.getInventory().getHandItem(Hand.MAIN_HAND);
                    if (java.util.List.of(held.definition()
                                            .bindings()
                                            .get("classHierarchy")
                                            .split(","))
                                    .contains("net.minecraft.world.item.BucketItem")
                            || java.util.List.of(held.definition()
                                            .bindings()
                                            .get("classHierarchy")
                                            .split(","))
                                    .contains("net.minecraft.world.item.PlaceOnWaterBlockItem")) {
                        player.compensatedWorld.advanceClientPredictionSequence();
                        PlaceHandler.handleQueuedUseItem(
                                player, new ServerboundUseItem(Hand.MAIN_HAND, sequence, yaw, pitch));
                    }
                    player.actionManager.useItem(Hand.MAIN_HAND);
                    player.getInventory().useItem(Hand.MAIN_HAND, yaw, pitch);
                }
            }
            default -> {}
        }
    }
}
