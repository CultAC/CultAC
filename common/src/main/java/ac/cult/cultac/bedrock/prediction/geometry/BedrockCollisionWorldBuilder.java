package ac.cult.cultac.bedrock.prediction.geometry;

import ac.cult.cultac.bedrock.prediction.api.BedrockCollisionShapeQuery;
import ac.cult.cultac.bedrock.prediction.world.BedrockBlockMetadata;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision.BlockContactBehavior;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChorusPlantBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;

public final class BedrockCollisionWorldBuilder {
    private static final double POWDER_SNOW_ABOVE_EPSILON = 1.1920929E-7D;

    private static final net.minecraft.tags.TagKey<Block> BARS_TAG = optionalBarsTag();

    @SuppressWarnings("unchecked")
    private static net.minecraft.tags.TagKey<Block> optionalBarsTag() {
        try {
            return (net.minecraft.tags.TagKey<Block>) BlockTags.class.getField("BARS").get(null);
        } catch (NoSuchFieldException absentOnOlderServer) {
            return null;
        } catch (IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private final BedrockCollisionOverrideCatalog catalog;

    public BedrockCollisionWorldBuilder(BedrockCollisionOverrideCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public BlockCollisionWorld build(Map<BlockPosition, BlockState> blockStates) {
        return build(blockStates, BedrockCollisionShapeQuery.NONE);
    }

    public BlockCollisionWorld build(Map<BlockPosition, BlockState> blockStates, BedrockCollisionShapeQuery query) {
        query = query == null ? BedrockCollisionShapeQuery.NONE : query;
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        for (Map.Entry<BlockPosition, BlockState> entry : blockStates.entrySet()) {
            BlockState state = entry.getValue();
            if (state == null || state.isAir()) {
                continue;
            }
            List<WorldCollisionBox> boxes = collisionBoxes(entry.getKey(), state, query);
            List<WorldCollisionBox> contactBoxes = movementResolvedContactUsesCollisionBoxes(state)
                    ? boxes
                    : List.of(fullBlockBox(entry.getKey()));
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

    public Map<String, Object> bedrockState(BlockState state) {
        java.util.OptionalLong mask = catalog.blockPropertyMask(Block.getId(state));
        return mask.isPresent()
                ? Map.of(BedrockBlockMetadata.BLOCK_PROPERTY_MASK, mask.getAsLong())
                : Map.of();
    }

    public static boolean hasBedrockBehavior(BlockState state, BedrockCollisionOverrideCatalog catalog) {
        if (state == null || state.isAir()) {
            return false;
        }
        return manualMovement(state)
                || catalog != null && catalog.hasOverride(Block.getId(state));
    }

    public static boolean dynamicMovement(BlockState state) {
        return scaffoldingBlock(state) || powderSnowBlock(state);
    }

    public static boolean clientComputedMovement(BlockState state) {
        return pistonArmBlock(state)
                || stairBlock(state)
                || fenceBlock(state)
                || thinFenceBlock(state)
                || chorusPlantBlock(state);
    }

    public static Set<BlockContactBehavior> contactBehaviors(BlockState state) {
        if (state == null || state.isAir()) {
            return Set.of();
        }

        EnumSet<BlockContactBehavior> behaviors = EnumSet.noneOf(BlockContactBehavior.class);
        if (state.getBlock() == Blocks.HONEY_BLOCK) {
            behaviors.add(BlockContactBehavior.HONEY);
        }
        if (state.getBlock() == Blocks.SLIME_BLOCK) {
            behaviors.add(BlockContactBehavior.SLIME);
        }
        if (state.getBlock() == Blocks.SOUL_SAND) {
            behaviors.add(BlockContactBehavior.SOUL_SAND);
        }
        if (state.getBlock() == Blocks.SOUL_SOIL) {
            behaviors.add(BlockContactBehavior.SOUL_SOIL);
        }
        if (state.getBlock() == Blocks.COBWEB) {
            behaviors.add(BlockContactBehavior.COBWEB);
        }
        if (state.getBlock() == Blocks.SWEET_BERRY_BUSH) {
            behaviors.add(BlockContactBehavior.SWEET_BERRY_BUSH);
        }
        if (state.getBlock() == Blocks.POWDER_SNOW) {
            behaviors.add(BlockContactBehavior.POWDER_SNOW);
        }
        if (state.is(BlockTags.CLIMBABLE)) {
            behaviors.add(BlockContactBehavior.CLIMBABLE);
        }
        if (state.getBlock() == Blocks.LADDER) {
            behaviors.add(BlockContactBehavior.LADDER);
        }
        if (state.getBlock() == Blocks.VINE) {
            behaviors.add(BlockContactBehavior.WALL_VINE);
        }
        if (state.getBlock() == Blocks.SCAFFOLDING) {
            behaviors.add(BlockContactBehavior.SCAFFOLDING);
        }
        return behaviors.isEmpty() ? Set.of() : Set.copyOf(behaviors);
    }

    private static boolean manualMovement(BlockState state) {
        return clientComputedMovement(state)
                || dynamicMovement(state)
                || movingPistonBlock(state)
                || shulkerBoxBlock(state);
    }

    private List<WorldCollisionBox> collisionBoxes(
            BlockPosition position,
            BlockState state,
            BedrockCollisionShapeQuery query
    ) {
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
        return catalog.override(Block.getId(state))
                .map(shape -> bambooBlock(state)
                        ? BedrockBambooCollisionShape.at(position, shape)
                        : overrideCollisionBoxes(position, shape))
                .orElseGet(List::of);
    }

    private static List<WorldCollisionBox> overrideCollisionBoxes(
            BlockPosition position,
            BedrockCollisionOverrideShape shape
    ) {
        List<WorldCollisionBox> boxes = new ArrayList<>(shape.boxes().size());
        for (BlockAabb box : shape.boxes()) {
            boxes.add(toWorldBox(position, box));
        }
        return List.copyOf(boxes);
    }

    private static List<WorldCollisionBox> insideBlockContactBoxes(
            BlockPosition position,
            List<WorldCollisionBox> collisionBoxes
    ) {
        return collisionBoxes.isEmpty()
                ? List.of(fullBlockBox(position))
                : List.copyOf(collisionBoxes);
    }

    private static boolean movementResolvedContactUsesCollisionBoxes(BlockState state) {
        return pistonArmBlock(state)
                || stairBlock(state)
                || fenceBlock(state)
                || thinFenceBlock(state)
                || chorusPlantBlock(state);
    }

    private static boolean movingPistonBlock(BlockState state) {
        return state.getBlock() == Blocks.MOVING_PISTON;
    }

    private static boolean pistonArmBlock(BlockState state) {
        return state.getBlock() instanceof PistonHeadBlock || state.getBlock() == Blocks.PISTON_HEAD;
    }

    private static boolean stairBlock(BlockState state) {
        return state.getBlock() instanceof StairBlock || state.is(net.minecraft.tags.BlockTags.STAIRS);
    }

    private static boolean fenceBlock(BlockState state) {
        return state.getBlock() instanceof FenceBlock || state.is(net.minecraft.tags.BlockTags.FENCES);
    }

    private static boolean thinFenceBlock(BlockState state) {
        return state.getBlock() instanceof IronBarsBlock || BARS_TAG != null && state.is(BARS_TAG);
    }

    private static boolean chorusPlantBlock(BlockState state) {
        return state.getBlock() instanceof ChorusPlantBlock || state.getBlock() == Blocks.CHORUS_PLANT;
    }

    private static boolean scaffoldingBlock(BlockState state) {
        return state.getBlock() instanceof ScaffoldingBlock || state.getBlock() == Blocks.SCAFFOLDING;
    }

    private static boolean powderSnowBlock(BlockState state) {
        return state.getBlock() instanceof PowderSnowBlock || state.getBlock() == Blocks.POWDER_SNOW;
    }

    private static boolean bambooBlock(BlockState state) {
        return state.getBlock() == Blocks.BAMBOO;
    }

    private static boolean shulkerBoxBlock(BlockState state) {
        return state.getBlock() instanceof ShulkerBoxBlock || state.is(net.minecraft.tags.BlockTags.SHULKER_BOXES);
    }

    private static List<WorldCollisionBox> powderSnowCollisionBoxes(
            BlockPosition position,
            BedrockCollisionShapeQuery query
    ) {
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

    private static List<WorldCollisionBox> stairCollisionBoxes(
            BlockPosition position,
            BlockState state
    ) {
        boolean upsideDown = state.getValue(StairBlock.HALF) == Half.TOP;
        int weirdoDirection = stairWeirdoDirection(state.getValue(StairBlock.FACING));

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

    private static void addStairStepBox(
            List<BlockAabb> boxes,
            int weirdoDirection,
            double minY,
            double maxY
    ) {
        switch (weirdoDirection) {
            case 0 -> boxes.add(new BlockAabb(0.5D, minY, 0.0D, 1.0D, maxY, 1.0D));
            case 1 -> boxes.add(new BlockAabb(0.0D, minY, 0.0D, 0.5D, maxY, 1.0D));
            case 2 -> boxes.add(new BlockAabb(0.0D, minY, 0.5D, 1.0D, maxY, 1.0D));
            case 3 -> boxes.add(new BlockAabb(0.0D, minY, 0.0D, 1.0D, maxY, 0.5D));
            default -> throw new IllegalArgumentException("unknown Bedrock stair weirdo_direction " + weirdoDirection);
        }
    }

    private static List<WorldCollisionBox> fenceCollisionBoxes(
            BlockPosition position,
            BlockState state
    ) {
        boolean negativeX = booleanProperty(state, BlockStateProperties.WEST);
        boolean positiveX = booleanProperty(state, BlockStateProperties.EAST);
        boolean negativeZ = booleanProperty(state, BlockStateProperties.NORTH);
        boolean positiveZ = booleanProperty(state, BlockStateProperties.SOUTH);

        List<BlockAabb> localBoxes = new ArrayList<>(2);
        if (negativeZ || positiveZ) {
            localBoxes.add(new BlockAabb(
                    0.375D,
                    0.0D,
                    negativeZ ? 0.0D : 0.375D,
                    0.625D,
                    1.5D,
                    positiveZ ? 1.0D : 0.625D));
        }
        if (negativeX || positiveX) {
            localBoxes.add(new BlockAabb(
                    negativeX ? 0.0D : 0.375D,
                    0.0D,
                    0.375D,
                    positiveX ? 1.0D : 0.625D,
                    1.5D,
                    0.625D));
        }
        if (localBoxes.isEmpty()) {
            localBoxes.add(new BlockAabb(0.375D, 0.0D, 0.375D, 0.625D, 1.5D, 0.625D));
        }
        return toWorldBoxes(position, localBoxes);
    }

    private static List<WorldCollisionBox> thinFenceCollisionBoxes(
            BlockPosition position,
            BlockState state
    ) {
        boolean negativeX = booleanProperty(state, BlockStateProperties.WEST);
        boolean positiveX = booleanProperty(state, BlockStateProperties.EAST);
        boolean negativeZ = booleanProperty(state, BlockStateProperties.NORTH);
        boolean positiveZ = booleanProperty(state, BlockStateProperties.SOUTH);

        List<BlockAabb> localBoxes = new ArrayList<>(2);
        if (negativeZ || positiveZ) {
            localBoxes.add(new BlockAabb(
                    0.4375D,
                    0.0D,
                    negativeZ ? 0.0D : 0.4375D,
                    0.5625D,
                    1.0D,
                    positiveZ ? 1.0D : 0.5625D));
        }
        if (negativeX || positiveX) {
            localBoxes.add(new BlockAabb(
                    negativeX ? 0.0D : 0.4375D,
                    0.0D,
                    0.4375D,
                    positiveX ? 1.0D : 0.5625D,
                    1.0D,
                    0.5625D));
        }
        if (localBoxes.isEmpty()) {
            localBoxes.add(new BlockAabb(0.4375D, 0.0D, 0.4375D, 0.5625D, 1.0D, 0.5625D));
        }
        return toWorldBoxes(position, localBoxes);
    }

    private static List<WorldCollisionBox> chorusPlantCollisionBoxes(
            BlockPosition position,
            BlockState state
    ) {
        boolean down = booleanProperty(state, PipeBlock.DOWN);
        boolean east = booleanProperty(state, PipeBlock.EAST);
        boolean north = booleanProperty(state, PipeBlock.NORTH);
        boolean south = booleanProperty(state, PipeBlock.SOUTH);
        boolean up = booleanProperty(state, PipeBlock.UP);
        boolean west = booleanProperty(state, PipeBlock.WEST);

        BlockAabb localBox = new BlockAabb(
                west ? 0.0D : 0.125D,
                down ? 0.0D : 0.125D,
                north ? 0.0D : 0.125D,
                east ? 1.0D : 0.875D,
                up ? 1.0D : 0.875D,
                south ? 1.0D : 0.875D);
        return List.of(toWorldBox(position, localBox));
    }

    private static List<WorldCollisionBox> pistonArmCollisionBoxes(
            BlockPosition position,
            BlockState state,
            double progress
    ) {
        BedrockPistonArmCollisionShape.FacingDirection direction = pistonFacingDirection(state);
        return toWorldBoxes(position, BedrockPistonArmCollisionShape.collisionAabbs(direction, progress));
    }

    private static BedrockPistonArmCollisionShape.FacingDirection pistonFacingDirection(BlockState state) {
        return switch (state.getValue(PistonHeadBlock.FACING)) {
            case DOWN -> BedrockPistonArmCollisionShape.FacingDirection.DOWN;
            case UP -> BedrockPistonArmCollisionShape.FacingDirection.UP;
            case SOUTH -> BedrockPistonArmCollisionShape.FacingDirection.POSITIVE_Z;
            case NORTH -> BedrockPistonArmCollisionShape.FacingDirection.NEGATIVE_Z;
            case EAST -> BedrockPistonArmCollisionShape.FacingDirection.POSITIVE_X;
            case WEST -> BedrockPistonArmCollisionShape.FacingDirection.NEGATIVE_X;
        };
    }

    private static List<WorldCollisionBox> scaffoldingCollisionBoxes(
            BlockPosition position,
            BedrockCollisionShapeQuery query
    ) {
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

    private static boolean booleanProperty(BlockState state, net.minecraft.world.level.block.state.properties.BooleanProperty property) {
        return state.hasProperty(property) && state.getValue(property);
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

    public static String javaIdentifier(BlockState state) {
        return ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil.registryKey(BuiltInRegistries.BLOCK, state.getBlock());
    }
}
