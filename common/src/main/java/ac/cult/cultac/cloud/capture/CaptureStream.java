package ac.cult.cultac.cloud.capture;

import ac.cult.cultac.cloud.intake.v3.Outcome;
import ac.cult.cultac.cloud.transport.CloudConnection;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.network.FrameCopies;
import com.google.protobuf.CodedOutputStream;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One player's capture as it goes to the service: packets gathered into frames, a frame cut at each of the client's
 * tick ends, each frame paired with CultAC's verdict on that tick, then filtered and written out. Only touched on the
 * player's event loop.
 *
 * <p>Frames and tick ends are paired by number, never by position: both count the same {@code client_tick_end}s from
 * the same start, the tap as it cuts and CultAC as it handles them. A frame whose tick end never brings a verdict goes
 * out marked missing; whole frames that are dropped are counted, and the next frame out says how many with a GAP.
 *
 * <p>On intake protocol 5 record clocks go out in microseconds, each the difference of two clocks rounded to the
 * microsecond, so rounding never drifts. A ping whose body is its id alone goes out as a ping marker, and a pong as a
 * pong marker when it answers the oldest pending ping: the pings sent as markers in frames the socket took and not yet
 * answered, by intake.proto's rules, which the service keeps the same way. Which marker a probe becomes is decided as
 * its frame is written, and the pending pings change only once the socket takes the frame.
 *
 * <p>On intake protocol 5 chunk and block-change packets never go out: the terrain is the player's simulation world,
 * its sections in range sent as {@link Sections} decides, into the open frame. Before JOIN they are not kept either.
 */
public final class CaptureStream {
    /** Where records go, and the server's shared budget for every frame retained until it is sent or dropped. */
    public interface Sink {
        /** Queues a record; false when it was dropped. */
        boolean send(byte[] record);

        /** Takes {@code bytes} from the shared budget for retained frames. */
        boolean hold(long bytes);

        void release(long bytes);

        /** Bytes the shared budget for retained frames has left. */
        default long holdRoom() {
            return Long.MAX_VALUE;
        }

        /** The largest frame record the socket takes. */
        int maxFrameBytes();

        /** The terms the socket was opened on. */
        default CloudConnection.Terms terms() {
            return CloudConnection.Terms.NONE;
        }

        /** Whether the socket's connect said {@code textStripped}: false lets packets out unstripped without a codec. */
        default boolean textStripped() {
            return true;
        }

        /** As {@link #send(byte[])}, for a frame its probe markers grow by {@code resolvedExtra} once resolved. */
        default boolean send(byte[] record, long resolvedExtra) {
            return send(record);
        }

        /** The socket's queue is backed up: sections wait until it drains. */
        default boolean backlogged() {
            return false;
        }
    }

    /** The session facts CultAC last sent the client; {@code world} when a login or respawn set them. */
    public record Session(Integer entityId, Integer gameMode, String dimension, boolean world) {}

    /** Bytes one open frame may gather; past it the frame is dropped whole. */
    static final long MAX_OPEN_FRAME_BYTES = 8L * 1024 * 1024;
    /** What each held record costs the open-frame cap beyond its bytes, so many tiny packets cannot slip past it. */
    static final int RECORD_OVERHEAD_BYTES = 64;
    /** Frames waiting for CultAC's verdict; past it the oldest is dropped. */
    static final int MAX_AWAITING = 40;
    /** Bytes retained per player, including packet overhead and verdict headroom. */
    static final long MAX_PRE_JOIN_BYTES = 16L * 1024 * 1024;
    /** Tick ends retained before the first JOIN; at 20 ticks/s this covers ten seconds. */
    static final int MAX_PRE_JOIN_FRAMES = 200;
    /** Actions the open frame names; past it they are all lost and the count starts over. */
    static final int MAX_OPEN_ACTIONS = 256;

    /** Play packet ids are below 256; a CAPTURE id past this names no packet. */
    static final int MAX_PACKET_ID = 1023;
    /** Ids a CAPTURE list is read for; the rest are ignored. */
    static final int MAX_POLICY_IDS = 1024;

    /** Outcomes one VERDICT carries at most, and their bytes with each one's envelope. */
    static final int MAX_OUTCOMES = 32;

    static final int MAX_OUTCOME_BYTES = 1024;
    /** Outcomes waiting to go out; past it new ones are dropped. */
    static final int MAX_QUEUED_OUTCOMES = 256;
    /** Frames an outcome waits for its action's frame to go out before it goes without the action's index. */
    static final int MAX_OUTCOME_WAIT = 100;
    /** Room each cut frame keeps for its VERDICT: the largest one {@link IntakeWire.VerdictWriter} writes. */
    static final int VERDICT_BYTES = maxVerdictBytes();
    /** Outcomes sent and dropped, across every player. */
    public static final AtomicLong outcomesSent = new AtomicLong();

    public static final AtomicLong outcomesDropped = new AtomicLong();
    // The intake's per-record limits.
    public static final int MAX_SERVERBOUND_BYTES = 64 * 1024;
    private static final int MAX_CLIENTBOUND_BYTES = 2 * 1024 * 1024;
    private static final int MAX_META_BYTES = 4096;
    /** Room kept in a frame for its header and two records' envelopes; a VERDICT's own bytes add to it. */
    private static final int FRAME_HEADROOM = 128;
    /** Pings whose ordinal a VELOCITY can still name. */
    private static final int PING_HISTORY = 256;
    /** A new frame's record list is sized for the last frame's records, within these bounds. */
    private static final int MIN_FRAME_RECORDS = 16;

    private static final int MAX_PRESIZED_RECORDS = 4096;
    /** Room a section record leaves in a frame for its header, verdict and envelopes. */
    static final int RELEASE_HEADROOM = FRAME_HEADROOM + 1024;

    private static final List<TerrainRecord> NO_TERRAIN = List.of();
    private static final List<ActionRef> NO_ACTIONS = List.of();
    private static final byte[] NO_BODY = new byte[0];
    /** A ping too short to carry a wire id: it takes an ordinal, and no VELOCITY can name it. */
    private static final long NO_WIRE_ID = Long.MIN_VALUE;

    /**
     * A packet as tapped, with the dimension and session the client was in when it crossed; a clientbound one with its
     * server tick as tapped (-1 when unknown) and a ping with its {@code PingKind} (0 for any other packet). A protocol 5
     * terrain record's {@code id}, or its own WORLD's, is its index among its frame's terrain records. An
     * {@code unstripped} one was tapped before JOIN without its protocol's codec: it goes out only on a socket that said
     * {@code textStripped} false.
     */
    private record Raw(
            int kind,
            long nanos,
            int id,
            byte[] bytes,
            int minY,
            int height,
            Session session,
            long serverTick,
            int pingKind,
            boolean unstripped) {
        Raw tickedAs(long tick, int kind) {
            return new Raw(this.kind, nanos, id, bytes, minY, height, session, tick, kind, unstripped);
        }
    }

