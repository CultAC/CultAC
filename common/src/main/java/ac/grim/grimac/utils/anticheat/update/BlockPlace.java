package ac.grim.grimac.utils.anticheat.update;

import ac.grim.grimac.checks.impl.movement.GhostBlockMitigator;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.collisions.ClientBlockShapes;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.HitData;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.nmsutil.BoundingBoxSize;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import ac.grim.grimac.utils.nmsutil.NmsBlockTags;
import net.minecraft.tags.BlockTags;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import net.minecraft.world.phys.Vec3;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;


public class BlockPlace {
    @Setter
    BlockPos blockPosition;
    @Getter
    InteractionHand hand;
    @Getter
    @Setter
    boolean replaceClicked;
    boolean isCancelled = false;
    @Getter
    @Setter
    boolean isUseItem = false;
    GrimPlayer player;
    @Getter
    ItemStack itemStack;
    @Getter
    final Material material;
    @Getter @Nullable
    HitData hitData;
    @Setter
    BlockFace face;
    @Getter
    @Setter
    boolean isInside;
    @Getter
    Vec3 cursor;

    @Getter private boolean placed;

    @Getter private final boolean block;
    public final int sequence;

    public BlockPlace(GrimPlayer player, InteractionHand hand, BlockPos blockPosition, BlockFace face, ItemStack itemStack, HitData hitData) {
        this(player, hand, blockPosition, face, itemStack, hitData, 0);
    }

    public BlockPlace(GrimPlayer player, InteractionHand hand, BlockPos blockPosition, BlockFace face,
                      ItemStack itemStack, HitData hitData, int sequence) {
        this.player = player;
        this.hand = hand;
        this.face = face;
        this.itemStack = itemStack;
        this.hitData = hitData;
        this.blockPosition = blockPosition == null ? null : blockPosition.immutable();
        this.sequence = sequence;

        Material placedType = resolvePlacedType(itemStack.getType());
        this.material = placedType == null ? Material.FIRE : placedType;
        this.block = placedType != null;

        BlockData clickedState = player.compensatedWorld.getBlockDataAt(getPlacedAgainstBlockLocation());
        this.replaceClicked = canBeReplaced(this.material, clickedState, face);
    }

    // TODO: Replace with NMS?
    private static Material resolvePlacedType(Material material) {
        // MCP-Reborn MultiPlayerGameMode#useItemOn sends ServerboundUseItemOnPacket
        // for any in-bounds block hit; an empty hand is interaction, not placement.
        if (material == null || material.isAir()) {
            return null;
        }

        return switch (material) {
            case REDSTONE -> Material.REDSTONE_WIRE;
            case CARROT -> Material.CARROTS;
            case POTATO -> Material.POTATOES;
            case WHEAT_SEEDS -> Material.WHEAT;
            case BEETROOT_SEEDS -> Material.BEETROOTS;
            case MELON_SEEDS -> Material.MELON_STEM;
            case PUMPKIN_SEEDS -> Material.PUMPKIN_STEM;
            case TORCHFLOWER_SEEDS -> Material.TORCHFLOWER_CROP;
            case COCOA_BEANS -> Material.COCOA;
            case SWEET_BERRIES -> Material.SWEET_BERRY_BUSH;
            case GLOW_BERRIES -> Material.CAVE_VINES;
            default -> material.isBlock() ? material : null;
        };
    }

    public void setCursor(Vec3 cursor) {
        this.cursor = cursor == null ? null : new Vec3(cursor.x, cursor.y, cursor.z);
    }

    public BlockPos getPlacedAgainstBlockLocation() {
        return blockPosition;
    }

    public Material getPlacedAgainstMaterial() {
        return player.compensatedWorld.getBlockDataAt(getPlacedAgainstBlockLocation()).getMaterial();
    }

