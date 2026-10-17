package ac.cult.cultac.protocol.packet.clientbound;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** A server plugin channel and its bytes; the record takes ownership of the array and exposes it uncopied. */
public record ClientboundCustomPayload(String channel, byte[] data) implements ClientboundPacket {
    public ClientboundCustomPayload {
        Objects.requireNonNull(channel);
        Objects.requireNonNull(data);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ClientboundCustomPayload payload
                && channel.equals(payload.channel)
                && Arrays.equals(data, payload.data);
    }

    @Override
    public int hashCode() {
        return 31 * channel.hashCode() + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "ClientboundCustomPayload[channel=" + channel + ", data="
                + HexFormat.of().formatHex(data) + "]";
    }
}
