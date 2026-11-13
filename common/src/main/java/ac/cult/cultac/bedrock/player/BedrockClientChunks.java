package ac.cult.cultac.bedrock.player;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * The chunk columns the Bedrock client holds, in world chunk coordinates.
 *
 * <p>The client keeps a column only while one of its views covers it, and drops it the moment none does; a column
 * it has not received inside them holds nothing. A chunk publisher update moves the publisher view to the circle of
 * columns its radius spans around its position. The spawn view, the circle 112 blocks around the level's default
 * spawn in the client's frame, stays where the level started. Before the first publisher update the views have no
 * edge and the client keeps every column it receives.
 *
 * <p>The client does not move its actor in a tick when the one column under the floor of the actor's position holds
 * nothing. It commits a received column on a worker thread, in the order it received the columns, and tells the
 * server nothing when it does: the tick the column holds its blocks from is known only to lie in a window after the
 * client answers the marker written behind it. The client's own moves narrow it. A tick it moves in a column commits
 * that column and every column it received before it; a tick it holds in one leaves that column and every column it
 * received after it uncommitted.
 */
public final class BedrockClientChunks {
    private static final long ANY_TICK = Long.MIN_VALUE;

    /**
     * The first and last tick after the one that answered the marker behind a column that the client was seen to
     * commit it by: clients moved in a column from one tick after that answer and held in one until three ticks after.
     */
    static final int EARLIEST_COMMIT = 1;

    static final int LATEST_COMMIT = 3;

    private static final int SPAWN_VIEW_RADIUS = 112;
    // The margin the client's views add to half their width, in chunks.
    private static final float VIEW_CIRCLE_MARGIN = 1.7320508F;

    /** What the client's chunk view does with its actor in a tick. */
    public enum Hold {
        /** The column under the actor holds nothing: the actor stays in place. */
        HELD,
        /** The column holds its blocks: the actor moves. */
        FREE,
        /** The client may or may not have committed the column yet. */
        EITHER
    }

    // Column -> first client tick its blocks may be there, last client tick they may still be missing, receive order.
    private final Long2LongOpenHashMap earliest = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap latest = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap order = new Long2LongOpenHashMap();
    private long received;
    private boolean bounded;
    private int minX, minZ, maxX, maxZ;
    private boolean spawnView;
    private int spawnX, spawnZ;

    /** The client processed a column in the tick after {@code acknowledgedAfterTick}; returns whether it was new. */
    public boolean receive(int chunkX, int chunkZ, long acknowledgedAfterTick) {
        return receive(BedrockCoordinateFrame.IDENTITY, chunkX, chunkZ, acknowledgedAfterTick);
    }

    /**
     * The client processed a column, in world chunk coordinates, while its frame was {@code frame}, in the tick after
     * {@code acknowledgedAfterTick}; returns whether it was new. A column no view covers is dropped as it commits.
     */
    public boolean receive(BedrockCoordinateFrame frame, int chunkX, int chunkZ, long acknowledgedAfterTick) {
        if (bounded && !viewed(frame, chunkX, chunkZ)) return false;
        long key = key(chunkX, chunkZ);
        if (earliest.containsKey(key)) return false;
        boolean any = acknowledgedAfterTick == ANY_TICK;
        earliest.put(key, any ? ANY_TICK : acknowledgedAfterTick + EARLIEST_COMMIT);
        latest.put(key, any ? ANY_TICK : acknowledgedAfterTick + LATEST_COMMIT - 1);
        order.put(key, received++);
        return true;
    }

    /** The client moved its publisher view to a publisher update's position and radius, in world blocks. */
    public void publish(int blockX, int blockZ, int radius) {
        publish(BedrockCoordinateFrame.IDENTITY, blockX, blockZ, radius);
    }

    /**
     * The client moved its publisher view to a publisher update's position and radius, in world blocks, while its
     * frame was {@code frame}; it drops every column no view covers any more.
     */
    public void publish(BedrockCoordinateFrame frame, int blockX, int blockZ, int radius) {
        bounded = true;
        minX = (blockX - radius) >> 4;
        maxX = (blockX + radius) >> 4;
        minZ = (blockZ - radius) >> 4;
        maxZ = (blockZ + radius) >> 4;
        earliest.keySet().removeIf(key -> {
            int x = (int) (key >> 32), z = (int) key;
            boolean outside = !viewed(frame, x, z);
            if (outside) {
                latest.remove(key);
                order.remove(key);
            }
            return outside;
        });
    }

    /** The level started with its default spawn at the given block of the client's frame. */
    public void spawn(int localBlockX, int localBlockZ) {
        spawnView = true;
        spawnX = localBlockX;
        spawnZ = localBlockZ;
    }

