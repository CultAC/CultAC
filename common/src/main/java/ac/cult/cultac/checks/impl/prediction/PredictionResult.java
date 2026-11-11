package ac.cult.cultac.checks.impl.prediction;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.prediction.checks.HorizontalAnalyzer;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFallDistance;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaTravelInputs;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfiles;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHelper;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.StringReturner;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.IntSupplier;
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
    SimpleCollisionBox realitiesExtent = null;

    // Computed upon compute()
    CollideAxisData collideAxisData;
    ValidMovements validMovements;

    /** The move whose exact-effects inside-block pass built the committed Java carry; null before that commit. */
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    JavaFallDistance.Moved javaCommittedMove;

    /**
     * Where the last pass the runner replayed after this result's commit left its actor: the block entities after a
     * pre-26.3 report, or the shulkers ticking before the next travel. Null until such a pass moves it.
     */
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    JavaPassState javaReplayedPassState;

    List<Flag> flags = List.of();
    double flagSeverity = 0; // Cache for performance and to allow exempting

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private double selectionOffset = Double.NaN;

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

    /** Whether showing this result's identifier builds its Cult Log: a check that shows it flagged the result. */
    @Getter
    @Setter
    @EqualsAndHashCode.Exclude
    boolean logOffered;

    /**
     * Builds the Cult Log and returns its identifier, or 0 off the thread that predicted this result. Set only while the
     * prediction's listeners run and nothing has shown the identifier yet.
     */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private IntSupplier logWhenShown;

    @Getter
    @Setter
    Boolean desiredOnGround = null;

    @Setter
    Object profileResult;

    @Setter
    Vec3 profilePredictionVector;

    /** The source packet position, when MoveVehicle reports a path target rather than physical travel. */
    Vec3 javaReportedMovement;

    public Vec3 getReportedPredictionVector() {
        return javaReportedMovement == null ? getAcceptedClosestToTarget() : javaReportedMovement;
    }

    /** Lets the first text that shows the identifier, until {@link #closeLog}, build the Cult Log with {@code log}. */
    public void buildLogWhenShown(IntSupplier log) {
        logWhenShown = log;
    }

    /** The prediction's listeners are done: text rendered from now on shows 0 unless a log was built. */
    public void closeLog() {
        logWhenShown = null;
    }

    /**
     * The Cult Log identifier, for text that shows it to someone. Text rendered while the prediction's listeners run
     * builds the log here, so a log exists only for an identifier that something showed; otherwise 0.
     */
    public int shownIdentifier() {
        IntSupplier log = logWhenShown;
        if (identifier == 0 && log != null) {
            int built = log.getAsInt();
            if (built != 0) {
                identifier = built;
                logWhenShown = null;
            }
        }
        return identifier;
    }

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
        if (javaReportedMovement != null) return javaReportedMovement.distanceTo(getTarget());
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
        // Ridden input is already in the physical trace. An interpolation-target report supplies no
        // extra moveRelative input and must never turn a server-authored path into carried velocity.
        if (javaReportedMovement != null) return getAcceptedClosestToTarget();
        if (profilePredictionVector != null) {
            return profilePredictionVector;
        }
        Vec3 closest = getAcceptedClosestToTarget();
        // Every Java Entity#move includes its input before clipping. No input may be added after that collision.
        if (collideAxisData instanceof ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMoveCollision)
            return closest;
        Vec3 inputTarget = getTarget();
        var pass = JavaMovementEngine.passAfterTravel(this);
        if (pass != null && !simulationContext.isWithheldPosition()) {
            inputTarget = new Vec3(
                    pass.result().fixedX() ? closest.x : inputTarget.x,
                    inputTarget.y,
                    pass.result().fixedZ() ? closest.z : inputTarget.z);
        }
        return UncertaintyHelper.handleCircular(new PredVector(closest), inputTarget, getHorizontalInputRadius());
    }

    /** Radius of the circular horizontal input allowance around the closest movement. */
    public double getHorizontalInputRadius() {
        return getSimulationContext().getHorizontalInputRadius(player, initialStartingVel);
    }

    public Vec3 getInputVectorAndVerticals() {
        Vec3 input = getHorizontalInput();
        return new Vec3(input.x, getAcceptedClosestToTarget().y, input.z);
    }

    /**
     * The strafe (x) and forward (z) input of this result's travel at its moveRelative speed, before
     * Entity#getInputVector rotates it. An exact-input candidate already added that input in LivingEntity#moveRelative
     * before Entity#move, so what is left of the report past it is no input; otherwise the input is the least that
     * explains that remainder.
     */
    public Vec3 getHorizontalInput() {
        Vec3 movement = JavaTravelInputs.isExact(initialStartingVel)
                ? initialStartingVel.getTravelInput()
                : getTarget().subtract(getAcceptedClosestToTarget());
        return HorizontalAnalyzer.getBestTheoreticalPlayerInput(
                movement,
                getSimulationContext().getXRot(),
                getSimulationContext().getVersion());
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
