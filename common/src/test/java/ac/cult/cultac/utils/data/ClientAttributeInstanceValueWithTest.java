package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** valueWith equals the copy-and-recalculate AttributeInstance#getValue bit for bit after every kind of update. */
class ClientAttributeInstanceValueWithTest {
    private static final String[] IDS = {
        "minecraft:sprinting", "minecraft:powder_snow", "minecraft:effect.speed", "minecraft:effect.slowness", "a", "b"
    };
    private static final double[] SPECIAL = {
        Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0, 0.0, -0.0, 1e300, 0.3F, -0.15F
    };
    private static final List<Set<String>> EXCLUDED = List.of(
            Set.of(),
            Set.of("minecraft:sprinting"),
            CompensatedEntities.EXCLUDED_MODIFIER_IDS,
            Set.of("a", "minecraft:effect.speed"));

    @Test
    void matchesTheCopiedMapCalculationAcrossReplacementsAndClears() {
        var random = new SplittableRandom(0x5EED7L);
        for (Set<String> excluded : EXCLUDED) {
            var instance = new ClientAttributeInstance(0.1F, 0.0D, 1024.0D, excluded);
            var other = new ClientAttributeInstance(0.1F, 0.0D, 1024.0D, excluded);
            double base = 0.1F;
            var modifiers = new LinkedHashMap<String, AttributeModifier>();
            double otherBase = 0.1F;
            var otherModifiers = new LinkedHashMap<String, AttributeModifier>();
            for (int step = 0; step < 20_000; step++) {
                switch (random.nextInt(8)) {
                    case 0, 1, 2 -> {
                        base = amount(random);
                        List<AttributeModifier> sent = modifiers(random);
                        instance.replace(base, sent);
                        modifiers.clear();
                        for (AttributeModifier modifier : sent) modifiers.put(modifier.id(), modifier);
                    }
                    case 3 -> {
                        otherBase = amount(random);
                        List<AttributeModifier> sent = modifiers(random);
                        other.onServerSnapshot(new AttributeSnapshot("minecraft:movement_speed", otherBase, sent));
                        otherModifiers.clear();
                        for (AttributeModifier modifier : sent) otherModifiers.put(modifier.id(), modifier);
                    }
                    case 4 -> {
                        instance.copyFrom(other);
                        base = otherBase;
                        modifiers = new LinkedHashMap<>(otherModifiers);
                    }
                    case 5 -> {
                        instance.clearModifiers();
                        modifiers.clear();
                    }
                    case 6 -> {
                        instance.reset();
                        base = 0.1F;
                        modifiers.clear();
                    }
                    default -> {}
                }
                for (int probe = 0; probe < 4; probe++) {
                    AttributeModifier modifier = modifier(random);
                    double expected = expected(base, modifiers, excluded, 0.0D, 1024.0D, modifier);
                    double actual = instance.valueWith(modifier);
                    assertEquals(
                            Double.doubleToRawLongBits(expected),
                            Double.doubleToRawLongBits(actual),
                            () -> "base=" + instance.value() + " probe=" + modifier + " excluded=" + excluded);
                }
            }
        }
    }

    /** The previous valueWith body: AttributeInstance#addTransientModifier on a copy, then calculateValue. */
    static double expected(
            double base,
            LinkedHashMap<String, AttributeModifier> modifiers,
            Set<String> excludedModifierIds,
            double minValue,
            double maxValue,
            AttributeModifier modifier) {
        LinkedHashMap<String, AttributeModifier> with = new LinkedHashMap<>(modifiers);
        with.put(modifier.id(), modifier);
        Set<String> excluded = new HashSet<>(excludedModifierIds);
        excluded.remove(modifier.id());
        double value = CompensatedEntities.calculateAttributeValue(
                new AttributeSnapshot("", base, new ArrayList<>(with.values())), excluded);
        return Double.isNaN(value) ? minValue : Math.min(Math.max(value, minValue), maxValue);
    }

    private static List<AttributeModifier> modifiers(SplittableRandom random) {
        int count = random.nextInt(6);
        List<AttributeModifier> modifiers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) modifiers.add(modifier(random));
        return modifiers;
    }

    private static AttributeModifier modifier(SplittableRandom random) {
        var operations = AttributeModifier.Operation.values();
        return new AttributeModifier(
                IDS[random.nextInt(IDS.length)], amount(random), operations[random.nextInt(operations.length)]);
    }

    private static double amount(SplittableRandom random) {
        return random.nextInt(8) == 0
                ? SPECIAL[random.nextInt(SPECIAL.length)]
                : (float) (random.nextDouble() * 4.0 - 2.0);
    }
}
