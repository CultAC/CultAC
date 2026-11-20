package ac.cult.cultac.utils.data;

import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** A local player AttributeInstance: a base value and the modifiers last sent by the server. */
public final class ClientAttributeInstance {
    private final double defaultBase;
    private final double minValue;
    private final double maxValue;
    // Modifiers kept for a respawn but left out of the value.
    private final Set<String> excludedModifierIds;
    private double base;
    private final LinkedHashMap<String, AttributeModifier> modifiers = new LinkedHashMap<>();
    // modifiers.values() as of the last calculateValue, which every write to modifiers runs.
    private List<AttributeModifier> ordered;
    private double value;

    public ClientAttributeInstance(double defaultBase, double minValue, double maxValue) {
        this(defaultBase, minValue, maxValue, Set.of());
    }

    public ClientAttributeInstance(
            double defaultBase, double minValue, double maxValue, Set<String> excludedModifierIds) {
        this.defaultBase = defaultBase;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.excludedModifierIds = excludedModifierIds;
        this.base = defaultBase;
        this.value = calculateValue();
    }

    /** ClientPacketListener#handleUpdateAttributes replaces the base and every modifier. */
    public void onServerSnapshot(AttributeSnapshot snapshot) {
        replace(snapshot.base(), snapshot.modifiers());
    }

    public void replace(double base, List<AttributeModifier> modifiers) {
        this.base = base;
        this.modifiers.clear();
        for (AttributeModifier modifier : modifiers) {
            this.modifiers.put(modifier.id(), modifier);
        }
        this.value = calculateValue();
    }

    /** AttributeMap#assignAllValues. */
    public void copyFrom(ClientAttributeInstance other) {
        replace(other.base, List.copyOf(other.modifiers.values()));
    }

    /** ClientPacketListener#handleRespawn without KEEP_ATTRIBUTE_MODIFIERS: AttributeMap#assignBaseValues. */
    public void clearModifiers() {
        modifiers.clear();
        value = calculateValue();
    }

    public void reset() {
        base = defaultBase;
        modifiers.clear();
        value = calculateValue();
    }

    /** AttributeInstance#getValue, recalculated only when the base or modifiers change. */
    public double value() {
        return value;
    }

    /** AttributeInstance#getValue once AttributeInstance#addTransientModifier has added {@code modifier}. */
    public double valueWith(AttributeModifier modifier) {
        int size = ordered.size();
        int at = size;
        for (int i = 0; i < size; i++) {
            if (ordered.get(i).id().equals(modifier.id())) {
                at = i;
                break;
            }
        }
        int count = at == size ? size + 1 : size;
        double d0 = base;
        for (int i = 0; i < count; i++) {
            AttributeModifier entry = modifierWith(i, at, modifier);
            if (entry != null && entry.operation() == AttributeModifier.Operation.ADD_VALUE) d0 += entry.amount();
        }
        double d1 = d0;
        for (int i = 0; i < count; i++) {
            AttributeModifier entry = modifierWith(i, at, modifier);
            if (entry != null && entry.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE)
                d1 += d0 * entry.amount();
        }
        for (int i = 0; i < count; i++) {
            AttributeModifier entry = modifierWith(i, at, modifier);
            if (entry != null && entry.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
                d1 *= 1.0D + entry.amount();
        }
        return Double.isNaN(d1) ? minValue : Math.min(Math.max(d1, minValue), maxValue);
    }

    /** The modifier at {@code index} once {@code modifier} holds index {@code at}, or null when it is excluded. */
    private AttributeModifier modifierWith(int index, int at, AttributeModifier modifier) {
        if (index == at) return modifier;
        AttributeModifier entry = ordered.get(index);
        return excludedModifierIds.contains(entry.id()) ? null : entry;
    }

    // AttributeInstance#calculateValue, then RangedAttribute#sanitizeValue
    private double calculateValue() {
        ordered = List.copyOf(modifiers.values());
        double value = CompensatedEntities.calculateAttributeValue(
                new AttributeSnapshot("", base, ordered), excludedModifierIds);
        return Double.isNaN(value) ? minValue : Math.min(Math.max(value, minValue), maxValue);
    }
}
