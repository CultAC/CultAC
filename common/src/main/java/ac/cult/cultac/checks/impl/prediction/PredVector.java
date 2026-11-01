package ac.cult.cultac.checks.impl.prediction;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientSprintState;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

public class PredVector extends Vec3 {

    PredVector lastVector = null;
    String reason = null;

    @Getter
    boolean isJump = false;

    @Getter
    boolean isKnockback = false;

    // Set from a set_entity_motion, current or overridden; only isKnockback feeds the velocity checks.
    @Getter
    @Setter
    boolean isEntityMotion = false;

    @Getter
    boolean isBoundedStep = false;

    @Getter
    @Setter
    boolean isTickSkip = false;

    // Player#causeExtraKnockback 0.6 X/Z dampings applied to this starting velocity
    @Getter
    @Setter
    int attackSlows = 0;

    // The minecraft:sprinting modifier this candidate travels with while a possible attack reset leaves it open
    @Getter
    ClientSprintState.Outcome sprintOutcome = ClientSprintState.Outcome.ANY;

    List<TransactionVel> packetModifiers = null;

    public PredVector(Vec3 vec, PredVector lastVector, String reason) {
        this(vec.x, vec.y, vec.z, lastVector, reason);
    }

    private PredVector(double x, double y, double z, PredVector lastVector, String reason) {
        super(x, y, z);
        this.lastVector = lastVector;
        this.isJump = lastVector.isJump;
        this.isKnockback = lastVector.isKnockback;
        this.isEntityMotion = lastVector.isEntityMotion;
        this.isBoundedStep = lastVector.isBoundedStep;
        this.isTickSkip = lastVector.isTickSkip;
        this.attackSlows = lastVector.attackSlows;
        this.sprintOutcome = lastVector.sprintOutcome;
        this.reason = reason;
        this.packetModifiers = lastVector.packetModifiers;
    }

    public PredVector(Vec3 input) {
        super(input.x, input.y, input.z);
    }

    /** This candidate travelling with {@code outcome}'s sprint modifier. */
    public PredVector withSprintOutcome(ClientSprintState.Outcome outcome) {
        PredVector candidate = new PredVector(this, this, "sprint modifier");
        candidate.sprintOutcome = outcome;
        return candidate;
    }

    public static PredVector fromStartingVector(Vec3 input) {
        if (input instanceof PredVector predVector) {
            return new PredVector(predVector, predVector, "starting velocity");
        }
        return new PredVector(input);
    }

    public void addPacketModifier(TransactionVel packetModifier) {
        if (packetModifier.isVelocity()) {
            isKnockback = true;
            isEntityMotion = true;
        }

        // Null check
        if (packetModifiers == null) {
            packetModifiers = new ArrayList<>(1);
        } else if (lastVector != null && lastVector.packetModifiers == this.packetModifiers) {
            // Don't modify previous vector's packet modifiers
            packetModifiers = new ArrayList<>(packetModifiers);
        }
        packetModifiers.add(packetModifier);
    }

    public boolean hasPacketModifier(TransactionVel vel) {
        return vel != null && packetModifiers != null && packetModifiers.contains(vel);
    }

    public void mergePacketModifierProvenance(PredVector equivalent) {
        if (equivalent == null || equivalent.packetModifiers == null || equivalent.packetModifiers.isEmpty()) {
            return;
        }

        List<TransactionVel> merged = packetModifiers == null
                ? new ArrayList<>(equivalent.packetModifiers.size())
                : new ArrayList<>(packetModifiers);
        for (TransactionVel modifier : equivalent.packetModifiers) {
            boolean alreadyPresent = false;
            for (TransactionVel existing : merged) {
                if (existing == modifier) {
                    alreadyPresent = true;
                    break;
                }
            }
            if (!alreadyPresent) {
                merged.add(modifier);
            }
            if (modifier.isVelocity()) {
                isKnockback = true;
                isEntityMotion = true;
            }
        }
        packetModifiers = merged;
    }

    public boolean hasReason(String reason) {
        PredVector lastVector = this;
        while (lastVector != null) {
            if (reason.equals(lastVector.reason)) {
                return true;
            }
            lastVector = lastVector.lastVector;
        }
        return false;
    }

    /**
     * Whether a step with {@code reason} after {@code from} in this lineage changed X ({@code xAxis}) or Z from the
     * step before it. Every step back to the root counts when {@code from} is not in the lineage.
     */
    public boolean reasonMovedAxisSince(String reason, PredVector from, boolean xAxis) {
        for (PredVector current = this;
                current != from && current != null && current.lastVector != null;
                current = current.lastVector) {
            if (!reason.equals(current.reason)) continue;
            PredVector previous = current.lastVector;
            if (xAxis ? current.x != previous.x : current.z != previous.z) return true;
        }
        return false;
    }

