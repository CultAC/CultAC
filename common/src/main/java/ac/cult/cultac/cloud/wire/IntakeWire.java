package ac.cult.cultac.cloud.wire;

import ac.cult.cultac.cloud.diag.Cut;
import ac.cult.cultac.cloud.diag.Diagnostics;
import ac.cult.cultac.cloud.diag.Limits;
import ac.cult.cultac.cloud.intake.v3.DiagLimits;
import ac.cult.cultac.cloud.intake.v3.Edition;
import ac.cult.cultac.cloud.intake.v3.Intake;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.Join;
import ac.cult.cultac.cloud.intake.v3.Leave;
import ac.cult.cultac.cloud.intake.v3.Meta;
import ac.cult.cultac.cloud.intake.v3.Registry;
import ac.cult.cultac.cloud.intake.v3.Replies;
import ac.cult.cultac.cloud.intake.v3.Unstreamed;
import ac.cult.cultac.player.CultPlayer;
import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.util.Native;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.Deflater;

/**
 * The intake service's protocols 3 to 5, whose contract is {@code common/src/main/proto/intake/v3/intake.proto}
 * (the service's schema under this project's package). A socket message is a flags byte, then an {@code Intake}: its
 * {@code IntakeMessage} entries back to back, each written on its own as a queued record. FRAME, its records and
 * VERDICT are written here with {@link CodedOutputStream} into arrays sized beforehand; JOIN, LEAVE, META and replies
 * use the generated classes.
 */
public final class IntakeWire {
    public static final int PROTOCOL_3 = 3;
    /**
     * Protocol 3 with probe markers, record clocks in microseconds, and a socket's compressed messages as one zstd
     * frame. Terrain goes as section, block and section_gone records read from CultAC's simulation world, never as chunk
     * packets.
     */
    public static final int PROTOCOL_5 = 5;

    /** The message flag: what follows the flags byte is compressed (raw DEFLATE on protocol 3, zstd on protocol 5). */
    public static final int COMPRESSED = 0x01;

    // Intake.messages, and IntakeMessage's FRAME.
    private static final int MESSAGES = 1;
    private static final int FRAME = 3;
    // Frame's fields.
    private static final int FRAME_PLAYER = 1;
    private static final int FRAME_SEQ = 2;
    private static final int FRAME_RECORDS = 3;
    private static final int FRAME_SERVER_TICK = 4;

    // Record bodies, by field number; each is length-delimited.
    public static final int SERVERBOUND = 1;
    public static final int CLIENTBOUND = 2;
    public static final int WORLD = 3;
    public static final int GAP = 4;
    public static final int VERDICT = 5;
    public static final int META = 6;
    /** Protocol 5: a ping without its id, its body field a varint of its kind, the one body not length-delimited. */
    public static final int PING_MARKER = 8;
    /** Protocol 5: a pong answering the oldest pending ping, without its id: an empty message. */
    public static final int PONG_MARKER = 9;
    /** Protocol 5's server_tick_delta, and a whole pong's pending pings before its own. */
    private static final int TICK_DELTA = 13;

    private static final int SKIPPED_PINGS = 18;
    /** Pings a stream keeps pending at most on protocol 5, past which the oldest is dropped; a wire contract. */
    public static final int MAX_PENDING_PINGS = 256;
    /** Protocol 5: a section of the player's simulation world, one block of a held section, and a section let go. */
    public static final int SECTION = 11;

    public static final int BLOCK = 12;
    public static final int SECTION_GONE = 14;
    /** Record's advance_nanos: nanoseconds on protocol 3, microseconds on protocol 5. */
    private static final int ADVANCE_NANOS = 15;

    private static final int SERVER_TICK_DELTA = 16;
    private static final int PING_KIND = 17;
    /**
     * {@code PingKind}s: CultAC's ping at the end of a server tick, its ping after and before a packet it proves, and
     * one CultAC did not write.
     */
    public static final int PING_END_OF_TICK = CultPlayer.PING_END_OF_TICK;

    public static final int PING_TRAILING = CultPlayer.PING_TRAILING;
    public static final int PING_LEADING = CultPlayer.PING_LEADING;
    public static final int PING_FOREIGN = 4;

    // Verdict's fields.
    public static final int CONDITIONS = 1;
    public static final int TARGET = 2;
    public static final int GEOMETRY = 4;
    public static final int TICK_END_SEQ = 5;
    public static final int VERDICT_MISSING = 6;
    public static final int FLAGS = 7;
    public static final int OUTCOMES = 8;
    public static final int ENGINE_TICK = 9;
    public static final int GROUND = 10;
    public static final int NO_JUMP_DELAY = 11;
    public static final int VELOCITY = 12;
    public static final int FALL = 13;
    public static final int CONTEXT = 14;
    public static final int USING_ITEM = 15;
    public static final int INPUT = 16;
    // CONDITIONS bits, Condition's order in the schema.
    public static final int TELEPORTED = 0;
    public static final int IN_VEHICLE = 1;
    public static final int FLYING = 2;
    public static final int SPECTATOR = 3;
    public static final int GLIDING = 4;
    public static final int RIPTIDE = 5;
    /** The chosen prediction applied a server velocity (not an explosion). */
    public static final int KNOCKBACK = 6;
    /** CultAC's model of the client replaced its player since the last tick end: a respawn or login. */
    public static final int RESPAWNED = 7;
    /** The chosen prediction jumped. */
    public static final int JUMPED = 8;
    /** The chosen prediction jumped while sprinting. */
    public static final int SPRINT_JUMPED = 9;
    /** Prediction exempted the tick. */
    public static final int ENGINE_EXEMPT = 10;
    // ENGINE_TICK bits, EngineTick's order in the schema.
    /** No prediction was committed this tick; the other engine fields keep their last values. */
    public static final int ENGINE_UNPREDICTED = 0;
    /** Prediction accepted both a jump and a no-jump candidate this tick. */
    public static final int ENGINE_JUMP_AMBIGUOUS = 1;
    /** The accepted movement rose. */
    public static final int ENGINE_MOVED_UP = 2;
    /** The sneak state or pose changed, so the eye height the client picked with is one of two. */
    public static final int ENGINE_EYE_UNCERTAIN = 3;
    // CONTEXT bits, Surrounding's order in the schema, each set when prediction allowed it at the tick's start.
    public static final int CONTEXT_WATER = 0;
    public static final int CONTEXT_LAVA = 1;
    public static final int CONTEXT_CLIMBING = 2;
    public static final int CONTEXT_STUCK = 3;
    public static final int CONTEXT_HONEY_SLIDE = 4;
    public static final int CONTEXT_BUBBLE_COLUMN = 5;
    /** Frames between full restatements of the engine fields sent on change. */
    public static final int KEYFRAME_FRAMES = 600;
    /** GEOMETRY fields one VERDICT carries at most, so it stays within the service's 8 KiB beside flags and outcomes. */
    public static final int MAX_GEOMETRIES = 5;
    /** Most eye positions one GEOMETRY carries. */
    public static final int MAX_EYES = 8;

