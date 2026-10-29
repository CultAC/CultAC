package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ReachInterpolationData;
import java.util.List;

/**
 * An untracked living rider's interpolation at its push point, without committing its tracked position. Only
 * active packet paths are replayed; ordinary remote riding trees still have no passenger tick or position pass.
 */
final class ClientRiderInterpolation {
    private JavaPistonEntityStates.Snapshot state;
    private JavaPistonEntityStates.Snapshot preview;
    private List<SimpleCollisionBox> pushBoxes;
    private int previewTick = Integer.MIN_VALUE;
    private boolean running;

    ClientRiderInterpolation(PacketEntity entity, SimpleCollisionBox physical) {
        state = entity.capturePistonPhysics(true);
        run(entity, () -> entity.setRiderReplayPosition(physical));
    }

    boolean running() {
        return running;
    }

    boolean active() {
        return state.location().hasActiveInterpolationTarget()
                || state.oldLocation() != null && state.oldLocation().hasActiveInterpolationTarget();
    }

    boolean applies(CultPlayer player) {
        return active() || preview != null && previewTick == player.packetStateData.acceptedClientTick;
    }

    void resumeTracked(PacketEntity entity) {
        entity.restorePistonPhysics(state);
    }

    /** Existing packet handlers and proof handling operate on the detached state, preserving their ordering. */
    void run(PacketEntity entity, Runnable operation) {
        var tracked = entity.capturePistonPhysics(false);
        running = true;
        try {
            entity.restorePistonPhysics(state);
            operation.run();
            state = entity.capturePistonPhysics(false);
            preview = null;
            pushBoxes = null;
        } finally {
            entity.restorePistonPhysics(tracked);
            running = false;
        }
    }

    List<SimpleCollisionBox> pushBoxes(CultPlayer player, PacketEntity entity) {
        preview(player, entity);
        return pushBoxes;
    }

    private void preview(CultPlayer player, PacketEntity entity) {
        int tick = player.packetStateData.acceptedClientTick;
        if (preview != null && previewTick == tick) return;
        var tracked = entity.capturePistonPhysics(false);
        running = true;
        long phase = player.compensatedEntities.passTickedThroughOrder();
        try {
            player.compensatedEntities.setPassTickedThroughOrder(entity.tickingOrder(player));
            entity.restorePistonPhysics(state);
            // Prediction can read this tick repeatedly. Only tick-end adopts these copies.
            entity.oldPacketLocation =
                    state.oldLocation() == null ? null : state.oldLocation().copy();
            entity.newPacketLocation = state.location().copy();
            entity.replayRiderCommonTick(player);
            preview = entity.capturePistonPhysics(false);
            pushBoxes = entity.getPossibleMovementCollisionBoxCandidates();
            previewTick = tick;
        } finally {
            entity.restorePistonPhysics(tracked);
            player.compensatedEntities.setPassTickedThroughOrder(phase);
            running = false;
        }
    }

    /** Entity#rideTick reseats only after commonTick and pushEntities; keep the preview for deferred predictions. */
    void finishTick(CultPlayer player, PacketEntity entity, SimpleCollisionBox seat) {
        preview(player, entity);
        state = preview;
        var tracked = entity.capturePistonPhysics(false);
        running = true;
        try {
            entity.restorePistonPhysics(state);
            entity.setRiderReplayPosition(seat);
            state = entity.capturePistonPhysics(false);
        } finally {
            entity.restorePistonPhysics(tracked);
            running = false;
        }
    }

    ReachInterpolationData path(boolean old) {
        return old ? state.oldLocation() : state.location();
    }

    /** The detached block-entity replay already applied recordMovement to each interpolation path. */
    void afterPass(
            PacketEntity entity, SimpleCollisionBox box, ReachInterpolationData path, ReachInterpolationData oldPath) {
        // Do not invalidate this tick's push preview: the block entities ran after that push.
        var tracked = entity.capturePistonPhysics(false);
        running = true;
        try {
            entity.restorePistonPhysics(state);
            if (path != null) entity.newPacketLocation = path;
            if (oldPath != null) entity.oldPacketLocation = oldPath;
            entity.setRiderReplayPosition(box);
            state = entity.capturePistonPhysics(false);
        } finally {
            entity.restorePistonPhysics(tracked);
            running = false;
        }
    }
}
