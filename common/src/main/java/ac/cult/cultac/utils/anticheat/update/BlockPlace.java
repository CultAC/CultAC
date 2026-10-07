package ac.cult.cultac.utils.anticheat.update;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.BlockTags;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.impl.movement.GhostBlockMitigator;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.collisions.ClientBlockShapes;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.HitData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.ClientBlockProperties;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

public class BlockPlace {
    @Setter
    BlockPos blockPosition;

    @Getter
    Hand hand;

    @Getter
    @Setter
    boolean replaceClicked;

    boolean isCancelled = false;

    @Getter
    @Setter
    boolean isUseItem = false;

    CultPlayer player;

    @Getter
    SimItemStack itemStack;

    @Getter
    final BlockDefinition material;

    @Getter
    @Nullable
    HitData hitData;

    @Setter
    Direction face;

    @Getter
    @Setter
    boolean isInside;

    @Getter
    Vec3 cursor;

    @Getter
    private boolean placed;

    @Getter
    private final boolean block;

    public final int sequence;

    public BlockPlace(
            CultPlayer player,
            Hand hand,
            BlockPos blockPosition,
            Direction face,
            SimItemStack itemStack,
            HitData hitData) {
        this(player, hand, blockPosition, face, itemStack, hitData, 0);
    }

    public BlockPlace(
            CultPlayer player,
            Hand hand,
            BlockPos blockPosition,
            Direction face,
            SimItemStack itemStack,
            HitData hitData,
            int sequence) {
        this.player = player;
        this.hand = hand;
        this.face = face;
        this.itemStack = itemStack;
        this.hitData = hitData;
        this.blockPosition = blockPosition == null ? null : blockPosition.immutable();
        this.sequence = sequence;

        BlockDefinition placedType = resolvePlacedType(itemStack.getItem());
        this.material = placedType == null ? BlockIds.FIRE : placedType;
        this.block = placedType != null;

        int clickedState = player.compensatedWorld.getBlockStateIdAt(getPlacedAgainstBlockLocation());
        this.replaceClicked = canBeReplaced(this.material, clickedState, face);
    }

    private static BlockDefinition resolvePlacedType(ac.cult.blocksim.data.ItemDefinition material) {
        // MCP-Reborn MultiPlayerGameMode#useItemOn sends ServerboundUseItemOnPacket
        // for any in-bounds block hit; an empty hand is interaction, not placement.
        if (material == null || material == ac.cult.cultac.utils.inventory.ItemTypes.AIR) {
            return null;
        }

        if (!material.block().isEmpty()) {
            return DataTables.defaults().registry().block(material.block());
        }
        return null;
    }

    public void setCursor(Vec3 cursor) {
        this.cursor = cursor == null ? null : new Vec3(cursor.x, cursor.y, cursor.z);
    }

    public BlockPos getPlacedAgainstBlockLocation() {
        return blockPosition;
    }

    public BlockDefinition getPlacedAgainstMaterial() {
        return DataTables.defaults()
                .registry()
                .block(player.compensatedWorld.getBlockStateIdAt(getPlacedAgainstBlockLocation()));
    }

