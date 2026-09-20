package ac.cult.cultac.bedrock.bridge;

import java.util.List;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeyserVehicleAttributesTest {
    @Test public void horseBaseSurvivesSlownessAndItsRefresh() {
        var writer = new GeyserVehicleAttributes();
        written(writer, writer.source(4, attribute(0.2961814)));
        var slow = written(writer, writer.beforeEffect(effect(4, 2, 0, MobEffectPacket.Event.ADD)));
        assertEquals(0.2517542F, slow.getAttributes().getFirst().getValue(), 1e-7F);
        assertEquals(0.2961814F, slow.getAttributes().getFirst().getDefaultValue(), 0);
        var modifier = slow.getAttributes().getFirst().getModifiers().getFirst();
        assertEquals("7107DE5E-7CE8-4030-940E-514C1F160890", modifier.getId());
        assertEquals("MovementSlowdown", modifier.getName());
        assertEquals(2, modifier.getOperation().ordinal());
        assertEquals(2, modifier.getOperand());
        // A later Java echo followed by an existing-effect refresh must never reset to 0.1.
        written(writer, writer.source(4, attribute(0.2961814, modifier("minecraft:effect.slowness", -0.15F))));
        var refreshed = written(writer, writer.beforeEffect(effect(4, 2, 0, MobEffectPacket.Event.ADD)));
        assertEquals(slow.getAttributes(), refreshed.getAttributes());
        var nativeState = GeyserMovementAttributeCodec.capture(refreshed.getAttributes().getFirst());
        assertEquals(nativeState.current(), nativeState.recalculate(nativeState.modifiers()).current(), 0);
    }

    @Test public void combinedEffectsAndRemovalDoNotDoubleApplyOrLoseTheBase() {
        var writer = new GeyserVehicleAttributes();
        written(writer, writer.source(4, attribute(0.2961814)));
        written(writer, writer.beforeEffect(effect(4, 1, 1, MobEffectPacket.Event.ADD)));
        var combined = written(writer, writer.beforeEffect(effect(4, 2, 0, MobEffectPacket.Event.ADD)));
        assertEquals(0.35245588F, combined.getAttributes().getFirst().getValue(), 1e-7F);
        assertEquals(2, combined.getAttributes().getFirst().getModifiers().size());
        var noSlow = written(writer, writer.beforeEffect(effect(4, 2, 0, MobEffectPacket.Event.REMOVE)));
        assertEquals(0.41465396F, noSlow.getAttributes().getFirst().getValue(), 1e-7F);
        var cleared = written(writer, writer.beforeEffect(effect(4, 1, 0, MobEffectPacket.Event.REMOVE)));
        assertEquals(0.2961814F, cleared.getAttributes().getFirst().getValue(), 0);
        assertTrue(cleared.getAttributes().getFirst().getModifiers().isEmpty());
    }

    @Test public void pluginModifiersRemainInstalledAcrossEffectUpdates() {
        var writer = new GeyserVehicleAttributes();
        written(writer, writer.source(4, attribute(0.2,
                new AttributeModifier(Key.key("test:add"), 0.1, ModifierOperation.ADD),
                new AttributeModifier(Key.key("test:base"), 0.5, ModifierOperation.ADD_MULTIPLIED_BASE),
                modifier("test:total", 0.2))));
        var packet = written(writer, writer.beforeEffect(effect(4, 2, 0, MobEffectPacket.Event.MODIFY)));
        assertEquals(0.459F, packet.getAttributes().getFirst().getValue(), 1e-7F);
        assertEquals(4, packet.getAttributes().getFirst().getModifiers().size());
    }

    @Test public void futureSourcePacketsCannotChangeAnEarlierEffectBoundary() {
        var writer = new GeyserVehicleAttributes();
        written(writer, writer.source(4, attribute(0.3)));
        var later = writer.source(4, attribute(0.6));
        var earlier = written(writer, writer.beforeEffect(effect(4, 2, 0, MobEffectPacket.Event.ADD)));
        assertEquals(0.255F, earlier.getAttributes().getFirst().getValue(), 1e-7F);
        written(writer, later);
        var after = written(writer, writer.beforeEffect(effect(4, 2, 0, MobEffectPacket.Event.ADD)));
        assertEquals(0.51F, after.getAttributes().getFirst().getValue(), 1e-7F);
    }

    @Test public void actorsAreIndependentAndDespawnDiscardsTranslationState() {
        var writer = new GeyserVehicleAttributes();
        written(writer, writer.source(4, attribute(0.3)));
        written(writer, writer.source(5, attribute(0.6)));
        writer.remove(4);
        assertNull(writer.beforeEffect(effect(4, 2, 0, MobEffectPacket.Event.ADD)));
        assertEquals(0.51F, writer.beforeEffect(effect(5, 2, 0, MobEffectPacket.Event.ADD))
                .getAttributes().getFirst().getValue(), 1e-7F);
        writer.clear();
        assertNull(writer.beforeEffect(effect(5, 2, 0, MobEffectPacket.Event.ADD)));
    }

    private static UpdateAttributesPacket written(GeyserVehicleAttributes writer, UpdateAttributesPacket packet) {
        assertNotNull(packet);
        writer.written(packet);
        return packet;
    }
    private static Attribute attribute(double base, AttributeModifier... modifiers) {
        return new Attribute(AttributeType.Builtin.MOVEMENT_SPEED, base, List.of(modifiers));
    }
    private static AttributeModifier modifier(String id, double amount) {
        return new AttributeModifier(Key.key(id), amount, ModifierOperation.ADD_MULTIPLIED_TOTAL);
    }
    private static MobEffectPacket effect(long actor, int id, int amplifier, MobEffectPacket.Event event) {
        var packet = new MobEffectPacket();
        packet.setRuntimeEntityId(actor);
        packet.setEffectId(id);
        packet.setAmplifier(amplifier);
        packet.setEvent(event);
        return packet;
    }
}
