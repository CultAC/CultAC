package ac.cult.cultac.protocol.codec.connection;

import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.WritablePacketCodec;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundCustomPayload;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Server plugin messages travel as DiscardedPayload: the identifier, then the raw bytes to the frame end.
 *
 * <p>Only channels the codec is constructed with keep their bytes. When there are any, an untranslated frame on any
 * other channel is forwarded without reaching handlers. Where a frame is decoded anyway, another channel decodes with
 * {@link #SKIPPED} as its data and cannot be written back; the original frame is forwarded unless a handler replaces
 * the packet.
 */
public final class ClientboundCustomPayloadCodec implements WritablePacketCodec<ClientboundCustomPayload> {
    /** Vanilla's ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE. */
    private static final int MAX_PAYLOAD_BYTES = 1048576;

    /** The data of a payload whose channel is not retained; compared by identity. */
    public static final byte[] SKIPPED = new byte[0];

    private final Set<String> retained;
    private final byte[][] retainedIdentifiers;

    public ClientboundCustomPayloadCodec(Set<String> retained) {
        this.retained = Set.copyOf(retained);
        this.retainedIdentifiers = this.retained.stream()
                .map(channel -> channel.getBytes(StandardCharsets.UTF_8))
                .toArray(byte[][]::new);
    }

    @Override
    public boolean forwardsUndecoded(ByteBuf frame, int payloadIndex) {
        if (retainedIdentifiers.length == 0) return false;
        int length = 0;
        int at = payloadIndex;
        for (int shift = 0; ; shift += 7) {
            if (shift > 28 || at >= frame.writerIndex()) return false;
            byte part = frame.getByte(at++);
            length |= (part & 0x7F) << shift;
            if (part >= 0) break;
        }
        if (at + length > frame.writerIndex()) return false;
        for (byte[] identifier : retainedIdentifiers) {
            if (identifier.length == length && matches(frame, at, identifier)) return false;
        }
        return true;
    }

    private static boolean matches(ByteBuf frame, int index, byte[] identifier) {
        for (int i = 0; i < identifier.length; i++) {
            if (frame.getByte(index + i) != identifier[i]) return false;
        }
        return true;
    }

    @Override
    public ClientboundCustomPayload read(ByteBuf input, ProtocolContext context) {
        String channel = Wire.readIdentifier(input);
        int length = input.readableBytes();
        if (length > MAX_PAYLOAD_BYTES)
            throw new MalformedPacketException("Custom payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        if (!retained.contains(channel)) {
            input.skipBytes(length);
            return new ClientboundCustomPayload(channel, SKIPPED);
        }
        byte[] data = new byte[length];
        input.readBytes(data);
        return new ClientboundCustomPayload(channel, data);
    }

    @Override
    public void write(ByteBuf output, ProtocolContext context, ClientboundCustomPayload packet) {
        if (packet.data() == SKIPPED) {
            throw new IllegalStateException("Custom payload on " + packet.channel() + " was decoded without its bytes");
        }
        Wire.writeString(output, packet.channel(), 32767);
        output.writeBytes(packet.data());
    }
}
