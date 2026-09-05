package ac.cult.cultac.utils.anticheat.update;

import ac.cult.cultac.checks.impl.prediction.AuthoredMovementFrame;
import ac.cult.cultac.utils.data.TeleportAcceptData;
import net.minecraft.world.phys.Vec3;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

@Getter
@Setter
public final class PositionUpdate {
    private final Vec3 from, to;
    private final float xRot, yRot;
    private final boolean onGround;
    private final TeleportAcceptData teleportData;
    @Nullable
    private final AuthoredMovementFrame authoredMovementFrame;

    public PositionUpdate(Vec3 from, Vec3 to, float xRot, float yRot, boolean onGround, TeleportAcceptData teleportData) {
        this(from, to, xRot, yRot, onGround, teleportData, null);
    }

    public PositionUpdate(Vec3 from, Vec3 to, float xRot, float yRot, boolean onGround, TeleportAcceptData teleportData, @Nullable AuthoredMovementFrame authoredMovementFrame) {
        this.from = from;
        this.to = to;
        this.xRot = xRot;
        this.yRot = yRot;
        this.onGround = onGround;
        this.teleportData = teleportData;
        this.authoredMovementFrame = authoredMovementFrame;
    }
}
