package ac.cult.cultac.bridge.protocol;

/** Grim API listener families present on a backend; each owns one bit of the listener bitset. */
public enum ListenerKind {
    FLAG(0),
    COMMAND_EXECUTE(1),
    SETBACK(2),
    RELOAD(3);

    private final int bit;

    ListenerKind(int bit) {
        this.bit = bit;
    }

    public int flag() {
        return 1 << bit;
    }

    public static boolean has(int mask, ListenerKind kind) {
        return (mask & kind.flag()) != 0;
    }
}
