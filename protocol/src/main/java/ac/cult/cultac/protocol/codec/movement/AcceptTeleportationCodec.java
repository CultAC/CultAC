package ac.cult.cultac.protocol.codec.movement;

import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WritablePacketCodec;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import java.util.Objects;

public final class AcceptTeleportationCodec implements WritablePacketCodec<ServerboundAcceptTeleportation> {
    @Override
    public ServerboundAcceptTeleportation read(ByteBuf input, ProtocolContext context) {
        int id = Wire.readVarInt(input);
        if (!context.version().atLeast(ProtocolVersion.V26_3)) {
            return new ServerboundAcceptTeleportation(id, null, 0, 0);
        }
        var position = Wire.readVec3(input);
        return new ServerboundAcceptTeleportation(id, position, input.readFloat(), input.readFloat());
    }

    @Override
    public void write(ByteBuf output, ProtocolContext context, ServerboundAcceptTeleportation packet) {
        Wire.writeVarInt(output, packet.id());
        if (!context.version().atLeast(ProtocolVersion.V26_3)) return;
        Wire.writeVec3(output, Objects.requireNonNull(packet.position(), "A 26.3 acceptance carries a position"));
        output.writeFloat(packet.yaw());
        output.writeFloat(packet.pitch());
    }
}
