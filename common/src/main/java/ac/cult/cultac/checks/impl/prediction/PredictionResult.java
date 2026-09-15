package ac.cult.cultac.checks.impl.prediction;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.prediction.checks.HorizontalAnalyzer;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfiles;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHelper;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.StringReturner;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.TeleportData;
import net.minecraft.world.phys.Vec3;
import lombok.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
    SimpleCollisionBox realitiesExtent = null;

    // Computed upon compute()
    CollideAxisData collideAxisData;
    ValidMovements validMovements;

    List<Flag> flags = new ArrayList<>();
    double flagSeverity = 0; // Cache for performance and to allow exempting
    private Boolean cachedAnyRealityControlsVerticalMovement;
    private Boolean cachedAnyRealityDidTickSkipGravity;
    @Getter
    @Setter
    boolean isExempt = false;
    @Setter
    boolean isTeleport = false;
    @Getter
    @Setter
    boolean exceedsSlowedSpeed = false;
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
        flags.add(new Flag(check, message, flagSeverity));
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

    public boolean anyRealityControlsVerticalMovement() {
        if (cachedAnyRealityControlsVerticalMovement != null) {
            return cachedAnyRealityControlsVerticalMovement;
        }

        boolean result = false;
        for (PredictionResult reality : realities) {
            if (reality.getValidMovements().isControlsVerticalMovement()) {
                result = true;
                break;
            }
        }
        cachedAnyRealityControlsVerticalMovement = result;
        return result;
    }

    public boolean anyRealityDidTickSkipGravity() {
        if (cachedAnyRealityDidTickSkipGravity != null) {
            return cachedAnyRealityDidTickSkipGravity;
        }

        boolean result = false;
        for (PredictionResult reality : realities) {
            if (reality.getValidMovements().isDidTickSkipGravity()) {
                result = true;
                break;
            }
        }
        cachedAnyRealityDidTickSkipGravity = result;
        return result;
    }

    public int getEffectiveFlagCount() {
        return hasEffectiveFlags() ? flags.size() : 0;
    }

    public Vec3 getLegacyLikePredictionVector() {
        if (profilePredictionVector != null) {
            return profilePredictionVector;
        }
        float speed = getSimulationContext().getMaxSpeed(player);
        Vec3 closest = getAcceptedClosestToTarget();
        return UncertaintyHelper.handleCircular(new PredVector(closest), getTarget(),
                getSimulationContext().getVehicle() == null ? speed * 1.3D : speed);
    }

    public Vec3 getInputVectorAndVerticals() {
        Vec3 closest = getAcceptedClosestToTarget();
        Vec3 diff = getTarget().subtract(closest);
        Vec3 minimumInputRequired = HorizontalAnalyzer.getBestTheoreticalPlayerInput(diff, getSimulationContext().getXRot());
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
        if (initialStartingVel.isTickSkip() || validMovements.getCollisionIgnoredMaxStartingVelExtents() == null) return DesyncStatus.UNKNOWN;
        DesyncStatus falling = DesyncStatus.fromBoolean(validMovements.getCollisionIgnoredMaxStartingVelExtents().minY <= 0);
        if (falling.determineOptimistically() && validMovements.getCollisionIgnoredMaxStartingVelExtents().maxY > 0) {
            falling = DesyncStatus.UNKNOWN;
        }
        return falling;
    }

    public List<Double> getPossibleGravity() {
        if (!getSimulationContext().getEntities().getEntityInControl().hasGravity) {
            return Collections.singletonList(0.0);
        }

        DesyncStatus falling = getIsFalling();
        boolean hasSlowFalling = getSimulationContext().getEntities().getSlowFallingAmplifier() != null;

        double gravity = getSimulationContext().getEntities().getEntityInControl().gravity;
        if (!hasSlowFalling || falling == DesyncStatus.FALSE) { // No slow falling, or not falling
            return Collections.singletonList(gravity);
        } else if (falling == DesyncStatus.UNKNOWN) { // We don't know
            return Arrays.asList(Math.min(gravity, 0.01D), gravity);
        } else { // Slow falling
            return Collections.singletonList(Math.min(gravity, 0.01D));
        }
    }

    @Override
    public String toString() {
        return "PredictionResult{" + "player=" + player.getName() +
                ", initialStartingVel=" + initialStartingVel +
                ", simulationContext=" + simulationContext +
                ", target=" + target +
                ", setBackData=" + setBackData +
                ", collideAxisData=" + collideAxisData +
                ", validMovements=" + validMovements +
                ", flags=" + flags +
                ", flagSeverity=" + flagSeverity +
                ", isExempt=" + isExempt +
                ", isTeleport=" + isTeleport +
                '}';
    }
}
