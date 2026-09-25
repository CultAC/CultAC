package ac.cult.cultac.bedrock.bridge;

import java.util.ArrayList;
import java.util.function.Predicate;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ByteEntityMetadata;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;

/** Java pose echoes must not replace the local Bedrock player's input-derived movement pose. */
final class GeyserClientPoseTranslator extends PacketTranslator<ClientboundSetEntityDataPacket> {
    private final PacketTranslator<ClientboundSetEntityDataPacket> delegate;
    private final Predicate<GeyserSession> attached;

    @SuppressWarnings("unchecked")
    GeyserClientPoseTranslator(Predicate<GeyserSession> attached) {
        this.attached = attached;
        delegate = (PacketTranslator<ClientboundSetEntityDataPacket>) Registries.JAVA_PACKET_TRANSLATORS
                .get(ClientboundSetEntityDataPacket.class);
        if (delegate == null) throw new IllegalStateException("Missing Geyser metadata translator");
        Registries.JAVA_PACKET_TRANSLATORS.register(ClientboundSetEntityDataPacket.class, this);
    }

    @Override public void translate(GeyserSession session, ClientboundSetEntityDataPacket packet) {
        var entity = session.getPlayerEntity();
        if (!attached.test(session) || packet.getEntityId() != entity.getEntityId()) {
            delegate.translate(session, packet);
            return;
        }
        var filtered = filter(packet, entity.getFlag(EntityFlag.SNEAKING), entity.getFlag(EntityFlag.GLIDING),
                entity.getFlag(EntityFlag.DAMAGE_NEARBY_MOBS));
        if (filtered.getMetadata().length != 0) delegate.translate(session, filtered);
    }

    static ClientboundSetEntityDataPacket filter(ClientboundSetEntityDataPacket packet, boolean sneaking,
                                                 boolean gliding, boolean spinning) {
        var metadata = new ArrayList<EntityMetadata<?, ?>>();
        for (var entry : packet.getMetadata()) {
            // EntityDefinitions.entityBase: shared flags are field 0; the Java pose is field 6.
            if (entry.getId() == 6 && entry.getType() == MetadataTypes.POSE) continue;
            if (entry.getId() == 0 && entry instanceof ByteEntityMetadata flags) {
                int value = flags.getPrimitiveValue() & ~0x82;
                if (sneaking) value |= 0x02;
                if (gliding) value |= 0x80;
                entry = new ByteEntityMetadata(0, MetadataTypes.BYTE, (byte) value);
            } else if (entry.getId() == 8 && entry instanceof ByteEntityMetadata flags) {
                // LivingEntity::setLivingEntityFlags also carries the Java riptide pose bit.
                int value = flags.getPrimitiveValue() & ~0x04;
                if (spinning) value |= 0x04;
                entry = new ByteEntityMetadata(8, MetadataTypes.BYTE, (byte) value);
            }
            metadata.add(entry);
        }
        return new ClientboundSetEntityDataPacket(packet.getEntityId(), metadata.toArray(EntityMetadata[]::new));
    }

    boolean isInstalled() { return Registries.JAVA_PACKET_TRANSLATORS.get(ClientboundSetEntityDataPacket.class) == this; }
    void close() {
        if (isInstalled()) Registries.JAVA_PACKET_TRANSLATORS.register(ClientboundSetEntityDataPacket.class, delegate);
    }
}
