package ac.grim.grimac.bedrock.prediction.integration;

import ac.grim.grimac.utils.data.CollideAxisData;
import java.util.ArrayList;

final class BedrockCollisionAxisData {
    private BedrockCollisionAxisData() {
    }

    static CollideAxisData neutral() {
        return new CollideAxisData(
                new CollideAxisData.CollideResult(false, 0.0D),
                new CollideAxisData.CollideResult(false, 0.0D),
                new CollideAxisData.CollideResult(false, 0.0D),
                new CollideAxisData.CollideResult(false, 0.0D),
                new ArrayList<>());
    }
}
