package ac.cult.cultac.bedrock.protocol;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;

public record BedrockMoveFrame(
        UUID playerUuid,
        BedrockProtocolVersion protocolVersion,
        long clientTick,
        Vec3 position,
        float yaw,
        float pitch,
        float headYaw,
        Vec3 packetPosition,
        BedrockCoordinateFrame coordinateFrame,
        boolean coordinateProvenance
) {
    public BedrockMoveFrame(UUID uuid, BedrockProtocolVersion version, long tick, Vec3 position,
                            float yaw, float pitch, float headYaw) {
        this(uuid, version, tick, position, yaw, pitch, headYaw, null, BedrockCoordinateFrame.IDENTITY, false);
    }

    public BedrockMoveFrame resolveCoordinates(BedrockCoordinateFrame frame) {
        if (coordinateFrame.equals(frame)) return this;
        Vec3 feet = packetPosition == null ? coordinateFrame.toLocal(position)
                : new Vec3((float) packetPosition.x, position.y, (float) packetPosition.z);
        return new BedrockMoveFrame(playerUuid, protocolVersion, clientTick, frame.toWorld(feet), yaw, pitch,
                headYaw, packetPosition, frame, coordinateProvenance);
    }
}
