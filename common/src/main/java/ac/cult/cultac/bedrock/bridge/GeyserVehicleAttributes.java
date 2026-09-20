package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.prediction.state.BedrockMovementAttributeState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementAttributeState.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.Attribute;

/** Translation state, keyed by native actor lifetime; prediction observes only emitted packets. */
final class GeyserVehicleAttributes {
    private static final String SPEED = "91AEAA56-376B-4498-935B-2F7F68070635";
    private static final String SLOWNESS = "7107DE5E-7CE8-4030-940E-514C1F160890";
    private final Map<Long, BedrockMovementAttributeState> movement = new HashMap<>();
    private final Map<UpdateAttributesPacket, BedrockMovementAttributeState> pending = new IdentityHashMap<>();

    UpdateAttributesPacket source(long runtimeId, Attribute attribute) {
        var modifiers = new ArrayList<Modifier>();
        for (var modifier : attribute.getModifiers()) {
            String key;
            // Geyser-Spigot relocates Adventure Key across the plugin classloader boundary.
            try { key = modifier.getClass().getMethod("getId").invoke(modifier).toString(); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot read Java modifier ID", failure); }
            String id = switch (key) {
                case "minecraft:effect.speed" -> SPEED;
                case "minecraft:effect.slowness" -> SLOWNESS;
                case "minecraft:sprinting" -> BedrockMovementAttributeState.SPRINT_ID;
                default -> UUID.nameUUIDFromBytes(("cultac:java-attribute:" + key).getBytes(StandardCharsets.UTF_8)).toString();
            };
            String name = id.equals(SPEED) ? "MovementSpeed" : id.equals(SLOWNESS) ? "MovementSlowdown"
                    : id.equals(BedrockMovementAttributeState.SPRINT_ID) ? "Sprinting speed boost" : key;
            int operation = switch (modifier.getOperation()) {
                case ADD -> 0;
                case ADD_MULTIPLIED_BASE -> 1;
                case ADD_MULTIPLIED_TOTAL -> 2;
            };
            modifiers.add(new Modifier(id, name, (float) modifier.getAmount(), operation, 2, false));
        }
        float base = (float) attribute.getValue();
        return publish(runtimeId, new BedrockMovementAttributeState(base, 0, 1024, 0, 1024, base, List.of())
                .recalculate(modifiers));
    }

    /** Write before the effect so duplicate adds and refreshes retain the translated value. */
    UpdateAttributesPacket beforeEffect(MobEffectPacket effect) {
        var previous = movement.get(effect.getRuntimeEntityId());
        String id = switch (effect.getEffectId()) { case 1 -> SPEED; case 2 -> SLOWNESS; default -> null; };
        if (previous == null || id == null || effect.getEvent() == MobEffectPacket.Event.NONE) return null;
        var modifiers = new ArrayList<>(previous.modifiers().stream()
                .filter(modifier -> !modifier.id().equalsIgnoreCase(id)).toList());
        if (effect.getEvent() != MobEffectPacket.Event.REMOVE) {
            float amount = (effect.getEffectId() == 1 ? 0.2F : -0.15F) * (effect.getAmplifier() + 1);
            modifiers.add(new Modifier(id, effect.getEffectId() == 1 ? "MovementSpeed" : "MovementSlowdown",
                    amount, 2, 2, false));
        }
        return publish(effect.getRuntimeEntityId(), previous.recalculate(modifiers));
    }

    private UpdateAttributesPacket publish(long runtimeId, BedrockMovementAttributeState value) {
        var packet = new UpdateAttributesPacket();
        packet.setRuntimeEntityId(runtimeId);
        packet.setAttributes(List.of(GeyserMovementAttributeCodec.encode(value)));
        pending.put(packet, value);
        return packet;
    }

    void written(UpdateAttributesPacket packet) {
        var value = pending.remove(packet);
        if (value != null) movement.put(packet.getRuntimeEntityId(), value);
        else if (movement.containsKey(packet.getRuntimeEntityId())) {
            packet.getAttributes().stream().filter(attribute -> attribute.getName().equals("minecraft:movement"))
                    .findFirst().ifPresent(attribute -> movement.put(packet.getRuntimeEntityId(), GeyserMovementAttributeCodec.capture(attribute)));
        }
    }

    void remove(long runtimeId) { movement.remove(runtimeId); }
    void clear() { movement.clear(); pending.clear(); }
}