    /** A protocol 5 terrain record in a frame, or a WORLD starting the terrain over: its kind and section. */
    private record TerrainRecord(int kind, long section) {}

    /** An outcome waiting to go out: the action it names (null for none) and the clock when it was seen. */
    private static final class Queued {
        final Outcome outcome;
        final ActionRef action;
        final long nanos;
        /** Frames sent while it waited for its action's frame. */
        int waited;

        Queued(Outcome outcome, ActionRef action, long nanos) {
            this.outcome = outcome;
            this.action = action;
            this.nanos = nanos;
        }
    }

    /**
     * A cut frame. {@code index} counts cuts from the tap's start, as CultAC counts tick ends; a {@code dropped} frame
     * holds nothing and only counts as lost. {@code lostBefore} is set once it leaves the awaiting queue.
     */
    private static final class Tick {
        final List<Raw> records;
        final long bytes;
        final long nanos;
        final long index;
        final boolean dropped;
        /** On protocol 5: its terrain records, by the index their record's id holds. */
        List<TerrainRecord> terrain = List.of();
        /** The terrain records it left out, by index; null while none. */
        BitSet omittedTerrain;
        /** The client was sent a world while it was open. */
        boolean world;
        /** The actions tapped into it, kept or not. */
        List<ActionRef> actions = NO_ACTIONS;

        boolean missing = true;
        long conditions;
        long handled;
        List<IntakeWire.Flag> flags = List.of();
        IntakeWire.Target target;
        List<IntakeWire.Geometry> geometries = List.of();
        IntakeWire.Engine engine;
        int lostBefore;
        long reserved;
        /** The arrays its records hold, until it is released. */
        CapturePacketCopies copies;

        Tick(List<Raw> records, long bytes, long nanos, long index, boolean dropped) {
            this.records = records;
            this.bytes = bytes;
            this.nanos = nanos;
            this.index = index;
            this.dropped = dropped;
        }
    }

    /**
     * Which ids to keep, by packet id; the service decides, per the client's protocol. Ids past {@link #MAX_PACKET_ID}
     * name no play packet and are ignored, as is every id past the first {@link #MAX_POLICY_IDS} of a list.
     */
    private record Policy(boolean[] serverbound, boolean[] clientbound, boolean[] dimension) {
        static boolean[] of(int[] ids) {
            int count = Math.min(ids.length, MAX_POLICY_IDS);
            int max = -1;
            for (int i = 0; i < count; i++) if (ids[i] <= MAX_PACKET_ID) max = Math.max(max, ids[i]);
            boolean[] allowed = new boolean[max + 1];
            for (int i = 0; i < count; i++) if (ids[i] >= 0 && ids[i] <= MAX_PACKET_ID) allowed[ids[i]] = true;
            return allowed;
        }

        static boolean has(boolean[] allowed, int id) {
            return id >= 0 && id < allowed.length && allowed[id];
        }
    }

    private final UUID player;
    private final IntakeWire.FrameBuilder builder = new IntakeWire.FrameBuilder();
    private final IntakeWire.VerdictWriter verdicts = new IntakeWire.VerdictWriter();

    private Sink sink;
    private boolean joined;
    private boolean preparing;
    private long firstNanos = Long.MIN_VALUE;
    private int preJoinFrames;
    private long frameReserved;
    /** The client was sent a world while the open frame was open. */
    private boolean frameWorld;
    /** A world the service never got: the next frame it takes starts with WORLD and META. */
    private boolean owedWorld;
    /** The client's dimension as the last clientbound packet tapped found it. */
    private int worldMinY;

    private int worldHeight;
    /** The open frame ran out of room for sections: they are not checked again until the next cut. */
    private boolean terrainFull;

    private Policy policy;
    private byte[] pendingJoin;
    private long base;
    private long lastNanos;
    /** The server tick of the last end-of-tick ping tapped; -1 before the first. */
    private long lastEndOfTick = -1;
    /** Tick ends the tap has cut since it started, joined or not. */
    private long cuts;
    /** {@link #cuts} at this capture's start; frame {@code seq}s count from here. */
    private long origin;

    private Session tapped;
    /** The clientbound ping's packet id, -1 while unknown. */
    private int pingPacket = -1;
    /** The serverbound pong's packet id, -1 while unknown. */
    private int pongPacket = -1;
    /** On protocol 5: the wire ids of the pings sent as markers and not yet answered, oldest first. */
    private IntArrayList pendingPings = new IntArrayList();
    /** The pending pings a frame being written would leave, swapped with {@link #pendingPings} once it is taken. */
    private IntArrayList spare = new IntArrayList();
    /** Pings the service has been sent since the JOIN; the last {@link #PING_HISTORY} wire ids, by ordinal. */
    private long pings;

    private final long[] pingIds = new long[PING_HISTORY];
    /** The engine state the service last got; null when it must be restated in full. */
    private IntakeWire.Engine lastEngine;
    /** Frames sent since the engine state was last restated in full. */
    private int sinceKeyframe;
    /** Whole frames dropped since the last one that left the awaiting queue. */
    private int lostFrames;
    /** Whole frames, sent ones included, the socket refused since the last one it took. */
    private int lostSending;

    private List<Raw> frame = new ArrayList<>();
    /** The open frame's packet copies; null until it copies one. */
    private CapturePacketCopies copies;
    /** Packet copies a released frame left, for the next frame to reuse. */
    private CapturePacketCopies spareCopies;

    private final CapturePacketCopies.Budget copyBudget = new CapturePacketCopies.Budget();
    /** The latest clock a copy or cut has seen, against which idle copy capacity is trimmed. */
    private long copyClock = Long.MIN_VALUE;
    /** Counts captures; an action belongs to the one it was tapped under. */
    private long generation;
    /** The open frame's position of the last serverbound packet the tap saw; -1 when the frame does not hold it. */
    private int lastServerbound = -1;
    /** The actions tapped into the open frame, kept or not. */
    private List<ActionRef> actions = NO_ACTIONS;

    private final ArrayDeque<Queued> outcomes = new ArrayDeque<>();
    /** The outcomes in the frame being sent; back to the queue if the socket refuses it. */
    private List<Queued> sending = List.of();
    /** {@link #serverTicks}'s result, reused across sends; only the indices one send writes are read. */
    private long[] serverTickScratch = new long[MIN_FRAME_RECORDS];
    /** {@link #pings}'s result, reused across sends; only as many ids as it counted are read. */
    private long[] framePings = new long[4];

    private long frameBytes;
    private boolean overflow;
    private final ArrayDeque<Tick> awaiting = new ArrayDeque<>();
    private final ArrayDeque<Tick> held = new ArrayDeque<>();
    /** What this frame's probe markers add once resolved; only touched while sending. */
    private long resolvedExtra;

    private long heldBytes;

    /** The client protocol's chunk packets; null until set. */
    private ChunkIds chunkIds;

    /** The player's simulation world, which protocol 5 sends its terrain from; null until set. */
    private Sections.Terrain terrain;
    /** What takes player-written text out of packets before they are kept; null until set. */
    private PlayerText text;

