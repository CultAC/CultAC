package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ReachInterpolationData;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity#commonTick's interpolation at a movement reader's place in ClientLevel#tickEntities. Packet/interaction
 * readers keep the committed entity. A replay can read either side of an already committed interpolation step.
 */
final class ClientTickPositions {
    private int tickedAt = Integer.MIN_VALUE;
    private List<SimpleCollisionBox> before;
    private long orderAtTickStart;
    private long previewEpoch = Long.MIN_VALUE;
    private List<SimpleCollisionBox> preview;

    void beforeTick(PacketEntity entity, CultPlayer player, boolean changing) {
        before = changing ? entity.getPossibleMovementCollisionBoxCandidates() : null;
        orderAtTickStart = player.compensatedEntities.clientTickOrder(entity);
        tickedAt = player.packetStateData.acceptedClientTick;
        preview = null;
    }

    boolean tickedThisTick(CultPlayer player) {
        return tickedAt == player.packetStateData.acceptedClientTick;
    }

    boolean tickedBefore(long order) {
        return orderAtTickStart <= order;
    }

    List<SimpleCollisionBox> read(PacketEntity entity, CultPlayer player, boolean after, long epoch) {
        if (tickedAt == player.packetStateData.acceptedClientTick) return after ? null : before;
        if (!after || entity.interpolatedBeforeLocalTravel(player)) return null;
        if (preview == null || previewEpoch != epoch) {
            preview = new ArrayList<>();
            append(entity, player, entity.capturePistonPhysics(false), preview);
            var other = entity.otherPistonPhysics();
            if (other != null) append(entity, player, other, preview);
            previewEpoch = epoch;
        }
        return preview;
    }

    private static void append(
            PacketEntity entity,
            CultPlayer player,
            JavaPistonEntityStates.Snapshot state,
            List<SimpleCollisionBox> boxes) {
        ReachInterpolationData next = state.location().copy();
        entity.carryInterpolationTargetForPreview(player, next, state);
        SimpleCollisionBox exact = null;
        if (state.oldLocation() == null && next.interpolatesFromPhysicalAfterClientTick() && state.exact()) {
            var at = state.physicalPosition();
            exact = next.getExactMovementLocationAfterClientTickFromPhysical(
                    GetBoundingBox.getBoundingBoxFromPosAndSize(
                            at.x,
                            at.y,
                            at.z,
                            BoundingBoxSize.getWidth(player, entity),
                            BoundingBoxSize.getHeight(player, entity)));
        } else if (next.hasExactMovementLocationAfterClientTick()) {
            exact = next.exactMovementLocationAfterClientTick(new SimpleCollisionBox());
        }
        next.tickMovement(state.oldLocation() == null);
        if (exact != null) next.setExactMovementLocation(exact);
        if (state.oldLocation() != null) {
            ReachInterpolationData old = state.oldLocation().copyAdvancedForClientTick(true);
            next.updatePossibleStartingLocation(old.getPossibleMovementLocationCombined());
            boxes.addAll(old.getPossibleMovementLocations());
        }
        boxes.addAll(next.getPossibleMovementLocations());
    }
}
