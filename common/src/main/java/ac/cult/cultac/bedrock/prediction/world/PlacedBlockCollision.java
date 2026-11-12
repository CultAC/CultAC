package ac.cult.cultac.bedrock.prediction.world;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.BlockTags;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.util.ImmutableJsonValue;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record PlacedBlockCollision(
        BlockPosition position,
        String javaState,
        JavaStateProperties javaStateProperties,
        String bedrockIdentifier,
        Map<String, Object> bedrockState,
        List<WorldCollisionBox> collisionBoxes,
        List<WorldCollisionBox> contactBoxes,
        List<WorldCollisionBox> insideBlockContactBoxes,
        Set<BlockContactBehavior> contactBehaviors) {
    /** The identifier of a solid actor's box, which a move collides with after the blocks. */
    public static final String SOLID_ACTOR = "minecraft:hard_entity_collision";

    public PlacedBlockCollision(
            BlockPosition position,
            String javaState,
            String bedrockIdentifier,
            Map<String, Object> bedrockState,
            List<WorldCollisionBox> collisionBoxes,
            List<WorldCollisionBox> contactBoxes,
            List<WorldCollisionBox> insideBlockContactBoxes,
            Set<BlockContactBehavior> contactBehaviors) {
        this(
                position,
                javaState,
                JavaStateProperties.from(javaState),
                bedrockIdentifier,
                bedrockState,
                collisionBoxes,
                contactBoxes,
                insideBlockContactBoxes,
                contactBehaviors);
    }

    public PlacedBlockCollision(
            BlockPosition position,
            String javaState,
            String bedrockIdentifier,
            Map<String, Object> bedrockState,
            List<WorldCollisionBox> collisionBoxes) {
        this(
                position,
                javaState,
                bedrockIdentifier,
                bedrockState,
                collisionBoxes,
                fullBlockContactBoxes(position),
                fullBlockContactBoxes(position));
    }

    public PlacedBlockCollision(
            BlockPosition position,
            String javaState,
            String bedrockIdentifier,
            Map<String, Object> bedrockState,
            List<WorldCollisionBox> collisionBoxes,
            List<WorldCollisionBox> contactBoxes) {
        this(
                position,
                javaState,
                bedrockIdentifier,
                bedrockState,
                collisionBoxes,
                contactBoxes,
                fullBlockContactBoxes(position));
    }

    public PlacedBlockCollision(
            BlockPosition position,
            String javaState,
            String bedrockIdentifier,
            Map<String, Object> bedrockState,
            List<WorldCollisionBox> collisionBoxes,
            List<WorldCollisionBox> contactBoxes,
            List<WorldCollisionBox> insideBlockContactBoxes) {
        this(
                position,
                javaState,
                bedrockIdentifier,
                bedrockState,
                collisionBoxes,
                contactBoxes,
                insideBlockContactBoxes,
                PlacedBlockContactBehaviorResolver.derive(javaState, bedrockIdentifier, bedrockState));
    }

    public PlacedBlockCollision {
        position = Objects.requireNonNull(position, "position");
        javaState = Objects.requireNonNull(javaState, "javaState");
        javaStateProperties = Objects.requireNonNull(javaStateProperties, "javaStateProperties");
        bedrockIdentifier = Objects.requireNonNull(bedrockIdentifier, "bedrockIdentifier");
        bedrockState = ImmutableJsonValue.copyObjectMap(bedrockState);
        collisionBoxes = List.copyOf(collisionBoxes);
        contactBoxes = List.copyOf(contactBoxes);
        insideBlockContactBoxes = List.copyOf(insideBlockContactBoxes);
        contactBehaviors = PlacedBlockContactBehaviorResolver.copy(contactBehaviors);
    }

    public static PlacedBlockCollision manual(
            BlockPosition position,
            String javaState,
            String bedrockIdentifier,
            List<WorldCollisionBox> collisionBoxes) {
        return new PlacedBlockCollision(position, javaState, bedrockIdentifier, Map.of(), collisionBoxes);
    }

    public static PlacedBlockCollision manual(
            BlockPosition position,
            String javaState,
            String bedrockIdentifier,
            List<WorldCollisionBox> collisionBoxes,
            Set<BlockContactBehavior> contactBehaviors) {
        return manual(
                position,
                javaState,
                bedrockIdentifier,
                collisionBoxes,
                fullBlockContactBoxes(position),
                fullBlockContactBoxes(position),
                contactBehaviors);
    }

    public static PlacedBlockCollision manual(
            BlockPosition position,
            String javaState,
            String bedrockIdentifier,
            List<WorldCollisionBox> collisionBoxes,
            List<WorldCollisionBox> contactBoxes) {
        return new PlacedBlockCollision(position, javaState, bedrockIdentifier, Map.of(), collisionBoxes, contactBoxes);
    }

    public static PlacedBlockCollision manual(
            BlockPosition position,
            String javaState,
            String bedrockIdentifier,
            List<WorldCollisionBox> collisionBoxes,
            List<WorldCollisionBox> contactBoxes,
            List<WorldCollisionBox> insideBlockContactBoxes) {
        return manual(
                position,
                javaState,
                bedrockIdentifier,
                collisionBoxes,
                contactBoxes,
                insideBlockContactBoxes,
                PlacedBlockContactBehaviorResolver.derive(javaState, bedrockIdentifier, Map.of()));
    }

    public static PlacedBlockCollision manual(
            BlockPosition position,
            String javaState,
            String bedrockIdentifier,
            List<WorldCollisionBox> collisionBoxes,
            List<WorldCollisionBox> contactBoxes,
            List<WorldCollisionBox> insideBlockContactBoxes,
            Set<BlockContactBehavior> contactBehaviors) {
        return new PlacedBlockCollision(
                position,
                javaState,
                bedrockIdentifier,
                Map.of(),
                collisionBoxes,
                contactBoxes,
                insideBlockContactBoxes,
                contactBehaviors);
    }

    public static PlacedBlockCollision manual(
            BlockPosition position,
            String javaState,
            String bedrockIdentifier,
            Map<String, Object> bedrockState,
            List<WorldCollisionBox> collisionBoxes) {
        return new PlacedBlockCollision(position, javaState, bedrockIdentifier, bedrockState, collisionBoxes);
    }

    public static PlacedBlockCollision manual(
            BlockPosition position,
            String javaState,
            String bedrockIdentifier,
            Map<String, Object> bedrockState,
            List<WorldCollisionBox> collisionBoxes,
            List<WorldCollisionBox> contactBoxes) {
        return new PlacedBlockCollision(
                position, javaState, bedrockIdentifier, bedrockState, collisionBoxes, contactBoxes);
    }

    public static PlacedBlockCollision manual(
            BlockPosition position,
            String javaState,
            String bedrockIdentifier,
            Map<String, Object> bedrockState,
            List<WorldCollisionBox> collisionBoxes,
            List<WorldCollisionBox> contactBoxes,
            List<WorldCollisionBox> insideBlockContactBoxes) {
        return manual(
                position,
                javaState,
                bedrockIdentifier,
                bedrockState,
                collisionBoxes,
                contactBoxes,
                insideBlockContactBoxes,
                PlacedBlockContactBehaviorResolver.derive(javaState, bedrockIdentifier, bedrockState));
    }

    public static PlacedBlockCollision manual(
            BlockPosition position,
            String javaState,
            String bedrockIdentifier,
            Map<String, Object> bedrockState,
            List<WorldCollisionBox> collisionBoxes,
            List<WorldCollisionBox> contactBoxes,
            List<WorldCollisionBox> insideBlockContactBoxes,
            Set<BlockContactBehavior> contactBehaviors) {
        return new PlacedBlockCollision(
                position,
                javaState,
                bedrockIdentifier,
                bedrockState,
                collisionBoxes,
                contactBoxes,
                insideBlockContactBoxes,
                contactBehaviors);
    }

    public boolean hasContactBehavior(BlockContactBehavior behavior) {
        return contactBehaviors.contains(Objects.requireNonNull(behavior, "behavior"));
    }

    /** Whether this is the box of a solid actor rather than a block. */
    public boolean isEntityCollision() {
        return SOLID_ACTOR.equals(bedrockIdentifier);
    }

    public static PlacedBlockCollision sampled(
            BlockPosition position,
            String javaIdentifier,
            int javaState,
            String bedrockIdentifier,
            Map<String, Object> bedrockState,
            List<WorldCollisionBox> collisionBoxes) {
        return sampled(
                position,
                javaIdentifier,
                javaState,
                bedrockIdentifier,
                bedrockState,
                collisionBoxes,
                fullBlockContactBoxes(position),
                fullBlockContactBoxes(position),
                PlacedBlockContactBehaviorResolver.derive(javaIdentifier, bedrockIdentifier, bedrockState));
    }

    public static PlacedBlockCollision sampled(
            BlockPosition position,
            String javaIdentifier,
            int javaState,
            String bedrockIdentifier,
            Map<String, Object> bedrockState,
            List<WorldCollisionBox> collisionBoxes,
            Set<BlockContactBehavior> contactBehaviors) {
        return sampled(
                position,
                javaIdentifier,
                javaState,
                bedrockIdentifier,
                bedrockState,
                collisionBoxes,
                fullBlockContactBoxes(position),
                fullBlockContactBoxes(position),
                contactBehaviors);
    }

    public static PlacedBlockCollision sampled(
            BlockPosition position,
            String javaIdentifier,
            int javaState,
            String bedrockIdentifier,
            List<WorldCollisionBox> collisionBoxes,
            Set<BlockContactBehavior> contactBehaviors) {
        return sampled(
                position,
                javaIdentifier,
                javaState,
                bedrockIdentifier,
                Map.of(),
                collisionBoxes,
                fullBlockContactBoxes(position),
                fullBlockContactBoxes(position),
                contactBehaviors);
    }

    public static PlacedBlockCollision sampled(
            BlockPosition position,
            String javaIdentifier,
            int javaState,
            String bedrockIdentifier,
            Map<String, Object> bedrockState,
            List<WorldCollisionBox> collisionBoxes,
            List<WorldCollisionBox> contactBoxes,
            List<WorldCollisionBox> insideBlockContactBoxes,
            Set<BlockContactBehavior> contactBehaviors) {
        return new PlacedBlockCollision(
                position,
                javaIdentifier,
                JavaStateProperties.from(javaState),
                bedrockIdentifier,
                bedrockState,
                collisionBoxes,
                contactBoxes,
                insideBlockContactBoxes,
                contactBehaviors);
    }

    /**
     * @param stairFacing a stair's horizontal facing as a 3D data value (2 north, 3 south, 4 west, 5 east), or
     *     {@link #NO_FACING}
     * @param shelfFacing a shelf's horizontal facing as a 3D data value, or {@link #NO_FACING}
     */
    public record JavaStateProperties(
            boolean waterlogged, String level, boolean drag, int stairFacing, int shelfFacing) {
        public static final int NO_FACING = -1;
        private static final boolean[] SHELVES = shelves();
        private static final JavaStateProperties EMPTY = new JavaStateProperties(false, null, false);

        public JavaStateProperties(boolean waterlogged, String level, boolean drag) {
            this(waterlogged, level, drag, NO_FACING, NO_FACING);
        }

        private static JavaStateProperties from(String javaState) {
            int properties = javaState.indexOf('[');
            if (properties < 0) {
                return EMPTY;
            }
            String identifier = javaState.substring(0, properties);
            int facing = horizontalFacing(property(javaState, "facing"));
            return new JavaStateProperties(
                    Boolean.parseBoolean(property(javaState, "waterlogged")),
                    property(javaState, "level"),
                    Boolean.parseBoolean(property(javaState, "drag")),
                    identifier.endsWith("_stairs") ? facing : NO_FACING,
                    identifier.endsWith("_shelf") ? facing : NO_FACING);
        }

        private static JavaStateProperties from(int javaState) {
            boolean waterlogged =
                    BlockProps.WATERLOGGED.has(javaState) && BlockProps.WATERLOGGED.booleanValue(javaState);
            boolean drag = BlockIds.is(javaState, BlockIds.BUBBLE_COLUMN) && BlockProps.DRAG.booleanValue(javaState);
            int facing = BlockProps.HORIZONTAL_FACING.has(javaState)
                    ? BlockProps.HORIZONTAL_FACING.value(javaState)
                    : NO_FACING;
            int stairFacing =
                    BlockFamilies.STAIR.test(javaState) || BlockTags.STAIRS.test(javaState) ? facing : NO_FACING;
            int shelfFacing = facing != NO_FACING
                            && SHELVES[DataTables.defaults().registry().blockIndex(javaState)]
                    ? facing
                    : NO_FACING;
            return waterlogged || drag || stairFacing != NO_FACING || shelfFacing != NO_FACING
                    ? new JavaStateProperties(waterlogged, null, drag, stairFacing, shelfFacing)
                    : EMPTY;
        }

        private static boolean[] shelves() {
            List<BlockDefinition> blocks = DataTables.defaults().registry().blocks();
            boolean[] shelves = new boolean[blocks.size()];
            for (int index = 0; index < shelves.length; index++) {
                shelves[index] = blocks.get(index).key().endsWith("_shelf");
            }
            return shelves;
        }

        private static int horizontalFacing(String facing) {
            return facing == null
                    ? NO_FACING
                    : switch (facing) {
                        case "north" -> 2;
                        case "south" -> 3;
                        case "west" -> 4;
                        case "east" -> 5;
                        default -> NO_FACING;
                    };
        }

        private static String property(String javaState, String name) {
            String needle = name + '=';
            int start = javaState.indexOf(needle);
            while (start >= 0 && start > 0) {
                char previous = javaState.charAt(start - 1);
                if (previous == '[' || previous == ',') {
                    int valueStart = start + needle.length();
                    int comma = javaState.indexOf(',', valueStart);
                    int bracket = javaState.indexOf(']', valueStart);
                    int end = comma >= 0 && (bracket < 0 || comma < bracket) ? comma : bracket;
                    return end < 0 ? javaState.substring(valueStart) : javaState.substring(valueStart, end);
                }
                start = javaState.indexOf(needle, start + needle.length());
            }
            return null;
        }
    }

    private static List<WorldCollisionBox> fullBlockContactBoxes(BlockPosition position) {
        Objects.requireNonNull(position, "position");
        return List.of(new WorldCollisionBox(
                position.x(),
                position.y(),
                position.z(),
                position.x() + 1.0D,
                position.y() + 1.0D,
                position.z() + 1.0D));
    }

    public enum BlockContactBehavior {
        HONEY,
        SLIME,
        SOUL_SAND,
        SOUL_SOIL,
        COBWEB,
        SWEET_BERRY_BUSH,
        POWDER_SNOW,
        SCAFFOLDING,
        CLIMBABLE,
        LADDER,
        WALL_VINE
    }
}
