package ac.cult.cultac.bedrock.bridge;

import java.util.ArrayList;
import java.util.function.BiConsumer;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.Attribute;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.AttributeType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundUpdateAttributesPacket;

/** Keeps the Java modifier identities available before Geyser's numeric translation. */
final class GeyserSprintAttributeTranslator extends PacketTranslator<ClientboundUpdateAttributesPacket> {
    private final PacketTranslator<ClientboundUpdateAttributesPacket> delegate;
    private final BiConsumer<GeyserSession, Attribute> movement;

    static boolean available() {
        return Registries.JAVA_PACKET_TRANSLATORS.get(ClientboundUpdateAttributesPacket.class) != null;
    }

    @SuppressWarnings("unchecked")
    GeyserSprintAttributeTranslator(BiConsumer<GeyserSession, Attribute> movement) {
        this.movement = movement;
        delegate = (PacketTranslator<ClientboundUpdateAttributesPacket>) Registries.JAVA_PACKET_TRANSLATORS
                .get(ClientboundUpdateAttributesPacket.class);
        if (delegate == null) throw new IllegalStateException("Missing Geyser attribute translator");
        Registries.JAVA_PACKET_TRANSLATORS.register(ClientboundUpdateAttributesPacket.class, this);
    }

    @Override public void translate(GeyserSession session, ClientboundUpdateAttributesPacket packet) {
        if (packet.getEntityId() != session.getPlayerEntity().getEntityId()) {
            delegate.translate(session, packet);
            return;
        }
        var remaining = new ArrayList<Attribute>();
        for (Attribute attribute : packet.getAttributes()) {
            if (attribute.getType() == AttributeType.Builtin.MOVEMENT_SPEED) movement.accept(session, attribute);
            else remaining.add(attribute);
        }
        if (!remaining.isEmpty()) delegate.translate(session, new ClientboundUpdateAttributesPacket(packet.getEntityId(), remaining));
    }

    void close() {
        if (isInstalled())
            Registries.JAVA_PACKET_TRANSLATORS.register(ClientboundUpdateAttributesPacket.class, delegate);
    }

    boolean isInstalled() {
        return Registries.JAVA_PACKET_TRANSLATORS.get(ClientboundUpdateAttributesPacket.class) == this;
    }
}
