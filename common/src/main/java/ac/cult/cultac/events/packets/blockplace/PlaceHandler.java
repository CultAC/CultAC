package ac.cult.cultac.events.packets.blockplace;

import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.blockplace.NmsBlockPlaceResolver;
import ac.cult.cultac.utils.blockplace.SmoketestPredictionSafety;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.TraverseBlocks;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import org.bukkit.GameMode;
import net.minecraft.world.InteractionHand;

public class PlaceHandler {
    public static void handleQueuedUseItem(CultPlayer player, ServerboundUseItemPacket packet) {
        NmsPacketUtil.UseItemData place = NmsPacketUtil.readUseItem(packet);
        handleQueuedPlace(player, true, place.yaw(), place.pitch(), place.sequence(), () -> handleUseItem(player, place));
    }

    public static void handleQueuedUseItemOn(CultPlayer player, ServerboundUseItemOnPacket packet) {
        NmsPacketUtil.UseItemOnData place = NmsPacketUtil.readUseItemOn(packet);
        handleQueuedPlace(player, false, 0, 0, place.sequence(), () -> handleUseItemOn(player, place));
    }

    private static void handleQueuedPlace(CultPlayer player, boolean updateRotation, float yaw, float pitch, int sequence, Runnable action) {
        // Handle queue'd block places
        double lastX = player.x;
        double lastY = player.y;
        double lastZ = player.z;
        float lastXRot = player.xRot;
        float lastYRot = player.yRot;

        player.x = player.packetStateData.clientSidePosition.x;
        player.y = player.packetStateData.clientSidePosition.y;
        player.z = player.packetStateData.clientSidePosition.z;

        if (player.compensatedEntities.getSelf().getRiding() != null) {
            Vec3 posFromVehicle = BoundingBoxSize.getRidingOffsetFromVehicle(player.compensatedEntities.getSelf().getRiding(), player);
            player.x = posFromVehicle.x;
            player.y = posFromVehicle.y;
            player.z = posFromVehicle.z;
        }

        if (updateRotation) {
            player.xRot = yaw;
            player.yRot = pitch;
        }

        player.compensatedWorld.startPredicting();
        try (SmoketestPredictionSafety.Scope ignored = SmoketestPredictionSafety.enter(
                player,
                updateRotation ? "use-item" : "use-item-on"
        )) {
            action.run();
        } finally {
            player.compensatedWorld.stopPredicting(sequence);
            player.x = lastX;
            player.y = lastY;
            player.z = lastZ;
            player.xRot = lastXRot;
            player.yRot = lastYRot;
        }
    }

    private static void handleUseItem(CultPlayer player, NmsPacketUtil.UseItemData place) {
        if (player.gamemode == GameMode.SPECTATOR || player.gamemode == GameMode.ADVENTURE) return;

        ItemStack placedWith = player.getInventory().getHandItem(place.hand());
        UseItemHandler.handleUseItem(player, placedWith, place.hand());
    }

    private static void handleUseItemOn(CultPlayer player, NmsPacketUtil.UseItemOnData place) {
        // Check for interactable first (door, etc)
        ItemStack placedWith = player.getInventory().getHandItem(place.hand());
        ItemStack offhand = player.getInventory().getOffHand();

        boolean onlyAir = placedWith.isEmpty() && offhand.isEmpty();

        // The offhand is unable to interact with blocks like this... try to stop some desync points before they happen
        if ((!player.isSneaking || onlyAir) && place.hand() == InteractionHand.MAIN_HAND) {
            BlockPlace blockPlace = createUseItemOnBlockPlace(player, place, placedWith);

            boolean consumesPlace = NmsBlockPlaceResolver.applyBlockUse(player, blockPlace);

            if (player.debugPlaces) { player.sendMessage("Place: use=" + blockPlace.isUseItem() + " place=" + blockPlace.isPlaced() + " isBlock=" + blockPlace.isBlock() + " consumes=" + consumesPlace); }

            if (consumesPlace) { return; }
        }

        if (player.gamemode == GameMode.SPECTATOR || player.gamemode == GameMode.ADVENTURE) return;

        BlockPlace blockPlace = createUseItemOnBlockPlace(player, place, placedWith);

        if (player.checkManager.getCompensatedCooldown().hasItem(placedWith)) {
            return;
        }

        if (placedWith.getType() == Material.FIRE_CHARGE || placedWith.getType() == Material.FLINT_AND_STEEL) {
            NmsBlockPlaceResolver.applyIgnitionItem(player, blockPlace, placedWith.getType() == Material.FIRE_CHARGE);
            return;
        }

        if (placedWith.getType() == Material.POWDER_SNOW_BUCKET) {
            var before = ac.cult.cultac.utils.blockplace.PlacementSnapshot.capture(player, blockPlace);
            if (NmsBlockPlaceResolver.applyBlockPlace(player, blockPlace)) {
                if (player.gamemode != GameMode.CREATIVE) UseItemHandler.setPlayerItem(player, place.hand(), Material.BUCKET);
                NmsBlockPlaceResolver.applyAfterUseOn(player, before);
            } else {
                NmsBlockPlaceResolver.applyWorldModifyingUseItem(player, blockPlace);
            }
            return;
        }

        // BlockItem.useOn tries placement before falling back to Item.useOn.
        if (blockPlace.isBlock() && NmsBlockPlaceResolver.applyBlockPlace(player, blockPlace)) {
            return;
        }
        if (NmsBlockPlaceResolver.applyClientSideUseOnItem(player, blockPlace)) {
            return;
        }
        NmsBlockPlaceResolver.applyWorldModifyingUseItem(player, blockPlace);
    }

    private static BlockPlace createUseItemOnBlockPlace(CultPlayer player, NmsPacketUtil.UseItemOnData place, ItemStack placedWith) {
        BlockPlace blockPlace = new BlockPlace(
                player,
                place.hand(),
                place.blockPosition(),
                place.blockFace(),
                placedWith,
                TraverseBlocks.getNearestHitResult(player, null, true),
                place.sequence());
        blockPlace.setCursor(place.cursor());
        blockPlace.setInside(place.insideBlock());
        return blockPlace;
    }
}
