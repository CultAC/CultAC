package ac.cult.cultac.manager.player.setback;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.math.Vec3;

/**
 * The position, velocity and state a setback returns the player to, and the one rule for what may replace it.
 *
 * <ul>
 *   <li>It comes only from server-verified state: a placement the server or Cult sent, an answered teleport, or a
 *       movement every check passed. Never from a flagged or claimed position.
 *   <li>A movement is staged until every check on it, and its packet's receive handlers, have run, so a check that sets
 *       it back rolls back past it, not onto it.
 *   <li>A staged movement commits only over the safe position it was staged over. A placement, answer or release that
 *       replaced it in between is authoritative state the movement was predicted without, and is never rolled back.
 *   <li>It belongs to the entity a setback moves now; a position of another entity never replaces it.
 *   <li>It does not advance while a correction is unanswered, offsets are blocked, or the client has not proven the
 *       chunk it is in.
 *   <li>It does not exist before the first placement, and leaving the level or respawning clears it.
 *   <li>A Bedrock client's anchor is its answer: Geyser's emissions are not placements.
 * </ul>
 */
public final class SafePositionTracker {
    /** What the rule reads from the setback in flight. */
    public interface Corrections {
        /** The entity a setback moves now. */
        SetbackEntity setbackEntity();

        /** No correction is unanswered, offsets are not blocked and the client has proven the chunk. */
        boolean safePositionUnblocked();

        /** A Cult setback is waiting for its answer. */
        boolean isPendingSetback();
    }

    /** How the safe position changed. */
    public enum Write {
        /** The client answered a teleport. */
        ANSWER,
        /** A predicted movement passed every check. */
        MOVEMENT,
        /** The level was left or the player respawned. */
        CLEAR,
        /** A Bedrock client answered a Geyser teleport. */
        BEDROCK_ANSWER,
        /** Observe-only released a pending setback where the player is. */
        OBSERVE_RELEASE,
        /** The server or Cult placed the player's vehicle. */
        VEHICLE_PLACEMENT,
        /** The server or Cult sent the player a position. */
        PLACEMENT,
        /** A test fixture placed the player. */
        TEST_SEED
    }

    private final CultPlayer player;
    private final Corrections corrections;
    private SetbackPosWithVector current;
    // The safe position a movement reached, staged until every check on that movement has run, and the safe position
    // it replaces.
    private SetbackPosWithVector staged;
    private SetbackPosWithVector stagedOver;
    // A Java movement packet's receive handlers check it after its prediction pass.
    private boolean holding;
    // The safe position when the client's latest movement arrived. While it is still the safe position, that movement
    // is not part of it, and a setback rejects it.
    private SetbackPosWithVector atLatestMovement;

    public SafePositionTracker(CultPlayer player, Corrections corrections) {
        this.player = player;
        this.corrections = corrections;
    }

    /** The position, velocity and state a setback returns the player to; null before the player is placed. */
    public SetbackPosWithVector current() {
        return current;
    }

    /** Whether {@code safe} is the safe position, or the one staged to become it. */
    public boolean holds(SetbackPosWithVector safe) {
        return safe != null && (safe == current || safe == staged);
    }

    /** Writes {@code candidate} as the safe position if {@link #accepts} takes it. */
    public void offer(SetbackPosWithVector candidate, Write kind) {
        if (accepts(candidate, kind == Write.MOVEMENT)) write(candidate, kind);
    }

