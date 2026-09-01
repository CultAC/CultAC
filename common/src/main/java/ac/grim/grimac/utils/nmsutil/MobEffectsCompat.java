package ac.grim.grimac.utils.nmsutil;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;

import java.lang.reflect.Field;

/**
 * Cross-version access to vanilla mob effect constants. 26.2 renamed several
 * {@link net.minecraft.world.effect.MobEffects} fields (for example SPEED was
 * MOVEMENT_SPEED), so resolve them by name to keep one jar working on both
 * sides of the rename.
 */
public final class MobEffectsCompat {
    public static final Holder<MobEffect> SPEED = holder("SPEED", "MOVEMENT_SPEED");
    public static final Holder<MobEffect> SLOWNESS = holder("SLOWNESS", "MOVEMENT_SLOWDOWN");
    public static final Holder<MobEffect> HASTE = holder("HASTE", "DIG_SPEED");
    public static final Holder<MobEffect> MINING_FATIGUE = holder("MINING_FATIGUE", "DIG_SLOWDOWN");
    public static final Holder<MobEffect> BLINDNESS = holder("BLINDNESS");
    public static final Holder<MobEffect> CONDUIT_POWER = holder("CONDUIT_POWER");
    public static final Holder<MobEffect> DOLPHINS_GRACE = holder("DOLPHINS_GRACE");
    public static final Holder<MobEffect> JUMP_BOOST = holder("JUMP_BOOST", "JUMP");
    public static final Holder<MobEffect> LEVITATION = holder("LEVITATION");
    public static final Holder<MobEffect> SLOW_FALLING = holder("SLOW_FALLING");
    public static final Holder<MobEffect> WEAVING = holder("WEAVING");

    private MobEffectsCompat() {
    }

    @SuppressWarnings("unchecked")
    private static Holder<MobEffect> holder(String... names) {
        for (String name : names) {
            try {
                Field field = net.minecraft.world.effect.MobEffects.class.getField(name);
                return (Holder<MobEffect>) field.get(null);
            } catch (NoSuchFieldException | IllegalAccessException ignored) {
                // Try the name used by the other supported server generation.
            }
        }
        return null;
    }
}
