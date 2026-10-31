package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ReachInterpolationData;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * Entity#commonTick's interpolation at a movement reader's place in ClientLevel#tickEntities. Packet/interaction
 * readers keep the committed entity. A replay can read either side of an already committed interpolation step.
 */
final class ClientTickPositions {
    private int tickedAt = Integer.MIN_VALUE;
    // The position boxes held before this tick's step, rewritten in place each tick; beforeCount is -1 when the
    // entity kept still. Only beforeView reads them, and only inside PacketEntity.
    private SimpleCollisionBox[] beforeBoxes = new SimpleCollisionBox[0];
    private int beforeCount = -1;
    private final List<SimpleCollisionBox> beforeView = new BeforeView();
    // This tick's copy of the before boxes for list readers, which may keep it.
    private List<SimpleCollisionBox> beforeShared;
    private long orderAtTickStart;
    private long previewEpoch = Long.MIN_VALUE;
    private List<SimpleCollisionBox> preview;

    void beforeTick(PacketEntity entity, CultPlayer player, boolean changing) {
        // Position boxes, like the preview's: readers open entity-specific geometry from them once. With the tick
        // positions unread, these are the getPositionCollisionBoxCandidates of every entity that ticks here.
        beforeCount = -1;
        beforeShared = null;
        if (changing) {
            int count = entity.possibleMovementCollisionBoxCandidateCount();
            if (beforeBoxes.length < count) {
                int filled = beforeBoxes.length;
                beforeBoxes = Arrays.copyOf(beforeBoxes, count);
                for (int i = filled; i < count; i++) beforeBoxes[i] = new SimpleCollisionBox();
            }
            for (int i = 0; i < count; i++) entity.positionCollisionBoxCandidate(i, beforeBoxes[i]);
            beforeCount = count;
        }
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

    /**
     * The position boxes held before this tick's step, or null when the entity has not ticked yet or kept still. The
     * view is rewritten by the next tick: read it, never keep it.
     */
    List<SimpleCollisionBox> beforeThisTick(CultPlayer player) {
        return tickedAt == player.packetStateData.acceptedClientTick ? before() : null;
    }

    /** {@link #read}'s boxes, as a list a reader outside PacketEntity may keep. */
    List<SimpleCollisionBox> shareable(List<SimpleCollisionBox> view) {
        if (view != beforeView) return view;
        if (beforeShared == null) {
            var boxes = new ArrayList<SimpleCollisionBox>(beforeCount);
            for (int i = 0; i < beforeCount; i++) boxes.add(beforeBoxes[i].copy());
            beforeShared = boxes;
        }
        return beforeShared;
    }

    /** The boxes on the {@code after} side of this entity's step; a before view must not be kept, see shareable. */
    List<SimpleCollisionBox> read(PacketEntity entity, CultPlayer player, boolean after, long epoch) {
        if (tickedAt == player.packetStateData.acceptedClientTick) return after ? null : before();
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

    private List<SimpleCollisionBox> before() {
        return beforeCount < 0 ? null : beforeView;
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
            var at = next.exactPositionAfterClientTickFromPhysical(state.physicalPosition());
            exact = GetBoundingBox.getBoundingBoxFromPosAndSize(
                    at.x, at.y, at.z,
                    BoundingBoxSize.getWidth(player, entity), BoundingBoxSize.getHeight(player, entity));
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

    /** A read-only view of the before boxes. */
    private final class BeforeView extends AbstractList<SimpleCollisionBox> implements RandomAccess {
        @Override
        public SimpleCollisionBox get(int index) {
            return beforeBoxes[Objects.checkIndex(index, size())];
        }

        @Override
        public int size() {
            return Math.max(beforeCount, 0);
        }
    }
}
