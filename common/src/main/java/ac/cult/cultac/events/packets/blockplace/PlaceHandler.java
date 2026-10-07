package ac.cult.cultac.events.packets.blockplace;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.protocol.util.SpigotConversionUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.blockplace.ClientBlockActions;
import ac.cult.cultac.utils.blockplace.SmoketestPredictionSafety;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.TraverseBlocks;

public class PlaceHandler {
    public static void handleQueuedUseItem(CultPlayer player, ServerboundUseItem place) {
        handleQueuedPlace(
                player, true, place.yaw(), place.pitch(), place.sequence(), () -> handleUseItem(player, place));
    }

    public static void handleQueuedUseItemOn(CultPlayer player, ServerboundUseItemOn place) {
        handleQueuedPlace(player, false, 0, 0, place.sequence(), () -> handleUseItemOn(player, place));
    }

    private static void handleQueuedPlace(
            CultPlayer player, boolean updateRotation, float yaw, float pitch, int sequence, Runnable action) {
        double lastX = player.x;
        double lastY = player.y;
        double lastZ = player.z;
        float lastXRot = player.xRot;
        float lastYRot = player.yRot;
        var lastBoundingBox = player.boundingBox;

        player.x = player.packetStateData.clientSidePosition.x;
        player.y = player.packetStateData.clientSidePosition.y;
        player.z = player.packetStateData.clientSidePosition.z;

        if (player.compensatedEntities.getSelf().getRiding() != null) {
            Vec3 posFromVehicle = BoundingBoxSize.getRidingOffsetFromVehicle(
                    player.compensatedEntities.getSelf().getRiding(), player);
            player.x = posFromVehicle.x;
            player.y = posFromVehicle.y;
            player.z = posFromVehicle.z;
        }

        if (updateRotation) {
            player.xRot = yaw;
            player.yRot = pitch;
        }

        if (player.isBedrockMovement()) {
            player.boundingBox = GetBoundingBox.getPlayerBoundingBox(player, player.x, player.y, player.z);
        }

        player.compensatedWorld.startPredicting();
        try (SmoketestPredictionSafety.Scope ignored =
                SmoketestPredictionSafety.enter(player, updateRotation ? "use-item" : "use-item-on")) {
            action.run();
        } finally {
            player.compensatedWorld.stopPredicting(sequence);
            player.x = lastX;
            player.y = lastY;
            player.z = lastZ;
            player.xRot = lastXRot;
            player.yRot = lastYRot;
            if (player.isBedrockMovement()) player.boundingBox = lastBoundingBox;
        }
    }

    private static void handleUseItem(CultPlayer player, ServerboundUseItem place) {
        ClientBlockActions.use(player, place.hand());
    }

    private static void handleUseItemOn(CultPlayer player, ServerboundUseItemOn place) {
        Hand hand = place.hand();
        ClientBlockActions.useOn(
                player,
                createUseItemOnBlockPlace(player, place, player.getInventory().getHandItem(hand)));
    }

    private static BlockPlace createUseItemOnBlockPlace(
            CultPlayer player, ServerboundUseItemOn place, SimItemStack placedWith) {
        BlockPlace blockPlace = new BlockPlace(
                player,
                place.hand(),
                place.blockPosition(),
                place.blockFace(),
                placedWith,
                TraverseBlocks.getNearestHitResult(player, true),
                place.sequence());
        blockPlace.setCursor(SpigotConversionUtil.fromProtocolVec(place.cursor()));
        blockPlace.setInside(place.insideBlock());
        return blockPlace;
    }
}
