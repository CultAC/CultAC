package ac.grim.grimac.bedrock.protocol;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;

public record BedrockMoveFrame(
        UUID playerUuid,
        BedrockProtocolVersion protocolVersion,
        long clientTick,
        Vec3 position,
        float yaw,
        float pitch,
        float headYaw
) {
}