    private static final int MAX_STRING_BYTES = 128;
    private static final int MAX_KEY_BYTES = 64;
    /** A finding's detail as kept; its check and category keep {@link #MAX_STRING_BYTES}. */
    public static final int MAX_DETAIL_BYTES = 512;
    /** Flags one VERDICT carries at most; later ones in the tick are left out. */
    public static final int MAX_FLAGS = 32;

    private IntakeWire() {}

    /** What a JOIN tells the service about the session. A null field, a {@code tickMicros} of 0 or an empty map is left out. */
    public record Facts(
            Integer entityId,
            Integer gameMode,
            String dimension,
            long epochMillisAtZero,
            int tickMicros,
            boolean resumed,
            String server,
            Map<String, String> settings,
            Map<String, List<String>> registries) {}

    /**
     * A JOIN with the session's {@code facts} and the player's {@code name}, each when given; {@code serverProtocol} is
     * protocol 5's server protocol, 0 to leave it out.
     */
    public static byte[] join(
            UUID player,
            int protocol,
            String brand,
            int minY,
            int height,
            Facts facts,
            String name,
            int serverProtocol) {
        return join(player, false, protocol, brand, minY, height, facts, 0, name, serverProtocol);
    }

    /** A JOIN for a Bedrock player, whom CultAC does not stream: no FRAME follows, only its LEAVE. */
    public static byte[] unstreamed(
            UUID player, int protocol, String brand, int minY, int height, Facts facts, String name) {
        return join(player, true, protocol, brand, minY, height, facts, Unstreamed.UNSTREAMED_BEDROCK_VALUE, name, 0);
    }

    /** A JOIN; {@code serverProtocol} 0 leaves {@code server_protocol} out. */
    private static byte[] join(
            UUID player,
            boolean bedrock,
            int protocol,
            String brand,
            int minY,
            int height,
            Facts facts,
            int unstreamed,
            String name,
            int serverProtocol) {
        Join.Builder join = Join.newBuilder()
                .setPlayer(ByteString.copyFrom(uuid(player)))
                .setEdition(bedrock ? Edition.EDITION_BEDROCK : Edition.EDITION_JAVA)
                .setClientProtocol(protocol)
                .setBrand(truncate(brand == null ? "" : brand, MAX_STRING_BYTES))
                .setMinY(minY)
                .setHeight(height);
        if (facts != null) {
            if (facts.entityId() != null) join.setEntityId(facts.entityId());
            if (facts.gameMode() != null) join.setGameModeValue(facts.gameMode());
            if (facts.dimension() != null) join.setDimensionKey(truncate(facts.dimension(), MAX_STRING_BYTES));
            join.setEpochMillisAtZero(facts.epochMillisAtZero());
            if (facts.tickMicros() > 0) join.setTickMicros(facts.tickMicros());
            join.setResumed(facts.resumed());
            if (facts.server() != null) join.setServer(truncate(facts.server(), MAX_STRING_BYTES));
            facts.settings()
                    .forEach((key, value) ->
                            join.putSettings(truncate(key, MAX_KEY_BYTES), truncate(value, MAX_STRING_BYTES)));
            facts.registries().forEach((key, entries) -> {
                Registry.Builder registry = Registry.newBuilder();
                for (String entry : entries) registry.addEntries(truncate(entry, MAX_STRING_BYTES));
                join.putRegistries(truncate(key, MAX_STRING_BYTES), registry.build());
            });
        }
        if (unstreamed > 0) join.setUnstreamedValue(unstreamed);
        if (name != null && !name.isEmpty()) join.setName(truncate(name, MAX_KEY_BYTES));
        if (serverProtocol > 0) join.setServerProtocol(serverProtocol);
        return entry(IntakeMessage.newBuilder().setJoin(join).build());
    }

    public static byte[] leave(UUID player) {
        return entry(IntakeMessage.newBuilder()
                .setLeave(Leave.newBuilder().setPlayer(ByteString.copyFrom(uuid(player))))
                .build());
    }

    /** An {@code Intake} holding one message: a queued record, which concatenates with others into one. */
    private static byte[] entry(IntakeMessage message) {
        return Intake.newBuilder().addMessages(message).build().toByteArray();
    }

    /** A {@code META} record's body: the player's own combat settings. */
    public static byte[] meta(Map<String, String> settings) {
        Meta.Builder meta = Meta.newBuilder();
        settings.forEach(
                (key, value) -> meta.putSettings(truncate(key, MAX_KEY_BYTES), truncate(value, MAX_STRING_BYTES)));
        return meta.build().toByteArray();
    }

    /** A {@code META} record's body: the session facts that changed, null ones left out. */
    public static byte[] meta(Integer entityId, Integer gameMode, String dimension) {
        Meta.Builder meta = Meta.newBuilder();
        if (entityId != null) meta.setEntityId(entityId);
        if (gameMode != null) meta.setGameModeValue(gameMode);
        if (dimension != null) meta.setDimensionKey(truncate(dimension, MAX_STRING_BYTES));
        return meta.build().toByteArray();
    }

