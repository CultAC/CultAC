package ac.cult.cultac.protocol;

import ac.cult.cultac.protocol.data.ProtocolData;
import java.util.Objects;

/** Immutable wire identity and the connection values supplied for this codec invocation. */
public record ProtocolContext(
        PacketType<?> type,
        ProtocolData data,
        ConnectionPhase phase,
        String wireName,
        int commandInputLimit,
        int variant,
        CodecState state) {
    public ProtocolContext(
            PacketType<?> type,
            ProtocolData data,
            ConnectionPhase phase,
            String wireName,
            int commandInputLimit,
            int variant) {
        this(type, data, phase, wireName, commandInputLimit, variant, CodecState.EMPTY);
    }

    public ProtocolContext {
        Objects.requireNonNull(type);
        Objects.requireNonNull(data);
        Objects.requireNonNull(phase);
        Objects.requireNonNull(wireName);
        Objects.requireNonNull(state);
    }

    public ProtocolContext withState(CodecState state) {
        return state == this.state
                ? this
                : new ProtocolContext(type, data, phase, wireName, commandInputLimit, variant, state);
    }

    /** Whether this context is {@code base} carrying {@code state}, field for field. */
    public boolean binds(ProtocolContext base, CodecState state) {
        return this.state == state
                && type == base.type
                && data == base.data
                && phase == base.phase
                && wireName == base.wireName
                && commandInputLimit == base.commandInputLimit
                && variant == base.variant;
    }

    public PacketDirection direction() {
        return type.direction();
    }

    public ProtocolVersion version() {
        return data.version();
    }
}