    private final PlayerText.TeamNames teams = new PlayerText.TeamNames();

    private final Sections sections = new Sections();
    /** The open frame's terrain records, in the order put; each record's id is its index here. */
    private List<TerrainRecord> framedTerrain = NO_TERRAIN;
    /** Bytes of the open frame's section records. */
    private long framedTerrainBytes;

    private final Sections.Out terrainOut = new Sections.Out() {
        @Override
        public boolean put(int kind, byte[] body, long section, long nanos) {
            return putTerrain(kind, body, section, nanos);
        }

        @Override
        public long room() {
            long room = CaptureStream.this.room();
            return framedTerrainBytes == 0
                    ? room
                    : Math.min((long) sink.maxFrameBytes() / 2 - framedTerrainBytes, room);
        }

        @Override
        public long maxRoom() {
            return Math.min(MAX_CLIENTBOUND_BYTES, (long) sink.maxFrameBytes() - RELEASE_HEADROOM);
        }

        @Override
        public boolean backlogged() {
            return sink.backlogged();
        }

        @Override
        public boolean startOver(long nanos) {
            int minY = terrain.minSection() * 16;
            int height = (terrain.maxSection() - terrain.minSection() + 1) * 16;
            return putTerrain(IntakeWire.WORLD, IntakeWire.world(minY, height), Sections.NONE, nanos);
        }
    };

    /** The joined socket's terms; {@link CloudConnection.Terms#NONE} while not joined. */
    private CloudConnection.Terms terms = CloudConnection.Terms.NONE;
    /** The joined socket's {@code textStripped}; true while not joined. */
    private boolean textStripped = true;

    public CaptureStream(UUID player) {
        this.player = player;
    }

    /** The tap starts after CultAC has handled {@code tickEnds} tick ends, so its cuts count on from there. */
    public void start(long tickEnds) {
        cuts = tickEnds;
    }

    /** The client protocol's clientbound ping id, so VELOCITY can name the ping that proves a velocity. */
    public void pingPacket(int id) {
        pingPacket = id;
    }

    /** The client protocol's serverbound pong id, so protocol 5 can send pongs as markers. */
    public void pongPacket(int id) {
        pongPacket = id;
    }

    /** The client protocol's chunk packets, which protocol 5 leaves out as its terrain goes as sections. */
    public void chunks(ChunkIds ids) {
        chunkIds = ids;
    }

    /** The player's simulation world, read on the event loop: protocol 5's terrain. */
    public void terrain(Sections.Terrain terrain) {
        this.terrain = terrain;
    }

    /** The client protocol's {@link PlayerText}, applied to each packet before it is kept. */
    public void text(PlayerText text) {
        this.text = text;
    }

    public boolean joined() {
        return joined;
    }

    /** The tick-end count this capture's frames count from: tick end {@code n} cuts frame {@code n - origin - 1}. */
    public long origin() {
        return origin;
    }

    public boolean capturing() {
        return joined || preparing;
    }

    public boolean preparing() {
        return preparing;
    }

    /**
     * Retains only the initial capture; {@code sink}'s budget also belongs to its first JOIN's sink. Its chunk and block
     * packets are dropped: the terrain goes out as sections once joined.
     */
    public void prepare(Sink sink) {
        this.sink = sink;
        preparing = true;
        origin = cuts;
    }

    /** The first retained record's clock zero, or the JOIN's when there is no initial capture. */
    public long baseNanos(long joinNanos) {
        return preparing && firstNanos != Long.MIN_VALUE ? firstNanos : joinNanos;
    }

    /**
     * Starts a capture on {@code sink}, keeping the initial capture when present. Every later JOIN starts a new
     * clock and frame count. A JOIN the socket would not take is tried again at each tick end until it does.
     */
    public void join(Sink sink, byte[] join, long baseNanos, Session session) {
        baseNanos = baseNanos(baseNanos);
        if (preparing) {
            preparing = false;
            if (tapped == null) tapped = session;
        } else {
            reset();
            origin = cuts;
            tapped = session;
        }
        this.sink = sink;
        joined = true;
        base = baseNanos;
        terms = sink.terms();
        textStripped = sink.textStripped();
        if (!sink.send(join)) pendingJoin = join;
        if (terms.sections() && terrain != null) {
            sections.open(terrain, terms.chunkRadius(), terms.sectionRadius());
            terrainFull = !sections.restart(terrainOut, baseNanos);
        }
    }

    /** The service answered the JOIN: frames held for it go out, filtered as it asks. */
    public void capture(int[] serverbound, int[] clientbound, int[] dimension) {
        if (!joined) return;
        policy = new Policy(Policy.of(serverbound), Policy.of(withoutTerrain(clientbound)), Policy.of(dimension));
        Tick tick;
        while ((tick = held.poll()) != null) {
            try {
                send(tick);
            } finally {
                release(tick);
            }
        }
    }

    /** On protocol 5, the ids asked for less the chunk and block packets, whose terrain goes out as sections. */
    private int[] withoutTerrain(int[] clientbound) {
        ChunkIds ids = chunkIds;
        if (ids == null) return clientbound;
        return Arrays.stream(clientbound)
                .filter(id -> id != ids.levelChunk()
                        && id != ids.forget()
                        && id != ids.blockUpdate()
                        && id != ids.sectionUpdate())
                .toArray();
    }

    /**
     * A clientbound packet crossed with CultAC's session facts and the client's world bounds ({@code minY},
     * {@code height}) as they were when it was sent, whether the packet is kept or not. A game mode change outside a
     * login or respawn becomes a META record of its own; a login's or respawn's facts go out with its WORLD record. A login
     * or respawn while the open frame is dropped is owed as WORLD and META, with the facts as they are when a frame next
     * goes.
     */
    public void observe(Session session, int minY, int height, long nanos) {
        if (!capturing()) return;
        worldMinY = minY;
        worldHeight = height;
        // A login or respawn: the sections of the world the client leaves will never be shown.
        if (session != tapped && session.world()) {
            sections.forget();
            if (!overflow) frameWorld = true;
        }
        if (session == tapped) return;
        if (overflow) {
            if (session.world()) {
                tapped = session;
                owedWorld = true;
            }
            return;
        }
        Session previous = tapped;
        tapped = session;
        if (session.world()) return;
        if (session.gameMode() == null || session.gameMode().equals(previous == null ? null : previous.gameMode())) {
            return;
        }
        byte[] meta = IntakeWire.meta(null, session.gameMode(), null);
        put(IntakeWire.META, -1, meta, nanos, 0, 0, session, 0, false);
    }

    /** The player's own combat settings, read once they are in the world, as a META record in the open frame. */
    public void settings(Map<String, String> settings, long nanos) {
        if (!capturing() || overflow || settings.isEmpty()) return;
        byte[] meta = IntakeWire.meta(settings);
        put(IntakeWire.META, -1, meta, nanos, 0, 0, tapped, 0, false);
    }