    /** A {@code GAP} record's body: the whole frames lost right before this one. */
    public static byte[] gap(int lostFrames) {
        if (lostFrames == 0) return new byte[0];
        byte[] out = new byte[1 + CodedOutputStream.computeUInt64SizeNoTag(lostFrames)];
        write(out, stream -> stream.writeUInt64(1, lostFrames));
        return out;
    }

    /** A {@code WORLD} record's body: min y, then height. */
    public static byte[] world(int minY, int height) {
        int size = CodedOutputStream.computeSInt32Size(1, minY) + CodedOutputStream.computeUInt32Size(2, height);
        byte[] out = new byte[size];
        write(out, stream -> {
            stream.writeSInt32(1, minY);
            stream.writeUInt32(2, height);
        });
        return out;
    }

    /** A {@code SECTION} record's body: the column's x, the section's index, the column's z, then its block states. */
    public static byte[] section(int x, int y, int z, byte[] blocks) {
        byte[] out = new byte[positionSize(x, y, z) + CodedOutputStream.computeByteArraySize(4, blocks)];
        write(out, stream -> {
            writePosition(stream, x, y, z);
            stream.writeByteArray(4, blocks);
        });
        return out;
    }

    /** A {@code BLOCK} record's body: the block's x, y and z, then its block-state id in the server's registry. */
    public static byte[] block(int x, int y, int z, int state) {
        byte[] out = new byte[positionSize(x, y, z) + CodedOutputStream.computeUInt32Size(4, state)];
        write(out, stream -> {
            writePosition(stream, x, y, z);
            stream.writeUInt32(4, state);
        });
        return out;
    }

    /** A {@code SECTION_GONE} record's body: the column's x, the section's index, the column's z. */
    public static byte[] sectionGone(int x, int y, int z) {
        byte[] out = new byte[positionSize(x, y, z)];
        write(out, stream -> writePosition(stream, x, y, z));
        return out;
    }

    /** Fields 1 to 3 of a terrain record: x, y and z, zigzag-encoded. */
    private static int positionSize(int x, int y, int z) {
        return CodedOutputStream.computeSInt32Size(1, x)
                + CodedOutputStream.computeSInt32Size(2, y)
                + CodedOutputStream.computeSInt32Size(3, z);
    }

    private static void writePosition(CodedOutputStream stream, int x, int y, int z) throws IOException {
        stream.writeSInt32(1, x);
        stream.writeSInt32(2, y);
        stream.writeSInt32(3, z);
    }

    /** A check that flagged, and its violation level after the flag in hundredths; the wire caps it at an int. */
    public record Flag(String check, long hundredths) {
        public static Flag of(String check, double violations) {
            return new Flag(check, Math.round(violations * 100));
        }
    }

    /** The crosshair on the last attacked target: {@code reach} to it, null when the crosshair is off it. */
    public record Target(int entityId, Double reach) {}

    /** One attack's lag-compensated geometry; {@code box} is min then max, {@code eyes} x, y, z per eye. */
    public record Geometry(int attack, String type, double[] box, double[] eyes, Double reach) {}

    /**
     * The prediction engine's state after one tick. {@code ground} is -1 until prediction has run; fall distances are
     * in 1/64 block; {@code velocity} says prediction applied a server velocity this tick, proven by the ping
     * {@code velocityPing} (a wire id; null when CultAC has no id for it).
     */
    public record Engine(
            int tick,
            int ground,
            int noJumpDelay,
            int fall,
            int fallMin,
            int fallMax,
            int context,
            int friction,
            boolean usingItem,
            int forward,
            int strafe,
            boolean sneaking,
            boolean velocity,
            Integer velocityPing) {}

    /**
     * Writes VERDICT bodies into one array it keeps and grows, valid until the next write: fields in number order, a
     * nested message's length computed before it is written. Engine fields sent on change go out when they differ from
     * {@code last}, all of them when {@code last} is null. One per stream; only touched on its event loop.
     */
    public static final class VerdictWriter {
        private byte[] buffer = new byte[256];

        public byte[] bytes() {
            return buffer;
        }

        /**
         * Writes a VERDICT and returns its length. With {@code missing}, only that it went out without CultAC's verdict,
         * and the outcomes. {@code velocityPing} is the VELOCITY ordinal, negative when the ping is not in the capture;
         * {@code outcomes} are encoded {@code Outcome} messages, carried by the frame of seq {@code frameSeq}.
         */
        public int write(
                long bits,
                long tickEndSeq,
                boolean missing,
                List<Flag> flags,
                Target target,
                List<Geometry> geometries,
                Engine engine,
                Engine last,
                long velocityPing,
                long frameSeq,
                List<byte[]> outcomes) {
            int flagCount = Math.min(flags.size(), MAX_FLAGS);
            int geometryCount = Math.min(geometries.size(), MAX_GEOMETRIES);
            int size = outcomesSize(frameSeq, outcomes);
            size += missing
                    ? CodedOutputStream.computeBoolSize(VERDICT_MISSING, true)
                    : verdictSize(
                            bits,
                            tickEndSeq,
                            flags,
                            flagCount,
                            target,
                            geometries,
                            geometryCount,
                            engine,
                            last,
                            velocityPing);
            if (size > buffer.length) buffer = new byte[Math.max(size, buffer.length * 2)];
            CodedOutputStream out = CodedOutputStream.newInstance(buffer, 0, size);
            try {
                if (missing) {
                    out.writeBool(VERDICT_MISSING, true);
                    writeOutcomes(out, frameSeq, outcomes);
                } else {
                    if (bits != 0) out.writeUInt64(CONDITIONS, bits);
                    if (target != null) {
                        out.writeTag(TARGET, 2);
                        out.writeUInt32NoTag(targetSize(target));
                        writeTarget(out, target);
                    }
                    for (int i = 0; i < geometryCount; i++) {
                        Geometry geometry = geometries.get(i);
                        if (!valid(geometry)) continue;
                        out.writeTag(GEOMETRY, 2);
                        out.writeUInt32NoTag(geometrySize(geometry));
                        writeGeometry(out, geometry);
                    }
                    out.writeUInt64(TICK_END_SEQ, tickEndSeq);
                    for (int i = 0; i < flagCount; i++) {
                        Flag flag = flags.get(i);
                        out.writeTag(FLAGS, 2);
                        out.writeUInt32NoTag(flagSize(flag));
                        writeFlag(out, flag);
                    }
                    writeOutcomes(out, frameSeq, outcomes);
                    if (engine != null) writeEngine(out, engine, last, velocityPing);
                }
                out.checkNoSpaceLeft();
            } catch (IOException impossible) {
                throw new UncheckedIOException(impossible);
            }
            return size;
        }

