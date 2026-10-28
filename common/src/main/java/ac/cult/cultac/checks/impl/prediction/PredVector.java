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

    // Player#causeExtraKnockback 0.6 X/Z dampings applied to this starting velocity
    @Getter
    @Setter
    int attackSlows = 0;

    // The minecraft:sprinting modifier this candidate travels with while a possible attack reset leaves it open
    @Getter
    ClientSprintState.Outcome sprintOutcome = ClientSprintState.Outcome.ANY;

    // The exact LivingEntity#moveRelative input this candidate already holds, or null when the travel adds the
    // horizontal input allowance instead.
    @Getter
    Vec3 travelInput = null;

    List<TransactionVel> packetModifiers = null;

    // A bounded deltaMovement the client may hold, as offsets around this vector, or null when exact.
    @Getter
    SimpleCollisionBox velocityRange = null;

    // The end of velocityRange this candidate moves with per axis: -1 low, 0 nearest the report, 1 high.
    byte rangeEndX, rangeEndY, rangeEndZ;

    // The ranged velocity these ends were chosen from; candidates sharing it are one uncertain velocity.
    @Getter
    PredVector rangeOrigin = null;

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
        this.attackSlows = lastVector.attackSlows;
        this.sprintOutcome = lastVector.sprintOutcome;
        this.travelInput = lastVector.travelInput;
        this.reason = reason;
        this.packetModifiers = lastVector.packetModifiers;
        this.velocityRange = lastVector.velocityRange;
        this.rangeEndX = lastVector.rangeEndX;
        this.rangeEndY = lastVector.rangeEndY;
        this.rangeEndZ = lastVector.rangeEndZ;
        this.rangeOrigin = lastVector.rangeOrigin;
    }

    public PredVector(Vec3 input) {
        super(input.x, input.y, input.z);
    }

    /** This deltaMovement after LivingEntity#moveRelative added the exact {@code input}. */
    public PredVector withTravelInput(Vec3 input) {
        PredVector candidate = new PredVector(x + input.x, y + input.y, z + input.z, this, "travel input");
        candidate.travelInput = input;
        return candidate;
    }

    /** Whether this candidate's travel input is exact rather than the horizontal input allowance. */
    public boolean hasTravelInput() {
        return travelInput != null;
    }

    /** This candidate travelling with {@code outcome}'s sprint modifier. */
    public PredVector withSprintOutcome(ClientSprintState.Outcome outcome) {
        PredVector candidate = new PredVector(this, this, "sprint modifier");
        candidate.sprintOutcome = outcome;
        return candidate;
    }

    public static PredVector fromStartingVector(Vec3 input) {
        if (input instanceof PredVector predVector) {
            PredVector start = new PredVector(predVector, predVector, "starting velocity");
            // A carried range is split into its ends again this tick.
            start.rangeEndX = start.rangeEndY = start.rangeEndZ = 0;
            start.rangeOrigin = null;
            return start;
        }
        return new PredVector(input);
    }

    /** This velocity uncertain by {@code offsets} around it; a null or single-point range leaves it exact. */
    public PredVector withVelocityRange(SimpleCollisionBox offsets) {
        boolean point = offsets == null
                || offsets.minX == offsets.maxX && offsets.minY == offsets.maxY && offsets.minZ == offsets.maxZ;
        if (point && velocityRange == null) return this;
        PredVector ranged = new PredVector(this, this, "velocity range");
        ranged.velocityRange = point ? null : offsets;
        ranged.rangeEndX = ranged.rangeEndY = ranged.rangeEndZ = 0;
        ranged.rangeOrigin = null;
        return ranged;
    }

    /**
     * This vector after a transform mapped its range to {@code offsets}; the ends it moves with stay, so it still
     * takes the same end of the mapped range.
     */
    public PredVector withMappedRange(SimpleCollisionBox offsets) {
        if (velocityRange == null) return this;
        PredVector mapped = new PredVector(this, this, "mapped velocity range");
        mapped.velocityRange = offsets;
        return mapped;
    }

    /** This ranged velocity moving with the given end per axis (see {@link #getRangeEnd}). */
    public PredVector withRangeEnds(int x, int y, int z) {
        PredVector candidate = new PredVector(this, this, "velocity range end");
        candidate.rangeEndX = (byte) x;
        candidate.rangeEndY = (byte) y;
        candidate.rangeEndZ = (byte) z;
        candidate.rangeOrigin = this;
        return candidate;
    }

    /** -1 for the low end, 1 for the high end, 0 for the value nearest the report, on axis 0 (X), 1 (Y) or 2 (Z). */
    public int getRangeEnd(int axis) {
        return axis == 0 ? rangeEndX : axis == 1 ? rangeEndY : rangeEndZ;
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

    public Vec3 vectorAtFirstReason(String reason) {
        Vec3 found = this;
        for (PredVector current = this; current != null; current = current.lastVector) {
            if (reason.equals(current.reason)) found = current;
        }
        return found;
    }

    /** The carried point this candidate started from, before packet and travel modifiers. */
    public Vec3 carriedVelocity() {
        PredVector root = this;
        while (root.lastVector != null) root = root.lastVector;
        return root;
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

    /** The same packet velocities, the key the start-velocity sort orders by first. */
    public boolean hasSameStartAs(PredVector other) {
        if (packetModifiersLength() != other.packetModifiersLength()) return false;
        for (int i = 0, size = packetModifiersLength(); i < size; i++) {
            if (!other.packetModifiers.contains(packetModifiers.get(i))) return false;
        }
        return true;
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

    /** This vector scaled by {@code v}; a velocity range scales with it, so it still bounds the same velocities. */
    public PredVector multiply(double v, String reason) {
        PredVector scaled = new PredVector(x * v, y * v, z * v, this, reason);
        if (velocityRange != null) {
            scaled.velocityRange = SimpleCollisionBox.between(
                    velocityRange.minX * v,
                    velocityRange.minY * v,
                    velocityRange.minZ * v,
                    velocityRange.maxX * v,
                    velocityRange.maxY * v,
                    velocityRange.maxZ * v);
            if (v < 0) {
                // A negative scale swaps the low and high ends.
                scaled.rangeEndX = flipped(rangeEndX);
                scaled.rangeEndY = flipped(rangeEndY);
                scaled.rangeEndZ = flipped(rangeEndZ);
            }
        }
        return scaled;
    }

    private static byte flipped(byte end) {
        return end == -1 || end == 1 ? (byte) -end : end;
    }

    /**
     * This vector after a per-axis clamp into {@code [low, high]} (LivingEntity#handleOnClimbable); a velocity range
     * maps through the same clamp, so it holds exactly the clamped velocities.
     */
    public PredVector clamped(Vec3 low, Vec3 high, String reason) {
        PredVector clamped = withXYZOrSelf(
                Math.max(low.x, Math.min(high.x, x)),
                Math.max(low.y, Math.min(high.y, y)),
                Math.max(low.z, Math.min(high.z, z)),
                reason);
        if (velocityRange == null) return clamped;
        SimpleCollisionBox range = velocityRange;
        SimpleCollisionBox mapped = new SimpleCollisionBox(
                Math.max(low.x, Math.min(high.x, x + range.minX)) - clamped.x,
                Math.max(low.y, Math.min(high.y, y + range.minY)) - clamped.y,
                Math.max(low.z, Math.min(high.z, z + range.minZ)) - clamped.z,
                Math.max(low.x, Math.min(high.x, x + range.maxX)) - clamped.x,
                Math.max(low.y, Math.min(high.y, y + range.maxY)) - clamped.y,
                Math.max(low.z, Math.min(high.z, z + range.maxZ)) - clamped.z);
        if (clamped == this) clamped = new PredVector(this, this, reason);
        clamped.velocityRange = mapped;
        return clamped;
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
                + isKnockback + ", packetModifiers="
                + packetModifiers + ", lastVector="
                + lastVector + '}';
    }
}