    /** Whether a view of the client covers a column, in world chunk coordinates, while its frame is {@code frame}. */
    private boolean viewed(BedrockCoordinateFrame frame, int chunkX, int chunkZ) {
        if (circle(chunkX, chunkZ, minX, maxX, minZ, maxZ)) return true;
        if (!spawnView) return false;
        return circle(
                chunkX - (frame.originX() >> 4),
                chunkZ - (frame.originZ() >> 4),
                (spawnX - SPAWN_VIEW_RADIUS) >> 4,
                (spawnX + SPAWN_VIEW_RADIUS) >> 4,
                (spawnZ - SPAWN_VIEW_RADIUS) >> 4,
                (spawnZ + SPAWN_VIEW_RADIUS) >> 4);
    }

    /**
     * Whether a column lies in the circle a view keeps of the square of columns it spans: the client measures from the
     * square's middle in float and keeps what lies strictly within half its width and the margin.
     */
    private static boolean circle(int chunkX, int chunkZ, int minX, int maxX, int minZ, int maxZ) {
        if (chunkX < minX || chunkX > maxX || chunkZ < minZ || chunkZ > maxZ) return false;
        float dx = chunkX - (minX + maxX) * 0.5F;
        float dz = chunkZ - (minZ + maxZ) * 0.5F;
        float reach = (maxX - minX + 1) / 2.0F + VIEW_CIRCLE_MARGIN;
        return dx * dx + dz * dz < reach * reach;
    }

    /** The client entered a new level, which holds none of the earlier columns. */
    public void clear() {
        earliest.clear();
        latest.clear();
        order.clear();
        bounded = false;
    }

    /** What the chunk view does in the given client tick with an actor at the given world position in {@code frame}. */
    public Hold hold(BedrockCoordinateFrame frame, double x, double z, long tick) {
        if (!bounded) return Hold.FREE;
        long key = column(frame, x, z);
        if (!earliest.containsKey(key) || tick < earliest.get(key)) return Hold.HELD;
        return tick > latest.get(key) ? Hold.FREE : Hold.EITHER;
    }

    /** The client moved an actor at the given world position in the given tick. */
    public void moved(BedrockCoordinateFrame frame, double x, double z, long tick) {
        long key = column(frame, x, z);
        if (!order.containsKey(key)) return;
        long at = order.get(key);
        for (var entry : order.long2LongEntrySet()) {
            if (entry.getLongValue() <= at) {
                long column = entry.getLongKey();
                latest.put(column, Math.min(latest.get(column), tick - 1));
                earliest.put(column, Math.min(earliest.get(column), tick));
            }
        }
    }

    /** The client held an actor at the given world position in the given tick. */
    public void held(BedrockCoordinateFrame frame, double x, double z, long tick) {
        long key = column(frame, x, z);
        if (!order.containsKey(key)) return;
        long at = order.get(key);
        for (var entry : order.long2LongEntrySet()) {
            if (entry.getLongValue() >= at) {
                long column = entry.getLongKey();
                if (earliest.get(column) != ANY_TICK) earliest.put(column, Math.max(earliest.get(column), tick + 1));
            }
        }
    }

    /** Records a column written to the client, held from any tick; returns whether its view lacked it. */
    public boolean written(int chunkX, int chunkZ) {
        return written(BedrockCoordinateFrame.IDENTITY, chunkX, chunkZ);
    }

    /** Records a column written to the client in {@code frame}, held from any tick; returns whether its view lacked it. */
    public boolean written(BedrockCoordinateFrame frame, int chunkX, int chunkZ) {
        return receive(frame, chunkX, chunkZ, ANY_TICK);
    }

    /** The same view, with every column held from any tick. */
    public BedrockClientChunks copy() {
        var copy = new BedrockClientChunks();
        copy.replace(this);
        return copy;
    }

    /** Takes another view's area and columns, each held from any tick. */
    public void replace(BedrockClientChunks other) {
        clear();
        for (long key : other.order.keySet()) {
            earliest.put(key, ANY_TICK);
            latest.put(key, ANY_TICK);
            order.put(key, other.order.get(key));
        }
        received = other.received;
        bounded = other.bounded;
        spawnView = other.spawnView;
        spawnX = other.spawnX;
        spawnZ = other.spawnZ;
        minX = other.minX;
        minZ = other.minZ;
        maxX = other.maxX;
        maxZ = other.maxZ;
    }

    /** The column under the floor of the client's float position, in world chunk coordinates. */
    private static long column(BedrockCoordinateFrame frame, double x, double z) {
        return key(
                ((int) Math.floor(frame.localX(x)) >> 4) + (frame.originX() >> 4),
                ((int) Math.floor(frame.localZ(z)) >> 4) + (frame.originZ() >> 4));
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