        private static int verdictSize(
                long bits,
                long tickEndSeq,
                List<Flag> flags,
                int flagCount,
                Target target,
                List<Geometry> geometries,
                int geometryCount,
                Engine engine,
                Engine last,
                long velocityPing) {
            int size = bits != 0 ? CodedOutputStream.computeUInt64Size(CONDITIONS, bits) : 0;
            if (target != null) size += nested(TARGET, targetSize(target));
            for (int i = 0; i < geometryCount; i++) {
                Geometry geometry = geometries.get(i);
                if (valid(geometry)) size += nested(GEOMETRY, geometrySize(geometry));
            }
            size += CodedOutputStream.computeUInt64Size(TICK_END_SEQ, tickEndSeq);
            for (int i = 0; i < flagCount; i++) size += nested(FLAGS, flagSize(flags.get(i)));
            if (engine != null) size += engineSize(engine, last, velocityPing);
            return size;
        }
    }

    private static int nested(int field, int size) {
        return CodedOutputStream.computeTagSize(field) + CodedOutputStream.computeUInt32SizeNoTag(size) + size;
    }

    private static boolean onTarget(Target target) {
        return target.reach() != null && Double.isFinite(target.reach()) && target.reach() >= 0;
    }

    private static int targetSize(Target target) {
        int size = target.entityId() != 0 ? CodedOutputStream.computeUInt32Size(1, target.entityId()) : 0;
        return onTarget(target) ? size + CodedOutputStream.computeDoubleSize(2, target.reach()) : size;
    }

    private static void writeTarget(CodedOutputStream out, Target target) throws IOException {
        if (target.entityId() != 0) out.writeUInt32(1, target.entityId());
        if (onTarget(target)) out.writeDouble(2, target.reach());
    }

    private static boolean valid(Geometry geometry) {
        int eyes = Math.min(geometry.eyes().length / 3, MAX_EYES);
        if (geometry.attack() < 0 || geometry.box().length != 6 || eyes == 0) return false;
        for (double bound : geometry.box()) if (!Double.isFinite(bound)) return false;
        for (int axis = 0; axis < 3; axis++) if (geometry.box()[axis] > geometry.box()[axis + 3]) return false;
        for (int i = 0; i < eyes * 3; i++) if (!Double.isFinite(geometry.eyes()[i])) return false;
        return true;
    }

    private static boolean hasReach(Geometry geometry) {
        return geometry.reach() != null && Double.isFinite(geometry.reach()) && geometry.reach() >= 0;
    }

    private static int geometrySize(Geometry geometry) {
        int eyes = Math.min(geometry.eyes().length / 3, MAX_EYES);
        int size = geometry.attack() != 0 ? CodedOutputStream.computeUInt32Size(1, geometry.attack()) : 0;
        size += CodedOutputStream.computeStringSize(2, truncate(geometry.type(), MAX_STRING_BYTES));
        size += nested(3, 6 * 8);
        size += nested(4, eyes * 3 * 8);
        return hasReach(geometry) ? size + CodedOutputStream.computeDoubleSize(5, geometry.reach()) : size;
    }

    private static void writeGeometry(CodedOutputStream out, Geometry geometry) throws IOException {
        int eyes = Math.min(geometry.eyes().length / 3, MAX_EYES);
        if (geometry.attack() != 0) out.writeUInt32(1, geometry.attack());
        out.writeString(2, truncate(geometry.type(), MAX_STRING_BYTES));
        out.writeTag(3, 2);
        out.writeUInt32NoTag(6 * 8);
        for (double bound : geometry.box()) out.writeDoubleNoTag(bound);
        out.writeTag(4, 2);
        out.writeUInt32NoTag(eyes * 3 * 8);
        for (int i = 0; i < eyes * 3; i++) out.writeDoubleNoTag(geometry.eyes()[i]);
        if (hasReach(geometry)) out.writeDouble(5, geometry.reach());
    }

    private static int hundredths(Flag flag) {
        return (int) Math.min(Math.max(0, flag.hundredths()), Integer.MAX_VALUE);
    }

    private static int flagSize(Flag flag) {
        int size = CodedOutputStream.computeStringSize(1, truncate(flag.check(), MAX_KEY_BYTES));
        int hundredths = hundredths(flag);
        return hundredths != 0 ? size + CodedOutputStream.computeUInt32Size(2, hundredths) : size;
    }

    private static void writeFlag(CodedOutputStream out, Flag flag) throws IOException {
        out.writeString(1, truncate(flag.check(), MAX_KEY_BYTES));
        int hundredths = hundredths(flag);
        if (hundredths != 0) out.writeUInt32(2, hundredths);
    }

    private static int outcomesSize(long frameSeq, List<byte[]> outcomes) {
        if (outcomes.isEmpty()) return 0;
        return nested(OUTCOMES, outcomesBodySize(frameSeq, outcomes));
    }

    private static int outcomesBodySize(long frameSeq, List<byte[]> outcomes) {
        int size = frameSeq > 0 ? CodedOutputStream.computeUInt64Size(1, frameSeq) : 0;
        for (byte[] outcome : outcomes) size += nested(2, outcome.length);
        return size;
    }

