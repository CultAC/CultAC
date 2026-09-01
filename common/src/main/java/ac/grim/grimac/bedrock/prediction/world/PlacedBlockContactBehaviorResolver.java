package ac.grim.grimac.bedrock.prediction.world;

import ac.grim.grimac.bedrock.prediction.world.PlacedBlockCollision.BlockContactBehavior;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class PlacedBlockContactBehaviorResolver {
    private PlacedBlockContactBehaviorResolver() {
    }

    static Set<BlockContactBehavior> copy(Set<BlockContactBehavior> behaviors) {
        Objects.requireNonNull(behaviors, "contactBehaviors");
        if (behaviors.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(behaviors);
    }

    static Set<BlockContactBehavior> derive(
        String javaState,
        String bedrockIdentifier,
        Map<String, Object> bedrockState
    ) {
        EnumSet<BlockContactBehavior> behaviors = EnumSet.noneOf(BlockContactBehavior.class);
        Object explicitBehaviors = BedrockBlockMetadata.value(bedrockState, BedrockBlockMetadata.CONTACT_BEHAVIORS);
        if (explicitBehaviors != null) {
            addExplicitContactBehaviors(behaviors, explicitBehaviors);
            return behaviors.isEmpty() ? Set.of() : Set.copyOf(behaviors);
        }

        String javaIdentifier = baseIdentifier(javaState);
        String bedrockId = baseIdentifier(bedrockIdentifier);
        if ("minecraft:honey_block".equals(javaIdentifier) || "minecraft:honey_block".equals(bedrockId)) {
            behaviors.add(BlockContactBehavior.HONEY);
        }
        if (isSlime(javaIdentifier) || isSlime(bedrockId)) {
            behaviors.add(BlockContactBehavior.SLIME);
        }
        if ("minecraft:soul_sand".equals(javaIdentifier) || "minecraft:soul_sand".equals(bedrockId)) {
            behaviors.add(BlockContactBehavior.SOUL_SAND);
        }
        if ("minecraft:soul_soil".equals(javaIdentifier) || "minecraft:soul_soil".equals(bedrockId)) {
            behaviors.add(BlockContactBehavior.SOUL_SOIL);
        }
        if ("minecraft:cobweb".equals(javaIdentifier) || "minecraft:web".equals(bedrockId)) {
            behaviors.add(BlockContactBehavior.COBWEB);
        }
        if ("minecraft:sweet_berry_bush".equals(javaIdentifier)
            || "minecraft:sweet_berry_bush".equals(bedrockId)) {
            behaviors.add(BlockContactBehavior.SWEET_BERRY_BUSH);
        }
        if ("minecraft:powder_snow".equals(javaIdentifier)
            || "minecraft:powder_snow".equals(bedrockId)
            || "powder_snow".equals(bedrockId)) {
            behaviors.add(BlockContactBehavior.POWDER_SNOW);
        }
        if ("minecraft:scaffolding".equals(javaIdentifier) || "minecraft:scaffolding".equals(bedrockId)) {
            behaviors.add(BlockContactBehavior.SCAFFOLDING);
        }
        if ("minecraft:ladder".equals(javaIdentifier) || "minecraft:ladder".equals(bedrockId)) {
            behaviors.add(BlockContactBehavior.CLIMBABLE);
            behaviors.add(BlockContactBehavior.LADDER);
        }
        if ("minecraft:vine".equals(javaIdentifier) || "minecraft:vine".equals(bedrockId)) {
            behaviors.add(BlockContactBehavior.CLIMBABLE);
            behaviors.add(BlockContactBehavior.WALL_VINE);
        }
        if (isVerticalVineFamily(javaIdentifier) || isVerticalVineFamily(bedrockId)) {
            behaviors.add(BlockContactBehavior.CLIMBABLE);
        }
        return behaviors.isEmpty() ? Set.of() : Set.copyOf(behaviors);
    }

    private static boolean isVerticalVineFamily(String identifier) {
        return "minecraft:weeping_vines".equals(identifier)
                || "minecraft:weeping_vines_plant".equals(identifier)
                || "minecraft:twisting_vines".equals(identifier)
                || "minecraft:twisting_vines_plant".equals(identifier)
                || "minecraft:cave_vines".equals(identifier)
                || "minecraft:cave_vines_plant".equals(identifier);
    }

    private static boolean isSlime(String identifier) {
        return "minecraft:slime_block".equals(identifier)
                || "slime_block".equals(identifier)
                || "minecraft:slime".equals(identifier)
                || "slime".equals(identifier);
    }

    private static void addExplicitContactBehaviors(
        Set<BlockContactBehavior> behaviors,
        Object explicit
    ) {
        if (explicit == null) {
            return;
        }
        if (explicit instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                addExplicitContactBehavior(behaviors, value);
            }
            return;
        }
        if (explicit instanceof Object[] array) {
            for (Object value : array) {
                addExplicitContactBehavior(behaviors, value);
            }
            return;
        }
        addExplicitContactBehavior(behaviors, explicit);
    }

    private static void addExplicitContactBehavior(Set<BlockContactBehavior> behaviors, Object value) {
        if (value instanceof BlockContactBehavior behavior) {
            behaviors.add(behavior);
            return;
        }
        if (value instanceof String text) {
            behaviors.add(BlockContactBehavior.valueOf(text.trim().toUpperCase().replace('-', '_')));
            return;
        }
        throw new IllegalArgumentException(BedrockBlockMetadata.CONTACT_BEHAVIORS + " entries must be strings");
    }

    private static String baseIdentifier(String stateOrIdentifier) {
        if (stateOrIdentifier == null) {
            return "";
        }
        int bracket = stateOrIdentifier.indexOf('[');
        return bracket < 0 ? stateOrIdentifier : stateOrIdentifier.substring(0, bracket);
    }
}
