package ac.cult.cultac.utils.data;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionSetbackState;
import ac.cult.cultac.manager.player.setback.SafeProof;
import ac.cult.cultac.manager.player.setback.SetbackEntity;
import ac.cult.cultac.utils.math.Vec3;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SetbackPosWithVector {
    private final Vec3 pos;
    private Vec3 vector;
    private final PredictionSetbackState profileState;
    // The entity this is a position of: the player, or the vehicle it rode.
    private SetbackEntity owner = SetbackEntity.PLAYER;
    // How the position is known to be true, and from which transaction.
    private SafeProof proof = SafeProof.UNORDERED;
    // The sprint modifier state the prediction at this position committed; null keeps the current one.
    private ClientSprintState.Outcomes sprintOutcomes;
    // The onGround the horse's accepted move left at this position; null when no horse move reached it.
    private DesyncStatus horseOnGround;
    // The onGround the player's own move left at this position (Entity#move's verticalCollisionBelow); null unknown.
    private DesyncStatus onGround;
    // The sent knockback and explosion the vector already carries.
    private TransactionVel carriedKnockback;
    private TransactionVel carriedExplosion;

    public SetbackPosWithVector(Vec3 pos, Vec3 vector) {
        this(pos, vector, null);
    }

    public SetbackPosWithVector(Vec3 pos, Vec3 vector, PredictionSetbackState profileState) {
        this.pos = pos;
        this.vector = vector;
        this.profileState = profileState;
    }

    /** This position with its owner and proof set. */
    public SetbackPosWithVector provenBy(SetbackEntity owner, SafeProof proof) {
        this.owner = owner;
        this.proof = proof;
        return this;
    }

    public boolean carries(TransactionVel impulse) {
        return impulse != null && (impulse == carriedKnockback || impulse == carriedExplosion);
    }
}
