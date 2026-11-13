package ac.cult.cultac.bedrock.prediction.geometry;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.BlockTags;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.data.StateProperty;
import ac.cult.cultac.bedrock.prediction.api.BedrockBlockShapeContext;
import ac.cult.cultac.bedrock.prediction.api.BedrockCollisionShapeQuery;
import ac.cult.cultac.bedrock.prediction.world.BedrockBlockMetadata;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision.BlockContactBehavior;
import ac.cult.cultac.protocol.value.Direction;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class BedrockCollisionWorldBuilder {
    private static final double POWDER_SNOW_ABOVE_EPSILON = 1.1920929E-7D;
    private static final int STAIR_STRAIGHT = 0;
    private static final int STAIR_INNER_LEFT = 1;
    private static final int STAIR_INNER_RIGHT = 2;
    private static final int STAIR_OUTER_LEFT = 3;
    private static final int STAIR_OUTER_RIGHT = 4;

    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final BlockDefinition COMPOSTER = REGISTRY.block("minecraft:composter");
    private static final BlockDefinition BREWING_STAND = REGISTRY.block("minecraft:brewing_stand");
    private static final BlockDefinition HOPPER = REGISTRY.block("minecraft:hopper");
    /** A hopper's bowl: its floor, four 2/16 walls above it and the funnel under it. */
    private static final List<BlockAabb> HOPPER_BOWL = List.of(
            new BlockAabb(0.0D, 0.625D, 0.0D, 1.0D, 0.6875D, 1.0D),
            new BlockAabb(0.0D, 0.6875D, 0.0D, 0.125D, 1.0D, 1.0D),
            new BlockAabb(0.875D, 0.6875D, 0.0D, 1.0D, 1.0D, 1.0D),
            new BlockAabb(0.125D, 0.6875D, 0.0D, 0.875D, 1.0D, 0.125D),
            new BlockAabb(0.125D, 0.6875D, 0.875D, 0.875D, 1.0D, 1.0D),
            new BlockAabb(0.25D, 0.25D, 0.25D, 0.75D, 0.625D, 0.75D));

    private static final List<BlockDefinition> CAULDRONS = List.of(
            BlockIds.CAULDRON,
            REGISTRY.block("minecraft:water_cauldron"),
            REGISTRY.block("minecraft:lava_cauldron"),
            REGISTRY.block("minecraft:powder_snow_cauldron"));
    /** The four 2/16 thick walls of a composter or cauldron, each a full block high. */
    private static final List<BlockAabb> BASIN_WALLS = List.of(
            new BlockAabb(0.0D, 0.0D, 0.0D, 0.125D, 1.0D, 1.0D),
            new BlockAabb(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 0.125D),
            new BlockAabb(0.875D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D),
            new BlockAabb(0.0D, 0.0D, 0.875D, 1.0D, 1.0D, 1.0D));

    private final BedrockCollisionOverrideCatalog catalog;
    private final BedrockBlockShapeContext context;

    public BedrockCollisionWorldBuilder(BedrockCollisionOverrideCatalog catalog) {
        this(catalog, BedrockBlockShapeContext.NONE);
    }

    public BedrockCollisionWorldBuilder(BedrockCollisionOverrideCatalog catalog, BedrockBlockShapeContext context) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.context = Objects.requireNonNull(context, "context");
    }

    public BlockCollisionWorld build(Map<BlockPosition, Integer> blockStates, BedrockCollisionShapeQuery query) {
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        for (Map.Entry<BlockPosition, Integer> entry : blockStates.entrySet()) {
            Integer state = entry.getValue();
            if (state != null && !isAir(state)) {
                blocks.add(block(entry.getKey(), state, query));
            }
        }
        return new BlockCollisionWorld(blocks);
    }

    /** The collision of the non-air {@code state} at {@code position}, its shape resolved for {@code query}. */
    public PlacedBlockCollision block(BlockPosition position, int state, BedrockCollisionShapeQuery query) {
        query = query == null ? BedrockCollisionShapeQuery.NONE : query;
        List<WorldCollisionBox> boxes = collisionBoxes(position, state, query);
        List<WorldCollisionBox> contactBoxes =
                movementResolvedContactUsesCollisionBoxes(state) ? boxes : List.of(fullBlockBox(position));
        String javaIdentifier = javaIdentifier(state);
        Map<String, Object> metadata = bedrockState(state);
        if (movingPistonBlock(state)) metadata = withCarriedBlock(position, metadata);
        if (query.actorCollisionQuery().isPresent() && (powderSnowBlock(state) || scaffoldingBlock(state))) {
            var independent = new java.util.HashMap<>(metadata);
            independent.put(
                    BedrockBlockMetadata.GEYSER_PASSABLE,
                    collisionBoxes(position, state, BedrockCollisionShapeQuery.NONE)
                            .isEmpty());
            metadata = independent;
        }
        WorldCollisionBox standing = standingShape(position, state, boxes);
        if (standing != null) {
            var shaped = new java.util.HashMap<>(metadata);
            shaped.put(
                    BedrockBlockMetadata.STANDING_SHAPE,
                    List.of(
                            standing.minX(),
                            standing.minY(),
                            standing.minZ(),
                            standing.maxX(),
                            standing.maxY(),
                            standing.maxZ()));
            metadata = shaped;
        }
        return PlacedBlockCollision.sampled(
                position,
                javaIdentifier,
                state,
                javaIdentifier,
                metadata,
                boxes,
                contactBoxes,
                insideBlockContactBoxes(position, state, boxes),
                contactBehaviors(state));
    }

    /**
     * The one box an actor stands on a block of several collision boxes by, or null where that is the block's cell:
     * all that a fence, bars or a chorus plant spans, and a brewing stand's own box.
     */
    private static WorldCollisionBox standingShape(BlockPosition position, int state, List<WorldCollisionBox> boxes) {
        if (boxes.size() < 2) {
            return null;
        }
        if (BlockIds.is(state, BREWING_STAND)) {
            return toWorldBox(position, new BlockAabb(0.125D, 0.0D, 0.125D, 0.875D, 0.875D, 0.875D));
        }
        if (!fenceBlock(state) && !thinFenceBlock(state) && !chorusPlantBlock(state)) {
            return null;
        }
        WorldCollisionBox span = boxes.getFirst();
        for (WorldCollisionBox box : boxes) {
            span = new WorldCollisionBox(
                    Math.min(span.minX(), box.minX()),
                    Math.min(span.minY(), box.minY()),
                    Math.min(span.minZ(), box.minZ()),
                    Math.max(span.maxX(), box.maxX()),
                    Math.max(span.maxY(), box.maxY()),
                    Math.max(span.maxZ(), box.maxZ()));
        }
        return span;
    }

    /**
     * A landing on a moving block bounces off the block it carries. One without data, or carrying another moving
     * block, names none.
     */
    private Map<String, Object> withCarriedBlock(BlockPosition position, Map<String, Object> metadata) {
        int carried = context.movingBlockCarried().at(position);
        if (carried < 0 || movingPistonBlock(carried)) return metadata;
        var named = new java.util.HashMap<>(metadata);
        named.put(BedrockBlockMetadata.MOVING_BLOCK_CARRIED, javaIdentifier(carried));
        return named;
    }

    public Map<String, Object> bedrockState(int state) {
        java.util.OptionalLong mask = catalog.blockPropertyMask(state);
        if (BlockIds.is(state, BlockIds.POTENT_SULFUR)) {
            String phase = REGISTRY.value(state, "potent_sulfur_state");
            return mask.isPresent()
                    ? Map.of(BedrockBlockMetadata.BLOCK_PROPERTY_MASK, mask.getAsLong(), "potent_sulfur_state", phase)
                    : Map.of("potent_sulfur_state", phase);
        }
        return mask.isPresent() ? Map.of(BedrockBlockMetadata.BLOCK_PROPERTY_MASK, mask.getAsLong()) : Map.of();
    }

    public static boolean hasBedrockBehavior(int state, BedrockCollisionOverrideCatalog catalog) {
        if (state < 0 || isAir(state)) {
            return false;
        }
        return manualMovement(state) || catalog != null && catalog.hasOverride(state);
    }

    public static boolean dynamicMovement(int state) {
        return scaffoldingBlock(state) || powderSnowBlock(state);
    }

    /** Whether the shape of {@code state} depends on client state kept for the position it is at. */
    public static boolean positionDependentMovement(int state) {
        return shulkerBoxBlock(state)
                || movingPistonBlock(state)
                || pistonArmBlock(state)
                || maxPositionOffset(state) > 0.0F;
    }

    /** How far a hash of its position shifts the shape of {@code state} on X and Z; 0 for a shape it does not. */
    private static float maxPositionOffset(int state) {
        if (BlockIds.is(state, BlockIds.BAMBOO)) {
            return 0.25F;
        }
        return BlockIds.is(state, BlockIds.POINTED_DRIPSTONE) ? 0.125F : 0.0F;
    }

    public static boolean clientComputedMovement(int state) {
        return pistonArmBlock(state)
                || stairBlock(state)
                || fenceBlock(state)
                || thinFenceBlock(state)
                || chorusPlantBlock(state)
                || basinBlock(state)
                || BlockIds.is(state, BREWING_STAND)
                || BlockIds.is(state, HOPPER);
    }

    public static Set<BlockContactBehavior> contactBehaviors(int state) {
        if (state < 0 || isAir(state)) {
            return Set.of();
        }

        EnumSet<BlockContactBehavior> behaviors = EnumSet.noneOf(BlockContactBehavior.class);
        if (BlockIds.is(state, BlockIds.HONEY_BLOCK)) {
            behaviors.add(BlockContactBehavior.HONEY);
        }
        if (BlockIds.is(state, BlockIds.SLIME_BLOCK)) {
            behaviors.add(BlockContactBehavior.SLIME);
        }
        if (BlockIds.is(state, BlockIds.SOUL_SAND)) {
            behaviors.add(BlockContactBehavior.SOUL_SAND);
        }
        if (BlockIds.is(state, BlockIds.SOUL_SOIL)) {
            behaviors.add(BlockContactBehavior.SOUL_SOIL);
        }
        if (BlockIds.is(state, BlockIds.COBWEB)) {
            behaviors.add(BlockContactBehavior.COBWEB);
        }
        if (BlockIds.is(state, BlockIds.SWEET_BERRY_BUSH)) {
            behaviors.add(BlockContactBehavior.SWEET_BERRY_BUSH);
        }
        if (BlockIds.is(state, BlockIds.POWDER_SNOW)) {
            behaviors.add(BlockContactBehavior.POWDER_SNOW);
        }
        if (BlockTags.CLIMBABLE.test(state)) {
            behaviors.add(BlockContactBehavior.CLIMBABLE);
        }
        if (BlockIds.is(state, BlockIds.LADDER)) {
            behaviors.add(BlockContactBehavior.LADDER);
        }
        if (BlockIds.is(state, BlockIds.VINE)) {
            behaviors.add(BlockContactBehavior.WALL_VINE);
        }
        if (BlockIds.is(state, BlockIds.SCAFFOLDING)) {
            behaviors.add(BlockContactBehavior.SCAFFOLDING);
        }
        return behaviors.isEmpty() ? Set.of() : Set.copyOf(behaviors);
    }

    private static boolean manualMovement(int state) {
        return clientComputedMovement(state)
                || dynamicMovement(state)
                || movingPistonBlock(state)
                || shulkerBoxBlock(state);
    }

    private List<WorldCollisionBox> collisionBoxes(
            BlockPosition position, int state, BedrockCollisionShapeQuery query) {
        if (movingPistonBlock(state)) {
            return context.movingBlockBoxes().at(position);
        }
        if (pistonArmBlock(state)) {
            return pistonArmCollisionBoxes(position, state);
        }
        if (stairBlock(state)) {
            return stairCollisionBoxes(position, state);
        }
        if (fenceBlock(state)) {
            return fenceCollisionBoxes(position, state);
        }
        if (thinFenceBlock(state)) {
            return thinFenceCollisionBoxes(position, state);
        }
        if (chorusPlantBlock(state)) {
            return chorusPlantCollisionBoxes(position, state);
        }
        if (basinBlock(state)) {
            return basinCollisionBoxes(position, state);
        }
        if (BlockIds.is(state, BREWING_STAND)) {
            // A rod on a base plate that covers the whole block.
            return List.of(
                    toWorldBox(position, new BlockAabb(0.4375D, 0.0D, 0.4375D, 0.5625D, 0.875D, 0.5625D)),
                    toWorldBox(position, new BlockAabb(0.0D, 0.0D, 0.0D, 1.0D, 0.125D, 1.0D)));
        }
        if (BlockIds.is(state, HOPPER)) {
            return hopperCollisionBoxes(position, state);
        }
        if (scaffoldingBlock(state)) {
            return scaffoldingCollisionBoxes(position, query);
        }
        if (powderSnowBlock(state)) {
            return powderSnowCollisionBoxes(position, query);
        }
        if (shulkerBoxBlock(state)) {
            return List.of(shulkerBoxCollisionBox(position, state));
        }
        float maxOffset = maxPositionOffset(state);
        return catalog.override(state)
                .map(shape -> maxOffset > 0.0F
                        ? BedrockOffsetBlockShape.at(position, shape, maxOffset, context.frame())
                        : overrideCollisionBoxes(position, shape))
                .orElseGet(List::of);
    }

    private static List<WorldCollisionBox> overrideCollisionBoxes(
            BlockPosition position, BedrockCollisionOverrideShape shape) {
        List<WorldCollisionBox> boxes = new ArrayList<>(shape.boxes().size());
        for (BlockAabb box : shape.boxes()) {
            boxes.add(toWorldBox(position, box));
        }
        return List.copyOf(boxes);
    }

    private static List<WorldCollisionBox> insideBlockContactBoxes(
            BlockPosition position, int state, List<WorldCollisionBox> collisionBoxes) {
        // Powder snow slows an actor anywhere in its cell, whatever shape it collides as for that actor.
        return collisionBoxes.isEmpty() || powderSnowBlock(state)
                ? List.of(fullBlockBox(position))
                : List.copyOf(collisionBoxes);
    }

    private static boolean movementResolvedContactUsesCollisionBoxes(int state) {
        return pistonArmBlock(state)
                || stairBlock(state)
                || fenceBlock(state)
                || thinFenceBlock(state)
                || chorusPlantBlock(state);
    }

    private static boolean movingPistonBlock(int state) {
        return BlockIds.is(state, BlockIds.MOVING_PISTON);
    }

    private static boolean pistonArmBlock(int state) {
        return BlockFamilies.PISTON_HEAD.test(state) || BlockIds.is(state, BlockIds.PISTON_HEAD);
    }

    private static boolean stairBlock(int state) {
        return BlockFamilies.STAIR.test(state) || BlockTags.STAIRS.test(state);
    }

    private static boolean fenceBlock(int state) {
        return BlockFamilies.FENCE.test(state) || BlockTags.FENCES.test(state);
    }

    private static boolean thinFenceBlock(int state) {
        return BlockFamilies.IRON_BARS.test(state) || BlockTags.BARS.test(state);
    }

    private static boolean chorusPlantBlock(int state) {
        return BlockFamilies.CHORUS_PLANT.test(state) || BlockIds.is(state, BlockIds.CHORUS_PLANT);
    }

    private static boolean scaffoldingBlock(int state) {
        return BlockFamilies.SCAFFOLDING.test(state) || BlockIds.is(state, BlockIds.SCAFFOLDING);
    }

    private static boolean powderSnowBlock(int state) {
        return BlockFamilies.POWDER_SNOW.test(state) || BlockIds.is(state, BlockIds.POWDER_SNOW);
    }

    private static boolean basinBlock(int state) {
        return BlockIds.is(state, COMPOSTER) || CAULDRONS.stream().anyMatch(cauldron -> BlockIds.is(state, cauldron));
    }

    /**
     * A composter or cauldron collides as four walls around a floor: 5/16 high in a cauldron, and in a composter
     * 2/16 empty, (2 * level + 1)/16 filling, 15/16 full.
     */
    private static List<WorldCollisionBox> basinCollisionBoxes(BlockPosition position, int state) {
        double floor = 0.3125D;
        if (BlockIds.is(state, COMPOSTER)) {
            int level = Integer.parseInt(REGISTRY.value(state, "level"));
            floor = level == 0 ? 0.125D : level == 8 ? 0.9375D : (2 * level + 1) / 16.0D;
        }
        List<WorldCollisionBox> boxes = new ArrayList<>(5);
        boxes.add(toWorldBox(position, new BlockAabb(0.0D, 0.0D, 0.0D, 1.0D, floor, 1.0D)));
        for (BlockAabb wall : BASIN_WALLS) {
            boxes.add(toWorldBox(position, wall));
        }
        return List.copyOf(boxes);
    }

    /** The bowl and the spout, which points the way the hopper faces. */
    private static List<WorldCollisionBox> hopperCollisionBoxes(BlockPosition position, int state) {
        BlockAabb spout = switch (REGISTRY.value(state, "facing")) {
            case "north" -> new BlockAabb(0.375D, 0.25D, 0.0D, 0.625D, 0.5D, 0.25D);
            case "south" -> new BlockAabb(0.375D, 0.25D, 0.75D, 0.625D, 0.5D, 1.0D);
            case "west" -> new BlockAabb(0.0D, 0.25D, 0.375D, 0.25D, 0.5D, 0.625D);
            case "east" -> new BlockAabb(0.75D, 0.25D, 0.375D, 1.0D, 0.5D, 0.625D);
            default -> new BlockAabb(0.375D, 0.0D, 0.375D, 0.625D, 0.25D, 0.625D);
        };
        List<WorldCollisionBox> boxes = new ArrayList<>(HOPPER_BOWL.size() + 1);
        for (BlockAabb box : HOPPER_BOWL) {
            boxes.add(toWorldBox(position, box));
        }
        boxes.add(toWorldBox(position, spout));
        return List.copyOf(boxes);
    }

    private static boolean shulkerBoxBlock(int state) {
        return BlockFamilies.SHULKER_BOX.test(state) || BlockTags.SHULKER_BOXES.test(state);
    }

    /** A shulker box is one box whose face on the box's facing side its lid moves out along that facing. */
    private WorldCollisionBox shulkerBoxCollisionBox(BlockPosition position, int state) {
        WorldCollisionBox closed = fullBlockBox(position);
        float lift = context.shulkerBoxLift().at(position);
        if (lift <= 0.0F) {
            return closed;
        }
        Direction facing = Direction.from3DDataValue(BlockProps.FACING.value(state));
        double x = facing.getStepX() * (double) lift;
        double y = facing.getStepY() * (double) lift;
        double z = facing.getStepZ() * (double) lift;
        return new WorldCollisionBox(
                closed.minX() + Math.min(0.0D, x),
                closed.minY() + Math.min(0.0D, y),
                closed.minZ() + Math.min(0.0D, z),
                closed.maxX() + Math.max(0.0D, x),
                closed.maxY() + Math.max(0.0D, y),
                closed.maxZ() + Math.max(0.0D, z));
    }

    private static List<WorldCollisionBox> powderSnowCollisionBoxes(
            BlockPosition position, BedrockCollisionShapeQuery query) {
        if (query.actorCollisionQuery().isEmpty()) {
            return List.of();
        }
        WorldCollisionBox blockBox = fullBlockBox(position);
        WorldCollisionBox actorBox = query.actorCollisionQuery().get();
        boolean aboveTop = actorBox.minY() + POWDER_SNOW_ABOVE_EPSILON >= blockBox.maxY();
        if (!aboveTop) {
            return List.of();
        }

        BedrockPowderSnowCollisionShape.CollisionBranch branch = query.fallDistance() > 2.5F
                ? BedrockPowderSnowCollisionShape.CollisionBranch.FALL_INTO_COLLISION
                : query.leatherBoots()
                        ? BedrockPowderSnowCollisionShape.CollisionBranch.WALK_ON_TOP
                        : BedrockPowderSnowCollisionShape.CollisionBranch.EMPTY;
        List<WorldCollisionBox> boxes = new ArrayList<>();
        for (BlockAabb box : BedrockPowderSnowCollisionShape.collisionAabbs(branch)) {
            boxes.add(toWorldBox(position, box));
        }
        return List.copyOf(boxes);
    }

    private static List<WorldCollisionBox> stairCollisionBoxes(BlockPosition position, int state) {
        boolean upsideDown = BlockProps.HALF.value(state) == 0;
        Direction facing = Direction.from3DDataValue(BlockProps.HORIZONTAL_FACING.value(state));
        int shape = BlockProps.STAIRS_SHAPE.value(state);

        List<BlockAabb> localBoxes = new ArrayList<>(3);
        double stepMinY = upsideDown ? 0.0D : 0.5D;
        localBoxes.add(
                upsideDown
                        ? new BlockAabb(0.0D, 0.5D, 0.0D, 1.0D, 1.0D, 1.0D)
                        : new BlockAabb(0.0D, 0.0D, 0.0D, 1.0D, 0.5D, 1.0D));
        addStairStepBoxes(localBoxes, facing.getStepX(), facing.getStepZ(), shape, stepMinY, stepMinY + 0.5D);
        return toWorldBoxes(position, localBoxes);
    }

    /**
     * The raised half of a stair. Its corner state carries the shape: straight raises the facing half, an outer corner
     * only the facing half's quarter on its left or right, an inner corner the facing half and the left or right half.
     */
    private static void addStairStepBoxes(
            List<BlockAabb> boxes, int facingX, int facingZ, int shape, double minY, double maxY) {
        // The left of a facing turns it counter-clockwise seen from above.
        int leftX = facingZ;
        int leftZ = -facingX;
        switch (shape) {
            case STAIR_STRAIGHT -> addStairPiece(boxes, facingX, facingZ, minY, maxY);
            case STAIR_INNER_LEFT -> {
                addStairPiece(boxes, facingX, facingZ, minY, maxY);
                addStairPiece(boxes, leftX, leftZ, minY, maxY);
            }
            case STAIR_INNER_RIGHT -> {
                addStairPiece(boxes, facingX, facingZ, minY, maxY);
                addStairPiece(boxes, -leftX, -leftZ, minY, maxY);
            }
            case STAIR_OUTER_LEFT -> addStairPiece(boxes, facingX + leftX, facingZ + leftZ, minY, maxY);
            case STAIR_OUTER_RIGHT -> addStairPiece(boxes, facingX - leftX, facingZ - leftZ, minY, maxY);
            default -> throw new IllegalArgumentException("unknown stair shape " + shape);
        }
    }

    /** The half (one axis) or quarter (both axes) of a cell on the side of each non-zero sign. */
    private static void addStairPiece(List<BlockAabb> boxes, int signX, int signZ, double minY, double maxY) {
        boxes.add(new BlockAabb(
                signX > 0 ? 0.5D : 0.0D,
                minY,
                signZ > 0 ? 0.5D : 0.0D,
                signX < 0 ? 0.5D : 1.0D,
                maxY,
                signZ < 0 ? 0.5D : 1.0D));
    }

    private static List<WorldCollisionBox> fenceCollisionBoxes(BlockPosition position, int state) {
        boolean negativeX = booleanProperty(state, BlockProps.WEST);
        boolean positiveX = booleanProperty(state, BlockProps.EAST);
        boolean negativeZ = booleanProperty(state, BlockProps.NORTH);
        boolean positiveZ = booleanProperty(state, BlockProps.SOUTH);

        List<BlockAabb> localBoxes = new ArrayList<>(2);
        if (negativeZ || positiveZ) {
            localBoxes.add(
                    new BlockAabb(0.375D, 0.0D, negativeZ ? 0.0D : 0.375D, 0.625D, 1.5D, positiveZ ? 1.0D : 0.625D));
        }
        if (negativeX || positiveX) {
            localBoxes.add(
                    new BlockAabb(negativeX ? 0.0D : 0.375D, 0.0D, 0.375D, positiveX ? 1.0D : 0.625D, 1.5D, 0.625D));
        }
        if (localBoxes.isEmpty()) {
            localBoxes.add(new BlockAabb(0.375D, 0.0D, 0.375D, 0.625D, 1.5D, 0.625D));
        }
        return toWorldBoxes(position, localBoxes);
    }

    private static List<WorldCollisionBox> thinFenceCollisionBoxes(BlockPosition position, int state) {
        boolean negativeX = booleanProperty(state, BlockProps.WEST);
        boolean positiveX = booleanProperty(state, BlockProps.EAST);
        boolean negativeZ = booleanProperty(state, BlockProps.NORTH);
        boolean positiveZ = booleanProperty(state, BlockProps.SOUTH);

        List<BlockAabb> localBoxes = new ArrayList<>(2);
        if (negativeX || positiveX) {
            localBoxes.add(
                    new BlockAabb(negativeX ? 0.0D : 0.5D, 0.0D, 0.4375D, positiveX ? 1.0D : 0.5D, 1.0D, 0.5625D));
        }
        if (negativeZ || positiveZ) {
            localBoxes.add(
                    new BlockAabb(0.4375D, 0.0D, negativeZ ? 0.0D : 0.5D, 0.5625D, 1.0D, positiveZ ? 1.0D : 0.5D));
        }
        if (localBoxes.isEmpty()) {
            localBoxes.add(new BlockAabb(0.4375D, 0.0D, 0.4375D, 0.5625D, 1.0D, 0.5625D));
        }
        return toWorldBoxes(position, localBoxes);
    }

    private static List<WorldCollisionBox> chorusPlantCollisionBoxes(BlockPosition position, int state) {
        boolean down = booleanProperty(state, BlockProps.DOWN);
        boolean east = booleanProperty(state, BlockProps.EAST);
        boolean north = booleanProperty(state, BlockProps.NORTH);
        boolean south = booleanProperty(state, BlockProps.SOUTH);
        boolean up = booleanProperty(state, BlockProps.UP);
        boolean west = booleanProperty(state, BlockProps.WEST);

        BlockAabb localBox = new BlockAabb(
                west ? 0.0D : 0.125D,
                down ? 0.0D : 0.125D,
                north ? 0.0D : 0.125D,
                east ? 1.0D : 0.875D,
                up ? 1.0D : 0.875D,
                south ? 1.0D : 0.875D);
        return List.of(toWorldBox(position, localBox));
    }

    /**
     * A piston arm's boxes belong to its piston, a cell back along its facing: they sit where the piston's progress
     * has pushed them out of it, and a piston arm without a piston behind it has none.
     */
    private List<WorldCollisionBox> pistonArmCollisionBoxes(BlockPosition position, int state) {
        Direction facing = Direction.from3DDataValue(BlockProps.FACING.value(state));
        BlockPosition piston = new BlockPosition(
                position.x() - facing.getStepX(), position.y() - facing.getStepY(), position.z() - facing.getStepZ());
        float progress = context.pistonProgress().at(piston);
        if (progress < 0.0F) {
            return List.of();
        }
        return toWorldBoxes(
                piston, BedrockPistonArmCollisionShape.collisionAabbs(pistonFacingDirection(state), progress));
    }

    private static BedrockPistonArmCollisionShape.FacingDirection pistonFacingDirection(int state) {
        return switch (Direction.from3DDataValue(BlockProps.FACING.value(state))) {
            case DOWN -> BedrockPistonArmCollisionShape.FacingDirection.DOWN;
            case UP -> BedrockPistonArmCollisionShape.FacingDirection.UP;
            case SOUTH -> BedrockPistonArmCollisionShape.FacingDirection.POSITIVE_Z;
            case NORTH -> BedrockPistonArmCollisionShape.FacingDirection.NEGATIVE_Z;
            case EAST -> BedrockPistonArmCollisionShape.FacingDirection.POSITIVE_X;
            case WEST -> BedrockPistonArmCollisionShape.FacingDirection.NEGATIVE_X;
        };
    }

    private static List<WorldCollisionBox> scaffoldingCollisionBoxes(
            BlockPosition position, BedrockCollisionShapeQuery query) {
        if (query.scaffoldingPassThrough() || query.actorCollisionQuery().isEmpty()) {
            return List.of();
        }
        WorldCollisionBox actor = query.actorCollisionQuery().get();
        WorldCollisionBox block = fullBlockBox(position);
        if (actor.minY() + 1.0E-6D < position.y() + 1.0D || !actor.overlapsXz(block)) {
            return List.of();
        }
        return List.of(block);
    }

    private static boolean booleanProperty(int state, StateProperty property) {
        return property.has(state) && property.booleanValue(state);
    }

    private static List<WorldCollisionBox> toWorldBoxes(BlockPosition position, List<BlockAabb> localBoxes) {
        List<WorldCollisionBox> boxes = new ArrayList<>(localBoxes.size());
        for (BlockAabb box : localBoxes) {
            boxes.add(toWorldBox(position, box));
        }
        return List.copyOf(boxes);
    }

    private static WorldCollisionBox toWorldBox(BlockPosition position, BlockAabb box) {
        return new WorldCollisionBox(
                position.x() + box.minX(),
                position.y() + box.minY(),
                position.z() + box.minZ(),
                position.x() + box.maxX(),
                position.y() + box.maxY(),
                position.z() + box.maxZ());
    }

    private static WorldCollisionBox fullBlockBox(BlockPosition position) {
        return new WorldCollisionBox(
                position.x(),
                position.y(),
                position.z(),
                position.x() + 1.0D,
                position.y() + 1.0D,
                position.z() + 1.0D);
    }

    private static boolean isAir(int state) {
        return REGISTRY.facts(state).has(StateFacts.AIR);
    }

    public static String javaIdentifier(int state) {
        return REGISTRY.block(state).key();
    }
}
