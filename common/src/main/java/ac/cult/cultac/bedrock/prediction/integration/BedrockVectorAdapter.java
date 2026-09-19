package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import net.minecraft.world.phys.Vec3;

public final class BedrockVectorAdapter {
    private BedrockVectorAdapter() {
    }

    public static Vec3 toJava(Vec3d vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    public static Vec3d toBedrock(Vec3 vector) {
        return new Vec3d(vector.x, vector.y, vector.z);
    }
}
