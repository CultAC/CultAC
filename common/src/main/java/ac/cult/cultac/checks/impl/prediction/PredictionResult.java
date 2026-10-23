package ac.cult.cultac.checks.impl.prediction;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.prediction.checks.HorizontalAnalyzer;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFallDistance;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfiles;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHelper;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.StringReturner;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.*;

@Data
@RequiredArgsConstructor
public class PredictionResult {
    final CultPlayer player;
    final PredVector initialStartingVel;
    final SimulationContext simulationContext;
    final Vec3 target;
    final TeleportData setBackData;
    final List<PredictionResult> realities;
    final List<PredictionResult> anyReality;

    // Computed upon compute()
    CollideAxisData collideAxisData;
    ValidMovements validMovements;

    /** The move whose exact-effects inside-block pass built the committed Java carry; null before that commit. */
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    JavaFallDistance.Moved javaCommittedMove;

    List<Flag> flags = List.of();
    double flagSeverity = 0; // Cache for performance and to allow exempting

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private double selectionOffset = Double.NaN;

    /** VanillaStepProof's source-proven step for this result; one handleResult pass reads it from two checks. */
    @EqualsAndHashCode.Exclude
    Boolean sourceProvenStep;

    @Getter
    @Setter
    boolean isExempt = false;

    @Setter
    boolean isTeleport = false;

    @Getter
    @Setter
    ItemUseTickResult itemUseResult;

    ItemUseBurstResult itemUseBurstResult;

    // Filled by the existing horizontal input classifier, without another collision query.
    float sneakingInputMultiplier = 1F;

    @Getter
    @Setter
    boolean profileVerboseLog = false;

    @Getter
    @Setter
    int identifier;

    @Getter
    @Setter
    Boolean desiredOnGround = null;

    @Setter
    Object profileResult;

    @Setter
    Vec3 profilePredictionVector;

    public <T> T getProfileResult(Class<T> type) {
        return type.isInstance(profileResult) ? type.cast(profileResult) : null;
    }

    public void exempt() {
        isExempt = true;
        flagSeverity = 0;
    }

    public void addFlag(Check check, StringReturner message, double flagSeverity) {
        if (!MovementProfiles.forPlayer(player).shouldRunPseudoCheck(check.getClass())) {
            return;
        }
        this.flagSeverity += flagSeverity;
        if (flags.isEmpty()) flags = new ArrayList<>(2);
        flags.add(new Flag(check, message, flagSeverity));
    }

    /** Drops the flags of {@code check} and their severity, leaving every other flag on this result. */
    public void removeFlags(Class<? extends Check> check) {
        if (getFlag(check) == null) {
            return;
        }
        List<Flag> kept = new ArrayList<>(flags.size());
        double severity = 0.0D;
        for (Flag flag : flags) {
            if (flag.getCheck().getClass() != check) {
                kept.add(flag);
                severity += flag.getSeverity();
            }
        }
        flags = kept;
        if (!isExempt) {
            flagSeverity = severity;
        }
    }

    public boolean hasFlag(Class<? extends Check> check) {
        return getFlag(check) != null;
    }

    public Flag getFlag(Class<? extends Check> check) {
        for (Flag flag : flags) {
            if (flag.getCheck().getClass() == check) {
                return flag;
            }
        }
        return null;
    }

    public double getOffset() {
        Vec3 predicted = getLegacyLikePredictionVector();
        return predicted.distanceTo(getTarget());
    }

    /** {@link #getOffset()} read once during candidate selection, after the candidate's movement is final. */
    public double selectionOffset() {
        if (Double.isNaN(selectionOffset)) selectionOffset = getOffset();
        return selectionOffset;
    }

    public Vec3 getAcceptedClosestToTarget() {
        if (profilePredictionVector != null) {
            return profilePredictionVector;
        }
        return validMovements == null ? Vec3.ZERO : validMovements.getClosestToTarget();
    }

