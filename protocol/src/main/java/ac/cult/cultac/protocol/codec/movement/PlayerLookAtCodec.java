package ac.cult.cultac.protocol.codec.movement;

import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.WritablePacketCodec;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerLookAt;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;

public final class PlayerLookAtCodec implements WritablePacketCodec<ClientboundPlayerLookAt> {
    // EntityAnchorArgument.Anchor: FEET, then EYES.
    private static final int FEET = 0;
    private static final int EYES = 1;

    @Override
    public ClientboundPlayerLookAt read(ByteBuf input, ProtocolContext context) {
        boolean fromEyes = readAnchor(input);
        double x = input.readDouble();
        double y = input.readDouble();
        double z = input.readDouble();
        boolean atEntity = input.readBoolean();
        if (!atEntity) return new ClientboundPlayerLookAt(fromEyes, x, y, z, false, 0, false);
        int entityId = Wire.readVarInt(input);
        return new ClientboundPlayerLookAt(fromEyes, x, y, z, true, entityId, readAnchor(input));
    }

    @Override
    public void write(ByteBuf output, ProtocolContext context, ClientboundPlayerLookAt packet) {
        Wire.writeVarInt(output, packet.fromEyes() ? EYES : FEET);
        output.writeDouble(packet.x());
        output.writeDouble(packet.y());
        output.writeDouble(packet.z());
        output.writeBoolean(packet.atEntity());
        if (packet.atEntity()) {
            Wire.writeVarInt(output, packet.entityId());
            Wire.writeVarInt(output, packet.toEyes() ? EYES : FEET);
        }
    }

    private static boolean readAnchor(ByteBuf input) {
        int id = Wire.readVarInt(input);
        if (id != FEET && id != EYES) throw new MalformedPacketException("Invalid look anchor ID " + id);
        return id == EYES;
    }
}
