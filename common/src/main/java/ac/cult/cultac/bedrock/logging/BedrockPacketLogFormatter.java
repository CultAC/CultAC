package ac.cult.cultac.bedrock.logging;

import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;

public final class BedrockPacketLogFormatter {
    private BedrockPacketLogFormatter() { }

    public static String format(String direction, BedrockPacket packet) {
        String tick = packet instanceof PlayerAuthInputPacket input ? " client_tick=" + input.getTick()
                : packet instanceof MovePlayerPacket move ? " client_tick=" + move.getTick() : "";
        return direction + " " + packet.getClass().getSimpleName() + tick + " " + escape(packet.toString());
    }

    public static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }
}