    public Vec3 getPositionOnlyDelta() {
        return validMovements == null ? Vec3.ZERO : validMovements.getPositionOnlyDelta();
    }

    public boolean hasEffectiveFlags() {
        return !isExempt && flagSeverity > 0.0D;
    }

    public int getEffectiveFlagCount() {
        return hasEffectiveFlags() ? flags.size() : 0;
    }

    public Vec3 getLegacyLikePredictionVector() {
        if (profilePredictionVector != null) {
            return profilePredictionVector;
        }
        Vec3 closest = getAcceptedClosestToTarget();
        return UncertaintyHelper.handleCircular(new PredVector(closest), getTarget(), getHorizontalInputRadius());
    }

    /** Radius of the circular horizontal input allowance around the closest movement. */
    public double getHorizontalInputRadius() {
        if (validMovements != null
                && validMovements.getClosestTrace() != null
                && validMovements.getClosestTrace().position().isInputApplied()) return 0.0D;
        return getSimulationContext().getHorizontalInputRadius(player, initialStartingVel);
    }

    public Vec3 getInputVectorAndVerticals() {
        Vec3 closest = getAcceptedClosestToTarget();
        Vec3 diff = getTarget().subtract(closest);
        Vec3 minimumInputRequired = HorizontalAnalyzer.getBestTheoreticalPlayerInput(
                diff, getSimulationContext().getXRot(), getSimulationContext().getVersion());
        return new Vec3(minimumInputRequired.x, closest.y, minimumInputRequired.z);
    }

    public boolean isBetterThan(PredictionResult other) {
        if (other == null) return true; // by default, we are better than nothing
        // Count flag severity
        return flagSeverity < other.flagSeverity;
    }

    @AllArgsConstructor
    @Getter
    public static class Flag {
        Check check;
        StringReturner verbose;
        double severity;

        @Override
        public String toString() {
            return "Flag{" + "check=" + check + ", verbose=" + verbose.getString() + ", severity=" + severity + '}';
        }
    }

    public DesyncStatus getIsFalling() {
        if (validMovements.getCollisionIgnoredMaxStartingVelExtents() == null) return DesyncStatus.UNKNOWN;
        DesyncStatus falling =
                DesyncStatus.fromBoolean(validMovements.getCollisionIgnoredMaxStartingVelExtents().minY <= 0);
        if (falling.determineOptimistically() && validMovements.getCollisionIgnoredMaxStartingVelExtents().maxY > 0) {
            falling = DesyncStatus.UNKNOWN;
        }
        return falling;
    }

    /** LivingEntity#getEffectiveGravity for every stored deltaMovement with a y in this range. */
    public List<Double> getPossibleGravity(double minVelocityY, double maxVelocityY) {
        if (!getSimulationContext().getEntities().getEntityInControl().hasGravity) {
            return Collections.singletonList(0.0);
        }

        boolean hasSlowFalling = getSimulationContext().getEntities().getSlowFallingAmplifier() != null;

        double gravity = getSimulationContext().getEntities().getEntityInControl().gravity;
        if (!hasSlowFalling) {
            return Collections.singletonList(gravity);
        } else if (minVelocityY <= 0.0D && maxVelocityY > 0.0D) {
            return Arrays.asList(Math.min(gravity, 0.01D), gravity);
        } else {
            return Collections.singletonList(maxVelocityY <= 0.0D ? Math.min(gravity, 0.01D) : gravity);
        }
    }

    @Override
    public String toString() {
        return "PredictionResult{" + "player=" + player.getName() + ", initialStartingVel="
                + initialStartingVel + ", simulationContext="
                + simulationContext + ", target="
                + target + ", setBackData="
                + setBackData + ", collideAxisData="
                + collideAxisData + ", validMovements="
                + validMovements + ", flags="
                + flags + ", flagSeverity="
                + flagSeverity + ", isExempt="
                + isExempt + ", isTeleport="
                + isTeleport + '}';
    }
}