    private static void writeOutcomes(CodedOutputStream out, long frameSeq, List<byte[]> outcomes) throws IOException {
        if (outcomes.isEmpty()) return;
        out.writeTag(OUTCOMES, 2);
        out.writeUInt32NoTag(outcomesBodySize(frameSeq, outcomes));
        if (frameSeq > 0) out.writeUInt64(1, frameSeq);
        for (byte[] outcome : outcomes) out.writeByteArray(2, outcome);
    }

    private static boolean full(Engine last) {
        return last == null || last.ground() < 0;
    }

    private static boolean fallChanged(Engine now, Engine last) {
        return full(last)
                || last.fall() != now.fall()
                || last.fallMin() != now.fallMin()
                || last.fallMax() != now.fallMax();
    }

    private static boolean contextChanged(Engine now, Engine last) {
        return full(last) || last.context() != now.context() || last.friction() != now.friction();
    }

    private static boolean inputChanged(Engine now, Engine last) {
        return full(last)
                || last.forward() != now.forward()
                || last.strafe() != now.strafe()
                || last.sneaking() != now.sneaking();
    }

    private static int fallSize(Engine now) {
        return uint32Size(1, now.fall()) + uint32Size(2, now.fallMin()) + uint32Size(3, now.fallMax());
    }

    private static int contextSize(Engine now) {
        return uint32Size(1, now.context()) + uint32Size(2, now.friction());
    }

    private static int inputSize(Engine now) {
        return (now.forward() != 0 ? CodedOutputStream.computeSInt32Size(1, now.forward()) : 0)
                + (now.strafe() != 0 ? CodedOutputStream.computeSInt32Size(2, now.strafe()) : 0)
                + (now.sneaking() ? CodedOutputStream.computeBoolSize(3, true) : 0);
    }

    private static int uint32Size(int field, int value) {
        return value != 0 ? CodedOutputStream.computeUInt32Size(field, value) : 0;
    }

    private static int velocitySize(long velocityPing) {
        return velocityPing >= 0 ? CodedOutputStream.computeUInt64Size(1, velocityPing) : 0;
    }

    private static int engineSize(Engine now, Engine last, long velocityPing) {
        int size = now.tick() != 0 ? CodedOutputStream.computeUInt64Size(ENGINE_TICK, now.tick()) : 0;
        if (now.ground() >= 0) {
            if (full(last) || last.ground() != now.ground()) {
                size += CodedOutputStream.computeEnumSize(GROUND, now.ground());
            }
            if (full(last) || last.noJumpDelay() != now.noJumpDelay()) {
                size += CodedOutputStream.computeUInt32Size(NO_JUMP_DELAY, now.noJumpDelay());
            }
        }
        if (now.velocity()) size += nested(VELOCITY, velocitySize(velocityPing));
        if (now.ground() >= 0) {
            if (fallChanged(now, last)) size += nested(FALL, fallSize(now));
            if (contextChanged(now, last)) size += nested(CONTEXT, contextSize(now));
        }
        if (last == null || last.usingItem() != now.usingItem()) {
            size += CodedOutputStream.computeBoolSize(USING_ITEM, now.usingItem());
        }
        if (now.ground() >= 0 && inputChanged(now, last)) size += nested(INPUT, inputSize(now));
        return size;
    }

    private static void writeEngine(CodedOutputStream out, Engine now, Engine last, long velocityPing)
            throws IOException {
        if (now.tick() != 0) out.writeUInt64(ENGINE_TICK, now.tick());
        if (now.ground() >= 0) {
            if (full(last) || last.ground() != now.ground()) out.writeEnum(GROUND, now.ground());
            if (full(last) || last.noJumpDelay() != now.noJumpDelay()) {
                out.writeUInt32(NO_JUMP_DELAY, now.noJumpDelay());
            }
        }
        if (now.velocity()) {
            out.writeTag(VELOCITY, 2);
            out.writeUInt32NoTag(velocitySize(velocityPing));
            if (velocityPing >= 0) out.writeUInt64(1, velocityPing);
        }
        if (now.ground() >= 0) {
            if (fallChanged(now, last)) {
                out.writeTag(FALL, 2);
                out.writeUInt32NoTag(fallSize(now));
                if (now.fall() != 0) out.writeUInt32(1, now.fall());
                if (now.fallMin() != 0) out.writeUInt32(2, now.fallMin());
                if (now.fallMax() != 0) out.writeUInt32(3, now.fallMax());
            }
            if (contextChanged(now, last)) {
                out.writeTag(CONTEXT, 2);
                out.writeUInt32NoTag(contextSize(now));
                if (now.context() != 0) out.writeUInt32(1, now.context());
                if (now.friction() != 0) out.writeUInt32(2, now.friction());
            }
        }
        if (last == null || last.usingItem() != now.usingItem()) out.writeBool(USING_ITEM, now.usingItem());
        if (now.ground() >= 0 && inputChanged(now, last)) {
            out.writeTag(INPUT, 2);
            out.writeUInt32NoTag(inputSize(now));
            if (now.forward() != 0) out.writeSInt32(1, now.forward());
            if (now.strafe() != 0) out.writeSInt32(2, now.strafe());
            if (now.sneaking()) out.writeBool(3, true);
        }
    }

    /**
     * The id of a probe whose body is a 4-byte id alone, {@code id} being its packet id; null for any other packet. Only
     * such a probe goes out as a marker.
     */
    public static Integer bareId(byte[] packet, int id) {
        if (id < 0 || packet.length != varLongBytes(id) + 4) return null;
        return pingId(packet, id);
    }

    /** A clientbound ping's wire id from its packet bytes, {@code id} being the ping's packet id; null if not one. */
    public static Integer pingId(byte[] packet, int id) {
        long wireId = pingId(packet, id, Long.MIN_VALUE);
        return wireId == Long.MIN_VALUE ? null : (int) wireId;
    }

    /** As {@link #pingId(byte[], int)}, {@code absent} where that is null. */
    public static long pingId(byte[] packet, int id, long absent) {
        if (packetId(packet) != id || id < 0) return absent;
        int start = varLongBytes(id);
        if (packet.length < start + 4) return absent;
        return (packet[start] & 0xFF) << 24
                | (packet[start + 1] & 0xFF) << 16
                | (packet[start + 2] & 0xFF) << 8
                | packet[start + 3] & 0xFF;
    }