    /** Whether a packet would be kept, so its bytes are copied only then. */
    public boolean wants(int kind, int id) {
        if (!capturing() || overflow || id < 0) return false;
        if (kind == IntakeWire.CLIENTBOUND && terrainPacket(id)) return false;
        Policy known = policy;
        // Once the service has answered, what it does not want is not even held.
        if (known == null) return true;
        if (kind == IntakeWire.SERVERBOUND) return Policy.has(known.serverbound(), id);
        return Policy.has(known.clientbound(), id) || Policy.has(known.dimension(), id);
    }

    public void tap(int kind, int id, byte[] bytes, long nanos, int minY, int height, Session session) {
        tap(kind, id, bytes, nanos, minY, height, session, 0);
    }

    /**
     * As {@link #tap(int, int, byte[], long, int, int, Session, int)}, for a packet {@link #wants} keeps: a copy of
     * {@code source}'s readable bytes, which stay the caller's, lives until the open frame is sent, dropped or reset.
     */
    public void tap(int kind, int id, ByteBuf source, long nanos, int minY, int height, Session session, int pingKind) {
        tap(kind, id, copy(source, nanos), nanos, minY, height, session, pingKind);
    }

    private byte[] copy(ByteBuf source, long nanos) {
        copyClock = Math.max(copyClock, nanos);
        if (source.readableBytes() > CapturePacketCopies.MAX_PACKET_BYTES) return FrameCopies.bytes(source);
        if (copies == null) {
            copies = spareCopies != null ? spareCopies : CapturePacketCopies.of(copyBudget, nanos);
            spareCopies = null;
            if (copies == null) return FrameCopies.bytes(source);
        }
        return copies.copy(source, nanos);
    }

    /** Reusable packet-copy capacity this stream's pools hold, bookkeeping included. */
    long pooledBytes() {
        return copyBudget.bytes();
    }

    private void releaseCopies(CapturePacketCopies released) {
        if (released == null) return;
        released.clear(copyClock);
        if (spareCopies == null) spareCopies = released;
        else released.release();
    }

    /** As {@link #tap(int, int, byte[], long, int, int, Session)}, for a ping of {@code PingKind} {@code pingKind}. */
    public void tap(int kind, int id, byte[] bytes, long nanos, int minY, int height, Session session, int pingKind) {
        if (kind == IntakeWire.CLIENTBOUND) {
            worldMinY = minY;
            worldHeight = height;
        } else if (kind == IntakeWire.SERVERBOUND) {
            lastServerbound = -1;
        }
        if (!capturing() || overflow) return;
        if (kind == IntakeWire.CLIENTBOUND && terrainPacket(id)) return;
        boolean unstripped = false;
        if (text != null) {
            boolean serverbound = kind == IntakeWire.SERVERBOUND;
            // Before JOIN the socket's textStripped is unknown: such a packet waits for it in the held frames.
            unstripped = preparing && text.unstripped(!serverbound, id);
            boolean stripped = textStripped && !unstripped;
            bytes = serverbound ? text.serverbound(id, bytes, stripped) : text.clientbound(id, bytes, stripped, teams);
            if (bytes == null) return;
        }
        if (kind == IntakeWire.SERVERBOUND && !terrainFull) terrainFull = !sections.check(terrainOut, nanos);
        if (put(kind, id, bytes, nanos, minY, height, session, pingKind, unstripped) && kind == IntakeWire.SERVERBOUND)
            lastServerbound = frame.size() - 1;
    }

    /** A serverbound packet crossed the tap without being kept. */
    public void skipped() {
        lastServerbound = -1;
    }

    /** The serverbound packet the tap saw last, as an outcome names it; null while nothing is captured. */
    public ActionRef action() {
        if (!capturing()) return null;
        ActionRef action = new ActionRef(generation, lastServerbound);
        if (actions.size() == MAX_OPEN_ACTIONS) {
            lose(actions);
            actions = NO_ACTIONS;
        }
        if (actions == NO_ACTIONS) actions = new ArrayList<>();
        actions.add(action);
        return action;
    }

    /**
     * Queues an outcome for the VERDICT of a frame sent after the one holding its {@code action} (null when it names
     * none); {@code nanos} is when it was seen. Dropped and counted while nothing is captured, when its action belongs
     * to an earlier capture, or when the queue is full.
     */
    public void outcome(Outcome outcome, ActionRef action, long nanos) {
        if (!capturing()
                || action != null && action.generation != generation
                || outcomes.size() >= MAX_QUEUED_OUTCOMES) {
            outcomesDropped.incrementAndGet();
            return;
        }
        outcomes.add(new Queued(outcome, action, nanos));
    }

    private static void lose(List<ActionRef> lost) {
        for (ActionRef action : lost) action.state = ActionRef.LOST;
    }

    /** On protocol 5, whether a clientbound packet loads, forgets or changes terrain, which never goes out as one. */
    private boolean terrainPacket(int id) {
        ChunkIds ids = chunkIds;
        return ids != null
                && (id == ids.levelChunk()
                        || id == ids.forget()
                        || id == ids.blockUpdate()
                        || id == ids.sectionUpdate());
    }

    /** A record of protocol 5's terrain into the open frame; false when the frame dropped it. */
    private boolean putTerrain(int kind, byte[] body, long section, long nanos) {
        if (!put(kind, framedTerrain.size(), body, nanos, 0, 0, tapped, 0, false)) return false;
        if (framedTerrain == NO_TERRAIN) framedTerrain = new ArrayList<>();
        framedTerrain.add(new TerrainRecord(kind, section));
        if (kind == IntakeWire.SECTION) framedTerrainBytes += body.length;
        return true;
    }

    /** Terrain records that never reached the service are owed again. */
    private void lostTerrain(List<TerrainRecord> records, BitSet only) {
        for (int i = 0; i < records.size(); i++) {
            if (only == null || only.get(i))
                sections.lost(records.get(i).kind(), records.get(i).section());
        }
    }

    /** Protocol 5: the simulation world loaded, or loaded again, the column at chunk {@code x}, {@code z}. */
    public void columnLoaded(int x, int z, long nanos) {
        if (joined && !sections.columnLoaded(x, z, terrainOut, nanos, terrainFull || overflow)) terrainFull = true;
    }

    /** Protocol 5: the simulation world unloaded the column at chunk {@code x}, {@code z}. */
    public void columnUnloaded(int x, int z, long nanos) {
        if (joined && !sections.columnUnloaded(x, z, terrainOut, nanos, terrainFull || overflow)) terrainFull = true;
    }

    /** Protocol 5: the simulation world set a block to the server's block state {@code state}. */
    public void blockChanged(int x, int y, int z, int state, long nanos) {
        if (joined) sections.block(x, y, z, state, terrainOut, nanos);
    }

    Sections sections() {
        return sections;
    }

