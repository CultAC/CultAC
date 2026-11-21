package ac.cult.cultac.manager.player.setback;

import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.TeleportData;
import java.util.Collection;

/**
 * The teleport the client must accept next: Cult's own correction or the server's latest teleport, which replaces it.
 * A Cult correction with an absolute target blocks movement until the client answers it, replaced or not, and a full
 * resync also blocks offsets until then. Only one Cult correction is in flight at a time.
 */
public final class CorrectionState {
    // The teleports the client has yet to answer, in the order they were sent.
    private final Collection<TeleportData> pendingTeleports;
    // Written on the netty thread, and on the server thread for Bedrock corrections.
    private volatile SetBackData required;
    // Was there a ghost block that forces us to block offsets until the player accepts their teleport?
    private boolean blockOffsets;
    // The transaction of the ping before the latest Java correction's player_position. That teleport's absolute delta
    // movement replaces every impulse the client applied before it, and the correction's velocity accounts for them.
    private int correctionTransaction = Integer.MIN_VALUE;
    // Are we currently sending setback stuff?
    private boolean sending;

    public CorrectionState(Collection<TeleportData> pendingTeleports) {
        this.pendingTeleports = pendingTeleports;
    }

    /** The latest teleport the client must accept, accepted or not; null before the first. */
    public SetBackData required() {
        return required;
    }

    public void require(SetBackData data) {
        required = data;
    }

    /** Forgets the correction and its offset block: the client it was meant for cannot take it. */
    public void drop() {
        required = null;
        blockOffsets = false;
    }

    /** The client accepted {@code setback}, or Cult stopped waiting for it. */
    public void complete(SetBackData setback) {
        setback.setComplete(true);
        blockOffsets = false;
    }

    /**
     * Whether a Cult correction with an absolute target still waits for the client's answer. The client handles its
     * teleports in the order they were sent, so until it answers a correction it still moves from the state the
     * correction rejected, also once a later server teleport replaced that correction as the required one.
     */
    public boolean isPending() {
        SetBackData setback = required;
        if (setback == null || setback.isComplete()) return false;
        if (setback.isPlugin()) return awaitsReplacedCorrection();
        // Relative setbacks shouldn't count
        return !setback.getTeleportData().isRelativeX()
                && !setback.getTeleportData().isRelativeY()
                && !setback.getTeleportData().isRelativeZ();
    }

    /** Whether a Java correction a server teleport replaced is still among the teleports the client must answer. */
    private boolean awaitsReplacedCorrection() {
        for (TeleportData pending : pendingTeleports) {
            // Only a correction's bundle sets the client's ground.
            if (pending.getSentOnGround() != null) return true;
        }
        return false;
    }

    /** Whether the ping {@code transaction} precedes the latest teleport, accepted or not. */
    public boolean isRequired(int transaction) {
        SetBackData setback = required;
        return setback != null && setback.getTeleportData().getTransaction() == transaction;
    }

    /** Whether the ping {@code transaction} precedes the latest teleport and the client has yet to accept it. */
    public boolean isAwaiting(int transaction) {
        SetBackData setback = required;
        return setback != null
                && !setback.isComplete()
                && setback.getTeleportData().getTransaction() == transaction;
    }

    public boolean isBlockingOffsets() {
        return blockOffsets;
    }

    public void blockOffsets() {
        blockOffsets = true;
    }

    public void unblockOffsets() {
        blockOffsets = false;
    }

    public int correctionTransaction() {
        return correctionTransaction;
    }

    /** A Java correction's player_position follows the ping {@code transaction}. */
    public void correctionSent(int transaction) {
        correctionTransaction = transaction;
    }

    /** Whether Cult is writing its own setback packets right now. */
    public boolean isSending() {
        return sending;
    }

    public void setSending(boolean sending) {
        this.sending = sending;
    }
}