    /**
     * The one rule for what a setback may return a Java player to. A candidate must be a position of the entity a
     * setback moves now, so a vehicle the server seats the player on before the client rides it, or takes it off while
     * the client still rides it, never lets the other entity's position in. Then, by how it is proven:
     *
     * <ul>
     *   <li>A placement the server or Cult sent is taken at once: from then on the client is on its way there.
     *   <li>An answer is taken unless it answers a teleport older than the placement of the same entity the safe
     *       position holds. ClientPacketListener#handleMovePlayer handles teleports in order, so a client answering an
     *       older one is about to be moved by the newer one.
     *   <li>A movement is taken only once the client has answered every required teleport and has the vehicle
     *       placement it moves from, and never while offsets are blocked or the chunk is unproven.
     * </ul>
     *
     * For Java and Bedrock alike, a staged movement commits only if nothing replaced the safe position since it was
     * staged. Its prediction ran from the state before that placement or answer, so taking it would roll the
     * authoritative position back; the client's movements count again only once it moves from the new one.
     *
     * Bedrock keeps its own rules: a movement only while nothing blocks it; an answer only while no Cult setback is
     * pending.
     */
    public boolean accepts(SetbackPosWithVector candidate, boolean committing) {
        if (committing && current != stagedOver) return false;
        SafeProof proof = candidate.getProof();
        if (player.isBedrockMovement()) {
            return switch (proof.kind()) {
                case MOVEMENT -> corrections.safePositionUnblocked();
                case ANSWER -> !corrections.isPendingSetback();
                default -> true;
            };
        }
        if (proof.kind() == SafeProof.Kind.UNORDERED) return true;
        if (!candidate.getOwner().equals(corrections.setbackEntity())) return false;
        boolean placementOfOwner = current != null
                && current.getOwner().equals(candidate.getOwner())
                && current.getProof().kind() == SafeProof.Kind.PLACEMENT;
        return switch (proof.kind()) {
            case ANSWER ->
                !(placementOfOwner && proof.transaction() < current.getProof().transaction());
            case MOVEMENT ->
                corrections.safePositionUnblocked()
                        && !(placementOfOwner
                                && !current.getOwner().isPlayer()
                                && player.lastTransactionReceived.get()
                                        < current.getProof().transaction());
            default -> true;
        };
    }

    /** Writes {@code safe} without the rule: a clear, an observe-only release or a test fixture. */
    public void write(SetbackPosWithVector safe, Write kind) {
        current = safe;
    }

    /** Leaving the level or respawning discards the safe position and any staged one. */
    public void clear() {
        write(null, Write.CLEAR);
        staged = null;
    }

    /**
     * Scales the safe velocity a following setback sends. The elytra sprint-jump config rejects the sprint-jump boost
     * the safe tick took.
     */
    public void scaleVelocity(Vec3 factor) {
        current.setVector(current.getVector().multiply(factor));
    }

    /** Stages the safe position a movement reached until every check on that movement has run. */
    public void stage(SetbackPosWithVector safe) {
        // A packet that runs several prediction passes commits each before staging the next.
        commitStaged();
        staged = safe;
        stagedOver = current;
    }

    /** The vehicle runner resolves the root's post-tick ground after the prediction staged this safe position. */
    public void setStagedHorseOnGround(DesyncStatus onGround) {
        if (staged != null) staged.setHorseOnGround(onGround);
    }

    /**
     * Commits the safe position staged by this prediction pass once every listener on it has run. A Java movement
     * packet holds it until its receive handlers have run too.
     */
    public void commit() {
        if (!holding) commitStaged();
    }

    public void hold() {
        holding = true;
    }

    public void release() {
        holding = false;
        commitStaged();
    }

    private void commitStaged() {
        SetbackPosWithVector safe = staged;
        staged = null;
        // A check that ran after staging may have set back, or a placement replaced the safe position.
        if (safe != null) offer(safe, Write.MOVEMENT);
    }

    /** The client's movement for a tick arrived. Until a safe position includes it, a setback rejects it. */
    public void movementReceived() {
        atLatestMovement = current;
    }

    /**
     * Whether a setback now rejects a movement the safe position does not include. Its correction tick stands in for
     * that movement; with none rejected, the tick would come on top of the client's own.
     */
    public boolean rejectsLatestMovement() {
        return current != null && atLatestMovement == current;
    }
}
