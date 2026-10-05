package ac.cult.cultac.utils.data;

import org.jetbrains.annotations.Contract;

public record KnownInput(boolean forward, boolean backward, boolean left, boolean right,
                         boolean jump, boolean shift, boolean sprint) {
    public static final KnownInput DEFAULT = new KnownInput(false, false, false, false, false, false, false);

    @Contract(pure = true)
    public boolean moving() {
        return forward || backward || left || right || jump;
    }

    @Contract(pure = true)
    public float strafeImpulse() {
        return calculateImpulse(left, right);
    }

    @Contract(pure = true)
    public float forwardImpulse() {
        return calculateImpulse(forward, backward);
    }

    private static float calculateImpulse(boolean positive, boolean negative) {
        if (positive == negative) {
            return 0.0F;
        }
        return positive ? 1.0F : -1.0F;
    }
}
