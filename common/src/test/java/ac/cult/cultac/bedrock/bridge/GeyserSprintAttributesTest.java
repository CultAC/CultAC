package ac.cult.cultac.bedrock.bridge;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.*;
import org.junit.Test;

public class GeyserSprintAttributesTest {
    @Test
    public void javaSprintEchoIsRemovedBeforeCalculatingOtherModifiers() {
        var writer = new GeyserSprintAttributes();
        var packets = new ArrayList<UpdateAttributesPacket>();
        var boundary = new GeyserSprintAttributes.Boundary(7, 100, false);
        writer.source(attribute(true), boundary, 12, packets::add);
        assertEquals(1, packets.size());
        var data = packets.getFirst().getAttributes().getFirst();
        assertEquals(0.18F, data.getValue(), 1e-7F);
        assertEquals(data.getValue(), data.getDefaultValue(), 0);
        assertTrue(data.getModifiers().isEmpty());
        writer.source(attribute(false), boundary, 12, packets::add);
        assertEquals("Removing only Java sprint must not emit another update", 1, packets.size());
        writer.confirm(new GeyserSprintAttributes.Boundary(7, 101, true), 12, packets::add);
        data = packets.getLast().getAttributes().getFirst();
        assertEquals(101, packets.getLast().getTick());
        assertEquals(1, data.getModifiers().size());
        assertEquals(0.234F, data.getValue(), 1e-7F);
        assertEquals(0.18F, data.getDefaultValue(), 1e-7F);
    }

    @Test
    public void delayedConfirmationKeepsItsBoundaryAndOldGenerationIsDiscarded() {
        var writer = new GeyserSprintAttributes();
        var packets = new ArrayList<UpdateAttributesPacket>();
        writer.confirm(new GeyserSprintAttributes.Boundary(4, 91, true), 12, packets::add);
        assertTrue(writer.rewrite(packets.getFirst(), new GeyserSprintAttributes.Boundary(4, 93, false)));
        assertEquals(91, packets.getFirst().getTick());
        assertEquals(
                1, packets.getFirst().getAttributes().getFirst().getModifiers().size());
        writer.confirm(new GeyserSprintAttributes.Boundary(4, 93, false), 12, packets::add);
        assertFalse(writer.rewrite(packets.getLast(), new GeyserSprintAttributes.Boundary(5, 94, false)));
    }

    @Test
    public void cacheResendWrittenAfterTheNextJavaValueDoesNotSwallowTheOneAfterIt() {
        var writer = new GeyserSprintAttributes();
        var packets = new ArrayList<UpdateAttributesPacket>();
        var boundary = new GeyserSprintAttributes.Boundary(7, 100, false);
        // Slowness II, then Speed V, then Slowness II again: 0.07, 0.2, 0.07.
        writer.source(effect(-0.3), boundary, 12, packets::add);
        assertTrue(writer.rewrite(packets.getFirst(), boundary));
        assertEquals(0.07F, packets.getFirst().getAttributes().getFirst().getValue(), 0);

        // The Speed effect makes Geyser resend its cached 0.07 before the Java 0.2 arrives; both are
        // queued, and the resend reaches the write only after source() stored 0.2.
        var resend = new UpdateAttributesPacket();
        resend.setRuntimeEntityId(12);
        resend.setAttributes(List.of(packets.getFirst().getAttributes().getFirst()));
        writer.source(effect(1.0), boundary, 12, packets::add);
        assertEquals(2, packets.size());
        var written = new GeyserSprintAttributes.Boundary(7, 101, true);
        assertTrue(writer.rewrite(resend, written));
        var data = resend.getAttributes().getFirst();
        assertEquals("A resend carries the current value, not its stale cached one", 0.2F, data.getDefaultValue(), 0);
        assertEquals(0.26F, data.getValue(), 1e-7F);
        assertEquals(101, resend.getTick());
        assertTrue(writer.rewrite(packets.getLast(), written));
        assertEquals(0.2F, packets.getLast().getAttributes().getFirst().getValue(), 0);

        writer.source(effect(-0.3), boundary, 12, packets::add);
        assertEquals("The value the client last received is 0.2, so 0.07 must be sent", 3, packets.size());
        assertEquals(0.07F, packets.getLast().getAttributes().getFirst().getValue(), 0);
        writer.source(effect(-0.3), boundary, 12, packets::add);
        assertEquals(3, packets.size());
    }

    @Test
    public void respawnResetKeepsTheJavaValueAndOnlyTheFirstPacketSeedsIt() {
        var writer = new GeyserSprintAttributes();
        var packets = new ArrayList<UpdateAttributesPacket>();
        var boundary = new GeyserSprintAttributes.Boundary(7, 100, false);
        // The session's first movement attribute is Geyser's default; it is the only value known.
        assertTrue(writer.rewrite(bedrockDefault(), boundary));
        writer.source(effect(0), boundary, 12, packets::add);
        assertTrue("0.1 already reached the client", packets.isEmpty());

        writer.source(effect(1.0), boundary, 12, packets::add);
        assertTrue(writer.rewrite(packets.getFirst(), boundary));
        // A respawn resets Geyser's cache to the default and sends it; the Java server still holds 0.2.
        var reset = bedrockDefault();
        assertTrue(writer.rewrite(reset, boundary));
        assertEquals(0.2F, reset.getAttributes().getFirst().getValue(), 0);
        writer.source(effect(1.0), boundary, 12, packets::add);
        assertEquals(1, packets.size());
        writer.source(effect(0), boundary, 12, packets::add);
        assertEquals(2, packets.size());
        assertEquals(0.1F, packets.getLast().getAttributes().getFirst().getValue(), 0);
    }

    private static UpdateAttributesPacket bedrockDefault() {
        var packet = new UpdateAttributesPacket();
        packet.setRuntimeEntityId(12);
        packet.setAttributes(List.of(new org.cloudburstmc.protocol.bedrock.data.AttributeData(
                "minecraft:movement", 0, 1024, 0.1F, 0, 1024, 0.1F, List.of())));
        return packet;
    }

    private static Attribute effect(double multiplier) {
        return new Attribute(
                AttributeType.Builtin.MOVEMENT_SPEED,
                0.1,
                multiplier == 0
                        ? List.of()
                        : List.of(new AttributeModifier(
                                Key.key("minecraft:effect.speed"), multiplier, ModifierOperation.ADD_MULTIPLIED_TOTAL)));
    }

    private static Attribute attribute(boolean sprint) {
        var modifiers = new ArrayList<AttributeModifier>();
        modifiers.add(new AttributeModifier(Key.key("test:add"), 0.05, ModifierOperation.ADD));
        modifiers.add(new AttributeModifier(Key.key("test:effect"), 0.2, ModifierOperation.ADD_MULTIPLIED_TOTAL));
        if (sprint)
            modifiers.add(
                    new AttributeModifier(Key.key("minecraft:sprinting"), 0.3, ModifierOperation.ADD_MULTIPLIED_TOTAL));
        return new Attribute(AttributeType.Builtin.MOVEMENT_SPEED, 0.1, List.copyOf(modifiers));
    }
}
