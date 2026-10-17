package ac.cult.cultac.cloud.capture;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.utils.anticheat.LogUtil;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Intake protocol 5's terrain for one player: the sections of its simulation world within the chunk radius (columns,
 * Chebyshev) and the section radius (indices above and below, clamped to the dimension) of the player's section, and
 * the held set, the sections sent and not yet gone.
 *
 * <p>A start (JOIN, or a world the client was sent) empties the held set and sends every section in range whose column
 * is loaded. A move to another chunk or section index sends those in range not held, nearest first, and lets go, as
 * {@code section_gone}, every held one more than one column or index beyond either radius. A column that loads sends its
 * sections in range again, held or not, and its held ones; one that unloads lets its held ones go. A block change in a
 * held section goes as a {@code block} record.
 *
 * <p>Nothing sent is taken for delivered until its frame is: a section or block record that never reached the service
 * sends its section again while it is held, and a {@code section_gone} that never did goes again while its section is
 * not held. Sections go out within the frame's room for terrain, the rest at later checks, so every frame keeps room for
 * the rest of the stream and each check makes progress; a section too large for any frame is counted and left unsent.
 * Past {@link #MAX_GONE} owed {@code section_gone}s the receiver's world starts over with a WORLD record. Only touched
 * on the player's event loop, which owns its simulation world.
 */
public final class Sections {
    /** The player's simulation world, read on the thread that owns it. */
    public interface Terrain {
        /** The player's section, {@link #pack}ed. */
        long position();

        /** The lowest section index of the player's dimension. */
        int minSection();

        /** The highest section index of the player's dimension. */
        int maxSection();

        /** A section's block states as the server's protocol writes them; null while its column is not loaded. */
        byte[] blocks(int x, int y, int z);
    }

    /** Where records go. */
    interface Out {
        /** Puts a record of {@code section} into the open frame; false when the frame dropped it. */
        boolean put(int kind, byte[] body, long section, long nanos);

        /**
         * Bytes the open frame has left for section records: half the frame, or all its room while it holds none, so
         * one section as large as a frame always fits a frame of its own.
         */
        long room();

        /** The most bytes one section record may take in any frame. */
        long maxRoom();

        /** The socket is backed up: no section is checked or sent until it is not. */
        boolean backlogged();

        /** The player's dimension starts over in the open frame: a WORLD record, which empties the receiver's world. */
        boolean startOver(long nanos);
    }

    /** {@code section_gone}s owed at most; past it the receiver's world starts over instead. */
    public static final int MAX_GONE = 4096;
    /** Sections too large for any frame, never sent, since the process started. */
    public static final AtomicLong tooLarge = new AtomicLong();
    /** Times a player's owed {@code section_gone}s passed {@link #MAX_GONE} and its world started over. */
    public static final AtomicLong startedOver = new AtomicLong();

    private static final AtomicBoolean TOO_LARGE_LOGGED = new AtomicBoolean();

    /** No section: the position checked before any. */
    static final long NONE = Long.MIN_VALUE;
    /** No section record is smaller: one at the origin with no blocks. */
    private static final int MIN_SECTION_BYTES = IntakeWire.section(0, 0, 0, new byte[0]).length;

    // The results of sending one section.
    private static final int SENT = 0;
    private static final int UNLOADED = 1;
    /** The frame has no room left, or dropped the record: the rest waits for a later check. */
    private static final int FULL = 2;

    private final LongOpenHashSet held = new LongOpenHashSet();
    /** Held sections whose last record may not have reached the service, or whose column loaded again. */
    private final LongLinkedOpenHashSet resend = new LongLinkedOpenHashSet();
    /** Sections not held whose {@code section_gone} has not reached the service. */
    private final LongLinkedOpenHashSet gone = new LongLinkedOpenHashSet();
    /** Sections too large for any frame, never sent while in range or until their column loads again. */
    private final LongOpenHashSet unsendable = new LongOpenHashSet();
    /** A WORLD record is owed ahead of every other terrain record. */
    private boolean worldOwed;
    /** Times this player's world started over. */
    private int restarts;

    private Terrain terrain;
    private int chunkRadius;
    private int sectionRadius;
    /** The player's section at the last check; {@link #NONE} to check at the next one whatever the position. */
    private long checkedAt = NONE;
    /** Every held and unsendable section is within one column of the radii around it; {@link #NONE} while none is. */
    private long prunedAt = NONE;

    /** Streams {@code terrain} within these radii; nothing is sent until {@link #restart}. */
    void open(Terrain terrain, int chunkRadius, int sectionRadius) {
        this.terrain = terrain;
        this.chunkRadius = chunkRadius;
        this.sectionRadius = sectionRadius;
        forget();
    }

    void close() {
        terrain = null;
        forget();
    }

    boolean active() {
        return terrain != null;
    }

    /** Sections sent and not yet gone. */
    int held() {
        return held.size();
    }

    boolean holds(int x, int y, int z) {
        return held.contains(pack(x, y, z));
    }

    /** Sections whose {@code section_gone} is still to reach the service. */
    int goneToSend() {
        return gone.size();
    }

    /** Times this player's owed {@code section_gone}s passed {@link #MAX_GONE}. */
    int restarts() {
        return restarts;
    }

    /** The held set empties without a record: the receiver starts over, and the next check sends what is in range. */
    void forget() {
        held.clear();
        resend.clear();
        gone.clear();
        unsendable.clear();
        worldOwed = false;
        checkedAt = NONE;
        prunedAt = NONE;
    }

    /** The held set empties and every section in range whose column is loaded is sent; false as {@link #check}. */
    boolean restart(Out out, long nanos) {
        forget();
        return check(out, nanos);
    }

    /**
     * After a move to another section, or while sections are owed: far ones go, owed records go again, and those in
     * range not held are sent, as far as the frame has room. False when the frame ran out of room first.
     */
    boolean check(Out out, long nanos) {
        if (terrain == null || out.backlogged()) return true;
        long at = terrain.position();
        if (at == checkedAt) return true;
        checkedAt = at;
        for (LongIterator sections = held.iterator(); sections.hasNext(); ) {
            long section = sections.nextLong();
            if (!beyond(section, at)) continue;
            sections.remove();
            resend.remove(section);
            gone.add(section);
        }
        for (LongIterator sections = unsendable.iterator(); sections.hasNext(); ) {
            if (beyond(sections.nextLong(), at)) sections.remove();
        }
        if (gone.size() > MAX_GONE) startOver();
        prunedAt = at;
        if (!flush(out, nanos)) return false;
        int low = Math.max(terrain.minSection(), y(at) - sectionRadius);
        int high = Math.min(terrain.maxSection(), y(at) + sectionRadius);
        for (int ring = 0; ring <= chunkRadius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = x(at) + dx;
                    int z = z(at) + dz;
                    for (int y = low; y <= high; y++) {
                        long section = pack(x, y, z);
                        if (held.contains(section) || unsendable.contains(section)) continue;
                        int sent = send(x, y, z, out, nanos);
                        if (sent == FULL) return false;
                        if (sent == UNLOADED) break;
                    }
                }
            }
        }
        return true;
    }

    /**
     * A column the simulation (re)loaded: its sections in range and its held ones go again, now unless {@code wait}
     * (the open frame has no room for terrain) or the socket is backed up, else at a later check. False when the frame
     * ran out of room.
     */
    boolean columnLoaded(int x, int z, Out out, long nanos, boolean wait) {
        if (terrain == null) return true;
        long at = terrain.position();
        boolean near = Math.max(Math.abs(x - x(at)), Math.abs(z - z(at))) <= chunkRadius;
        boolean holding = holdsColumn(x, z);
        for (int y = terrain.minSection(); y <= terrain.maxSection(); y++) {
            if (holding) {
                long section = pack(x, y, z);
                unsendable.remove(section);
                if (held.contains(section)) {
                    resend.add(section);
                    continue;
                }
            }
            if (near && Math.abs(y - y(at)) <= sectionRadius) checkedAt = NONE;
        }
        if (deferred(out, wait)) return true;
        if (!flush(out, nanos)) return false;
        return checkedAt != NONE || check(out, nanos);
    }

    /** A column the simulation unloaded: its held sections go, when and as {@link #columnLoaded} sends. */
    boolean columnUnloaded(int x, int z, Out out, long nanos, boolean wait) {
        if (terrain == null || held.isEmpty()) return true;
        if (holdsColumn(x, z)) {
            for (int y = terrain.minSection(); y <= terrain.maxSection(); y++) {
                long section = pack(x, y, z);
                unsendable.remove(section);
                if (!held.remove(section)) continue;
                resend.remove(section);
                gone.add(section);
            }
        }
        if (gone.size() > MAX_GONE) startOver();
        if (deferred(out, wait)) return true;
        return flush(out, nanos);
    }

    /** Whether owed records wait for a later check; the next check then flushes them. */
    private boolean deferred(Out out, boolean wait) {
        if (!wait && !out.backlogged()) return false;
        if (worldOwed || !gone.isEmpty() || !resend.isEmpty()) checkedAt = NONE;
        return true;
    }

    /** The simulation set the block at {@code x}, {@code y}, {@code z} to {@code state}; sent when its section is held. */
    void block(int x, int y, int z, int state, Out out, long nanos) {
        long section = pack(x >> 4, y >> 4, z >> 4);
        if (!held.contains(section) || resend.contains(section)) return;
        if (!out.put(IntakeWire.BLOCK, IntakeWire.block(x, y, z, state), section, nanos)) {
            resend.add(section);
            checkedAt = NONE;
        }
    }

    /** A record of {@code kind} for {@code section} never reached the service: it is owed again. */
    void lost(int kind, long section) {
        if (kind == IntakeWire.WORLD) {
            forget();
            worldOwed = true;
        } else if (kind == IntakeWire.SECTION_GONE) {
            if (!held.contains(section) && !worldOwed) gone.add(section);
            if (gone.size() > MAX_GONE) startOver();
        } else if (held.contains(section)) {
            resend.add(section);
        }
        checkedAt = NONE;
    }

    /** Owed records go out, every {@code section_gone} then sections again; false when some wait. */
    private boolean flush(Out out, long nanos) {
        if (worldOwed) {
            if (!out.startOver(nanos)) {
                checkedAt = NONE;
                return false;
            }
            worldOwed = false;
        }
        for (LongIterator owed = gone.iterator(); owed.hasNext(); ) {
            long section = owed.nextLong();
            byte[] body = IntakeWire.sectionGone(x(section), y(section), z(section));
            if (!out.put(IntakeWire.SECTION_GONE, body, section, nanos)) {
                checkedAt = NONE;
                return false;
            }
            owed.remove();
        }
        if (resend.isEmpty()) return true;
        for (long section : resend.toLongArray()) {
            int sent = send(x(section), y(section), z(section), out, nanos);
            if (sent == FULL) return false;
            if (sent == UNLOADED) resend.remove(section);
        }
        return true;
    }

    /** Sends a section and holds it. */
    private int send(int x, int y, int z, Out out, long nanos) {
        if (out.room() < MIN_SECTION_BYTES) {
            checkedAt = NONE;
            return FULL;
        }
        byte[] blocks = terrain.blocks(x, y, z);
        if (blocks == null) return UNLOADED;
        long section = pack(x, y, z);
        byte[] body = IntakeWire.section(x, y, z, blocks);
        if (body.length > out.maxRoom()) {
            tooLarge(section, body.length);
            return SENT;
        }
        if (body.length > out.room() || !out.put(IntakeWire.SECTION, body, section, nanos)) {
            checkedAt = NONE;
            return FULL;
        }
        held.add(section);
        resend.remove(section);
        gone.remove(section);
        return SENT;
    }

    /** A section no frame can carry: counted, left unsent until it leaves range or its column loads again. */
    private void tooLarge(long section, int bytes) {
        held.remove(section);
        resend.remove(section);
        unsendable.add(section);
        tooLarge.incrementAndGet();
        if (CultAPI.INSTANCE.getLoader() != null && !TOO_LARGE_LOGGED.getAndSet(true)) {
            LogUtil.warn("Cloud section " + x(section) + ", " + y(section) + ", " + z(section) + " is " + bytes
                    + " bytes, more than any frame takes; such sections are not sent");
        }
    }

    /** Owed {@code section_gone}s passed {@link #MAX_GONE}: the receiver's world starts over and is sent again. */
    private void startOver() {
        forget();
        worldOwed = true;
        restarts++;
        startedOver.incrementAndGet();
    }

    /** Whether the column at {@code x}, {@code z} can hold a held or unsendable section. */
    private boolean holdsColumn(int x, int z) {
        return prunedAt != NONE && Math.max(Math.abs(x - x(prunedAt)), Math.abs(z - z(prunedAt))) <= chunkRadius + 1;
    }

    /** Whether a held section is more than one column or index beyond the radii around {@code at}. */
    private boolean beyond(long section, long at) {
        return Math.max(Math.abs(x(section) - x(at)), Math.abs(z(section) - z(at))) > chunkRadius + 1
                || Math.abs(y(section) - y(at)) > sectionRadius + 1;
    }

    /** A section's column x and z (22 bits each) and index (20 bits) in one long. */
    public static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFF) << 42 | ((long) z & 0x3FFFFF) << 20 | (long) y & 0xFFFFF;
    }

    static int x(long section) {
        return (int) (section >> 42);
    }

    static int y(long section) {
        return (int) (section << 44 >> 44);
    }

    static int z(long section) {
        return (int) (section << 22 >> 42);
    }
}
