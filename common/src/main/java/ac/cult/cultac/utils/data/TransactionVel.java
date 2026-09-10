package ac.cult.cultac.utils.data;

import net.minecraft.world.phys.Vec3;
import lombok.Data;
import lombok.ToString;


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
    private boolean consumed;

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

    public long getBedrockTeleportRevision() {
        return -1L;
    }

    public static final class Bedrock extends TransactionVel {
        private final long teleportRevision;

        public Bedrock(Vec3 vel, int transaction, boolean isSetbackVel, int sourceEntityId, long teleportRevision) {
            super(vel, transaction, true, isSetbackVel, sourceEntityId);
            this.teleportRevision = teleportRevision;
        }

        @Override public long getBedrockTeleportRevision() { return teleportRevision; }
        @Override public boolean equals(Object other) { return this == other; }
        @Override public int hashCode() { return System.identityHashCode(this); }
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
        return Integer.hashCode(transaction);
    }

    @Override
    public int getTransaction() {
        return this.transaction;
    }
}