    /** Adds a record to the open frame; false when it passed a cap, and the frame was dropped unless terrain waits. */
    private boolean put(
            int kind,
            int id,
            byte[] bytes,
            long nanos,
            int minY,
            int height,
            Session session,
            int pingKind,
            boolean unstripped) {
        if (overflow) return false;
        if (frameBytes + bytes.length + (long) (frame.size() + 1) * RECORD_OVERHEAD_BYTES > MAX_OPEN_FRAME_BYTES) {
            dropOpen();
            return false;
        }
        if (!reserve(bytes.length + (long) RECORD_OVERHEAD_BYTES, terrainRecord(kind))) return false;
        if (preparing && firstNanos == Long.MIN_VALUE) firstNanos = nanos;
        long serverTick = kind != IntakeWire.CLIENTBOUND
                ? -1
                : pingKind == IntakeWire.PING_END_OF_TICK || lastEndOfTick < 0 ? lastEndOfTick : lastEndOfTick + 1;
        frame.add(new Raw(kind, nanos, id, bytes, minY, height, session, serverTick, pingKind, unstripped));
        frameBytes += bytes.length;
        return true;
    }

    /** Protocol 5's section, block and section_gone records, which stay outside the frame's server-tick chain. */
    private static boolean terrainRecord(int kind) {
        return kind == IntakeWire.SECTION || kind == IntakeWire.BLOCK || kind == IntakeWire.SECTION_GONE;
    }

    /**
     * Room the open frame has left for terrain, within the player's budget and the server's once the frame's verdict
     * is reserved.
     */
    private long room() {
        return Math.min(
                Math.min(
                        MAX_OPEN_FRAME_BYTES - frameBytes - (long) frame.size() * RECORD_OVERHEAD_BYTES,
                        (long) sink.maxFrameBytes() - RELEASE_HEADROOM - frameBytes),
                Math.min(MAX_PRE_JOIN_BYTES - heldBytes, sink.holdRoom()) - VERDICT_BYTES);
    }

    /**
     * Takes {@code bytes} for the open frame from the player's budget and the server's shared one; false when either
     * is spent, and the open frame is dropped, unless only the shared one is spent and {@code waits}.
     */
    private boolean reserve(long bytes, boolean waits) {
        if (heldBytes + bytes > MAX_PRE_JOIN_BYTES) {
            dropOpen();
            return false;
        }
        if (!sink.hold(bytes)) {
            if (!waits) dropOpen();
            return false;
        }
        heldBytes += bytes;
        frameReserved += bytes;
        return true;
    }

    /**
     * The open frame passed a cap: it is dropped and its reservation goes back; before the first JOIN the initial
     * capture ends instead.
     */
    private void dropOpen() {
        if (preparing) {
            reset();
            return;
        }
        overflow = true;
        lastServerbound = -1;
        for (ActionRef action : actions) action.position = -1;
        if (frameWorld) owedWorld = true;
        frameWorld = false;
        frame = new ArrayList<>();
        frameBytes = 0;
        if (copies != null) copies.clear(copyClock);
        releaseOpen();
        List<TerrainRecord> terrain = framedTerrain;
        framedTerrain = NO_TERRAIN;
        framedTerrainBytes = 0;
        lostTerrain(terrain, null);
    }

    private void releaseOpen() {
        if (frameReserved != 0) sink.release(frameReserved);
        heldBytes -= frameReserved;
        frameReserved = 0;
    }

    /**
     * Server tick {@code serverTick} has ended on the wire, at its place among the tapped records and before an
     * end-of-tick ping CultAC writes for it. The open frame's clientbound records tapped since the last tick end went
     * out in this tick, whichever tick they were tapped with.
     */
    public void endOfTick(long serverTick) {
        for (int i = frame.size() - 1; i >= 0; i--) {
            Raw raw = frame.get(i);
            if (raw.kind() != IntakeWire.CLIENTBOUND) continue;
            if (raw.serverTick() >= 0 && raw.serverTick() <= lastEndOfTick) break;
            if (raw.serverTick() != serverTick) frame.set(i, raw.tickedAs(serverTick, raw.pingKind()));
        }
        lastEndOfTick = serverTick;
    }

    /** The client ended a tick: its frame is complete, and waits for CultAC's verdict on it. */
    public void cut(long nanos) {
        cuts++;
        copyClock = Math.max(copyClock, nanos);
        if (spareCopies != null && copyClock - spareCopies.lastUse() > CapturePacketCopies.IDLE_NANOS) {
            spareCopies.release();
            spareCopies = null;
        }
        if (pendingJoin != null && sink.send(pendingJoin)) pendingJoin = null;
        List<ActionRef> cutActions = actions;
        actions = NO_ACTIONS;
        lastServerbound = -1;
        if (!capturing()) {
            lose(cutActions);
            return;
        }
        if (preparing) {
            if (++preJoinFrames > MAX_PRE_JOIN_FRAMES) {
                lose(cutActions);
                reset();
                return;
            }
            if (firstNanos == Long.MIN_VALUE) firstNanos = nanos;
        }
        if (!overflow && !terrainFull) sections.check(terrainOut, nanos);
        terrainFull = false;
        if (capturing() && !overflow) reserve(VERDICT_BYTES, false);
        if (!capturing()) {
            lose(cutActions);
            return;
        }
        awaiting.add(takeOpen(nanos, cuts, cutActions));
        if (awaiting.size() > MAX_AWAITING) leaveQueue(awaiting.poll());
    }

    /** The open frame as the frame of tick end {@code index}, holding {@code cutActions}; a new frame opens. */
    private Tick takeOpen(long nanos, long index, List<ActionRef> cutActions) {
        List<Raw> records = frame;
        if (records.isEmpty()) records = List.of();
        else frame = new ArrayList<>(Math.clamp(records.size(), MIN_FRAME_RECORDS, MAX_PRESIZED_RECORDS));
        Tick tick = new Tick(records, frameBytes, nanos, index, overflow);
        tick.reserved = frameReserved;
        tick.copies = copies;
        copies = null;
        tick.terrain = framedTerrain;
        tick.world = frameWorld;
        tick.actions = cutActions;
        for (ActionRef action : cutActions) {
            action.frameSeq = index - origin - 1;
            action.state = ActionRef.CUT;
        }
        frameBytes = 0;
        frameReserved = 0;
        overflow = false;
        frameWorld = false;
        framedTerrain = NO_TERRAIN;
        framedTerrainBytes = 0;
        return tick;
    }

    /**
     * CultAC received its {@code tickEnd}th tick end, counted as the tap counts cuts, and this is its tick-end handler's
     * {@code handled}th run on the same count, with the checks that flagged during that tick in flag order, the
     * crosshair's sight of the last attacked target, the tick's attack geometries and prediction engine state. Frames
     * before that one go out marked missing; a verdict whose frame is gone is dropped.
     */
    public void verdict(
            long tickEnd,
            long handled,
            long conditions,
            List<IntakeWire.Flag> flags,
            IntakeWire.Target target,
            List<IntakeWire.Geometry> geometries,
            IntakeWire.Engine engine) {
        Tick tick;
        while ((tick = awaiting.peek()) != null && tick.index < tickEnd) leaveQueue(awaiting.poll());
        if (tick == null || tick.index != tickEnd) return;
        awaiting.poll();
        tick.missing = false;
        tick.conditions = conditions;
        tick.handled = handled;
        tick.flags = flags;
        tick.target = target;
        tick.geometries = geometries;
        tick.engine = engine;
        leaveQueue(tick);
    }

