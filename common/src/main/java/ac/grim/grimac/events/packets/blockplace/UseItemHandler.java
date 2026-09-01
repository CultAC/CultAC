package ac.grim.grimac.events.packets.blockplace;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockPlace;
import ac.grim.grimac.utils.blockplace.NmsBlockPlaceResolver;
import ac.grim.grimac.utils.data.HitData;
import ac.grim.grimac.utils.inventory.Inventory;
import ac.grim.grimac.utils.nmsutil.NmsBlockTags;
import ac.grim.grimac.utils.nmsutil.TraverseBlocks;
import net.minecraft.world.phys.Vec3;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import net.minecraft.core.BlockPos;
import org.bukkit.GameMode;
import net.minecraft.world.InteractionHand;

public class UseItemHandler {
    public static void handleUseItem(GrimPlayer player, ItemStack placedWith, InteractionHand hand) {
        // Lilypads are USE_ITEM (THIS CAN DESYNC, WTF MOJANG)
        if (placedWith.getType() == Material.LILY_PAD) {
            placeLilypad(player, placedWith, hand); // Pass a block place because lily pads have a hitbox
            return;
        }

        Material toBucketMat = NmsBlockTags.transformBucketMaterial(placedWith.getType());
        if (toBucketMat != null) {
            UseItemHandler.placeWaterLavaSnowBucket(player, placedWith, hand);
            return;
        }

        if (placedWith.getType() == Material.BUCKET) {
            placeBucket(player, hand);
        }
    }

    public static void placeWaterLavaSnowBucket(GrimPlayer player, ItemStack held, InteractionHand hand) {
        HitData data = TraverseBlocks.getNearestHitResult(player, Material.AIR, false);
        if (data != null) {
            BlockPlace blockPlace = new BlockPlace(player, hand, data.getPosition(), data.getClosestDirection(), held, data);
            blockPlace.setUseItem(true);

            boolean didPlace = NmsBlockPlaceResolver.applyBucketPlace(player, blockPlace);
            if (didPlace && player.gamemode != GameMode.CREATIVE) {
                if (hand == InteractionHand.MAIN_HAND) {
                    player.getInventory().inventory.setHeldItem(new ItemStack(Material.BUCKET, 1));
                } else {
                    player.getInventory().inventory.setPlayerInventoryItem(
                            Inventory.SLOT_OFFHAND,
                            new ItemStack(Material.BUCKET, 1));
                }
            }
        }
    }

    private static void placeLilypad(GrimPlayer player, ItemStack placedWith, InteractionHand hand) {
        HitData data = TraverseBlocks.getNearestPlaceOnWaterHitResult(player);

        if (data == null) {
            return;
        }

        BlockPos clickedPos = data.getPosition().above();
        BlockPlace blockPlace = new BlockPlace(player, hand, clickedPos, data.getClosestDirection(), placedWith, data);
        blockPlace.setCursor(new Vec3(
                data.getBlockHitLocation().getX() - clickedPos.getX(),
                data.getBlockHitLocation().getY() - clickedPos.getY(),
                data.getBlockHitLocation().getZ() - clickedPos.getZ()
        ));
        blockPlace.setUseItem(true);

        NmsBlockPlaceResolver.applyBlockPlace(player, blockPlace);
    }

    private static void placeBucket(GrimPlayer player, InteractionHand hand) {
        HitData data = TraverseBlocks.getNearestHitResult(player, null, true);

        if (data != null) {
            ItemStack held = player.getInventory().getHandItem(hand);
            BlockPlace blockPlace = new BlockPlace(player, hand, data.getPosition(), data.getClosestDirection(), held, data);
            blockPlace.setReplaceClicked(true); // Replace the block clicked, not the block in the direction
            blockPlace.setUseItem(true);

            Material type = NmsBlockPlaceResolver.applyBucketPickup(player, blockPlace);
            if (type == null) {
                return;
            }

            if (player.gamemode != GameMode.CREATIVE) {
                setPlayerItem(player, hand, type);
            }
        }
    }

    public static void setPlayerItem(GrimPlayer player, InteractionHand hand, Material type) {
        if (player.gamemode == GameMode.CREATIVE) {
            return;
        }

        ItemStack held = player.getInventory().getHandItem(hand);
        if (held.getAmount() == 1) {
            if (hand == InteractionHand.MAIN_HAND) {
                player.getInventory().inventory.setHeldItem(new ItemStack(type, 1));
            } else {
                player.getInventory().inventory.setPlayerInventoryItem(Inventory.SLOT_OFFHAND, new ItemStack(type, 1));
            }
            return;
        }

        player.getInventory().inventory.add(new ItemStack(type, 1));
        held.setAmount(held.getAmount() - 1);
    }
}
