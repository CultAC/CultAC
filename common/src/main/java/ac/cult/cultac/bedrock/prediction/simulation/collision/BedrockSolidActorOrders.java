package ac.cult.cultac.bedrock.prediction.simulation.collision;

import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.world.BlockCollision;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import java.util.ArrayList;
import java.util.List;

/**
 * The client meets the solid actors of a move before its blocks, in an order of its own that Cult cannot know. Within
 * a phase, each actor the move meets bounds it along one axis, from below or from above: the face across a gap stops
 * it, and an overlap within the limit moves it out through the nearest face. Bounds of one axis commute unless a lower
 * one lies above an upper one, which only an overlap can give: then the actor met last decides the axis. An axis the
 * move's first phase crosses with one actor reaching across from each side ends at one of two ends whatever the
 * order: the tick takes the collected order's move and one more per such axis put the other way round, the actors
 * bounding it from the deciding side at the move's start met last, those bounding it from the other side first, and
 * every other shape in its place. At most two axes take part (X, then Z, then Y), so a tick has at most
 * {@link #MAX_ORDERS} actor orders. Every other crossed axis may end anywhere between its two ends, which the move carries as
 * a range; any other tick has its one move in the collected order.
 *
 * <p>An order is an int: 0 when the actors' order decides no axis; otherwise {@code ARRANGED | axes | winners << 3},
 * with a bit per axis (X 1, Y 2, Z 4) and a winner bit set when the actors bounding the axis from below are met last,
 * and {@code COLLECTED} on the tick's first move, which keeps the order the actors were collected in. Any other axis
 * the actors' order decides is a range of the move's end (see {@link BedrockActorOrderCrossing}).
 *
 * <p>The existing moving-actor position budget may also change contact onset or release on each axis. Bits 8..10
 * name those axes, 11..13 the selected changes, 14..16 the nominal buffered-actor contacts and 17..19 the changes
 * realized by the normal collision pass. Their eight combinations multiply the actor orders.
 *
 * <p>An onset can also carry the move over the actor instead of stopping it: an actor the move steps onto. Bits
 * 20..22 name the selected onsets a step realized that way. Where a step carried the nominal move over an actor that
 * ended its sweep along an axis, the same budget may show that actor inside the box instead, which moves the box out
 * before the step: bits 23..25 name those axes and 26..28 the selected ones, which multiply the moves again, to at
 * most {@link #MAX_VARIANTS} current-tick moves. Only the selected movement state is retained for the following
 * tick.
 *
 * <p>A sneaking player's moving actor may also hold its move back from an edge elsewhere (see
 * {@link BedrockSneakEdgeMovement#shownElsewhere}). Bits 29..30 count those places in the collected order; each is one
 * more move of the tick, which names its place, from 1, in the same bits and takes no other change.
 */
public final class BedrockSolidActorOrders {
    public static final int MAX_ORDERS = 4;
    public static final int MAX_VARIANTS = MAX_ORDERS * 8 * 8 + BedrockSneakEdgeMovement.MAX_SHOWN_ELSEWHERE;
    private static final int ARRANGED = 1 << 6;
    private static final int COLLECTED = 1 << 7;
    private static final int[] AXIS_PRIORITY = {0, 2, 1};

    private BedrockSolidActorOrders() {}

    /** The axes, at most two, the tick decides for a move whose actors' bounds crossed along {@code axes}. */
    static int decidedAxes(int axes) {
        int decided = 0;
        int count = 0;
        for (int axis : AXIS_PRIORITY) {
            if ((axes & 1 << axis) != 0 && count < 2) {
                decided |= 1 << axis;
                count++;
            }
        }
        return decided;
    }

    /** How many moves a tick whose move decides {@code axes} takes: one per way each axis can end. */
    public static int count(int axes) {
        return 1 << Integer.bitCount(axes);
    }

    /** The collected order of a move deciding {@code axes}, along which {@code belowWon} met lower bounds last. */
    static int collected(int axes, int belowWon) {
        return COLLECTED | ARRANGED | axes | (belowWon & axes) << 3;
    }

    /**
     * The {@code index}th variant, from 1 below {@link #variants(int)}, of the tick whose collected order is {@code
     * collected}: the first index bits flip decided actor orders, followed by contact-position states and by the
     * actors shown inside the box.
     */
    public static int order(int collected, int index) {
        int others = count(axes(collected)) * count(positionAxes(collected)) * count(overlapAxes(collected));
        if (index >= others) return ARRANGED | index - others + 1 << 29;
        int axes = axes(collected);
        int flipped = 0;
        int bit = 0;
        for (int axis : AXIS_PRIORITY) {
            if ((axes & 1 << axis) == 0) continue;
            if ((index >> bit++ & 1) != 0) flipped |= 1 << axis;
        }
        int positions = positionAxes(collected);
        int positionChanges = 0;
        for (int axis : AXIS_PRIORITY) {
            if ((positions & 1 << axis) != 0 && (index >> bit++ & 1) != 0) positionChanges |= 1 << axis;
        }
        int overlaps = overlapAxes(collected);
        int overlapChanges = 0;
        for (int axis : AXIS_PRIORITY) {
            if ((overlaps & 1 << axis) != 0 && (index >> bit++ & 1) != 0) overlapChanges |= 1 << axis;
        }
        return ARRANGED
                | axes
                | ((collected >> 3 & 7) ^ flipped) << 3
                | (positions << 8)
                | (positionChanges << 11)
                | (collected & (7 << 14))
                | (overlaps << 23)
                | (overlapChanges << 26);
    }

