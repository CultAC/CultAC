package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.reload.integrations.GeyserTranslatorRouter;
import java.util.ArrayList;
import java.util.function.BiConsumer;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.entity.vehicle.ClientVehicle;
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
    private final VehicleMovement vehicleMovement;

    interface VehicleMovement {
        void accept(GeyserSession session, Entity entity, Attribute attribute);
    }

    static boolean available() {
        return Registries.JAVA_PACKET_TRANSLATORS.get(ClientboundUpdateAttributesPacket.class) != null;
    }

    @SuppressWarnings("unchecked")
    GeyserSprintAttributeTranslator(BiConsumer<GeyserSession, Attribute> movement, VehicleMovement vehicleMovement) {
        this.movement = movement;
        this.vehicleMovement = vehicleMovement;
        var host = RuntimeEnvironment.host();
        if (host != null) {
            delegate = GeyserTranslatorRouter.install(ClientboundUpdateAttributesPacket.class, host, this);
            return;
        }
        delegate = (PacketTranslator<ClientboundUpdateAttributesPacket>)
                Registries.JAVA_PACKET_TRANSLATORS.get(ClientboundUpdateAttributesPacket.class);
        if (delegate == null) throw new IllegalStateException("Missing Geyser attribute translator");
        Registries.JAVA_PACKET_TRANSLATORS.register(ClientboundUpdateAttributesPacket.class, this);
    }

    @Override
    public void translate(GeyserSession session, ClientboundUpdateAttributesPacket packet) {
        boolean self = packet.getEntityId() == session.getPlayerEntity().getEntityId();
        var entity =
                self ? session.getPlayerEntity() : session.getEntityCache().getEntityByJavaId(packet.getEntityId());
        if (!self && (!(entity instanceof ClientVehicle) || !entity.isValid())) {
            delegate.translate(session, packet);
            return;
        }
        var remaining = new ArrayList<Attribute>();
        for (Attribute attribute : packet.getAttributes()) {
            if (attribute.getType() == AttributeType.Builtin.MOVEMENT_SPEED) {
                if (self) movement.accept(session, attribute);
                else vehicleMovement.accept(session, entity, attribute);
            } else remaining.add(attribute);
        }
        if (!remaining.isEmpty())
            delegate.translate(session, new ClientboundUpdateAttributesPacket(packet.getEntityId(), remaining));
    }

    void close() {
        var host = RuntimeEnvironment.host();
        if (host != null) {
            GeyserTranslatorRouter.remove(ClientboundUpdateAttributesPacket.class, host, this);
            return;
        }
        if (isInstalled())
            Registries.JAVA_PACKET_TRANSLATORS.register(ClientboundUpdateAttributesPacket.class, delegate);
    }

    boolean isInstalled() {
        var host = RuntimeEnvironment.host();
        if (host != null) return GeyserTranslatorRouter.installed(ClientboundUpdateAttributesPacket.class, host, this);
        return Registries.JAVA_PACKET_TRANSLATORS.get(ClientboundUpdateAttributesPacket.class) == this;
    }
}
