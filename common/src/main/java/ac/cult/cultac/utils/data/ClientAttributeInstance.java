package ac.cult.cultac.utils.data;

import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import java.util.LinkedHashMap;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Set;

/** A client AttributeInstance: the packet base, id index and any locally written operation entries. */
public final class ClientAttributeInstance {
    private final double defaultBase;
    private final double minValue;
    private final double maxValue;
    // Modifiers kept for a respawn but left out of the value.
    private final Set<String> excludedModifierIds;
    private double base;
    private final LinkedHashMap<String, AttributeModifier> modifiers = new LinkedHashMap<>();
    // Created only for addOrUpdateTransientModifier: vanilla retains an old operation entry when its id changes operations.
    private EnumMap<AttributeModifier.Operation, LinkedHashMap<String, AttributeModifier>> operationModifiers;
    // Operation entries as of the last calculateValue, which every write runs.
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

    /** ClientPacketListener#handleUpdateAttributes replaces the base and each indexed modifier. */
    public void onServerSnapshot(AttributeSnapshot snapshot) {
        replace(snapshot.base(), snapshot.modifiers());
    }

    public void replace(double base, List<AttributeModifier> modifiers) {
        this.base = base;
        removeIndexedModifiers();
        for (AttributeModifier modifier : modifiers) {
            this.modifiers.put(modifier.id(), modifier);
            if (operationModifiers != null) operation(modifier.operation()).put(modifier.id(), modifier);
        }
        this.value = calculateValue();
    }

    /** AttributeMap#assignAllValues. */
    public void copyFrom(ClientAttributeInstance other) {
        replace(other.base, List.copyOf(other.modifiers.values()));
    }

    /** ClientPacketListener#handleRespawn without KEEP_ATTRIBUTE_MODIFIERS: AttributeMap#assignBaseValues. */
    public void clearModifiers() {
        removeIndexedModifiers();
        value = calculateValue();
    }

    /** A client's removeModifier followed by addTransientModifier; null leaves the modifier removed. */
    public void replaceModifier(String id, AttributeModifier modifier) {
        AttributeModifier removed = modifiers.remove(id);
        if (operationModifiers != null && removed != null) operation(removed.operation()).remove(id);
        if (modifier != null) {
            modifiers.put(id, modifier);
            if (operationModifiers != null) operation(modifier.operation()).put(id, modifier);
        }
        if (removed != null || modifier != null) value = calculateValue();
    }

    /** AttributeInstance#addOrUpdateTransientModifier changes the id index and its new operation only. */
    public void updateModifier(String id, AttributeModifier modifier) {
        if (operationModifiers == null) {
            operationModifiers = new EnumMap<>(AttributeModifier.Operation.class);
            for (AttributeModifier entry : modifiers.values()) operation(entry.operation()).put(entry.id(), entry);
        }
        AttributeModifier previous = modifier == null ? modifiers.remove(id) : modifiers.put(id, modifier);
        if (modifier != null) operation(modifier.operation()).put(id, modifier);
        else if (previous != null) operation(previous.operation()).remove(id);
        if (previous != null || modifier != null) value = calculateValue();
    }

    private Map<String, AttributeModifier> operation(AttributeModifier.Operation operation) {
        return operationModifiers.computeIfAbsent(operation, ignored -> new LinkedHashMap<>());
    }

    /** AttributeInstance#removeModifiers removes only the entries still reachable through its id index. */
    private void removeIndexedModifiers() {
        if (operationModifiers != null)
            for (AttributeModifier entry : modifiers.values()) operation(entry.operation()).remove(entry.id());
        modifiers.clear();
    }

    public void reset() {
        base = defaultBase;
        modifiers.clear();
        operationModifiers = null;
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
        if (operationModifiers == null) ordered = List.copyOf(modifiers.values());
        else {
            var entries = new ArrayList<AttributeModifier>();
            for (var bucket : operationModifiers.values()) entries.addAll(bucket.values());
            ordered = List.copyOf(entries);
        }
        double value = CompensatedEntities.calculateAttributeValue(
                new AttributeSnapshot("", base, ordered), excludedModifierIds);
        return Double.isNaN(value) ? minValue : Math.min(Math.max(value, minValue), maxValue);
    }
}