    /** A frame is done waiting for its verdict: it goes out, or is held for the service's answer. */
    private void leaveQueue(Tick tick) {
        if (tick.dropped) {
            release(tick);
            lostFrames++;
            lose(tick.actions);
            settle(tick, false);
            return;
        }
        tick.lostBefore = lostFrames;
        lostFrames = 0;
        if (policy != null) {
            try {
                send(tick);
            } finally {
                release(tick);
            }
        } else {
            held.add(tick);
        }
    }

    private void release(Tick tick) {
        if (tick.reserved != 0) sink.release(tick.reserved);
        heldBytes -= tick.reserved;
        tick.reserved = 0;
        releaseCopies(tick.copies);
        tick.copies = null;
    }

    /** A frame's terrain records are done with: those it did not carry to the service are owed again. */
    private void settle(Tick tick, boolean sent) {
        if (!sent) lostTerrain(tick.terrain, null);
        else if (tick.omittedTerrain != null) lostTerrain(tick.terrain, tick.omittedTerrain);
    }

    /**
     * Writes a tick as a FRAME, keeping what the policy asks for; a login or respawn becomes a WORLD record. A world the
     * service never got starts the next frame it takes, as WORLD and META.
     */
    private void send(Tick tick) {
        int maxFrameBytes = sink.maxFrameBytes();
        long before = lastNanos;
        int lost = tick.lostBefore + lostSending;
        builder.clear();
        IntArrayList unanswered = null;
        long seq = tick.index - origin - 1;
        List<byte[]> carried = outcomes();
        int framePingCount = pings(tick);
        IntakeWire.Engine engine = tick.missing ? null : tick.engine;
        IntakeWire.Engine last = lost > 0 || sinceKeyframe >= IntakeWire.KEYFRAME_FRAMES ? null : lastEngine;
        int verdict = writeVerdict(tick, engine, last, pingOrdinal(velocityPing(engine), framePingCount), seq, carried);
        int headroom = FRAME_HEADROOM + verdict;
        boolean gap = lost > 0;
        if (gap) {
            long at = tick.records.isEmpty() ? tick.nanos : tick.records.get(0).nanos();
            record(IntakeWire.GAP, at, IntakeWire.gap(lost));
        }
        boolean owed = owedWorld && policy.dimension().length > 0;
        if (owed) {
            long at = tick.records.isEmpty() ? tick.nanos : tick.records.get(0).nanos();
            if (IntakeWire.validDimension(worldMinY, worldHeight)) {
                record(IntakeWire.WORLD, at, IntakeWire.world(worldMinY, worldHeight));
            }
            byte[] meta =
                    tapped == null ? null : IntakeWire.meta(tapped.entityId(), tapped.gameMode(), tapped.dimension());
            if (meta != null) record(IntakeWire.META, at, meta);
        }
        boolean lostWorld = false;
        long[] serverTicks = serverTicks(tick.records);
        int serverbound = 0;
        int nextAction = 0;
        long previousTick = -1;
        resolvedExtra = 0;
        long[] emitted = framePings;
        int pingAt = -1;
        int emittedPings = 0;
        for (int index = 0; index < tick.records.size(); index++) {
            Raw raw = tick.records.get(index);
            if (raw.unstripped() && textStripped) continue;
            int kind = raw.kind();
            byte[] payload = raw.bytes();
            byte[] meta = null;
            int limit;
            long probe = NO_WIRE_ID;
            boolean ping = framePingCount > 0 && kind == IntakeWire.CLIENTBOUND && raw.id() == pingPacket;
            if (ping) pingAt++;
            if (kind == IntakeWire.META) {
                limit = MAX_META_BYTES;
            } else if (terrainRecord(kind)) {
                limit = MAX_CLIENTBOUND_BYTES;
            } else if (kind == IntakeWire.WORLD) {
                limit = 16;
            } else if (kind == IntakeWire.SERVERBOUND) {
                if (!Policy.has(policy.serverbound(), raw.id())) continue;
                limit = MAX_SERVERBOUND_BYTES;
                if (raw.id() == pongPacket) probe = IntakeWire.bareId(payload, pongPacket, NO_WIRE_ID);
            } else if (Policy.has(policy.dimension(), raw.id())) {
                Session session = raw.session();
                meta = IntakeWire.meta(session.entityId(), session.gameMode(), session.dimension());
                if (!IntakeWire.validDimension(raw.minY(), raw.height())) {
                    if (meta != null && fits(meta.length, maxFrameBytes, headroom))
                        record(IntakeWire.META, raw.nanos(), meta);
                    continue;
                }
                kind = IntakeWire.WORLD;
                payload = IntakeWire.world(raw.minY(), raw.height());
                limit = 16;
            } else if (Policy.has(policy.clientbound(), raw.id())) {
                limit = MAX_CLIENTBOUND_BYTES;
                if (raw.id() == pingPacket) {
                    probe = IntakeWire.bare(payload, pingPacket) ? framePings[pingAt] : NO_WIRE_ID;
                    if (probe != NO_WIRE_ID) {
                        kind = IntakeWire.PING_MARKER;
                        payload = NO_BODY;
                    }
                }
            } else {
                continue;
            }
            int resolved = resolvedLength(kind, raw, payload);
            if (payload.length > limit || !fits(resolved + (meta == null ? 0 : meta.length), maxFrameBytes, headroom)) {
                // What the service would refuse becomes a gap; one per run of them.
                if (!gap) record(IntakeWire.GAP, raw.nanos(), IntakeWire.gap(0));
                gap = true;
                if (terrainRecord(raw.kind()) || raw.kind() == IntakeWire.WORLD) {
                    omitted(tick).set(raw.id());
                } else if (kind == IntakeWire.WORLD) {
                    lostWorld = true;
                    sections.lost(IntakeWire.WORLD, Sections.NONE);
                }
                continue;
            }
            gap = false;
            if (ping) {
                if (pingAt != emittedPings && emitted == framePings) emitted = framePings.clone();
                emitted[emittedPings++] = framePings[pingAt];
            }
            int skipped = 0;
            if (probe != NO_WIRE_ID) {
                if (unanswered == null) {
                    unanswered = spare;
                    unanswered.clear();
                    unanswered.addAll(pendingPings);
                }
                if (kind == IntakeWire.PING_MARKER) {
                    if (unanswered.size() == IntakeWire.MAX_PENDING_PINGS) unanswered.removeInt(0);
                    unanswered.add((int) probe);
                } else {
                    int answered = unanswered.indexOf((int) probe);
                    unanswered.removeElements(0, answered + 1);
                    if (answered == 0) {
                        kind = IntakeWire.PONG_MARKER;
                        payload = NO_BODY;
                    } else if (answered > 0) {
                        skipped = answered;
                    }
                }
            }
            resolvedExtra += growth(payload.length, resolvedLength(kind, raw, payload));
            if (kind == IntakeWire.CLIENTBOUND || kind == IntakeWire.PING_MARKER) {
                int tickDelta = 0;
                if (serverTicks != null) {
                    long serverTick = serverTicks[index];
                    if (previousTick < 0) builder.serverTick(serverTick);
                    else tickDelta = Math.toIntExact(serverTick - previousTick);
                    previousTick = serverTick;
                }
                record(kind, raw.nanos(), payload, payload.length, tickDelta, raw.pingKind(), 0);
            } else {
                record(kind, raw.nanos(), payload, payload.length, 0, 0, skipped);
            }
            if (raw.kind() == IntakeWire.SERVERBOUND) {
                // Actions are in tap order, so a position past -1 never goes back.
                while (nextAction < tick.actions.size() && tick.actions.get(nextAction).position <= index) {
                    ActionRef action = tick.actions.get(nextAction++);
                    if (action.position == index) action.index = serverbound;
                }
                serverbound++;
            }
            if (meta != null) record(IntakeWire.META, raw.nanos(), meta);
        }
        if (emittedPings != framePingCount && velocityPing(engine) != null) {
            // The verdict names its velocity's ping among the pings the service gets, which only shrinks the ordinal.
            verdict = writeVerdict(
                    tick,
                    engine,
                    last,
                    emittedOrdinal(velocityPing(engine), emitted, emittedPings, framePingCount),
                    seq,
                    carried);
        }
        record(IntakeWire.VERDICT, tick.nanos, verdicts.bytes(), verdict, 0, 0, 0);
        byte[] record = builder.build(player, seq);
        builder.clear();
        boolean sent = sink.send(record, resolvedExtra);
        for (ActionRef action : tick.actions) action.state = sent ? ActionRef.SENT : ActionRef.LOST;
        if (sent) {
            outcomesSent.addAndGet(sending.size());
            lostSending = 0;
            owedWorld = lostWorld;
            if (unanswered != null) {
                spare = pendingPings;
                pendingPings = unanswered;
            }
            for (int i = 0; i < emittedPings; i++) pingIds[(int) (pings++ % PING_HISTORY)] = emitted[i];
            if (tick.missing) {
                lastEngine = null;
            } else if (engine != null) {
                sinceKeyframe = last == null ? 1 : sinceKeyframe + 1;
                lastEngine = engine;
            }
        } else {
            lastEngine = null;
            for (int i = sending.size() - 1; i >= 0; i--) outcomes.addFirst(sending.get(i));
            // The service never sees this frame, so the next one is timed from where this one began.
            lastNanos = before;
            lostSending = lost + 1;
            owedWorld |= owed || tick.world || lostWorld;
        }
        settle(tick, sent);
    }

