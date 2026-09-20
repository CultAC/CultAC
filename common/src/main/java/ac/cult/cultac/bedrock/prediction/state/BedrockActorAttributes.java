package ac.cult.cultac.bedrock.prediction.state;

import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import java.util.HashMap;
import java.util.Map;

/** Immutable client attribute instances, owned by one actor and copied into rewind history. */
public record BedrockActorAttributes(Map<String, BedrockMovementAttributeState> values) {
    public static final String MOVEMENT = "minecraft:movement";
    public static final BedrockActorAttributes EMPTY = new BedrockActorAttributes(Map.of());

    public BedrockActorAttributes { values = Map.copyOf(values); }

    public BedrockMovementAttributeState movement() {
        return values.getOrDefault(MOVEMENT, BedrockMovementAttributeState.DEFAULT);
    }

    public BedrockActorAttributes withMovement(BedrockMovementAttributeState value) {
        var next = new HashMap<>(values);
        next.put(MOVEMENT, value);
        return new BedrockActorAttributes(next);
    }

    /** A packet replaces supplied instances, retaining attributes omitted from that packet. */
    public BedrockActorAttributes replace(Map<String, BedrockMovementAttributeState> incoming) {
        var next = new HashMap<>(values);
        incoming.forEach((name, value) -> next.put(name, value.replace(value)));
        return new BedrockActorAttributes(next);
    }

    public float current(String name, float fallback) {
        var value = values.get(name);
        return value == null ? fallback : value.current();
    }

    public AttributeState apply(AttributeState defaults) {
        float speed = current(MOVEMENT, (float) defaults.baseMovementSpeed());
        return new AttributeState(speed, speed,
                current("minecraft:underwater_movement", defaults.underwaterMovementSpeed()),
                current("minecraft:lava_movement", defaults.lavaMovementSpeed()),
                current("minecraft:horse.jump_strength", defaults.jumpStrength()), defaults.frictionModifier());
    }
}
