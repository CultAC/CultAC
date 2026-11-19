package ac.cult.cultac.bedrock.player;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.function.LongPredicate;

/**
 * The lid of each shulker box for a Bedrock player. A handled block event sets where the lid is heading; its
 * openness then steps 0.05 towards that at the end of each client tick and stays there. The lid shoves nothing: it
 * grows the box's collision box by {@link #lift(float)}.
 */
public final class BedrockShulkerBoxLids {
    /** Client ticks from closed to fully open. */
    public static final int OPEN_STEPS = 20;

    private static final float STEP = 0.05F;

    private final Long2ObjectMap<Lid> lids = new Long2ObjectOpenHashMap<>();

    /** A handled block event at {@code position}, whose data opens or closes the lid. */
    public void blockEvent(long position, boolean open) {
        Lid lid = lids.get(position);
        if (lid == null) {
            if (!open) return;
            lids.put(position, new Lid(true));
        } else {
            lid.opening = open;
        }
    }

    /** One client tick. {@code shulkerBoxAt} tells whether a position still holds a shulker box. */
    public void tick(LongPredicate shulkerBoxAt) {
        if (lids.isEmpty()) return;
        var iterator = lids.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Lid lid = entry.getValue();
            if (!shulkerBoxAt.test(entry.getLongKey())) {
                iterator.remove();
                continue;
            }
            lid.steps = lid.opening ? Math.min(lid.steps + 1, OPEN_STEPS) : lid.steps - 1;
            if (lid.steps <= 0) iterator.remove();
        }
    }

    /** How far open the lid at {@code position} is, from 0 to 1. */
    public float openness(long position) {
        Lid lid = lids.get(position);
        return lid == null ? 0.0F : lid.steps * STEP;
    }

    /** How far the lid at {@code position} extends the box's collision box. */
    public float lift(long position) {
        Lid lid = lids.get(position);
        return lid == null ? 0.0F : lift(lid.steps * STEP);
    }

    /** How far a lid that is {@code openness} open extends the box's collision box. */
    public static float lift(float openness) {
        float closed = 1.0F - openness;
        return 0.5F * (1.0F - closed * closed * closed);
    }

    public void clear() {
        lids.clear();
    }

    private static final class Lid {
        private int steps;
        private boolean opening;

        private Lid(boolean opening) {
            this.opening = opening;
        }
    }
}
