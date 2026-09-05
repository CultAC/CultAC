package ac.cult.cultac.checks.impl.prediction;

import java.util.Arrays;
import java.util.List;

public enum DesyncStatus {
    TRUE(true),
    FALSE(false),
    UNKNOWN(false, true);

    private final List<Boolean> states;

    DesyncStatus(Boolean... states) {
        this.states = Arrays.asList(states);
    }

    public static DesyncStatus fromBoolean(boolean b) {
        return b ? TRUE : FALSE;
    }

    public DesyncStatus addBoolean(boolean b) {
        if (this == UNKNOWN) return UNKNOWN;
        if (this == TRUE && b) return TRUE;
        if (this == FALSE && !b) return FALSE;
        return UNKNOWN;
    }

    public DesyncStatus addBoolean(DesyncStatus b) {
        if (this == UNKNOWN || b == UNKNOWN) return UNKNOWN;
        if (this == TRUE && b == TRUE) return TRUE;
        if (this == FALSE && b == FALSE) return FALSE;
        return UNKNOWN;
    }

    public static DesyncStatus fromBooleans(boolean b1, boolean b2) {
        if (b1 == b2) {
            return fromBoolean(b1);
        } else {
            return UNKNOWN;
        }
    }

    public boolean is(boolean b) {
        return this == fromBoolean(b);
    }

    public boolean determineOptimistically() {
        return this != FALSE;
    }

    public boolean determinePessimistically() {
        return this == TRUE;
    }

    public boolean isDesync() {
        return this == UNKNOWN;
    }

    public List<Boolean> getStates() {
        return states;
    }
}