    /** A dimension the service takes: sections whole, within the build limits it knows. */
    public static boolean validDimension(int minY, int height) {
        return minY % 16 == 0 && height > 0 && height % 16 == 0 && minY >= -2032 && minY + height <= 2032;
    }

    /**
     * The bytes a record of {@code length} body bytes takes in a frame, its own field in the frame included: a
     * clientbound one with its server tick delta and ping kind, a whole pong with its {@code skipped} pings, in protocol
     * 5's fields when {@code compact}.
     */
    public static long captureBytes(
            int kind, long sinceLast, int length, int tickDelta, int pingKind, int skipped, boolean compact) {
        int record = recordSize(kind, sinceLast, length, tickDelta, pingKind, skipped, compact);
        return 1 + CodedOutputStream.computeUInt32SizeNoTag(record) + record;
    }

    private static int recordSize(
            int kind, long sinceLast, int length, int tickDelta, int pingKind, int skipped, boolean compact) {
        int size = kind == PING_MARKER
                ? CodedOutputStream.computeEnumSize(PING_MARKER, pingKind)
                : CodedOutputStream.computeTagSize(kind) + CodedOutputStream.computeUInt32SizeNoTag(length) + length;
        if (sinceLast != 0) size += CodedOutputStream.computeUInt64Size(ADVANCE_NANOS, sinceLast);
        if (tickDelta != 0) {
            size += CodedOutputStream.computeSInt32Size(compact ? TICK_DELTA : SERVER_TICK_DELTA, tickDelta);
        }
        if (pingKind != 0 && kind != PING_MARKER) size += CodedOutputStream.computeEnumSize(PING_KIND, pingKind);
        if (skipped != 0) size += CodedOutputStream.computeUInt32Size(SKIPPED_PINGS, skipped);
        return size;
    }

    /**
     * Gathers one frame's records, then writes the FRAME into one array of its exact size; the bodies are copied once,
     * there. Reusable after {@link #clear()}; {@link #compact} writes protocol 5's fields.
     */
    public static final class FrameBuilder {
        private int count;
        private int[] kinds = new int[32];
        private long[] sinceLast = new long[32];
        private byte[][] payloads = new byte[32][];
        private int[] lengths = new int[32];
        private int[] tickDeltas = new int[32];
        private int[] pingKinds = new int[32];
        private int[] skips = new int[32];
        private long serverTick = -1;
        private long bytes;
        private boolean compact;

        /**
         * Whether records go out in protocol 5's fields: clock advances in microseconds, the tick delta in field 13, and
         * {@link #PING_MARKER} bodies. Set while the builder is empty.
         */
        public void compact(boolean compact) {
            this.compact = compact;
        }

        /**
         * Appends a record: its body field, the clock's advance since the previous record as the wire carries it
         * (nanoseconds, or microseconds when compact), and the body, the first {@code length} bytes of {@code payload}.
         * A clientbound record has its server tick minus the previous clientbound record's ({@link #serverTick(long)}
         * for the first) and a ping's {@code PingKind}, 0 for none. On protocol 5 a {@link #PING_MARKER}'s value is its
         * {@code pingKind}, and a pong sent whole carries the {@code skipped} pending pings before the one it answers.
         */
        public void add(
                int kind, long sinceLast, byte[] payload, int length, int tickDelta, int pingKind, int skipped) {
            if (count == kinds.length) {
                kinds = Arrays.copyOf(kinds, count * 2);
                this.sinceLast = Arrays.copyOf(this.sinceLast, count * 2);
                payloads = Arrays.copyOf(payloads, count * 2);
                lengths = Arrays.copyOf(lengths, count * 2);
                tickDeltas = Arrays.copyOf(tickDeltas, count * 2);
                pingKinds = Arrays.copyOf(pingKinds, count * 2);
                skips = Arrays.copyOf(skips, count * 2);
            }
            kinds[count] = kind;
            this.sinceLast[count] = sinceLast;
            payloads[count] = payload;
            lengths[count] = length;
            tickDeltas[count] = tickDelta;
            pingKinds[count] = pingKind;
            skips[count] = skipped;
            count++;
            bytes += captureBytes(kind, sinceLast, length, tickDelta, pingKind, skipped, compact);
        }

        /** The server tick of the frame's first clientbound record. */
        public void serverTick(long tick) {
            bytes -= serverTickBytes();
            serverTick = tick;
            bytes += serverTickBytes();
        }

        private int serverTickBytes() {
            return serverTick < 0 ? 0 : CodedOutputStream.computeUInt64Size(FRAME_SERVER_TICK, serverTick);
        }

        /** The records' bytes so far. */
        public long bytes() {
            return bytes;
        }

        /** The FRAME, as one {@code Intake} entry. */
        public byte[] build(UUID player, long seq) {
            int frame =
                    Math.toIntExact(18 + (seq != 0 ? CodedOutputStream.computeUInt64Size(FRAME_SEQ, seq) : 0) + bytes);
            int message = nested(FRAME, frame);
            byte[] out = new byte[nested(MESSAGES, message)];
            CodedOutputStream stream = CodedOutputStream.newInstance(out);
            try {
                stream.writeTag(MESSAGES, 2);
                stream.writeUInt32NoTag(message);
                stream.writeTag(FRAME, 2);
                stream.writeUInt32NoTag(frame);
                stream.writeTag(FRAME_PLAYER, 2);
                stream.writeUInt32NoTag(16);
                stream.writeFixed64NoTag(Long.reverseBytes(player.getMostSignificantBits()));
                stream.writeFixed64NoTag(Long.reverseBytes(player.getLeastSignificantBits()));
                if (seq != 0) stream.writeUInt64(FRAME_SEQ, seq);
                for (int i = 0; i < count; i++) {
                    stream.writeTag(FRAME_RECORDS, 2);
                    stream.writeUInt32NoTag(recordSize(
                            kinds[i], sinceLast[i], lengths[i], tickDeltas[i], pingKinds[i], skips[i], compact));
                    if (kinds[i] == PING_MARKER) {
                        stream.writeEnum(PING_MARKER, pingKinds[i]);
                    } else {
                        stream.writeTag(kinds[i], 2);
                        stream.writeUInt32NoTag(lengths[i]);
                        stream.writeRawBytes(payloads[i], 0, lengths[i]);
                    }
                    if (sinceLast[i] != 0) stream.writeUInt64(ADVANCE_NANOS, sinceLast[i]);
                    if (tickDeltas[i] != 0) stream.writeSInt32(compact ? TICK_DELTA : SERVER_TICK_DELTA, tickDeltas[i]);
                    if (pingKinds[i] != 0 && kinds[i] != PING_MARKER) stream.writeEnum(PING_KIND, pingKinds[i]);
                    if (skips[i] != 0) stream.writeUInt32(SKIPPED_PINGS, skips[i]);
                }
                if (serverTick >= 0) stream.writeUInt64(FRAME_SERVER_TICK, serverTick);
                stream.checkNoSpaceLeft();
            } catch (IOException impossible) {
                throw new UncheckedIOException(impossible);
            }
            return out;
        }

