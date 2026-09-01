package ac.grim.grimac.utils.data;

import net.minecraft.world.phys.Vec3;
import lombok.Data;
import lombok.ToString;

import java.util.Objects;

@Data
@ToString
public class TransactionVel implements TransactionOrder {
    public static final int UNKNOWN_SOURCE_ENTITY_ID = Integer.MIN_VALUE;

    Vec3 vel;
    final int transaction;
    final boolean isVelocity;
    final boolean isSetbackVel;
    final int sourceEntityId;
    private double offset = Double.MAX_VALUE;

    public TransactionVel(Vec3 vel, int transaction, boolean isVelocity, boolean isSetbackVel) {
        this(vel, transaction, isVelocity, isSetbackVel, UNKNOWN_SOURCE_ENTITY_ID);
    }

    public TransactionVel(Vec3 vel, int transaction, boolean isVelocity, boolean isSetbackVel, int sourceEntityId) {
        this.vel = vel;
        this.transaction = transaction;
        this.isVelocity = isVelocity;
        this.isSetbackVel = isSetbackVel;
        this.sourceEntityId = sourceEntityId;
    }

    public void addOffset(double offset) {
        this.offset = Math.min(this.offset, offset);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TransactionVel that = (TransactionVel) o;
        return transaction == that.transaction;
    }

    @Override
    public int hashCode() {
        return Objects.hash(transaction);
    }

    @Override
    public int getTransaction() {
        return this.transaction;
    }
}
