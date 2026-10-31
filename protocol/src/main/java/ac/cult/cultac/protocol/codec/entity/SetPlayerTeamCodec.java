package ac.cult.cultac.protocol.codec.entity;

import ac.cult.cultac.protocol.PacketCodec;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPlayerTeam;
import ac.cult.cultac.protocol.wire.NbtSkipper;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;

/** ClientboundSetPlayerTeamPacket: 1.21.5 uses rule ids; 26.2 reorders parameters and makes color optional. */
public final class SetPlayerTeamCodec implements PacketCodec<ClientboundSetPlayerTeam> {
    private static final String[] RULES = {"always", "never", "pushOtherTeams", "pushOwnTeam"};

    @Override
    public ClientboundSetPlayerTeam read(ByteBuf input, ProtocolContext context) {
        String name = Wire.readString(input, 32767);
        int action = input.readByte();
        String collision = null;
        if (action == 0 || action == 2) {
            NbtSkipper.skip(input, 512); // Display name.
            if (context.version().atLeast(ProtocolVersion.V26_2)) {
                NbtSkipper.skip(input, 512); // Prefix.
                NbtSkipper.skip(input, 512); // Suffix.
                Wire.readVarInt(input); // Visibility.
                collision = rule(Wire.readVarInt(input));
                if (input.readBoolean()) Wire.readVarInt(input);
                input.readByte(); // Options.
            } else {
                input.readByte();
                if (context.version().atLeast(ProtocolVersion.V1_21_5)) {
                    Wire.readVarInt(input);
                    collision = rule(Wire.readVarInt(input));
                } else {
                    Wire.readString(input, 40);
                    collision = Wire.readString(input, 40);
                }
                Wire.readVarInt(input); // Color.
                NbtSkipper.skip(input, 512);
                NbtSkipper.skip(input, 512);
            }
        }
        List<String> members = List.of();
        if (action == 0 || action == 3 || action == 4) {
            int count = Wire.readLength(input, input.readableBytes());
            var names = new ArrayList<String>(count);
            for (int i = 0; i < count; i++) names.add(Wire.readString(input, 32767));
            members = names;
        }
        return new ClientboundSetPlayerTeam(name, action, collision, members);
    }

    private static String rule(int id) {
        return RULES[id >= 0 && id < RULES.length ? id : 0];
    }
}
