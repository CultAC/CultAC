package ac.cult.cultac.bedrock.prediction.input;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import java.util.Objects;

public record BedrockTickInput(
        BedrockInputFrame inputFrame,
        Vec3d packetPhysicalFeetPosition,
        long clientTick
) {
    public BedrockTickInput {
        inputFrame = Objects.requireNonNull(inputFrame, "inputFrame");
        packetPhysicalFeetPosition = Objects.requireNonNull(packetPhysicalFeetPosition, "packetPhysicalFeetPosition");
        if (clientTick < 0L) {
            clientTick = 0L;
        }
    }
}
