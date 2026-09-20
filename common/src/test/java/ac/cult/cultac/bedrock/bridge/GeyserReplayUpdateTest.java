package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.prediction.integration.BedrockReplayContextEvent;
import ac.cult.cultac.bedrock.prediction.integration.BedrockReplayAttributeEvent;
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.cloudburstmc.protocol.bedrock.data.AttributeData;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

public final class GeyserReplayUpdateTest {
    @Test public void resistanceDoesNotCreateAMovementReplay() {
        var packet = new MobEffectPacket();
        packet.setRuntimeEntityId(9);
        packet.setEffectId(11);
        packet.setAmplifier(3);
        packet.setDuration(100);
        packet.setEvent(MobEffectPacket.Event.ADD);
        assertNull(GeyserReplayUpdate.capture(packet));
        packet.setEvent(MobEffectPacket.Event.REMOVE);
        assertNull(GeyserReplayUpdate.capture(packet));
    }

    @Test
    public void attributeCaptureKeepsTimestampAndOwnsOnlySuppliedValues() {
        var packet = new UpdateAttributesPacket();
        packet.setRuntimeEntityId(12);
        packet.setTick(53);
        packet.setAttributes(new ArrayList<>());
        packet.getAttributes().add(new AttributeData("minecraft:movement", 0, 10, 0.2F, 0.1F));
        var captured = GeyserReplayUpdate.capture(packet);
        packet.getAttributes().clear();
        packet.setTick(0);
        assertEquals(53, captured.tick());
        assertEquals(12, captured.actorId());
        var update = (BedrockReplayAttributeEvent) captured.event();
        assertEquals(1, update.attributes().size());
        var movement = update.attributes().get("minecraft:movement");
        assertEquals(0.2F, movement.current(), 0);
        assertEquals(0.1F, movement.defaultValue(), 0);
        assertTrue(update.historical());
        assertNull(GeyserReplayUpdate.capture(packet));
    }

    @Test
    public void spawnAndNonMovementAttributesKeepFullInstances() {
        var packet = new org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket();
        packet.setRuntimeEntityId(4);
        packet.getAttributes().add(new AttributeData("minecraft:horse.jump_strength", 0, 2, 0.81F, 0.7F));
        packet.getAttributes().add(new AttributeData("minecraft:movement", 0, 1024, 0.35245588F, 0.1F));
        var captured = GeyserReplayUpdate.capture(packet);
        packet.getAttributes().clear();
        var update = (BedrockReplayAttributeEvent) captured.event();
        assertEquals(4, captured.actorId());
        assertFalse(update.historical());
        assertEquals(0.81F, update.attributes().get("minecraft:horse.jump_strength").current(), 0);
        assertEquals(0.7F, update.attributes().get("minecraft:horse.jump_strength").defaultValue(), 0);
        assertEquals(0.35245588F, update.attributes().get("minecraft:movement").current(), 0);
        assertEquals(0.1F, update.attributes().get("minecraft:movement").defaultValue(), 0);
    }

    @Test
    public void effectCapturePreservesZeroTickAndRemovalSemantics() {
        var packet = new MobEffectPacket();
        packet.setRuntimeEntityId(12);
        packet.setEvent(MobEffectPacket.Event.NONE);
        assertNull(GeyserReplayUpdate.capture(packet));
        packet.setEvent(MobEffectPacket.Event.ADD);
        packet.setEffectId(8);
        packet.setAmplifier(2);
        packet.setDuration(100);
        var captured = GeyserReplayUpdate.capture(packet);
        assertEquals(0, captured.tick());
        var update = (BedrockReplayContextEvent) captured.event();
        assertEquals(Integer.valueOf(3), update.effectLevel());
        assertEquals(100, update.duration());
        packet.setEvent(MobEffectPacket.Event.REMOVE);
        packet.setTick(72);
        captured = GeyserReplayUpdate.capture(packet);
        assertEquals(72, captured.tick());
        assertEquals(Integer.valueOf(0), ((BedrockReplayContextEvent) captured.event()).effectLevel());
    }
}