    // TODO: Rely on NMS?
    private boolean canBeReplaced(Material heldItem, BlockData state, BlockFace face) {
        // Cave vines and weeping vines have a special case... that always returns false (just like the base case for it!)
        boolean baseReplaceable = state.getMaterial() != heldItem && NmsBlockTags.isReplaceable(state.getMaterial());
        BlockState nmsState = NmsBlockTags.toNmsState(state);

        if (nmsState.is(BlockTags.CANDLES)) {
            return heldItem == state.getMaterial() && NmsBlockTags.getInt(nmsState, BlockStateProperties.CANDLES, 0) < 4 && !isSecondaryUse();
        }
        if (state.getMaterial() == Material.SEA_PICKLE) {
            return heldItem == state.getMaterial() && NmsBlockTags.getInt(nmsState, BlockStateProperties.PICKLES, 0) < 4 && !isSecondaryUse();
        }
        if (state.getMaterial() == Material.TURTLE_EGG) {
            return heldItem == state.getMaterial() && NmsBlockTags.getInt(nmsState, BlockStateProperties.EGGS, 0) < 4 && !isSecondaryUse();
        }
        // Glow lichen can be replaced if it has an open face, or the player is placing something
        if (state.getMaterial() == Material.GLOW_LICHEN) {
            if (heldItem != Material.GLOW_LICHEN) {
                return true;
            }
            if (!NmsBlockTags.hasDirection(nmsState, BlockFace.UP)) return true;
            if (!NmsBlockTags.hasDirection(nmsState, BlockFace.DOWN)) return true;
            if (!NmsBlockTags.hasDirection(nmsState, BlockFace.NORTH)) return true;
            if (!NmsBlockTags.hasDirection(nmsState, BlockFace.SOUTH)) return true;
            if (!NmsBlockTags.hasDirection(nmsState, BlockFace.EAST)) return true;
            return !NmsBlockTags.hasDirection(nmsState, BlockFace.WEST);
        }
        if (state.getMaterial() == Material.SCAFFOLDING) {
            return heldItem == Material.SCAFFOLDING;
        }
        if (NmsBlockTags.isSlab(nmsState)) {
            SlabType slabType = nmsState.getValue(BlockStateProperties.SLAB_TYPE);
            if (slabType == SlabType.DOUBLE || state.getMaterial() != heldItem) return false;

            // Here vanilla refers from
            // Set check can replace -> get block -> call block canBeReplaced -> check can replace boolean (default true)
            // uh... what?  I'm unsure what Mojang is doing here.  I think they just made a stupid mistake.
            // as this code is quite old.
            boolean flag = getClickedLocation().getY() > 0.5D;
            BlockFace clickedFace = getDirection();
            if (slabType == SlabType.BOTTOM) {
                return clickedFace == BlockFace.UP || flag && isFaceHorizontal();
            } else {
                return clickedFace == BlockFace.DOWN || !flag && isFaceHorizontal();
            }
        }
        if (state.getMaterial() == Material.SNOW) {
            int layers = NmsBlockTags.getInt(nmsState, BlockStateProperties.LAYERS, 0);
            if (heldItem == state.getMaterial() && layers < 8) { // We index at 1 (less than 8 layers)
                return face == BlockFace.UP;
            } else {
                return layers == 1; // index at 1, (1 layer)
            }
        }
        if (state.getMaterial() == Material.VINE) {
            if (baseReplaceable) return true;
            if (heldItem != state.getMaterial()) return false;
            if (!NmsBlockTags.hasDirection(nmsState, BlockFace.UP))
                return true;
            if (!NmsBlockTags.hasDirection(nmsState, BlockFace.NORTH)) return true;
            if (!NmsBlockTags.hasDirection(nmsState, BlockFace.SOUTH)) return true;
            if (!NmsBlockTags.hasDirection(nmsState, BlockFace.EAST)) return true;
            return !NmsBlockTags.hasDirection(nmsState, BlockFace.WEST);
        }

        return baseReplaceable;
    }

    // I believe this is correct, although I'm using a method here just in case it's a tick off... I don't trust Mojang
    public boolean isSecondaryUse() {
        return player.isSneaking;
    }

    public BlockFace getDirection() {
        return face;
    }

    public boolean isFaceHorizontal() {
        BlockFace face = getDirection();
        return face == BlockFace.NORTH || face == BlockFace.EAST || face == BlockFace.SOUTH || face == BlockFace.WEST;
    }

    public boolean isCancelled() {
        return isCancelled;
    }

    public BlockPos getPlacedBlockPos() {
        if (replaceClicked) return blockPosition;

        BlockPos normal = getNormalBlockFace();
        return blockPosition.offset(normal.getX(), normal.getY(), normal.getZ());
    }

    public BlockPos getNormalBlockFace() {
        switch (face) {
            default:
            case UP:
                return new BlockPos(0, 1, 0);
            case DOWN:
                return new BlockPos(0, -1, 0);
            case SOUTH:
                return new BlockPos(0, 0, 1);
            case NORTH:
                return new BlockPos(0, 0, -1);
            case WEST:
                return new BlockPos(-1, 0, 0);
            case EAST:
                return new BlockPos(1, 0, 0);
        }
    }