    private boolean canBeReplaced(BlockDefinition heldItem, int state, Direction face) {
        // Cave vines and weeping vines have a special case... that always returns false (just like the base case for
        // it!)
        var registry = DataTables.defaults().registry();
        var blockType = registry.block(state);
        var defaults = registry.facts(blockType.defaultState());
        boolean baseReplaceable =
                blockType != heldItem && (defaults.has(StateFacts.AIR) || defaults.has(StateFacts.REPLACEABLE));

        if (BlockTags.CANDLES.test(state)) {
            return heldItem == blockType
                    && (BlockProps.CANDLES.has(state) ? BlockProps.CANDLES.value(state) : 0) < 4
                    && !isSecondaryUse();
        }
        if (blockType == BlockIds.SEA_PICKLE) {
            return heldItem == blockType
                    && (BlockProps.PICKLES.has(state) ? BlockProps.PICKLES.value(state) : 0) < 4
                    && !isSecondaryUse();
        }
        if (blockType == BlockIds.TURTLE_EGG) {
            return heldItem == blockType
                    && (BlockProps.EGGS.has(state) ? BlockProps.EGGS.value(state) : 0) < 4
                    && !isSecondaryUse();
        }
        // Glow lichen can be replaced if it has an open face, or the player is placing something
        if (blockType == BlockIds.GLOW_LICHEN) {
            if (heldItem != BlockIds.GLOW_LICHEN) {
                return true;
            }
            if (!ClientBlockProperties.hasDirection(state, Direction.UP)) return true;
            if (!ClientBlockProperties.hasDirection(state, Direction.DOWN)) return true;
            if (!ClientBlockProperties.hasDirection(state, Direction.NORTH)) return true;
            if (!ClientBlockProperties.hasDirection(state, Direction.SOUTH)) return true;
            if (!ClientBlockProperties.hasDirection(state, Direction.EAST)) return true;
            return !ClientBlockProperties.hasDirection(state, Direction.WEST);
        }
        if (blockType == BlockIds.SCAFFOLDING) {
            return heldItem == BlockIds.SCAFFOLDING;
        }
        if (ClientBlockProperties.isSlab(state)) {
            int slabType = BlockProps.SLAB_TYPE.value(state); // TOP=0, BOTTOM=1, DOUBLE=2.
            if (slabType == 2 || blockType != heldItem) return false;

            // Here vanilla refers from
            // Set check can replace -> get block -> call block canBeReplaced -> check can replace boolean (default
            // true)
            // uh... what?  I'm unsure what Mojang is doing here.  I think they just made a stupid mistake.
            // as this code is quite old.
            boolean flag = getClickedLocation().getY() > 0.5D;
            Direction clickedFace = getDirection();
            if (slabType == 1) {
                return clickedFace == Direction.UP || flag && isFaceHorizontal();
            } else {
                return clickedFace == Direction.DOWN || !flag && isFaceHorizontal();
            }
        }
        if (blockType == BlockIds.SNOW) {
            int layers = (BlockProps.LAYERS.has(state) ? BlockProps.LAYERS.value(state) : 0);
            if (heldItem == blockType && layers < 8) { // We index at 1 (less than 8 layers)
                return face == Direction.UP;
            } else {
                return layers == 1; // index at 1, (1 layer)
            }
        }
        if (blockType == BlockIds.VINE) {
            if (baseReplaceable) return true;
            if (heldItem != blockType) return false;
            if (!ClientBlockProperties.hasDirection(state, Direction.UP)) return true;
            if (!ClientBlockProperties.hasDirection(state, Direction.NORTH)) return true;
            if (!ClientBlockProperties.hasDirection(state, Direction.SOUTH)) return true;
            if (!ClientBlockProperties.hasDirection(state, Direction.EAST)) return true;
            return !ClientBlockProperties.hasDirection(state, Direction.WEST);
        }

        return baseReplaceable;
    }

    // I believe this is correct, although I'm using a method here just in case it's a tick off... I don't trust Mojang
    public boolean isSecondaryUse() {
        return player.isSneaking;
    }

    public Direction getDirection() {
        return face;
    }

