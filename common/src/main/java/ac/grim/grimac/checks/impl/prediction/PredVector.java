package ac.grim.grimac.checks.impl.prediction;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.TransactionVel;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.CollideAxisData;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class PredVector extends Vec3 {

    PredVector lastVector = null;
    String reason = null;
    @Getter
    boolean isJump = false;
    @Getter
    boolean isKnockback = false;
    @Getter
    boolean isBoundedStep = false;
    @Getter
    @Setter
    boolean isTickSkip = false;
    List<TransactionVel> packetModifiers = null;

    public PredVector(Vec3 vec, PredVector lastVector, String reason) {
        this(vec);
        this.lastVector = lastVector;
        this.isJump = lastVector.isJump;
        this.isKnockback = lastVector.isKnockback;
        this.isBoundedStep = lastVector.isBoundedStep;
        this.isTickSkip = lastVector.isTickSkip;
        this.reason = reason;
        this.packetModifiers = lastVector.packetModifiers;
    }

    public PredVector(Vec3 input) {
        super(input.x, input.y, input.z);
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
        if (equivalent == null || equivalent.packetModifiers == null
                || equivalent.packetModifiers.isEmpty()) {
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

    public boolean hasVectorInLineage(Vec3 vector, double maxDelta) {
        PredVector current = this;
        while (current != null) {
            if (maxAbsDelta(current, vector) <= maxDelta) {
                return true;
            }
            current = current.lastVector;
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

    public double maxUpStep(GrimPlayer player) {
        return lastVector == null ? player.getMaxUpStep() : lastVector.maxUpStep(player);
    }

    public Vec3 collisionStuckSpeedMultiplier(SimulationContext context) {
        return lastVector == null ? context.getLastStuckSpeed() : lastVector.collisionStuckSpeedMultiplier(context);
    }

    public Vec3 resolvedMovementDelta() {
        return lastVector == null ? null : lastVector.resolvedMovementDelta();
    }

    private static double maxAbsDelta(Vec3 first, Vec3 second) {
        if (first == null || second == null) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(
                Math.max(Math.abs(first.x - second.x), Math.abs(first.y - second.y)),
                Math.abs(first.z - second.z));
    }

    public int packetModifiersLength() {
        return packetModifiers == null ? 0 : packetModifiers.size();
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

    public record StepCandidate(PredVector exact, boolean boundedFallback) {
    }

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
        return new PredVector(new Vec3(x, y, z), lastVector, "clone");
    }

    public PredVector withXYZ(double x, double y, double z, String reason) {
        return new PredVector(new Vec3(x, y, z), this, reason);
    }

    public PredVector withX(double x, String reason) {
        return new PredVector(super.with(Direction.Axis.X, x), this, reason);
    }

    public Vec3 withX(double x) {
        return super.with(Direction.Axis.X, x);
    }

    public PredVector withY(double y, String reason) {
        return new PredVector(super.with(Direction.Axis.Y, y), this, reason);
    }

    public Vec3 withY(double y) {
        return super.with(Direction.Axis.Y, y);
    }

    public PredVector withZ(double z, String reason) {
        return new PredVector(super.with(Direction.Axis.Z, z), this, reason);
    }

    public Vec3 withZ(double z) {
        return super.with(Direction.Axis.Z, z);
    }

    public PredVector add(Vec3 jumpBonus, String reason) {
        return new PredVector(super.add(jumpBonus), this, reason);
    }

    public PredVector add(double x, double y, double z, String reason) {
        return new PredVector(super.add(x, y, z), this, reason);
    }

    public PredVector with(Vec3 vector, String reason) {
        return this.withXYZ(vector.x, vector.y, vector.z, reason);
    }

    public PredVector multiply(double v, String reason) {
        return new PredVector(super.scale(v), this, reason);
    }

    public Vec3 multiply(double v) {
        return super.scale(v);
    }

    public double distanceSquared(Vec3 vector) {
        return distanceToSqr(vector);
    }

    @Override
    public String toString() {
        return "PredVector{" +
                "x=" + x +
                ", y=" + y +
                ", z=" + z +
                ", reason='" + reason + '\'' +
                ", isJump=" + isJump +
                ", isKnockback=" + isKnockback +
                ", isTickSkip=" + isTickSkip +
                ", packetModifiers=" + packetModifiers +
                ", lastVector=" + lastVector +
                '}';
    }
}
