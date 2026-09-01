package ac.grim.grimac.bedrock.prediction.integration;

import ac.grim.grimac.checks.impl.prediction.PredVector;
import java.util.Objects;

final class BedrockStateVelocity extends PredVector {
    private final BedrockProfileState.Entry entry;

    BedrockStateVelocity(BedrockProfileState.Entry entry) {
        super(BedrockVectorAdapter.toJava(Objects.requireNonNull(entry, "entry").state().velocity()));
        this.entry = entry;
    }

    BedrockStateVelocity(BedrockProfileState.Entry entry, PredVector source) {
        super(
                BedrockVectorAdapter.toJava(Objects.requireNonNull(entry, "entry").state().velocity()),
                Objects.requireNonNull(source, "source"),
                "bedrock profile state");
        this.entry = entry;
    }

    BedrockProfileState.Entry entry() {
        return entry;
    }
}