        public void clear() {
            Arrays.fill(payloads, 0, count, null);
            count = 0;
            bytes = 0;
            serverTick = -1;
        }
    }

    /**
     * Writes whole records as one socket message: a flags byte, then the records, deflated. One per writing thread; a
     * message stays valid until the next.
     */
    public static final class MessageWriter {
        private final Deflater deflater = new Deflater(Deflater.BEST_SPEED, true);
        private byte[] buffer = new byte[64 * 1024];

        public ByteBuffer write(List<byte[]> records) {
            deflater.reset();
            buffer[0] = COMPRESSED;
            int position = 1;
            for (byte[] record : records) {
                deflater.setInput(record);
                while (!deflater.needsInput()) position = deflate(position);
            }
            deflater.finish();
            while (!deflater.finished()) position = deflate(position);
            return ByteBuffer.wrap(buffer, 0, position);
        }

        private int deflate(int position) {
            if (position == buffer.length) buffer = Arrays.copyOf(buffer, buffer.length * 2);
            return position + deflater.deflate(buffer, position, buffer.length - position);
        }
    }

    /**
     * Writes protocol 5 messages as one socket's zstd frame: one compression context at {@link #LEVEL} and window log
     * {@link #WINDOW_LOG} kept across messages, each message ending at a flush so it decompresses on arrival. The frame
     * is never ended. The receiver decompresses the messages in the order written, so each must be sent, in that order,
     * or the socket closed. One per socket, used by one thread at a time; a message stays valid until the next. Needs
     * {@link #zstdUnavailable} to be null.
     */
    public static final class StreamWriter implements AutoCloseable {
        public static final int LEVEL = 3;
        /** The receiver's window limit (2 MiB); the frame header declares it. */
        public static final int WINDOW_LOG = 21;

        private final ZstdCompressCtx stream = new ZstdCompressCtx()
                .setLevel(LEVEL)
                .setWindowLog(WINDOW_LOG)
                .setChecksum(false)
                .setContentSize(false);
        /**
         * The buffers' resting size, the size past which they drop back to it, and the run of messages no larger than
         * it that drops them, so a burst followed by small messages does not reallocate them each time.
         */
        private static final int RESTING_BYTES = 64 * 1024;

        private static final int SHRINK_PAST_BYTES = 256 * 1024;
        private static final int SHRINK_AFTER_MESSAGES = 64;
        /** The context reads and writes direct buffers: a heap array would be copied whole across JNI on each call. */
        private ByteBuffer input = ByteBuffer.allocateDirect(RESTING_BYTES);

        private ByteBuffer output = ByteBuffer.allocateDirect(RESTING_BYTES);
        private byte[] buffer = new byte[RESTING_BYTES];
        /** Messages in a row no larger than {@link #RESTING_BYTES}. */
        private int small;

        /** {@code bytes} is the records' total length. */
        public ByteBuffer write(List<byte[]> records, int bytes) {
            small = bytes <= RESTING_BYTES ? small + 1 : 0;
            if (small >= SHRINK_AFTER_MESSAGES
                    && (input.capacity() > SHRINK_PAST_BYTES
                            || output.capacity() > SHRINK_PAST_BYTES
                            || buffer.length > SHRINK_PAST_BYTES)) {
                input = ByteBuffer.allocateDirect(RESTING_BYTES);
                output = ByteBuffer.allocateDirect(RESTING_BYTES);
                buffer = new byte[RESTING_BYTES];
                small = 0;
            }
            if (input.capacity() < bytes) input = ByteBuffer.allocateDirect(bytes);
            input.clear();
            for (byte[] record : records) input.put(record);
            input.flip();
            int bound = (int) Zstd.compressBound(bytes);
            if (output.capacity() < bound) output = ByteBuffer.allocateDirect(bound);
            output.clear();
            while (!stream.compressDirectByteBufferStream(output, input, EndDirective.FLUSH)) {
                if (!output.hasRemaining())
                    output = ByteBuffer.allocateDirect(output.capacity() * 2).put(output.flip());
            }
            output.flip();
            int length = 1 + output.remaining();
            if (buffer.length < length) buffer = new byte[Math.max(length, buffer.length * 2)];
            buffer[0] = COMPRESSED;
            output.get(buffer, 1, length - 1);
            return ByteBuffer.wrap(buffer, 0, length);
        }

        /** Frees the native context; the frame is left unended. */
        @Override
        public void close() {
            stream.close();
        }
    }

    /** Why protocol 5's compression cannot run here, or null when it can; loads zstd's native library on first call. */
    public static String zstdUnavailable() {
        return ZstdLibrary.UNAVAILABLE;
    }

    private static final class ZstdLibrary {
        public static final String UNAVAILABLE = load();

        private static String load() {
            try {
                Native.load();
                return null;
            } catch (Throwable failed) {
                return String.valueOf(failed);
            }
        }
    }

