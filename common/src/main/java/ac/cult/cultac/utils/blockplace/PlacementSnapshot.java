package ac.cult.cultac.utils.blockplace;

import ac.cult.cultac.network.protocol.util.SpigotConversionUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import lombok.Getter;
import ac.cult.cultac.network.protocol.ClientVersion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.bukkit.GameMode;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

@Getter
public final class PlacementSnapshot {
    private final ClientVersion clientVersion;
    private final InteractionHand hand;
    private final org.bukkit.inventory.ItemStack bukkitItemStack;
    private final ItemStack itemStack;
    private final ItemStack mainHandItemStack;
    private final ItemStack offHandItemStack;
    private final BlockPos clickedBlockPos;
    private final BlockPos placedBlockPos;
    private final Direction clickedFace;
    private final Vec3 clickLocation;
    private final Vec3 relativeClickLocation;
    private final boolean insideBlock;
    private final Vec3 clientPosition;
    private final float xRot;
    private final float yRot;
    private final Direction horizontalDirection;
    private final boolean secondaryUse;
    private final GameMode gameMode;
    private final int foodLevel;
    private final int minY;
    private final int maxY;
    private final boolean replaceClicked;

    private PlacementSnapshot(
            InteractionHand hand,
            org.bukkit.inventory.ItemStack bukkitItemStack,
            ItemStack itemStack,
            ItemStack mainHandItemStack,
            ItemStack offHandItemStack,
            BlockPos clickedBlockPos,
            BlockPos placedBlockPos,
            Direction clickedFace,
            Vec3 clickLocation,
            Vec3 relativeClickLocation,
            boolean insideBlock,
            Vec3 clientPosition,
            float xRot,
            float yRot,
            Direction horizontalDirection,
            boolean secondaryUse,
            GameMode gameMode,
            int foodLevel,
            int minY,
            int maxY,
            boolean replaceClicked,
            ClientVersion clientVersion
    ) {
        this.clientVersion = clientVersion;
        this.hand = hand;
        this.bukkitItemStack = bukkitItemStack;
        this.itemStack = itemStack;
        this.mainHandItemStack = mainHandItemStack;
        this.offHandItemStack = offHandItemStack;
        this.clickedBlockPos = clickedBlockPos;
        this.placedBlockPos = placedBlockPos;
        this.clickedFace = clickedFace;
        this.clickLocation = clickLocation;
        this.relativeClickLocation = relativeClickLocation;
        this.insideBlock = insideBlock;
        this.clientPosition = clientPosition;
        this.xRot = xRot;
        this.yRot = yRot;
        this.horizontalDirection = horizontalDirection;
        this.secondaryUse = secondaryUse;
        this.gameMode = gameMode;
        this.foodLevel = foodLevel;
        this.minY = minY;
        this.maxY = maxY;
        this.replaceClicked = replaceClicked;
    }

    public static PlacementSnapshot of(
            InteractionHand hand,
            org.bukkit.inventory.ItemStack bukkitItemStack,
            ItemStack itemStack,
            ItemStack mainHandItemStack,
            ItemStack offHandItemStack,
            BlockPos clickedBlockPos,
            BlockPos placedBlockPos,
            Direction clickedFace,
            Vec3 clickLocation,
            Vec3 relativeClickLocation,
            boolean insideBlock,
            Vec3 clientPosition,
            float xRot,
            float yRot,
            Direction horizontalDirection,
            boolean secondaryUse,
            GameMode gameMode,
            int foodLevel,
            int minY,
            int maxY,
            boolean replaceClicked
    ) {
        return new PlacementSnapshot(
                hand,
                bukkitItemStack,
                itemStack,
                mainHandItemStack,
                offHandItemStack,
                clickedBlockPos,
                placedBlockPos,
                clickedFace,
                clickLocation,
                relativeClickLocation,
                insideBlock,
                clientPosition,
                xRot,
                yRot,
                horizontalDirection,
                secondaryUse,
                gameMode,
                foodLevel,
                minY,
                maxY,
                replaceClicked,
                ClientVersion.fromProtocolVersion(net.minecraft.SharedConstants.getProtocolVersion())
        );
    }

    public static PlacementSnapshot of(
            InteractionHand hand,
            org.bukkit.inventory.ItemStack bukkitItemStack,
            ItemStack itemStack,
            BlockPos clickedBlockPos,
            BlockPos placedBlockPos,
            Direction clickedFace,
            Vec3 clickLocation,
            Vec3 relativeClickLocation,
            boolean insideBlock,
            Vec3 clientPosition,
            float xRot,
            float yRot,
            Direction horizontalDirection,
            boolean secondaryUse,
            GameMode gameMode,
            int minY,
            int maxY,
            boolean replaceClicked
    ) {
        return of(
                hand,
                bukkitItemStack,
                itemStack,
                hand == InteractionHand.MAIN_HAND ? itemStack : ItemStack.EMPTY,
                hand == InteractionHand.OFF_HAND ? itemStack : ItemStack.EMPTY,
                clickedBlockPos,
                placedBlockPos,
                clickedFace,
                clickLocation,
                relativeClickLocation,
                insideBlock,
                clientPosition,
                xRot,
                yRot,
                horizontalDirection,
                secondaryUse,
                gameMode,
                20,
                minY,
                maxY,
                replaceClicked
        );
    }