    /** The same actor-position budget can decide contact onset or release on each axis. */
    static int withPositionStates(int order, int axes, int nominalContacts) {
        if (axes == 0) return order;
        return order | ARRANGED | COLLECTED | axes << 8 | nominalContacts << 14;
    }

    /**
     * Along {@code axes} a step carried the move over an actor that the same budget may show inside the box, among
     * the {@code nominalContacts}.
     */
    static int withOverlapStates(int order, int axes, int nominalContacts) {
        if (axes == 0) return order;
        return order | ARRANGED | COLLECTED | axes << 23 | nominalContacts << 14;
    }

    static int overlapAxes(int order) {
        return order >> 23 & 7;
    }

    static int overlapChanges(int order) {
        return order >> 26 & 7;
    }

    /** The selected onsets along {@code stepped} carried the move over their actor instead of stopping it. */
    static int withSteppedPositions(int order, int stepped) {
        return order | stepped << 17 | stepped << 20;
    }

    /** The collected order of a tick whose sneaking player's edge may lie at {@code places} other places. */
    static int withSneakEdges(int order, int places) {
        return places == 0 ? order : order | ARRANGED | COLLECTED | places << 29;
    }

    /** In a collected order, how many other places the edge may lie at; in any other, which of them, from 1. */
    static int sneakEdge(int order) {
        return order >> 29 & 3;
    }

    static int positionAxes(int order) {
        return order >> 8 & 7;
    }

    static int positionChanges(int order) {
        return order >> 11 & 7;
    }

    static int nominalContacts(int order) {
        return order >> 14 & 7;
    }

    public static int variants(int order) {
        return count(axes(order)) * count(positionAxes(order)) * count(overlapAxes(order)) + sneakEdge(order);
    }

    /**
     * A requested contact-state change must survive the ordinary sweep, its blocks and any step, unless the step is
     * what an onset gave the move; an actor shown inside the box must leave the move collided along its axis. One
     * axis takes one change.
     */
    public static boolean realizesPositionState(int order, int contacts) {
        int changed = positionChanges(order);
        int overlaps = overlapChanges(order);
        if ((changed & overlaps) != 0 || (contacts & overlaps) != overlaps) return false;
        if ((order >> 17 & changed) != changed) return false;
        int stopped = changed & ~(order >> 20);
        return ((contacts ^ nominalContacts(order)) & stopped) == stopped;
    }

    static int withRealizedPositions(int order, int realized) {
        return order | realized << 17;
    }

    /** Whether {@code order} is the collected order of a tick its actors' order decides. */
    public static boolean isCollected(int order) {
        return (order & COLLECTED) != 0;
    }

    /** The axes an order decides; 0 when the actors' order decides none. */
    public static int axes(int order) {
        return order & 7;
    }

    /**
     * {@code obstacles} in the given order: the blocks as collected, then the actors bounding a decided axis from its
     * deciding side, the others as collected, and last the actors bounding it from the other side. The move meets
     * them in reverse.
     */
    static List<BlockCollision> arrange(
            List<BlockCollision> obstacles,
            int order,
            WorldCollisionBox start,
            BedrockCoordinateFrame frame,
            BedrockDepenetrationLimit limit) {
        List<BlockCollision> arranged = new ArrayList<>(obstacles.size());
        List<BlockCollision> undecided = new ArrayList<>();
        List<BlockCollision> losing = new ArrayList<>();
        for (BlockCollision obstacle : obstacles) {
            if (!BedrockCollisionClipper.isSolidActor(obstacle)) {
                arranged.add(obstacle);
                continue;
            }
            int bound = BedrockCollisionClipper.bound(obstacle.box(), start, frame, limit);
            int axis = bound >> 1;
            if (bound < 0 || (order & 1 << axis) == 0) {
                undecided.add(obstacle);
            } else if ((bound & 1) == (order >> 3 + axis & 1)) {
                // Placed first among the actors, so met last.
                arranged.add(obstacle);
            } else {
                losing.add(obstacle);
            }
        }
        arranged.addAll(undecided);
        arranged.addAll(losing);
        return arranged;
    }
}
