package ac.cult.cultac.bedrock.bridge;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeyserSprintAttributesTest {
    @Test public void javaSprintEchoIsRemovedBeforeCalculatingOtherModifiers() {
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

    @Test public void delayedConfirmationKeepsItsBoundaryAndOldGenerationIsDiscarded() {
        var writer = new GeyserSprintAttributes();
        var packets = new ArrayList<UpdateAttributesPacket>();
        writer.confirm(new GeyserSprintAttributes.Boundary(4, 91, true), 12, packets::add);
        assertTrue(writer.rewrite(packets.getFirst(), new GeyserSprintAttributes.Boundary(4, 93, false)));
        assertEquals(91, packets.getFirst().getTick());
        assertEquals(1, packets.getFirst().getAttributes().getFirst().getModifiers().size());
        writer.confirm(new GeyserSprintAttributes.Boundary(4, 93, false), 12, packets::add);
        assertFalse(writer.rewrite(packets.getLast(), new GeyserSprintAttributes.Boundary(5, 94, false)));
    }

    private static Attribute attribute(boolean sprint) {
        var modifiers = new ArrayList<AttributeModifier>();
        modifiers.add(new AttributeModifier(Key.key("test:add"), 0.05, ModifierOperation.ADD));
        modifiers.add(new AttributeModifier(Key.key("test:effect"), 0.2, ModifierOperation.ADD_MULTIPLIED_TOTAL));
        if (sprint) modifiers.add(new AttributeModifier(Key.key("minecraft:sprinting"), 0.3, ModifierOperation.ADD_MULTIPLIED_TOTAL));
        return new Attribute(AttributeType.Builtin.MOVEMENT_SPEED, 0.1, List.copyOf(modifiers));
    }
}