    public void set(Material material) {
        set(material.createBlockData());
    }

    public boolean applyResolvedPrimary(BlockPos position, BlockData state) {
        return applyResolvedState(position, state, true, true);
    }

    public void applyResolvedSecondary(BlockPos position, BlockData state) {
        applyResolvedState(position, state, false, false);
    }

    public void set(BlockFace face, BlockData state) {
        BlockPos blockPos = getPlacedBlockPos().offset(face.getModX(), face.getModY(), face.getModZ());
        set(blockPos, state);
    }

    public void set(BlockPos position, BlockData state) {
        // Hack for scaffolding to be the correct bounding box
        CollisionBox box = ClientBlockShapes.movement(player, state, position.getX(), position.getY(), position.getZ());

        if (player.debugPlaces) player.sendMessage("Placing start " + formatState(state) + " at " + position);

        // Note scaffolding is a special case because it can never intersect with the player's bounding box,
        // and we fetch it with lastY instead of y which is wrong, so it is easier to just ignore scaffolding here
        if (state.getMaterial() != Material.SCAFFOLDING && isPlacementObstructed(box)) {
            return;
        }

        // If a block already exists here, then we can't override it.
        BlockData existingState = player.compensatedWorld.getBlockDataAt(position);
        if (!replaceClicked && !canBeReplaced(material, existingState, face)) {
            return;
        }

        // Check for min and max bounds of world
        if (player.compensatedWorld.getMaxHeight() <= position.getY() || position.getY() < player.compensatedWorld.getMinHeight()) {
            return;
        }

        // Check for waterlogged
        BlockState nmsState = NmsBlockTags.toNmsState(state);
        if (nmsState.hasProperty(BlockStateProperties.WATERLOGGED)) {
            boolean waterlogged = existingState.getMaterial() == Material.WATER && NmsBlockTags.isWaterSource(existingState);
            state = withWaterlogged(nmsState, waterlogged);
        }
        if (player.debugPlaces) player.sendMessage("Block actually placed " + formatState(state) + " at " + position);
        placed = true;
        player.getInventory().onBlockPlace(this);
        BlockData original = player.compensatedWorld.updateBlock(position.getX(), position.getY(), position.getZ(), NmsBlockTags.toNmsState(state));
        GhostBlockMitigator mitigator = player.getGhostBlockMitigator();

        mitigator.handleBlockPlace(position, original, state, this);
    }

    private boolean isPlacementObstructed(CollisionBox box) {
        // A player cannot place a block in themselves.
        // 0.03 can desync quite easily
        // 0.002 desync must be done with teleports, it is very difficult to do with slightly moving.
        if (box.isIntersected(player.boundingBox)) {
            return true;
        }

        // Other entities can also block block-placing
        // This sucks and desyncs constantly, but what can you do?
        //
        // 1.9+ introduced the mechanic where both the client and server must agree upon a block place
        // 1.8 clients will simply not send the place when it fails, thanks mojang.
        for (PacketEntity entity : player.compensatedEntities.entityMap.values()) {
            if (box.isIntersected(currentEntityCollisionBox(entity))) {
                return true; // Blocking the block placement
            }
        }
        return false;
    }

    private SimpleCollisionBox currentEntityCollisionBox(PacketEntity entity) {
        SimpleCollisionBox interpBox = entity.getPossibleMovementCollisionBoxes();

        double width = BoundingBoxSize.getWidth(player, entity);
        double height = BoundingBoxSize.getHeight(player, entity);
        double interpWidth = Math.max(interpBox.maxX - interpBox.minX, interpBox.maxZ - interpBox.minZ);
        double interpHeight = interpBox.maxY - interpBox.minY;

        // If not accurate, fall back to desync pos
        // This happens due to the lack of an idle packet on 1.9+ clients
        // On 1.8 clients this should practically never happen
        if (interpWidth - width > 0.05 || interpHeight - height > 0.05) {
            Vec3 entityPos = entity.desyncClientPos;
            return GetBoundingBox.getPacketEntityBoundingBox(player, entityPos.x, entityPos.y, entityPos.z, entity);
        }
        return interpBox;
    }

    private static BlockData withWaterlogged(BlockState nmsState, boolean waterlogged) {
        BlockState adjusted = nmsState.setValue(BlockStateProperties.WATERLOGGED, waterlogged);
        return ac.grim.grimac.network.protocol.util.SpigotConversionUtil.fromNmsBlockState(adjusted).clone();
    }

