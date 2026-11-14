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
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

public class PlaceHandler {
    /** The prediction applied to the simulation world, or {@code null} when none was made. */
    public static @Nullable ClientBlockActions.Prediction handleQueuedUseItem(
            CultPlayer player, ServerboundUseItem place) {
        return handleQueuedPlace(
                player, true, place.yaw(), place.pitch(), place.sequence(), () -> handleUseItem(player, place));
    }

    /** The prediction applied to the simulation world. */
    public static ClientBlockActions.Prediction handleQueuedUseItemOn(CultPlayer player, ServerboundUseItemOn place) {
        return handleQueuedPlace(player, false, 0, 0, place.sequence(), () -> handleUseItemOn(player, place));
    }

    public static ClientBlockActions.Prediction predictRejectedUseItemOn(
            CultPlayer player, ServerboundUseItemOn place) {
        return atClientPosition(
                player,
                false,
                0,
                0,
                () -> ClientBlockActions.predictUseOn(
                        player,
                        createUseItemOnBlockPlace(
                                player, place, player.getInventory().getHandItem(place.hand()))));
    }

    private static ClientBlockActions.Prediction handleQueuedPlace(
            CultPlayer player,
            boolean updateRotation,
            float yaw,
            float pitch,
            int sequence,
            Supplier<ClientBlockActions.Prediction> action) {
        return atClientPosition(player, updateRotation, yaw, pitch, () -> {
            player.compensatedWorld.startPredicting();
            try (SmoketestPredictionSafety.Scope ignored =
                    SmoketestPredictionSafety.enter(player, updateRotation ? "use-item" : "use-item-on")) {
                return action.get();
            } finally {
                player.compensatedWorld.stopPredicting(sequence);
            }
        });
    }

    private static <T> T atClientPosition(
            CultPlayer player, boolean updateRotation, float yaw, float pitch, Supplier<T> action) {
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

        try {
            return action.get();
        } finally {
            player.x = lastX;
            player.y = lastY;
            player.z = lastZ;
            player.xRot = lastXRot;
            player.yRot = lastYRot;
            if (player.isBedrockMovement()) player.boundingBox = lastBoundingBox;
        }
    }

    private static ClientBlockActions.Prediction handleUseItem(CultPlayer player, ServerboundUseItem place) {
        return ClientBlockActions.use(player, place.hand());
    }

    private static ClientBlockActions.Prediction handleUseItemOn(CultPlayer player, ServerboundUseItemOn place) {
        Hand hand = place.hand();
        return ClientBlockActions.useOn(
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
                player.compensatedWorld.withSelectionSnapshot(() -> TraverseBlocks.getNearestHitResult(player, true)),
                place.sequence());
        blockPlace.setCursor(SpigotConversionUtil.fromProtocolVec(place.cursor()));
        blockPlace.setInside(place.insideBlock());
        return blockPlace;
    }
}
