package ac.grim.grimac.bedrock.prediction.integration;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import java.util.ArrayList;
import java.util.List;

final class BedrockRequiredInputMath {
    static final float DEGREES_TO_RADIANS = 0.017453292F;
    private static final float TRIG_TABLE_SCALE = 10430.378F;
    private static final float TRIG_HALF_PI_INDEX = 16384.0F;
    private static final List<Float> SIN_TABLE = buildSinTable();

    private BedrockRequiredInputMath() {
    }

    static Vec3d horizontalInputForDelta(Vec3d wantedMovement, float yawDegrees) {
        float yawRadians = yawDegrees * DEGREES_TO_RADIANS;
        float sinYaw = sin(yawRadians);
        float cosYaw = cos(yawRadians);
        return new Vec3d(
            wantedMovement.x() * cosYaw + wantedMovement.z() * sinYaw,
            0.0D,
            wantedMovement.z() * cosYaw - wantedMovement.x() * sinYaw
        );
    }

    static float sin(float radians) {
        return SIN_TABLE.get(((int) (radians * TRIG_TABLE_SCALE)) & 0xffff);
    }

    static float cos(float radians) {
        return SIN_TABLE.get(((int) (radians * TRIG_TABLE_SCALE + TRIG_HALF_PI_INDEX)) & 0xffff);
    }

    private static List<Float> buildSinTable() {
        List<Float> table = new ArrayList<>(65536);
        for (int index = 0; index < 65536; index++) {
            table.add((float) Math.sin((float) index / TRIG_TABLE_SCALE));
        }
        return List.copyOf(table);
    }
}
