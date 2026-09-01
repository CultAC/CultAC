package ac.grim.grimac.utils.data;

import ac.grim.grimac.checks.impl.prediction.PredictionSetbackState;
import net.minecraft.world.phys.Vec3;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SetbackPosWithVector {
    private final Vec3 pos;
    private Vec3 vector;
    private final int tick;
    private final PredictionSetbackState profileState;

    public SetbackPosWithVector(Vec3 pos, Vec3 vector, int tick) {
        this(pos, vector, tick, null);
    }

    public SetbackPosWithVector(Vec3 pos, Vec3 vector, int tick, PredictionSetbackState profileState) {
        this.pos = pos;
        this.vector = vector;
        this.tick = tick;
        this.profileState = profileState;
    }
}
