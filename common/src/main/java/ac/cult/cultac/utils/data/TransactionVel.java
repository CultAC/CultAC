package ac.cult.cultac.utils.data;

import ac.cult.cultac.utils.math.Vec3;
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
    /** Outbound impulse order, independent of the receipt ping shared by a flush. */
    final long outboundSequence;

    private double offset = Double.MAX_VALUE;
    private boolean consumed;
    /** The transaction whose answer proves the client handled this velocity. */
    private int proofTransaction;

    public TransactionVel(Vec3 vel, int transaction, boolean isVelocity, boolean isSetbackVel) {
        this(vel, transaction, isVelocity, isSetbackVel, UNKNOWN_SOURCE_ENTITY_ID);
    }

    public TransactionVel(Vec3 vel, int transaction, boolean isVelocity, boolean isSetbackVel, int sourceEntityId) {
        this(vel, transaction, isVelocity, isSetbackVel, sourceEntityId, transaction);
    }

    public TransactionVel(
            Vec3 vel,
            int transaction,
            boolean isVelocity,
            boolean isSetbackVel,
            int sourceEntityId,
            long outboundSequence) {
        this.vel = vel;
        this.transaction = transaction;
        this.isVelocity = isVelocity;
        this.isSetbackVel = isSetbackVel;
        this.sourceEntityId = sourceEntityId;
        this.outboundSequence = outboundSequence;
        this.proofTransaction = transaction;
    }

    public long getBedrockTeleportRevision() {
        return -1L;
    }

    public static final class Bedrock extends TransactionVel {
        private final long teleportRevision;

        public Bedrock(
                Vec3 vel,
                int transaction,
                boolean isSetbackVel,
                int sourceEntityId,
                long teleportRevision,
                long outboundSequence) {
            super(vel, transaction, true, isSetbackVel, sourceEntityId, outboundSequence);
            this.teleportRevision = teleportRevision;
        }

        @Override
        public long getBedrockTeleportRevision() {
            return teleportRevision;
        }
    }

    public void addOffset(double offset) {
        this.offset = Math.min(this.offset, offset);
    }

    @Override
    public final boolean equals(Object o) {
        // A shared receipt ping proves several distinct impulses; provenance follows the impulse itself.
        return this == o;
    }

    @Override
    public final int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public int getTransaction() {
        return this.transaction;
    }
}
