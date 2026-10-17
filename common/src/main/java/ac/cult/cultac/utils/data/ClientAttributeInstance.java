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
    private double base;
    private final LinkedHashMap<String, AttributeModifier> modifiers = new LinkedHashMap<>();
    private double value;

    public ClientAttributeInstance(double defaultBase, double minValue, double maxValue) {
        this.defaultBase = defaultBase;
        this.minValue = minValue;
        this.maxValue = maxValue;
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

    // AttributeInstance#calculateValue, then RangedAttribute#sanitizeValue
    private double calculateValue() {
        double value = CompensatedEntities.calculateAttributeValue(
                new AttributeSnapshot("", base, List.copyOf(modifiers.values())), Set.of());
        return Double.isNaN(value) ? minValue : Math.min(Math.max(value, minValue), maxValue);
    }
}
