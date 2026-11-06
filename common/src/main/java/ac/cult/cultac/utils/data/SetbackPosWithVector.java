package ac.cult.cultac.utils.data;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionSetbackState;
import ac.cult.cultac.utils.math.Vec3;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SetbackPosWithVector {
    private final Vec3 pos;
    private Vec3 vector;
    private final int tick;
    private final PredictionSetbackState profileState;
    // The sprint modifier state the prediction at this position committed; null keeps the current one.
    private ClientSprintState.Outcomes sprintOutcomes;
    // The onGround the horse's accepted move left at this position; null when no horse move reached it.
    private DesyncStatus horseOnGround;
    // The sent knockback and explosion the vector already carries.
    private TransactionVel carriedKnockback;
    private TransactionVel carriedExplosion;

    public SetbackPosWithVector(Vec3 pos, Vec3 vector, int tick) {
        this(pos, vector, tick, null);
    }

    public SetbackPosWithVector(Vec3 pos, Vec3 vector, int tick, PredictionSetbackState profileState) {
        this.pos = pos;
        this.vector = vector;
        this.tick = tick;
        this.profileState = profileState;
    }

    public boolean carries(TransactionVel impulse) {
        return impulse != null && (impulse == carriedKnockback || impulse == carriedExplosion);
    }
}