    public boolean isFaceHorizontal() {
        Direction face = getDirection();
        return face == Direction.NORTH || face == Direction.EAST || face == Direction.SOUTH || face == Direction.WEST;
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

    public void set(BlockDefinition material) {
        set(material.defaultState());
    }

    public boolean applyResolvedPrimary(BlockPos position, int state) {
        return applyResolvedState(position, state, true, true);
    }

    public void applyResolvedSecondary(BlockPos position, int state) {
        applyResolvedState(position, state, false, false);
    }

    /** Vanilla already checked placement obstruction and supplies the resulting hand count. */
    public boolean applyResolvedPrediction(BlockPos position, int state) {
        return applyResolvedState(position, state, false, false);
    }

    public void set(Direction face, int state) {
        BlockPos blockPos = getPlacedBlockPos().offset(face.getModX(), face.getModY(), face.getModZ());
        set(blockPos, state);
    }

    public void set(BlockPos position, int state) {
        // Hack for scaffolding to be the correct bounding box
        CollisionBox box = ClientBlockShapes.movement(player, state, position.getX(), position.getY(), position.getZ());

        if (player.debugPlaces) player.sendMessage("Placing start " + formatState(state) + " at " + position);

        // Note scaffolding is a special case because it can never intersect with the player's bounding box,
        // and we fetch it with lastY instead of y which is wrong, so it is easier to just ignore scaffolding here
        if (!BlockIds.is(state, BlockIds.SCAFFOLDING) && isPlacementObstructed(box)) {
            return;
        }

        // If a block already exists here, then we can't override it.
        int existingState = player.compensatedWorld.getBlockStateIdAt(position);
        if (!replaceClicked && !canBeReplaced(material, existingState, face)) {
            return;
        }

        // Check for min and max bounds of world
        if (player.compensatedWorld.getMaxHeight() <= position.getY()
                || position.getY() < player.compensatedWorld.getMinHeight()) {
            return;
        }

        // Check for waterlogged
        if (BlockProps.WATERLOGGED.has(state)) {
            boolean waterlogged = BlockIds.is(existingState, BlockIds.WATER)
                    && ClientFluidQueries.modelFluid(existingState).isSourceOfType("minecraft:water");
            state = BlockProps.WATERLOGGED.with(state, waterlogged);
        }
        if (player.debugPlaces) player.sendMessage("Block actually placed " + formatState(state) + " at " + position);
        placed = true;
        player.getInventory().onBlockPlace(this);
        int original = player.compensatedWorld.updateBlock(position.getX(), position.getY(), position.getZ(), state);
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

    private boolean applyResolvedState(BlockPos position, int state, boolean consumeItem, boolean checkIntersections) {
        CollisionBox box = ClientBlockShapes.movement(player, state, position.getX(), position.getY(), position.getZ());

        if (checkIntersections && !BlockIds.is(state, BlockIds.SCAFFOLDING)) {
            if (box.isIntersected(player.boundingBox)) {
                if (player.debugPlaces)
                    player.sendMessage("Resolved place rejected: collision with player at " + position);
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
                    interpBox = GetBoundingBox.getPacketEntityBoundingBox(
                            player, entityPos.x, entityPos.y, entityPos.z, entity);
                }

                if (box.isIntersected(interpBox)) {
                    if (player.debugPlaces)
                        player.sendMessage("Resolved place rejected: collision with entity at " + position);
                    return false;
                }
            }
        }

        // Check for min and max bounds of world
        if (player.compensatedWorld.getMaxHeight() <= position.getY()
                || position.getY() < player.compensatedWorld.getMinHeight()) {
            return false;
        }

        if (player.debugPlaces) {
            player.sendMessage(
                    "Applying resolved " + formatState(state) + " at " + position + " consume=" + consumeItem);
        }

        placed = true;
        if (consumeItem) {
            player.getInventory().onBlockPlace(this);
        }

        int original = player.compensatedWorld.updateBlock(position.getX(), position.getY(), position.getZ(), state);
        player.getGhostBlockMitigator().handleBlockPlace(position, original, state, this);
        return true;
    }

    public void set(int state) {
        set(getPlacedBlockPos(), state);
    }

    public void resync() {
        if (player.debugPlaces) player.sendMessage("Block place resync'd");
        isCancelled = true;
    }

    private static String formatState(int state) {
        return DataTables.defaults().registry().serialize(state);
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
    public Vector3dm getClickedLocation() {
        if (cursor != null) return new Vector3dm(cursor.x, cursor.y, cursor.z);

        SimpleCollisionBox box = new SimpleCollisionBox(getPlacedAgainstBlockLocation());
        Vector3dm look = ReachUtils.getLook(player, player.xRot, player.yRot);

        Vector3dm eyePos = new Vector3dm(player.x, player.y + player.getBukkitHeight(), player.z);
        Vector3dm endReachPos = eyePos.clone().add(new Vector3dm(look.getX() * 6, look.getY() * 6, look.getZ() * 6));
        Vector3dm intercept =
                ReachUtils.calculateIntercept(box, eyePos, endReachPos).getFirst();

        // Bring this back to relative to the block
        // The player didn't even click the block... (we should force resync BEFORE we get here!)
        if (intercept == null) return new Vector3dm();

        intercept.setX(intercept.getX() - box.minX);
        intercept.setY(intercept.getY() - box.minY);
        intercept.setZ(intercept.getZ() - box.minZ);

        return intercept;
    }

    public void set() {
        if (material == null) {
            LogUtil.warn("Block " + null + " has no placed type!");
            return;
        }
        set(material);
    }

    public void setAbove(int toReplaceWith) {
        BlockPos placed = getPlacedBlockPos().offset(0, 1, 0);
        set(placed, toReplaceWith);
    }
}