    private int writeVerdict(
            Tick tick,
            IntakeWire.Engine engine,
            IntakeWire.Engine last,
            long velocityOrdinal,
            long seq,
            List<byte[]> carried) {
        return verdicts.write(
                tick.conditions,
                tick.handled - origin - 1,
                tick.missing,
                tick.flags,
                tick.target,
                tick.geometries,
                engine,
                last,
                engine == null || !engine.velocity() ? -1 : velocityOrdinal,
                seq,
                carried);
    }

    /**
     * The outcomes that go out with the frame being sent, encoded: those whose actions' frames have gone out or never
     * will, up to {@link #MAX_OUTCOMES} and {@link #MAX_OUTCOME_BYTES}. One whose action's frame has not gone out yet
     * waits, up to {@link #MAX_OUTCOME_WAIT} frames.
     */
    private List<byte[]> outcomes() {
        sending = List.of();
        if (outcomes.isEmpty()) return List.of();
        List<byte[]> written = new ArrayList<>();
        List<Queued> carried = new ArrayList<>();
        List<Queued> waiting = new ArrayList<>();
        int bytes = 0;
        for (int i = Math.min(outcomes.size(), MAX_OUTCOMES); i > 0; i--) {
            Queued queued = outcomes.poll();
            if (queued.action != null && !queued.action.settled() && queued.waited++ < MAX_OUTCOME_WAIT) {
                waiting.add(queued);
                continue;
            }
            byte[] encoded = encode(queued);
            if (encoded.length + 5 > MAX_OUTCOME_BYTES) {
                outcomesDropped.incrementAndGet();
            } else if (bytes + encoded.length + 5 > MAX_OUTCOME_BYTES) {
                waiting.add(queued);
            } else {
                bytes += encoded.length + 5;
                written.add(encoded);
                carried.add(queued);
            }
        }
        for (int i = waiting.size() - 1; i >= 0; i--) outcomes.addFirst(waiting.get(i));
        sending = carried;
        return written;
    }

    /** An outcome's bytes: its action named by frame seq, and by index once that frame went out holding it. */
    private byte[] encode(Queued queued) {
        Outcome.Builder outcome = queued.outcome.toBuilder().setNanos(queued.nanos - base);
        ActionRef action = queued.action;
        if (action != null && action.frameSeq >= 0) {
            outcome.setActionFrame(action.frameSeq);
            if (action.state == ActionRef.SENT && action.index >= 0) outcome.setActionIndex(action.index);
        }
        return outcome.build().toByteArray();
    }

    /** The wire id of the ping that proves the tick's velocity; null when there is none. */
    private static Integer velocityPing(IntakeWire.Engine engine) {
        return engine == null || !engine.velocity() ? null : engine.velocityPing();
    }

    /**
     * {@link #pingOrdinal} once some of the frame's pings were left out: the first {@code count} of {@code emitted} are
     * those it carries, and one it left out has none.
     */
    private long emittedOrdinal(Integer id, long[] emitted, int count, int framePingCount) {
        if (id == null) return -1;
        for (int i = count - 1; i >= 0; i--) if (emitted[i] == id) return pings + i;
        for (int i = 0; i < framePingCount; i++) if (framePings[i] == id) return -1;
        return pingOrdinal(id, 0);
    }

    private static BitSet omitted(Tick tick) {
        if (tick.omittedTerrain == null) tick.omittedTerrain = new BitSet();
        return tick.omittedTerrain;
    }

    /**
     * Fills {@link #framePings} with the wire ids of the pings this tick will carry, in order, {@link #NO_WIRE_ID} for
     * one without, and returns how many: every ping counts, as the service counts them, whatever its body.
     */
    private int pings(Tick tick) {
        if (pingPacket < 0 || !Policy.has(policy.clientbound(), pingPacket)) return 0;
        int count = 0;
        for (int index = 0; index < tick.records.size(); index++) {
            Raw raw = tick.records.get(index);
            if (raw.kind() != IntakeWire.CLIENTBOUND || raw.id() != pingPacket) continue;
            if (count == framePings.length) framePings = Arrays.copyOf(framePings, count * 2);
            framePings[count++] = IntakeWire.pingId(raw.bytes(), pingPacket, NO_WIRE_ID);
        }
        return count;
    }

