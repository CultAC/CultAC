package ac.cult.blocksim;

import ac.cult.blocksim.data.InteractionRegistries;
import ac.cult.blocksim.enchantment.EffectRandom;
import ac.cult.blocksim.enchantment.EnchantmentEffects;
import com.google.gson.JsonParser;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnchantmentEffectsTest {
    @Test void receivedEffectsReplaceDefaultsAndKeepTheirSnapshotAndSequence() {
        var entry = JsonParser.parseString("""
                {"effects":{"trident_spin_attack_strength":{"type":"all_of","effects":[
                  {"type":"set","value":{"type":"linear","base":2,"per_level_above_first":1}},
                  {"type":"multiply","factor":3},
                  {"type":"add","value":1}
                ]}}}
                """);
        var received = InteractionRegistries.defaults().withRegistry("enchantment", Map.of("test:spin", entry, "test:ordinary", JsonParser.parseString("{}")));
        entry.getAsJsonObject().remove("effects");
        var effects = new EnchantmentEffects(received);
        assertEquals(10.0F, effects.modify("test:spin", EnchantmentEffects.TRIDENT_SPIN_ATTACK_STRENGTH, 2, null, 99.0F));
        assertEquals(99.0F, effects.modify("test:ordinary", EnchantmentEffects.TRIDENT_SPIN_ATTACK_STRENGTH, 2, null, 99.0F));
        assertThrows(IllegalArgumentException.class, () -> effects.modify("minecraft:riptide", EnchantmentEffects.TRIDENT_SPIN_ATTACK_STRENGTH, 2, null, 0.0F));
    }

    @Test void stochasticEffectsConsumeOnlyTheProvidedStream() {
        var entry = JsonParser.parseString("""
                {"effects":{"minecraft:crossbow_charge_time":{"type":"minecraft:all_of","effects":[
                  {"type":"minecraft:set","value":3},
                  {"type":"minecraft:remove_binomial","chance":0.5},
                  {"type":"minecraft:multiply","factor":0.25}
                ]}}}
                """);
        var effects = new EnchantmentEffects(InteractionRegistries.defaults().withRegistry("enchantment", Map.of("test:charge", entry)));
        var random = new EffectRandom() {
            final float[] draws = {0.25F, 0.5F, 0.75F};
            int cursor;
            public float nextFloat() { return draws[cursor++]; }
            public double nextGaussian() { throw new AssertionError("Small count must use individual trials"); }
        };
        assertEquals(0.5F, effects.modify("test:charge", EnchantmentEffects.CROSSBOW_CHARGE_TIME, 1, random, 1.25F));
        assertEquals(3, random.cursor);
    }
}
