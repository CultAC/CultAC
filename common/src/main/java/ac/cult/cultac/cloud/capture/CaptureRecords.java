package ac.cult.cultac.cloud.capture;

import java.util.Arrays;

/**
 * A frame's packets as tapped, by position, held in columns and reusable after {@link #clear()}. Each has the dimension
 * and session the client was in when it crossed; a clientbound one its server tick as tapped (-1 when unknown) and a
 * ping its {@code PingKind} (0 for any other packet). A protocol 5 terrain record's {@code id}, or its own WORLD's, is
 * its index among its frame's terrain records. An {@code unstripped} one was tapped before JOIN without its protocol's
 * codec: it goes out only on a socket that said {@code textStripped} false.
 */
final class CaptureRecords {
    static final CaptureRecords EMPTY = new CaptureRecords(0);

    private int size;
    private int[] kinds;
    private long[] nanos;
    private int[] ids;
    private byte[][] bytes;
    private int[] minYs;
    private int[] heights;
    private CaptureStream.Session[] sessions;
    private long[] serverTicks;
    private int[] pingKinds;
    private boolean[] unstripped;

    CaptureRecords(int capacity) {
        kinds = new int[capacity];
        nanos = new long[capacity];
        ids = new int[capacity];
        bytes = new byte[capacity][];
        minYs = new int[capacity];
        heights = new int[capacity];
        sessions = new CaptureStream.Session[capacity];
        serverTicks = new long[capacity];
        pingKinds = new int[capacity];
        unstripped = new boolean[capacity];
    }

    void add(
            int kind,
            long nanos,
            int id,
            byte[] bytes,
            int minY,
            int height,
            CaptureStream.Session session,
            long serverTick,
            int pingKind,
            boolean unstripped) {
        if (size == kinds.length) grow();
        kinds[size] = kind;
        this.nanos[size] = nanos;
        ids[size] = id;
        this.bytes[size] = bytes;
        minYs[size] = minY;
        heights[size] = height;
        sessions[size] = session;
        serverTicks[size] = serverTick;
        pingKinds[size] = pingKind;
        this.unstripped[size] = unstripped;
        size++;
    }

    private void grow() {
        int capacity = Math.max(16, kinds.length * 2);
        kinds = Arrays.copyOf(kinds, capacity);
        nanos = Arrays.copyOf(nanos, capacity);
        ids = Arrays.copyOf(ids, capacity);
        bytes = Arrays.copyOf(bytes, capacity);
        minYs = Arrays.copyOf(minYs, capacity);
        heights = Arrays.copyOf(heights, capacity);
        sessions = Arrays.copyOf(sessions, capacity);
        serverTicks = Arrays.copyOf(serverTicks, capacity);
        pingKinds = Arrays.copyOf(pingKinds, capacity);
        unstripped = Arrays.copyOf(unstripped, capacity);
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    int capacity() {
        return kinds.length;
    }

    int kind(int index) {
        return kinds[index];
    }

    long nanos(int index) {
        return nanos[index];
    }

    int id(int index) {
        return ids[index];
    }

    byte[] bytes(int index) {
        return bytes[index];
    }

    int minY(int index) {
        return minYs[index];
    }

    int height(int index) {
        return heights[index];
    }

    CaptureStream.Session session(int index) {
        return sessions[index];
    }

    long serverTick(int index) {
        return serverTicks[index];
    }

    int pingKind(int index) {
        return pingKinds[index];
    }

    boolean unstripped(int index) {
        return unstripped[index];
    }

    void serverTick(int index, long tick) {
        serverTicks[index] = tick;
    }

    /** Empties it, letting go of every record's bytes and session. */
    void clear() {
        Arrays.fill(bytes, 0, size, null);
        Arrays.fill(sessions, 0, size, null);
        size = 0;
    }
}