    public static PlacementSnapshot capture(CultPlayer player, BlockPlace place) {
        Vector relative = place.getClickedLocation();
        BlockPos clickedBlockPos = place.getPlacedAgainstBlockLocation().immutable();
        Vec3 relativeClickLocation = new Vec3(relative.getX(), relative.getY(), relative.getZ());
        Vec3 clickLocation = new Vec3(
                clickedBlockPos.getX() + relative.getX(),
                clickedBlockPos.getY() + relative.getY(),
                clickedBlockPos.getZ() + relative.getZ()
        );
        Vec3 clientPosition = new Vec3(
                player.packetStateData.clientSidePosition.x,
                player.packetStateData.clientSidePosition.y,
                player.packetStateData.clientSidePosition.z
        );
        // ServerboundUseItemOnPacket carries the hit vector but not yaw/pitch. Vanilla client placement
        // uses the camera that produced this hit, so reconstruct that context from eye position -> hit.
        ViewRotation viewRotation = viewRotationFromHit(clientPosition, player.getEyeHeight(), clickLocation, player.xRot, player.yRot);

        return new PlacementSnapshot(
                place.getHand(),
                place.getItemStack(),
                SpigotConversionUtil.toNmsItemStack(place.getItemStack()),
                SpigotConversionUtil.toNmsItemStack(player.getInventory().getHeldItem()),
                SpigotConversionUtil.toNmsItemStack(player.getInventory().getOffHand()),
                clickedBlockPos,
                place.getPlacedBlockPos().immutable(),
                toDirection(place.getDirection()),
                clickLocation,
                relativeClickLocation,
                place.isInside(),
                clientPosition,
                viewRotation.xRot(),
                viewRotation.yRot(),
                horizontalDirectionFromYaw(viewRotation.yRot()),
                place.isSecondaryUse(),
                player.gamemode,
                player.food,
                player.compensatedWorld.getMinHeight(),
                player.compensatedWorld.getMaxHeight(),
                place.isReplaceClicked(),
                player.getClientVersion()
        );
    }

    public static PlacementSnapshot captureBreak(CultPlayer player, BlockPos blockPosition) {
        BlockPos immutablePos = blockPosition.immutable();
        Vec3 clickLocation = new Vec3(
                immutablePos.getX() + 0.5D,
                immutablePos.getY() + 0.5D,
                immutablePos.getZ() + 0.5D
        );
        Vec3 clientPosition = new Vec3(
                player.packetStateData.clientSidePosition.x,
                player.packetStateData.clientSidePosition.y,
                player.packetStateData.clientSidePosition.z
        );

        return new PlacementSnapshot(
                InteractionHand.MAIN_HAND,
                null,
                ItemStack.EMPTY,
                SpigotConversionUtil.toNmsItemStack(player.getInventory().getHeldItem()),
                SpigotConversionUtil.toNmsItemStack(player.getInventory().getOffHand()),
                immutablePos,
                immutablePos,
                Direction.UP,
                clickLocation,
                new Vec3(0.5D, 0.5D, 0.5D),
                false,
                clientPosition,
                player.xRot,
                player.yRot,
                horizontalDirectionFromYaw(player.yRot),
                false,
                player.gamemode,
                player.food,
                player.compensatedWorld.getMinHeight(),
                player.compensatedWorld.getMaxHeight(),
                true,
                player.getClientVersion()
        );
    }

    public boolean isOutsideBuildHeight(BlockPos pos) {
        return pos.getY() < minY || pos.getY() >= maxY;
    }

    public boolean isCreative() {
        return gameMode == GameMode.CREATIVE;
    }

    private static Direction toDirection(BlockFace face) {
        return switch (face) {
            case DOWN -> Direction.DOWN;
            case UP -> Direction.UP;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case WEST -> Direction.WEST;
            case EAST -> Direction.EAST;
            default -> Direction.DOWN;
        };
    }

    private static ViewRotation viewRotationFromHit(Vec3 clientPosition, double eyeHeight, Vec3 clickLocation, float fallbackYaw, float fallbackPitch) {
        double dx = clickLocation.x - clientPosition.x;
        double dy = clickLocation.y - (clientPosition.y + eyeHeight);
        double dz = clickLocation.z - clientPosition.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance < 1.0E-7D && Math.abs(dy) < 1.0E-7D) {
            return new ViewRotation(fallbackPitch, fallbackYaw);
        }

        float yRot = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float xRot = (float) (-Math.toDegrees(Math.atan2(dy, horizontalDistance)));
        return new ViewRotation(xRot, yRot);
    }

    private static Direction horizontalDirectionFromYaw(float yRot) {
        return switch (((int) Math.floor(yRot / 90.0F + 0.5F)) & 3) {
            case 0 -> Direction.SOUTH;
            case 1 -> Direction.WEST;
            case 2 -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    private record ViewRotation(float xRot, float yRot) {
    }
}
