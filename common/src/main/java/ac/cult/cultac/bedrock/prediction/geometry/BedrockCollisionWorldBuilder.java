package ac.cult.cultac.bedrock.prediction.geometry;

import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.BlockTags;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.data.StateProperty;
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

    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();

    private final BedrockCollisionOverrideCatalog catalog;

    public BedrockCollisionWorldBuilder(BedrockCollisionOverrideCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public BlockCollisionWorld build(Map<BlockPosition, Integer> blockStates) {
        return build(blockStates, BedrockCollisionShapeQuery.NONE);
    }

    public BlockCollisionWorld build(Map<BlockPosition, Integer> blockStates, BedrockCollisionShapeQuery query) {
        query = query == null ? BedrockCollisionShapeQuery.NONE : query;
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        for (Map.Entry<BlockPosition, Integer> entry : blockStates.entrySet()) {
            Integer state = entry.getValue();
            if (state == null || isAir(state)) {
                continue;
            }
            List<WorldCollisionBox> boxes = collisionBoxes(entry.getKey(), state, query);
            List<WorldCollisionBox> contactBoxes =
                    movementResolvedContactUsesCollisionBoxes(state) ? boxes : List.of(fullBlockBox(entry.getKey()));
            String javaIdentifier = javaIdentifier(state);
            Map<String, Object> bedrockState = bedrockState(state);
            blocks.add(PlacedBlockCollision.sampled(
                    entry.getKey(),
                    javaIdentifier,
                    state,
                    javaIdentifier,
                    bedrockState,
                    boxes,
                    contactBoxes,
                    insideBlockContactBoxes(entry.getKey(), boxes),
                    contactBehaviors(state)));
        }
        return new BlockCollisionWorld(blocks);
    }

    public Map<String, Object> bedrockState(int state) {
        java.util.OptionalLong mask = catalog.blockPropertyMask(state);
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

    public static boolean clientComputedMovement(int state) {
        return pistonArmBlock(state)
                || stairBlock(state)
                || fenceBlock(state)
                || thinFenceBlock(state)
                || chorusPlantBlock(state);
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
            return List.of();
        }
        if (pistonArmBlock(state)) {
            return pistonArmCollisionBoxes(position, state, 0.0D);
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
        if (scaffoldingBlock(state)) {
            return scaffoldingCollisionBoxes(position, query);
        }
        if (powderSnowBlock(state)) {
            return powderSnowCollisionBoxes(position, query);
        }
        if (shulkerBoxBlock(state)) {
            return List.of(fullBlockBox(position));
        }
        return catalog.override(state)
                .map(shape -> bambooBlock(state)
                        ? BedrockBambooCollisionShape.at(position, shape)
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
            BlockPosition position, List<WorldCollisionBox> collisionBoxes) {
        return collisionBoxes.isEmpty() ? List.of(fullBlockBox(position)) : List.copyOf(collisionBoxes);
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

    private static boolean bambooBlock(int state) {
        return BlockIds.is(state, BlockIds.BAMBOO);
    }

    private static boolean shulkerBoxBlock(int state) {
        return BlockFamilies.SHULKER_BOX.test(state) || BlockTags.SHULKER_BOXES.test(state);
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
        int weirdoDirection =
                stairWeirdoDirection(Direction.from3DDataValue(BlockProps.HORIZONTAL_FACING.value(state)));

        List<BlockAabb> localBoxes = new ArrayList<>(2);
        if (upsideDown) {
            localBoxes.add(new BlockAabb(0.0D, 0.5D, 0.0D, 1.0D, 1.0D, 1.0D));
            addStairStepBox(localBoxes, weirdoDirection, 0.0D, 0.5D);
        } else {
            localBoxes.add(new BlockAabb(0.0D, 0.0D, 0.0D, 1.0D, 0.5D, 1.0D));
            addStairStepBox(localBoxes, weirdoDirection, 0.5D, 1.0D);
        }
        return toWorldBoxes(position, localBoxes);
    }

    private static int stairWeirdoDirection(Direction direction) {
        return switch (direction) {
            case NORTH -> 3;
            case SOUTH -> 2;
            case WEST -> 1;
            case EAST -> 0;
            default -> throw new IllegalArgumentException("stair facing must be horizontal: " + direction);
        };
    }

    private static void addStairStepBox(List<BlockAabb> boxes, int weirdoDirection, double minY, double maxY) {
        switch (weirdoDirection) {
            case 0 -> boxes.add(new BlockAabb(0.5D, minY, 0.0D, 1.0D, maxY, 1.0D));
            case 1 -> boxes.add(new BlockAabb(0.0D, minY, 0.0D, 0.5D, maxY, 1.0D));
            case 2 -> boxes.add(new BlockAabb(0.0D, minY, 0.5D, 1.0D, maxY, 1.0D));
            case 3 -> boxes.add(new BlockAabb(0.0D, minY, 0.0D, 1.0D, maxY, 0.5D));
            default -> throw new IllegalArgumentException("unknown Bedrock stair weirdo_direction " + weirdoDirection);
        }
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

    private static List<WorldCollisionBox> pistonArmCollisionBoxes(BlockPosition position, int state, double progress) {
        BedrockPistonArmCollisionShape.FacingDirection direction = pistonFacingDirection(state);
        return toWorldBoxes(position, BedrockPistonArmCollisionShape.collisionAabbs(direction, progress));
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
