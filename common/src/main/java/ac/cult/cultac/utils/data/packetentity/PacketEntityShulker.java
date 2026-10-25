package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.VanillaMath;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PacketEntityShulker extends PacketEntity {
    public Direction facing = Direction.DOWN;

    private final CultPlayer player;
    // Each DATA_PEEK_ID * 0.01F the client may still handle, oldest first; the first is the one it has handled.
    private final List<Float> targets = new ArrayList<>(List.of(0.0F));
    // The sequence number of targets' first entry.
    private int firstTarget;
    /**
     * Each currentPeekAmount the client may hold, with the earliest sequence number of a target it may move toward:
     * a later one with the same amount reaches nothing the earliest cannot, as each step may branch to any later
     * target.
     */
    private Map<Float, Integer> peeks = new HashMap<>(Map.of(0.0F, 0));
    // Whether the last client tick's Shulker#updatePeekAmount step may have grown Shulker#getPhysicalPeek.
    private boolean lastStepGrew;
    // The largest Shulker#getPhysicalPeek of peeks, of their next step, and whether that step may grow one.
    private float physicalPeek;
    private float nextPhysicalPeek;
    private boolean nextStepGrows;
    private boolean atRest = true;

    public PacketEntityShulker(CultPlayer player, int entityId, int type, double x, double y, double z) {
        super(player, entityId, type, x, y, z);
        this.player = player;
    }

    // Shulker#sanitizeScale.
    @Override
    protected float sanitizeScale(float scale) {
        return Math.min(scale, 3.0F);
    }

    /** DATA_PEEK_ID, which the client has handled once it answers provenAt. */
    public void onPeek(int peek, int provenAt) {
        int target = firstTarget + targets.size();
        targets.add(peek * 0.01F);
        update();
        player.latencyUtils.addRealTimeTask(provenAt, () -> handled(target));
    }

    private void handled(int target) {
        if (target <= firstTarget) return;
        // A peek still moving toward an earlier target has not stepped since the client handled this one.
        Map<Float, Integer> handled = new HashMap<>();
        peeks.forEach((amount, from) -> handled.merge(amount, Math.max(from, target), Math::min));
        peeks = handled;
        targets.subList(0, target - firstTarget).clear();
        firstTarget = target;
        update();
    }

    /**
     * One client tick of Shulker#updatePeekAmount, which ClientLevel#tickEntities skips while
     * TickRateManager#isEntityFrozen.
     */
    public void tickPeek() {
        if (!ticks()) {
            lastStepGrew = false;
            return;
        }
        lastStepGrew = nextStepGrows;
        peeks = next();
        update();
    }

    /** Whether the bounding box may change, or a step that pushes may show in the next move. */
    public boolean isAnimating() {
        return !atRest || lastStepGrew;
    }

    /**
     * Whether Shulker#onPeekAmountChange may push entities in the next move: a step grows Shulker#getPhysicalPeek
     * at the client tick before it or the one it is part of, as ClientLevel#tickEntities ticks the shulker before or
     * after the player.
     */
    public boolean mayPush() {
        return lastStepGrew || nextStepGrows && ticks();
    }

    /**
     * Shulker#makeBoundingBox in the next move: {@code box}, the closed box, grown away from the attach face by
     * Shulker#getPhysicalPeek times the scale (Shulker#getProgressAabb), up to the largest physical peek the shulker
     * may hold before or after its step in that client tick.
     */
    public SimpleCollisionBox boundingBox(SimpleCollisionBox box) {
        double grow = (double) (ticks() ? Math.max(physicalPeek, nextPhysicalPeek) : physicalPeek) * scale;
        Direction direction = facing.getOppositeFace();
        return box.expandToCoordinate(
                direction.getModX() * grow, direction.getModY() * grow, direction.getModZ() * grow);
    }

    /**
     * Whether ClientLevel#tickEntities ticks the shulker in the client tick in progress: TickRateManager#isEntityFrozen
     * spares a vehicle that carries a player, and a passenger ticks with its root vehicle.
     */
    private boolean ticks() {
        if (player.compensatedWorld.clientRunsNormally()) return true;
        PacketEntity root = this;
        while (root.riding != null) root = root.riding;
        return carriesPlayer(root);
    }

    private static boolean carriesPlayer(PacketEntity entity) {
        for (PacketEntity passenger : entity.passengers) {
            if (passenger instanceof PacketEntityPlayer
                    || passenger instanceof PacketEntitySelf
                    || carriesPlayer(passenger)) return true;
        }
        return false;
    }

    // Each peek steps toward its target, or toward any later one the client handles before the step.
    private Map<Float, Integer> next() {
        Map<Float, Integer> next = new HashMap<>();
        int end = firstTarget + targets.size();
        peeks.forEach((amount, from) -> {
            for (int target = from; target < end; target++) {
                next.merge(step(amount, target(target)), target, Math::min);
            }
        });
        return next;
    }

    private void update() {
        int end = firstTarget + targets.size();
        physicalPeek = 0.0F;
        nextPhysicalPeek = 0.0F;
        nextStepGrows = false;
        atRest = peeks.size() == 1;
        for (Map.Entry<Float, Integer> peek : peeks.entrySet()) {
            float amount = peek.getKey();
            float current = physicalPeek(amount);
            physicalPeek = Math.max(physicalPeek, current);
            for (int target = peek.getValue(); target < end; target++) {
                float next = physicalPeek(step(amount, target(target)));
                nextPhysicalPeek = Math.max(nextPhysicalPeek, next);
                nextStepGrows |= next > current;
                atRest &= target(target) == amount;
            }
        }
    }

    private float target(int target) {
        return targets.get(target - firstTarget);
    }

    // Shulker#updatePeekAmount.
    private static float step(float current, float target) {
        if (current == target) return current;
        return current > target
                ? CultMath.clamp(current - 0.05F, target, 1.0F)
                : CultMath.clamp(current + 0.05F, 0.0F, target);
    }

    // Shulker#getPhysicalPeek.
    private float physicalPeek(float peekAmount) {
        return 0.5F - VanillaMath.sin(player.getClientVersion(), (0.5F + peekAmount) * (float) Math.PI) * 0.5F;
    }
}
