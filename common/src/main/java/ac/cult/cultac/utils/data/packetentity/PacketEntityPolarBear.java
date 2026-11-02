package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.player.CultPlayer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PacketEntityPolarBear extends PacketEntity {
    private final CultPlayer player;
    // Each DATA_STANDING_ID the client may still handle, oldest first; the first is the one it has handled.
    private final List<Boolean> targets = new ArrayList<>(List.of(false));
    // The sequence number of targets' first entry.
    private int firstTarget;
    /**
     * Each clientSideStandAnimation the client may hold, packed as {@code box * 7 + animation} with the one its
     * bounding box was last made at, and the earliest sequence number of a target it may move toward.
     */
    private Map<Integer, Integer> states = new HashMap<>(Map.of(0, 0));
    // The largest clientSideStandAnimation the bounding box may have been made at.
    private int standAnimation;
    private boolean atRest = true;

    public PacketEntityPolarBear(CultPlayer player, int entityId, int type, double x, double y, double z) {
        super(player, entityId, type, x, y, z);
        this.player = player;
    }

    /** DATA_STANDING_ID, which the client has handled once it answers provenAt. */
    public void onStanding(boolean standing, int provenAt) {
        int target = firstTarget + targets.size();
        targets.add(standing);
        atRest = false;
        player.latencyUtils.addRealTimeTask(provenAt, () -> handled(target));
    }

    private void handled(int target) {
        if (target <= firstTarget) return;
        // A state still moving toward an earlier target has not stepped since the client handled this one.
        Map<Integer, Integer> handled = new HashMap<>();
        states.forEach((state, from) -> handled.merge(state, Math.max(from, target), Math::min));
        states = handled;
        targets.subList(0, target - firstTarget).clear();
        firstTarget = target;
        updateRest();
    }

    private void updateRest() {
        int end = targets.getFirst() ? 6 : 0;
        atRest = targets.size() == 1 && states.size() == 1 && states.containsKey(end * 7 + end);
    }

    /** The largest clientSideStandAnimation PolarBear#getDefaultDimensions may have sized the bounding box at. */
    public int standAnimation() {
        return standAnimation;
    }

    @Override
    public void onMovement(CultPlayer player) {
        if (!player.isBedrockMovement()) tickStand();
        super.onMovement(player);
    }

    /**
     * One client tick of PolarBear#tick, which ClientLevel#tickEntities skips while TickRateManager#isEntityFrozen:
     * it refreshes the dimensions at the clientSideStandAnimation the last tick stepped to, then steps it toward
     * DATA_STANDING_ID, or toward any later one the client handles before the step.
     */
    private void tickStand() {
        if (atRest || !player.compensatedEntities.ticksInClientTick(this)) return;
        Map<Integer, Integer> next = new HashMap<>();
        List<Map.Entry<Integer, Integer>> latestFirst = new ArrayList<>(states.entrySet());
        latestFirst.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));
        Map<Boolean, Integer> earliest = new HashMap<>();
        int sequence = firstTarget + targets.size();
        for (Map.Entry<Integer, Integer> state : latestFirst) {
            while (sequence > state.getValue()) {
                sequence--;
                earliest.put(targets.get(sequence - firstTarget), sequence);
            }
            int animation = state.getKey() % 7;
            earliest.forEach((standing, from) -> {
                int stepped = standing ? Math.min(animation + 1, 6) : Math.max(animation - 1, 0);
                next.merge(animation * 7 + stepped, from, Math::min);
            });
        }
        states = next;
        updateRest();
        int box = 0;
        for (int state : states.keySet()) box = Math.max(box, state / 7);
        if (box == standAnimation) return;
        standAnimation = box;
        refreshDimensions(player);
    }
}
