package ac.grim.grimac.bedrock.prediction.geometry;

import java.util.List;

public record BedrockCollisionOverrideShape(List<BlockAabb> boxes) {
    public BedrockCollisionOverrideShape {
        boxes = boxes == null ? List.of() : List.copyOf(boxes);
    }
}