    public <T extends PredVector> T firstInLineage(Class<T> type) {
        PredVector current = this;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.lastVector;
        }
        return null;
    }

    public Vec3 vectorBeforeFirstReason(String reason) {
        PredVector current = this;
        Vec3 beforeReason = null;
        while (current != null) {
            if (reason.equals(current.reason) && current.lastVector != null) {
                beforeReason = current.lastVector;
            }
            current = current.lastVector;
        }
        return beforeReason == null ? this : beforeReason;
    }

    public SimpleCollisionBox collisionIgnoredExtents(Vec3 target) {
        return null;
    }

    public double horizontalInputRadius() {
        return lastVector == null ? 0.0D : lastVector.horizontalInputRadius();
    }

    public PredVector stepCandidate() {
        return lastVector == null ? null : lastVector.stepCandidate();
    }

    public PredVector stepCandidate(Vec3 end) {
        return lastVector == null ? stepCandidate() : lastVector.stepCandidate(end);
    }

    public StepCandidate stepCandidate(Vec3 end, CollideAxisData collisionData) {
        return lastVector == null
                ? new StepCandidate(stepCandidate(end), true)
                : lastVector.stepCandidate(end, collisionData);
    }

    public double maxUpStep(CultPlayer player) {
        return lastVector == null ? player.getMaxUpStep() : lastVector.maxUpStep(player);
    }

    public Vec3 collisionStuckSpeedMultiplier(SimulationContext context) {
        return lastVector == null ? context.getLastStuckSpeed() : lastVector.collisionStuckSpeedMultiplier(context);
    }

    public int packetModifiersLength() {
        return packetModifiers == null ? 0 : packetModifiers.size();
    }

    public List<TransactionVel> getPacketModifiers() {
        return packetModifiers == null ? List.of() : Collections.unmodifiableList(packetModifiers);
    }

    /** Same tick-skip state and the same packet velocities, the keys the start-velocity sort orders by first. */
    public boolean hasSameStartAs(PredVector other) {
        if (isTickSkip != other.isTickSkip || packetModifiersLength() != other.packetModifiersLength()) return false;
        for (int i = 0, size = packetModifiersLength(); i < size; i++) {
            if (!other.packetModifiers.contains(packetModifiers.get(i))) return false;
        }
        return true;
    }

    public int tickSkippingComparator() {
        return isTickSkip ? 0 : 1;
    }

    public void setJump() {
        this.isJump = true;
    }

    public PredVector markBoundedStep() {
        this.isBoundedStep = true;
        return this;
    }

    public record StepCandidate(PredVector exact, boolean boundedFallback) {}

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    @Override
    public PredVector clone() {
        return new PredVector(x, y, z, lastVector, "clone");
    }

    public PredVector withXYZ(double x, double y, double z, String reason) {
        return new PredVector(x, y, z, this, reason);
    }

    /** {@link #withXYZ}, or this vector itself when every component keeps its bits; {@code reason} then stays out of the lineage. */
    public PredVector withXYZOrSelf(double x, double y, double z, String reason) {
        if (Double.doubleToRawLongBits(x) == Double.doubleToRawLongBits(this.x)
                && Double.doubleToRawLongBits(y) == Double.doubleToRawLongBits(this.y)
                && Double.doubleToRawLongBits(z) == Double.doubleToRawLongBits(this.z)) {
            return this;
        }
        return new PredVector(x, y, z, this, reason);
    }

    public PredVector withX(double x, String reason) {
        return new PredVector(x, y, z, this, reason);
    }

    public Vec3 withX(double x) {
        return super.with(Direction.Axis.X, x);
    }

    public PredVector withY(double y, String reason) {
        return new PredVector(x, y, z, this, reason);
    }

    public Vec3 withY(double y) {
        return super.with(Direction.Axis.Y, y);
    }

    public PredVector withZ(double z, String reason) {
        return new PredVector(x, y, z, this, reason);
    }

    public Vec3 withZ(double z) {
        return super.with(Direction.Axis.Z, z);
    }

    public PredVector add(Vec3 jumpBonus, String reason) {
        return new PredVector(x + jumpBonus.x, y + jumpBonus.y, z + jumpBonus.z, this, reason);
    }

    public PredVector add(double x, double y, double z, String reason) {
        return new PredVector(this.x + x, this.y + y, this.z + z, this, reason);
    }

    public PredVector with(Vec3 vector, String reason) {
        return this.withXYZ(vector.x, vector.y, vector.z, reason);
    }

    public PredVector multiply(double v, String reason) {
        return new PredVector(x * v, y * v, z * v, this, reason);
    }

    public Vec3 multiply(double v) {
        return super.scale(v);
    }

    public double distanceSquared(Vec3 vector) {
        return distanceToSqr(vector);
    }

    @Override
    public String toString() {
        return "PredVector{" + "x="
                + x + ", y="
                + y + ", z="
                + z + ", reason='"
                + reason + '\'' + ", isJump="
                + isJump + ", isKnockback="
                + isKnockback + ", isTickSkip="
                + isTickSkip + ", packetModifiers="
                + packetModifiers + ", lastVector="
                + lastVector + '}';
    }
}
