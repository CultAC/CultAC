package ac.grim.grimac.utils.blockplace;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import org.bukkit.event.Event;

import java.util.concurrent.atomic.LongAdder;
import java.lang.reflect.Modifier;

public final class SmoketestPredictionSafety {
    private static final boolean ENABLED = Boolean.getBoolean("grim.validation.smoketestControl");
    private static final ThreadLocal<Phase> PHASE = new ThreadLocal<>();
    private static final LongAdder ACTIONS = new LongAdder();
    private static final LongAdder EVENTS = new LongAdder();
    private static final LongAdder VIOLATIONS = new LongAdder();
    private static final LongAdder ADAPTERS = new LongAdder();

    private SmoketestPredictionSafety() {
    }

    public static Scope enter(GrimPlayer player, String action) {
        if (!ENABLED) return Scope.NO_OP;
        if (PHASE.get() != null) return violation("nested-prediction", action);
        PHASE.set(new Phase(player.getName(), action, Thread.currentThread().getName()));
        ACTIONS.increment();
        return Scope.ACTIVE;
    }

    public static void event(Event event) {
        if (!ENABLED || PHASE.get() == null) return;
        EVENTS.increment();
        violation("bukkit-event", event.getEventName());
    }

    public static void forbiddenAccess(String capability) {
        if (ENABLED && PHASE.get() != null) violation("forbidden-access", capability);
    }

    public static void detachedAdapter(Object adapter) {
        if (!ENABLED || PHASE.get() == null) return;
        ADAPTERS.increment();
        for (Class<?> type = adapter.getClass(); type != null; type = type.getSuperclass()) {
            for (var field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(adapter);
                    if (value != null && forbiddenRuntimeType(value.getClass())) {
                        violation("live-reference", type.getName() + "#" + field.getName() + ":" + value.getClass().getName());
                    }
                } catch (ReflectiveOperationException exception) {
                    violation("adapter-audit", type.getName() + "#" + field.getName());
                }
            }
        }
    }

    private static boolean forbiddenRuntimeType(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            String name = current.getName();
            if (name.equals("net.minecraft.server.level.ServerLevel")
                    || name.equals("net.minecraft.server.level.ServerChunkCache")
                    || name.equals("net.minecraft.world.entity.Entity")
                    || name.equals("net.minecraft.world.level.block.entity.BlockEntity")
                    || name.equals("org.bukkit.craftbukkit.CraftWorld")
                    || name.equals("org.bukkit.craftbukkit.CraftServer")
                    || name.equals("org.bukkit.craftbukkit.entity.CraftPlayer")) return true;
        }
        return false;
    }

    public static void reset() {
        ACTIONS.reset();
        EVENTS.reset();
        VIOLATIONS.reset();
        ADAPTERS.reset();
    }

    public static void report(String label, String player) {
        if (!ENABLED) return;
        LogUtil.info("Placement safety report: label=" + safe(label)
                + " player=" + safe(player)
                + " actions=" + ACTIONS.sum()
                + " events=" + EVENTS.sum()
                + " violations=" + VIOLATIONS.sum()
                + " agent=" + Boolean.getBoolean("grim.validation.predictionSafetyAgentActive")
                + " adapters=" + ADAPTERS.sum());
    }

    private static <T> T violation(String kind, String detail) {
        VIOLATIONS.increment();
        Phase phase = PHASE.get();
        String context = phase == null ? "player=- action=- thread=" + Thread.currentThread().getName()
                : "player=" + safe(phase.player()) + " action=" + safe(phase.action()) + " thread=" + safe(phase.thread());
        String message = "Placement safety violation: kind=" + kind + " detail=" + safe(detail) + " " + context;
        LogUtil.error(message);
        throw new IllegalStateException(message);
    }

    private static String safe(String value) {
        return value == null ? "-" : value.replace(' ', '_');
    }

    private static void leave() {
        PHASE.remove();
    }

    private record Phase(String player, String action, String thread) {
    }

    public enum Scope implements AutoCloseable {
        ACTIVE,
        NO_OP;

        @Override
        public void close() {
            if (this == ACTIVE) leave();
        }
    }
}