    /** The ordinal the service gives the ping {@code id}; -1 when it has not been, or will not be, sent. */
    private long pingOrdinal(Integer id, int framePingCount) {
        if (id == null) return -1;
        for (int i = framePingCount - 1; i >= 0; i--) if (framePings[i] == id) return pings + i;
        for (long ordinal = pings - 1; ordinal >= Math.max(0, pings - PING_HISTORY); ordinal--) {
            if (pingIds[(int) (ordinal % PING_HISTORY)] == id) return ordinal;
        }
        return -1;
    }

    /** The payload bytes the service resolves a record to: a probe marker's packet. */
    private static int resolvedLength(int kind, Raw raw, byte[] payload) {
        if (kind == IntakeWire.PING_MARKER || kind == IntakeWire.PONG_MARKER) return raw.bytes().length;
        return payload.length;
    }

    /** What a record grows by once its payload of {@code payload} bytes resolves to {@code resolved}, length included. */
    private static int growth(int payload, int resolved) {
        return resolved
                - payload
                + CodedOutputStream.computeUInt32SizeNoTag(resolved)
                - CodedOutputStream.computeUInt32SizeNoTag(payload);
    }

    /** Sized as the service reads the frame: probe markers at their resolved length. */
    private boolean fits(int payloadBytes, int maxFrameBytes, int headroom) {
        return builder.bytes() + resolvedExtra + payloadBytes + headroom <= maxFrameBytes;
    }

    /**
     * Each record's server tick, by index, for the clientbound ones, as {@link #endOfTick} last set it. Null when a
     * clientbound record has none, so a frame's are all known or none.
     */
    private long[] serverTicks(List<Raw> records) {
        if (lastEndOfTick < 0) return null;
        long[] ticks = serverTickScratch;
        if (ticks.length < records.size()) {
            ticks = new long[Math.max(records.size(), ticks.length * 2)];
            if (ticks.length <= MAX_PRESIZED_RECORDS) serverTickScratch = ticks;
        }
        for (int i = 0; i < records.size(); i++) {
            Raw raw = records.get(i);
            if (raw.kind() != IntakeWire.CLIENTBOUND) continue;
            if (raw.serverTick() < 0) return null;
            ticks[i] = raw.serverTick();
        }
        return ticks;
    }

    /** Times run on from the player's previous record, from the JOIN; never backwards. */
    private void record(int kind, long nanos, byte[] payload) {
        record(kind, nanos, payload, payload.length, 0, 0, 0);
    }

    private void record(int kind, long nanos, byte[] payload, int length, int tickDelta, int pingKind, int skipped) {
        long at = Math.max(lastNanos, nanos - base);
        builder.add(kind, advance(at), payload, length, tickDelta, pingKind, skipped);
        lastNanos = at;
    }

    /** The clock's advance from the last record to {@code at} as the wire carries it, in microseconds. */
    private long advance(long at) {
        return micros(at) - micros(lastNanos);
    }

    /** Writes the widest VERDICT there is: every flag, geometry and engine field present and at its longest. */
    private static int maxVerdictBytes() {
        String wide = "x".repeat(256);
        List<IntakeWire.Flag> flags =
                Collections.nCopies(IntakeWire.MAX_FLAGS, new IntakeWire.Flag(wide, Long.MAX_VALUE));
        double[] box = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, 0, 0, 0};
        double[] eyes = new double[IntakeWire.MAX_EYES * 3];
        List<IntakeWire.Geometry> geometries = Collections.nCopies(
                IntakeWire.MAX_GEOMETRIES,
                new IntakeWire.Geometry(Integer.MAX_VALUE, wide, box, eyes, Double.MAX_VALUE));
        IntakeWire.Engine engine = new IntakeWire.Engine(
                -1,
                Integer.MAX_VALUE,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                true,
                Integer.MIN_VALUE,
                Integer.MIN_VALUE,
                true,
                true,
                0);
        return new IntakeWire.VerdictWriter()
                .write(
                        -1L,
                        -1L,
                        false,
                        flags,
                        new IntakeWire.Target(-1, Double.MAX_VALUE),
                        geometries,
                        engine,
                        null,
                        Long.MAX_VALUE,
                        Long.MAX_VALUE,
                        List.of(new byte[MAX_OUTCOME_BYTES]));
    }

    /** Nanoseconds rounded to the nearest microsecond. */
    static long micros(long nanos) {
        return Math.floorDiv(nanos + 500, 1000);
    }

    /**
     * The player left: the frames CultAC has not ended go out marked missing, then the unfinished frame, then
     * LEAVE, so the service checks what is pending. Frames still held for the service's answer are dropped.
     */
    public void leave(long nanos) {
        if (joined && pendingJoin == null) {
            if (policy != null) {
                Tick tick;
                while ((tick = awaiting.poll()) != null) leaveQueue(tick);
                if (!frame.isEmpty() && !overflow) {
                    Tick last = takeOpen(nanos, cuts + 1, actions);
                    actions = NO_ACTIONS;
                    leaveQueue(last);
                }
            }
            sink.send(IntakeWire.leave(player));
        }
        reset();
    }

    /** Ends the capture: nothing cut or held so far goes out. */
    public void reset() {
        generation++;
        builder.clear();
        lose(actions);
        actions = NO_ACTIONS;
        lastServerbound = -1;
        for (Tick tick : awaiting) {
            lose(tick.actions);
            if (tick.copies != null) tick.copies.release();
            tick.copies = null;
        }
        for (Tick tick : held) {
            lose(tick.actions);
            if (tick.copies != null) tick.copies.release();
            tick.copies = null;
        }
        outcomesDropped.addAndGet(outcomes.size());
        outcomes.clear();
        sending = List.of();
        framedTerrain = NO_TERRAIN;
        framedTerrainBytes = 0;
        sections.close();
        joined = false;
        preparing = false;
        firstNanos = Long.MIN_VALUE;
        preJoinFrames = 0;
        frameReserved = 0;
        frameWorld = false;
        owedWorld = false;
        terrainFull = false;
        policy = null;
        pendingJoin = null;
        lostFrames = 0;
        lostSending = 0;
        lastNanos = 0;
        tapped = null;
        pings = 0;
        pendingPings.clear();
        lastEndOfTick = -1;
        lastEngine = null;
        sinceKeyframe = 0;
        frame = new ArrayList<>();
        frameBytes = 0;
        if (copies != null) copies.release();
        if (spareCopies != null) spareCopies.release();
        copies = null;
        spareCopies = null;
        overflow = false;
        awaiting.clear();
        if (sink != null && heldBytes != 0) sink.release(heldBytes);
        held.clear();
        heldBytes = 0;
        terms = CloudConnection.Terms.NONE;
        textStripped = true;
        sink = null;
    }
}
