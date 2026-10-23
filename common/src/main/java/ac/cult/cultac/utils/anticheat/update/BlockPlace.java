package ac.cult.cultac.utils.anticheat.update;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.BlockTags;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemDefinition;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.collisions.ClientBlockShapes;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.HitData;
import ac.cult.cultac.utils.inventory.ItemTypes;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.nmsutil.ClientBlockProperties;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import lombok.Getter;
import lombok.Setter;

public class BlockPlace {
    @Setter
    BlockPos blockPosition;

    @Getter
    Hand hand;

    @Getter
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

    @Setter
    Direction face;

    @Getter
    @Setter
    boolean isInside;

    @Getter
    Vec3 cursor;

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
        this.blockPosition = blockPosition == null ? null : blockPosition.immutable();
        this.sequence = sequence;

        BlockDefinition placedType = resolvePlacedType(itemStack.getItem());
        this.material = placedType == null ? BlockIds.FIRE : placedType;
        this.block = placedType != null;

        int clickedState = player.compensatedWorld.getBlockStateIdAt(getPlacedAgainstBlockLocation());
        this.replaceClicked = canBeReplaced(this.material, clickedState, face);
    }

    private static BlockDefinition resolvePlacedType(ItemDefinition material) {
        // MCP-Reborn MultiPlayerGameMode#useItemOn sends ServerboundUseItemOnPacket
        // for any in-bounds block hit; an empty hand is interaction, not placement.
        if (material == null || material == ItemTypes.AIR) {
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

    /** Vanilla already checked placement obstruction and supplies the resulting hand count. */
    public boolean applyResolvedPrediction(BlockPos position, int state) {
        CollisionBox box = ClientBlockShapes.movement(player, state, position.getX(), position.getY(), position.getZ());

        // Check for min and max bounds of world
        if (player.compensatedWorld.getMaxHeight() <= position.getY()
                || position.getY() < player.compensatedWorld.getMinHeight()) {
            return false;
        }

        if (player.debugPlaces) {
            player.sendMessage("Applying resolved " + formatState(state) + " at " + position + " consume=false");
        }

        int original = player.compensatedWorld.updateBlock(position.getX(), position.getY(), position.getZ(), state);
        player.getGhostBlockMitigator().handleBlockPlace(position, original, state, this);
        return true;
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
}
