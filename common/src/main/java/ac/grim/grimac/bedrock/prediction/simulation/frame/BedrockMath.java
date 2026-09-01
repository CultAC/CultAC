package ac.grim.grimac.bedrock.prediction.simulation.frame;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.input.BedrockInputFrame;
import java.util.ArrayList;
import java.util.List;

public final class BedrockMath {
    public static final float PI = 3.1415927F;
    public static final float DEGREES_TO_RADIANS = 0.017453292F;
    private static final float TRIG_TABLE_SCALE = 10430.378F;
    private static final float TRIG_HALF_PI_INDEX = 16384.0F;
    private static final List<Float> SIN_TABLE = buildSinTable();

    private BedrockMath() {
    }

    public static double lookDirectionY(float pitchDegrees) {
        return -sin(pitchDegrees * DEGREES_TO_RADIANS);
    }

    public static Vec3d viewVector(BedrockInputFrame frame) {
        float pitchRadians = frame.pitch() * DEGREES_TO_RADIANS;
        float yawRadians = frame.yaw() * DEGREES_TO_RADIANS;
        float cosPitch = cos(pitchRadians);
        return new Vec3d(
            -sin(yawRadians) * cosPitch,
            -sin(pitchRadians),
            cos(yawRadians) * cosPitch
        );
    }

    public static double f(double value) {
        return (double) (float) value;
    }

    public static float sin(float radians) {
        return SIN_TABLE.get(((int) (radians * TRIG_TABLE_SCALE)) & 0xffff);
    }

    public static float cos(float radians) {
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
