package ac.cult.cultac.bedrock.prediction.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Movement attribute state, independent of the actor sprint flag.
 * See docs/bedrock-sprint-attributes.md for replacement and replay behavior.
 */
public record BedrockMovementAttributeState(float current, float minimum, float maximum,
        float defaultMinimum, float defaultMaximum, float defaultValue,
        List<Modifier> modifiers) {
    public static final String SPRINT_ID = "D208FC00-42AA-4AAD-9276-D5446530DE43";
    public static final Modifier SPRINT = new Modifier(SPRINT_ID, "Sprinting speed boost", 0.3F, 2, 2, false);
    public static final BedrockMovementAttributeState DEFAULT = serverValue(0.1F, false);

    public BedrockMovementAttributeState {
        modifiers = List.copyOf(modifiers);
    }

    /** Complete server replacement; current is supplied independently of defaults and modifiers. */
    public BedrockMovementAttributeState replace(BedrockMovementAttributeState incoming) {
        // The wire current incorporates the transient synchronization adjustment. Subsequent
        // modifier mutations recalculate from defaults, so no adjustment needs to survive replay.
        return new BedrockMovementAttributeState(clamp(incoming.current, incoming.minimum, incoming.maximum,
                incoming.modifiers), incoming.minimum, incoming.maximum, incoming.defaultMinimum,
                incoming.defaultMaximum, incoming.defaultValue, incoming.modifiers);
    }

    public boolean hasSprintModifier() {
        return modifiers.stream().anyMatch(modifier -> modifier.id().equalsIgnoreCase(SPRINT_ID));
    }

    public BedrockMovementAttributeState addSprint() {
        if (hasSprintModifier()) return this;
        var next = new ArrayList<>(modifiers);
        next.add(SPRINT);
        return recalculate(next);
    }

    public BedrockMovementAttributeState removeSprint() {
        if (!hasSprintModifier()) return this;
        return recalculate(modifiers.stream().filter(modifier -> !modifier.id().equalsIgnoreCase(SPRINT_ID)).toList());
    }

    public static BedrockMovementAttributeState serverValue(float nonSprintValue, boolean sprinting) {
        var base = new BedrockMovementAttributeState(nonSprintValue, 0, 1024, 0, 1024,
                nonSprintValue, List.of());
        return sprinting ? base.addSprint() : base;
    }

    private BedrockMovementAttributeState recalculate(List<Modifier> next) {
        float[] added = {defaultMinimum, defaultMaximum, defaultValue};
        for (Modifier modifier : next) {
            if (modifier.operation == 0 && modifier.validOperand()) added[modifier.operand] += modifier.amount;
        }
        float[] values = added.clone();
        for (Modifier modifier : next) {
            if (modifier.operation == 1 && modifier.validOperand())
                values[modifier.operand] += added[modifier.operand] * modifier.amount;
        }
        for (Modifier modifier : next) {
            if (modifier.operation == 2 && modifier.validOperand()) values[modifier.operand] *= 1.0F + modifier.amount;
        }
        // Syncable movement recalculates from its default when its last modifier is removed.
        // Nonempty, numerically neutral modifiers preserve the supplied current value.
        float value = !next.isEmpty() && values[2] == defaultValue ? current : values[2];
        return new BedrockMovementAttributeState(clamp(value, values[0], values[1], next), values[0], values[1],
                defaultMinimum, defaultMaximum, defaultValue, next);
    }

    private static float clamp(float value, float minimum, float maximum, List<Modifier> modifiers) {
        for (Modifier modifier : modifiers) if (modifier.operation == 3 && maximum > modifier.amount) maximum = modifier.amount;
        return value > maximum ? maximum : value > minimum ? value : minimum;
    }

    public record Modifier(String id, String name, float amount, int operation, int operand, boolean serializable) {
        public Modifier { Objects.requireNonNull(id); Objects.requireNonNull(name); }
        private boolean validOperand() { return operand >= 0 && operand < 3; }
    }
}
