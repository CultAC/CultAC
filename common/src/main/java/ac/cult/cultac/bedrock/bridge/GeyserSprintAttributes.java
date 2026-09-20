package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.prediction.state.BedrockMovementAttributeState;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.Attribute;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.AttributeModifier;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.ModifierOperation;

/** Outbound policy only. Actor state and tick remain owned by the existing prediction commit. */
final class GeyserSprintAttributes {
    private float nonSprintValue = Float.NaN;
    // Transport provenance exists only until the asynchronous Netty write; never copied into history.
    private final Map<UpdateAttributesPacket, Long> pending = new IdentityHashMap<>();

    void source(Attribute attribute, Boundary boundary, long actorId, Consumer<UpdateAttributesPacket> send) {
        float value = nonSprintValue(attribute);
        if (value == nonSprintValue) return;
        nonSprintValue = value;
        confirm(boundary, actorId, send);
    }

    void confirm(Boundary boundary, long actorId, Consumer<UpdateAttributesPacket> send) {
        var packet = new UpdateAttributesPacket();
        packet.setRuntimeEntityId(actorId);
        packet.setTick(boundary.tick());
        packet.setAttributes(List.of(GeyserMovementAttributeCodec.encode(attribute(boundary.sprinting()))));
        pending.put(packet, boundary.generation());
        try {
            send.accept(packet);
        } catch (RuntimeException | Error failure) {
            pending.remove(packet);
            throw failure;
        }
    }

    void close() { pending.clear(); }

    /** Returns false for a superseded actor's queued emission. */
    boolean rewrite(UpdateAttributesPacket packet, Boundary boundary) {
        Long generation = pending.remove(packet);
        if (generation != null) return generation == boundary.generation();
        var movement = packet.getAttributes().stream().filter(a -> a.getName().equals("minecraft:movement"))
                .findFirst().orElse(null);
        if (movement == null) return true;
        // Direct Bedrock updates already describe Bedrock attribute state; Java echoes go through source().
        nonSprintValue = GeyserMovementAttributeCodec.capture(movement).removeSprint().current();
        packet.setAttributes(packet.getAttributes().stream().map(a -> a == movement
                ? GeyserMovementAttributeCodec.encode(attribute(boundary.sprinting())) : a).toList());
        packet.setTick(boundary.tick());
        return true;
    }

    private BedrockMovementAttributeState attribute(boolean sprinting) {
        return BedrockMovementAttributeState.serverValue(Float.isNaN(nonSprintValue) ? 0.1F : nonSprintValue, sprinting);
    }

    static float nonSprintValue(Attribute attribute) {
        // Source packets are full snapshots. Retaining another copy of the Java modifiers is unnecessary.
        var modifiers = attribute.getModifiers().stream().filter(modifier -> !sprintModifier(modifier)).toList();
        double added = attribute.getValue();
        for (var modifier : modifiers) if (modifier.getOperation() == ModifierOperation.ADD) added += modifier.getAmount();
        double value = added;
        for (var modifier : modifiers) if (modifier.getOperation() == ModifierOperation.ADD_MULTIPLIED_BASE)
            value += added * modifier.getAmount();
        for (var modifier : modifiers) if (modifier.getOperation() == ModifierOperation.ADD_MULTIPLIED_TOTAL)
            value *= 1.0D + modifier.getAmount();
        return (float) Math.max(0, Math.min(1024, value));
    }

    private static boolean sprintModifier(AttributeModifier modifier) {
        // Geyser-Spigot relocates Adventure Key. Avoid linking its return type across classloaders.
        try { return modifier.getClass().getMethod("getId").invoke(modifier).toString().equals("minecraft:sprinting"); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot read Java modifier ID", failure); }
    }

    /** A call-local view of the existing commit, never retained as another actor timeline. */
    record Boundary(long generation, long tick, boolean sprinting) { }
}
