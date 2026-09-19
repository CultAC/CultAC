package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;

public final class BedrockControlInput {
    private BedrockControlInput() { }

    public static boolean validControl(BedrockAuthInputFrame frame) {
        var vector = frame.getMoveVector();
        return vector != null && Float.isFinite(vector.x()) && Float.isFinite(vector.z())
                && Math.abs(vector.x()) <= 1.0F && Math.abs(vector.z()) <= 1.0F;
    }

    static Vec3d control(BedrockAuthInputFrame frame, BedrockMovementState state) {
        if (!validControl(frame)) throw new IllegalArgumentException("Invalid movement control");
        if (state.isBoat() && !state.boat().analogPaddles()) {
            return new Vec3d(frame.hasRawInputFlag(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.PADDLE_LEFT) ? 1 : 0,
                    0, frame.hasRawInputFlag(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.PADDLE_RIGHT) ? 1 : 0);
        }
        var vector = frame.getMoveVector();
        float divisor = Math.max(1.0F, (float) Math.sqrt(vector.x() * vector.x() + vector.z() * vector.z()));
        float forward = state.isBoat() && frame.hasRawInputFlag(org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.UP)
                ? 1.0F : vector.z() / divisor;
        return new Vec3d(vector.x() / divisor, 0, forward);
    }

}