    public sealed interface Reply permits Capture, Refused, Event, DiagLimitsReply, DiagWatchReply, DiagCutReply {
        /** The player it is for; null for a reply to the whole socket. */
        UUID player();
    }

    public record Capture(UUID player, int[] serverbound, int[] clientbound, int[] dimension) implements Reply {}

    public record Refused(UUID player, String reason) implements Reply {}

    public record Event(UUID player, long tick, String checkId, String category, double confidence, String detail)
            implements Reply {}

    /** Diagnostics: the socket's new limits. */
    public record DiagLimitsReply(Limits limits) implements Reply {
        @Override
        public UUID player() {
            return null;
        }
    }

    /** Diagnostics: watch the player for {@code leaseSeconds} from the call; 0 stops. */
    public record DiagWatchReply(UUID player, int leaseSeconds) implements Reply {}

    /** Diagnostics: send the player's SuperDebug of a range. */
    public record DiagCutReply(UUID player, Cut cut) implements Reply {}

    /** A whole message from the service. Kinds this client does not know are skipped. */
    public static List<Reply> replies(byte[] message) {
        Replies parsed;
        try {
            parsed = Replies.parseFrom(message);
        } catch (InvalidProtocolBufferException malformed) {
            throw new IllegalArgumentException("malformed replies: " + malformed.getMessage());
        }
        List<Reply> replies = new ArrayList<>(parsed.getRepliesCount());
        for (var reply : parsed.getRepliesList()) {
            switch (reply.getKindCase()) {
                case CAPTURE -> {
                    var capture = reply.getCapture();
                    replies.add(new Capture(
                            uuid(capture.getPlayer()),
                            ids(capture.getServerboundList()),
                            ids(capture.getClientboundList()),
                            ids(capture.getDimensionList())));
                }
                case REFUSED ->
                    replies.add(new Refused(
                            uuid(reply.getRefused().getPlayer()),
                            reply.getRefused().getReason()));
                case EVENT -> {
                    var event = reply.getEvent();
                    replies.add(new Event(
                            uuid(event.getPlayer()),
                            event.getTick(),
                            truncate(event.getCheckId(), MAX_STRING_BYTES),
                            truncate(event.getCategory(), MAX_STRING_BYTES),
                            event.getConfidence(),
                            truncate(event.getDetail(), MAX_DETAIL_BYTES)));
                }
                case DIAG_LIMITS -> replies.add(new DiagLimitsReply(limits(reply.getDiagLimits())));
                case DIAG_WATCH -> {
                    var watch = reply.getDiagWatch();
                    UUID player = diagPlayer(watch.getPlayer());
                    if (player != null) {
                        replies.add(new DiagWatchReply(player, (int)
                                Math.min(Integer.MAX_VALUE, Integer.toUnsignedLong(watch.getLeaseSeconds()))));
                    }
                }
                case DIAG_CUT -> {
                    var cut = reply.getDiagCut();
                    UUID player = diagPlayer(cut.getPlayer());
                    if (player != null) {
                        replies.add(new DiagCutReply(
                                player,
                                new Cut(
                                        cut.getBundle().toByteArray(),
                                        cut.getFromSeq(),
                                        cut.getToSeq(),
                                        cut.getFullFromSeq(),
                                        cut.getFullToSeq())));
                    }
                }
                default -> {}
            }
        }
        return replies;
    }

    /** {@code DiagLimits} as this build takes it: every value clamped to its compiled maximum. */
    public static Limits limits(DiagLimits limits) {
        return Limits.of(
                limits.getEnabled(),
                Integer.toUnsignedLong(limits.getRingTicks()),
                Integer.toUnsignedLong(limits.getPlayerRingBytes()),
                Integer.toUnsignedLong(limits.getServerRingBytes()),
                Integer.toUnsignedLong(limits.getMaxPlayers()),
                Integer.toUnsignedLong(limits.getPartBytes()),
                Integer.toUnsignedLong(limits.getUploadBytesPerSecond()),
                Integer.toUnsignedLong(limits.getBundleBytes()),
                Integer.toUnsignedLong(limits.getBundlesPerHour()));
    }

    /** A diagnostics reply's player; null, and counted, when malformed: it is dropped, never the whole message. */
    private static UUID diagPlayer(ByteString bytes) {
        if (bytes.size() == 16) return uuid(bytes);
        Diagnostics.malformed();
        return null;
    }

    private static int[] ids(List<Integer> ids) {
        int[] out = new int[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
        return out;
    }

    public static byte[] uuid(UUID player) {
        return ByteBuffer.allocate(16)
                .putLong(player.getMostSignificantBits())
                .putLong(player.getLeastSignificantBits())
                .array();
    }

    public static UUID uuid(ByteString bytes) {
        if (bytes.size() != 16) throw new IllegalArgumentException("player of " + bytes.size() + " bytes");
        ByteBuffer buffer = bytes.asReadOnlyByteBuffer();
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private interface Body {
        void write(CodedOutputStream stream) throws IOException;
    }

    private static void write(byte[] out, Body body) {
        CodedOutputStream stream = CodedOutputStream.newInstance(out);
        try {
            body.write(stream);
            stream.checkNoSpaceLeft();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private static String truncate(String value, int maxBytes) {
        if (value.length() <= maxBytes / 3) return value;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return bytes.length <= maxBytes
                ? value
                : new String(bytes, 0, maxBytes, StandardCharsets.UTF_8).replace("\uFFFD", "");
    }

    private static int varLongBytes(long value) {
        int bytes = 1;
        while ((value & ~0x7FL) != 0) {
            value >>>= 7;
            bytes++;
        }
        return bytes;
    }

    /** The packet id leading a packet's bytes; -1 when they do not start with a whole varint. */
    public static int packetId(byte[] packet) {
        int value = 0;
        for (int i = 0; i < 5 && i < packet.length; i++) {
            int b = packet[i] & 0xFF;
            value |= (b & 0x7F) << 7 * i;
            if ((b & 0x80) == 0) return value;
        }
        return -1;
    }
}
