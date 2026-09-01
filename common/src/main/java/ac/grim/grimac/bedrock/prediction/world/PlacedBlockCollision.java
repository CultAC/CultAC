package ac.grim.grimac.bedrock.prediction.world;

import ac.grim.grimac.bedrock.prediction.geometry.BlockPosition;
import ac.grim.grimac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.grim.grimac.bedrock.prediction.util.ImmutableJsonValue;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public record PlacedBlockCollision(
    BlockPosition position,
    String javaState,
    JavaStateProperties javaStateProperties,
    String bedrockIdentifier,
    Map<String, Object> bedrockState,
    List<WorldCollisionBox> collisionBoxes,
    List<WorldCollisionBox> contactBoxes,
    List<WorldCollisionBox> insideBlockContactBoxes,
    Set<BlockContactBehavior> contactBehaviors
) {
    public PlacedBlockCollision(
        BlockPosition position,
        String javaState,
        String bedrockIdentifier,
        Map<String, Object> bedrockState,
        List<WorldCollisionBox> collisionBoxes,
        List<WorldCollisionBox> contactBoxes,
        List<WorldCollisionBox> insideBlockContactBoxes,
        Set<BlockContactBehavior> contactBehaviors
    ) {
        this(
            position,
            javaState,
            JavaStateProperties.from(javaState),
            bedrockIdentifier,
            bedrockState,
            collisionBoxes,
            contactBoxes,
            insideBlockContactBoxes,
            contactBehaviors
        );
    }

    public PlacedBlockCollision(
        BlockPosition position,
        String javaState,
        String bedrockIdentifier,
        Map<String, Object> bedrockState,
        List<WorldCollisionBox> collisionBoxes
    ) {
        this(
            position,
            javaState,
            bedrockIdentifier,
            bedrockState,
            collisionBoxes,
            fullBlockContactBoxes(position),
            fullBlockContactBoxes(position)
        );
    }

    public PlacedBlockCollision(
        BlockPosition position,
        String javaState,
        String bedrockIdentifier,
        Map<String, Object> bedrockState,
        List<WorldCollisionBox> collisionBoxes,
        List<WorldCollisionBox> contactBoxes
    ) {
        this(
            position,
            javaState,
            bedrockIdentifier,
            bedrockState,
            collisionBoxes,
            contactBoxes,
            fullBlockContactBoxes(position)
        );
    }

    public PlacedBlockCollision(
        BlockPosition position,
        String javaState,
        String bedrockIdentifier,
        Map<String, Object> bedrockState,
        List<WorldCollisionBox> collisionBoxes,
        List<WorldCollisionBox> contactBoxes,
        List<WorldCollisionBox> insideBlockContactBoxes
    ) {
        this(
            position,
            javaState,
            bedrockIdentifier,
            bedrockState,
            collisionBoxes,
            contactBoxes,
            insideBlockContactBoxes,
            PlacedBlockContactBehaviorResolver.derive(javaState, bedrockIdentifier, bedrockState)
        );
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
        List<WorldCollisionBox> collisionBoxes
    ) {
        return new PlacedBlockCollision(position, javaState, bedrockIdentifier, Map.of(), collisionBoxes);
    }

    public static PlacedBlockCollision manual(
        BlockPosition position,
        String javaState,
        String bedrockIdentifier,
        List<WorldCollisionBox> collisionBoxes,
        Set<BlockContactBehavior> contactBehaviors
    ) {
        return manual(
            position,
            javaState,
            bedrockIdentifier,
            collisionBoxes,
            fullBlockContactBoxes(position),
            fullBlockContactBoxes(position),
            contactBehaviors
        );
    }

    public static PlacedBlockCollision manual(
        BlockPosition position,
        String javaState,
        String bedrockIdentifier,
        List<WorldCollisionBox> collisionBoxes,
        List<WorldCollisionBox> contactBoxes
    ) {
        return new PlacedBlockCollision(position, javaState, bedrockIdentifier, Map.of(), collisionBoxes, contactBoxes);
    }

    public static PlacedBlockCollision manual(
        BlockPosition position,
        String javaState,
        String bedrockIdentifier,
        List<WorldCollisionBox> collisionBoxes,
        List<WorldCollisionBox> contactBoxes,
        List<WorldCollisionBox> insideBlockContactBoxes
    ) {
        return manual(
            position,
            javaState,
            bedrockIdentifier,
            collisionBoxes,
            contactBoxes,
            insideBlockContactBoxes,
            PlacedBlockContactBehaviorResolver.derive(javaState, bedrockIdentifier, Map.of())
        );
    }

    public static PlacedBlockCollision manual(
        BlockPosition position,
        String javaState,
        String bedrockIdentifier,
        List<WorldCollisionBox> collisionBoxes,
        List<WorldCollisionBox> contactBoxes,
        List<WorldCollisionBox> insideBlockContactBoxes,
        Set<BlockContactBehavior> contactBehaviors
    ) {
        return new PlacedBlockCollision(
            position,
            javaState,
            bedrockIdentifier,
            Map.of(),
            collisionBoxes,
            contactBoxes,
            insideBlockContactBoxes,
            contactBehaviors
        );
    }

    public static PlacedBlockCollision manual(
        BlockPosition position,
        String javaState,
        String bedrockIdentifier,
        Map<String, Object> bedrockState,
        List<WorldCollisionBox> collisionBoxes
    ) {
        return new PlacedBlockCollision(position, javaState, bedrockIdentifier, bedrockState, collisionBoxes);
    }

    public static PlacedBlockCollision manual(
        BlockPosition position,
        String javaState,
        String bedrockIdentifier,
        Map<String, Object> bedrockState,
        List<WorldCollisionBox> collisionBoxes,
        List<WorldCollisionBox> contactBoxes
    ) {
        return new PlacedBlockCollision(position, javaState, bedrockIdentifier, bedrockState, collisionBoxes, contactBoxes);
    }

    public static PlacedBlockCollision manual(
        BlockPosition position,
        String javaState,
        String bedrockIdentifier,
        Map<String, Object> bedrockState,
        List<WorldCollisionBox> collisionBoxes,
        List<WorldCollisionBox> contactBoxes,
        List<WorldCollisionBox> insideBlockContactBoxes
    ) {
        return manual(
            position,
            javaState,
            bedrockIdentifier,
            bedrockState,
            collisionBoxes,
            contactBoxes,
            insideBlockContactBoxes,
            PlacedBlockContactBehaviorResolver.derive(javaState, bedrockIdentifier, bedrockState)
        );
    }

    public static PlacedBlockCollision manual(
        BlockPosition position,
        String javaState,
        String bedrockIdentifier,
        Map<String, Object> bedrockState,
        List<WorldCollisionBox> collisionBoxes,
        List<WorldCollisionBox> contactBoxes,
        List<WorldCollisionBox> insideBlockContactBoxes,
        Set<BlockContactBehavior> contactBehaviors
    ) {
        return new PlacedBlockCollision(
            position,
            javaState,
            bedrockIdentifier,
            bedrockState,
            collisionBoxes,
            contactBoxes,
            insideBlockContactBoxes,
            contactBehaviors
        );
    }

    public boolean hasContactBehavior(BlockContactBehavior behavior) {
        return contactBehaviors.contains(Objects.requireNonNull(behavior, "behavior"));
    }

    public static PlacedBlockCollision sampled(
        BlockPosition position,
        String javaIdentifier,
        BlockState javaState,
        String bedrockIdentifier,
        Map<String, Object> bedrockState,
        List<WorldCollisionBox> collisionBoxes
    ) {
        return sampled(
            position,
            javaIdentifier,
            javaState,
            bedrockIdentifier,
            bedrockState,
            collisionBoxes,
            fullBlockContactBoxes(position),
            fullBlockContactBoxes(position),
            PlacedBlockContactBehaviorResolver.derive(javaIdentifier, bedrockIdentifier, bedrockState)
        );
    }

    public static PlacedBlockCollision sampled(
        BlockPosition position,
        String javaIdentifier,
        BlockState javaState,
        String bedrockIdentifier,
        Map<String, Object> bedrockState,
        List<WorldCollisionBox> collisionBoxes,
        Set<BlockContactBehavior> contactBehaviors
    ) {
        return sampled(
            position,
            javaIdentifier,
            javaState,
            bedrockIdentifier,
            bedrockState,
            collisionBoxes,
            fullBlockContactBoxes(position),
            fullBlockContactBoxes(position),
            contactBehaviors
        );
    }

    public static PlacedBlockCollision sampled(
        BlockPosition position,
        String javaIdentifier,
        BlockState javaState,
        String bedrockIdentifier,
        List<WorldCollisionBox> collisionBoxes,
        Set<BlockContactBehavior> contactBehaviors
    ) {
        return sampled(
            position,
            javaIdentifier,
            javaState,
            bedrockIdentifier,
            Map.of(),
            collisionBoxes,
            fullBlockContactBoxes(position),
            fullBlockContactBoxes(position),
            contactBehaviors
        );
    }

    public static PlacedBlockCollision sampled(
        BlockPosition position,
        String javaIdentifier,
        BlockState javaState,
        String bedrockIdentifier,
        Map<String, Object> bedrockState,
        List<WorldCollisionBox> collisionBoxes,
        List<WorldCollisionBox> contactBoxes,
        List<WorldCollisionBox> insideBlockContactBoxes,
        Set<BlockContactBehavior> contactBehaviors
    ) {
        return new PlacedBlockCollision(
            position,
            javaIdentifier,
            JavaStateProperties.from(javaState),
            bedrockIdentifier,
            bedrockState,
            collisionBoxes,
            contactBoxes,
            insideBlockContactBoxes,
            contactBehaviors
        );
    }

    public record JavaStateProperties(boolean waterlogged, String level, boolean drag) {
        private static final JavaStateProperties EMPTY = new JavaStateProperties(false, null, false);

        private static JavaStateProperties from(String javaState) {
            if (javaState.indexOf('[') < 0) {
                return EMPTY;
            }
            return new JavaStateProperties(
                    Boolean.parseBoolean(property(javaState, "waterlogged")),
                    property(javaState, "level"),
                    Boolean.parseBoolean(property(javaState, "drag")));
        }

        private static JavaStateProperties from(BlockState javaState) {
            boolean waterlogged = javaState.hasProperty(BlockStateProperties.WATERLOGGED)
                    && javaState.getValue(BlockStateProperties.WATERLOGGED);
            boolean drag = javaState.getBlock() == Blocks.BUBBLE_COLUMN
                    && javaState.getValue(BubbleColumnBlock.DRAG_DOWN);
            return waterlogged || drag
                    ? new JavaStateProperties(waterlogged, null, drag)
                    : EMPTY;
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
            position.z() + 1.0D
        ));
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