    private boolean applyResolvedState(BlockPos position, BlockData state, boolean consumeItem, boolean checkIntersections) {
        CollisionBox box = ClientBlockShapes.movement(player, state, position.getX(), position.getY(), position.getZ());

        if (checkIntersections && state.getMaterial() != Material.SCAFFOLDING) {
            if (box.isIntersected(player.boundingBox)) {
                if (player.debugPlaces) player.sendMessage("Resolved place rejected: collision with player at " + position);
                return false;
            }

            for (PacketEntity entity : player.compensatedEntities.entityMap.values()) {
                SimpleCollisionBox interpBox = entity.getPossibleMovementCollisionBoxes();

                double width = BoundingBoxSize.getWidth(player, entity);
                double height = BoundingBoxSize.getHeight(player, entity);
                double interpWidth = Math.max(interpBox.maxX - interpBox.minX, interpBox.maxZ - interpBox.minZ);
                double interpHeight = interpBox.maxY - interpBox.minY;

                if (interpWidth - width > 0.05 || interpHeight - height > 0.05) {
                    Vec3 entityPos = entity.desyncClientPos;
                    interpBox = GetBoundingBox.getPacketEntityBoundingBox(player, entityPos.x, entityPos.y, entityPos.z, entity);
                }

                if (box.isIntersected(interpBox)) {
                    if (player.debugPlaces) player.sendMessage("Resolved place rejected: collision with entity at " + position);
                    return false;
                }
            }
        }

        // Check for min and max bounds of world
        if (player.compensatedWorld.getMaxHeight() <= position.getY() || position.getY() < player.compensatedWorld.getMinHeight()) {
            return false;
        }

        if (player.debugPlaces) {
            player.sendMessage("Applying resolved " + formatState(state) + " at " + position + " consume=" + consumeItem);
        }

        placed = true;
        if (consumeItem) {
            player.getInventory().onBlockPlace(this);
        }

        BlockData original = player.compensatedWorld.updateBlock(position.getX(), position.getY(), position.getZ(), NmsBlockTags.toNmsState(state));
        player.getGhostBlockMitigator().handleBlockPlace(position, original, state, this);
        return true;
    }

    public void set(BlockData state) {
        set(getPlacedBlockPos(), state);
    }

    public void resync() {
        if (player.debugPlaces) player.sendMessage("Block place resync'd");
        isCancelled = true;
    }

    private static String formatState(BlockData state) {
        return state.getAsString(false);
    }

    // All method with rants about mojang must go below this line

    // MOJANG??? Why did you remove this from the damn packet.  YOU DON'T DO BLOCK PLACING RIGHT!
    // You use last tick vector on the server and current tick on the client...
    // You also have 0.03 for FIVE YEARS which will mess this up.  nice one mojang
    // * 0.0004 as of 2/24/2022
    // Fix your damn netcode
    //
    // You also have the desync caused by eye height as apparently tracking the player's ticks wasn't important to you
    // No mojang, you really do need to track client ticks to get their accurate eye height.
    // another damn desync added... maybe next decade it will get fixed and double the amount of issues.
    public Vector getClickedLocation() {
        if (cursor != null) return new Vector(cursor.x, cursor.y, cursor.z);

        SimpleCollisionBox box = new SimpleCollisionBox(getPlacedAgainstBlockLocation());
        Vector look = ReachUtils.getLook(player, player.xRot, player.yRot);

        Vector eyePos = new Vector(player.x, player.y + player.getBukkitHeight(), player.z);
        Vector endReachPos = eyePos.clone().add(new Vector(look.getX() * 6, look.getY() * 6, look.getZ() * 6));
        Vector intercept = ReachUtils.calculateIntercept(box, eyePos, endReachPos).getFirst();

        // Bring this back to relative to the block
        // The player didn't even click the block... (we should force resync BEFORE we get here!)
        if (intercept == null) return new Vector();

        intercept.setX(intercept.getX() - box.minX);
        intercept.setY(intercept.getY() - box.minY);
        intercept.setZ(intercept.getZ() - box.minZ);

        return intercept;
    }

    public void set() {
        if (material == null) {
            LogUtil.warn("Material " + null + " has no placed type!");
            return;
        }
        set(material);
    }

    public void setAbove(BlockData toReplaceWith) {
        BlockPos placed = getPlacedBlockPos().offset(0, 1, 0);
        set(placed, toReplaceWith);
    }
}
