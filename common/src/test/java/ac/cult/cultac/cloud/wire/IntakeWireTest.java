package ac.cult.cultac.cloud.wire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.intake.v3.Capture;
import ac.cult.cultac.cloud.intake.v3.Condition;
import ac.cult.cultac.cloud.intake.v3.Edition;
import ac.cult.cultac.cloud.intake.v3.Event;
import ac.cult.cultac.cloud.intake.v3.Frame;
import ac.cult.cultac.cloud.intake.v3.GameMode;
import ac.cult.cultac.cloud.intake.v3.Gap;
import ac.cult.cultac.cloud.intake.v3.Geometry;
import ac.cult.cultac.cloud.intake.v3.Ground;
import ac.cult.cultac.cloud.intake.v3.Intake;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.Join;
import ac.cult.cultac.cloud.intake.v3.Meta;
import ac.cult.cultac.cloud.intake.v3.Outcome;
import ac.cult.cultac.cloud.intake.v3.Record;
import ac.cult.cultac.cloud.intake.v3.Refused;
import ac.cult.cultac.cloud.intake.v3.Replies;
import ac.cult.cultac.cloud.intake.v3.Reply;
import ac.cult.cultac.cloud.intake.v3.Unstreamed;
import ac.cult.cultac.cloud.intake.v3.Verdict;
import ac.cult.cultac.cloud.intake.v3.World;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.junit.jupiter.api.Test;

class IntakeWireTest {
    private static final UUID PLAYER = new UUID(0x0102030405060708L, 0x090A0B0C0D0E0F10L);
    private static final ByteString PLAYER_BYTES =
            ByteString.copyFrom(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
    private static final IntakeWire.Facts BARE_FACTS =
            new IntakeWire.Facts(null, null, null, 7, 0, false, null, Map.of(), Map.of());

    private static IntakeMessage message(byte[] entry) throws InvalidProtocolBufferException {
        Intake intake = Intake.parseFrom(entry);
        assertEquals(1, intake.getMessagesCount());
        return intake.getMessages(0);
    }

    private static Verdict verdict(long bits, long tickEndSeq, boolean missing) throws InvalidProtocolBufferException {
        return verdict(bits, tickEndSeq, missing, List.of());
    }

    private static Verdict verdict(long bits, long tickEndSeq, boolean missing, List<IntakeWire.Flag> flags)
            throws InvalidProtocolBufferException {
        return verdict(bits, tickEndSeq, missing, flags, null, List.of(), null, null, -1);
    }

    /** A VERDICT as {@link IntakeWire.VerdictWriter} writes it, read back. */
    private static Verdict verdict(
            long bits,
            long tickEndSeq,
            boolean missing,
            List<IntakeWire.Flag> flags,
            IntakeWire.Target target,
            List<IntakeWire.Geometry> geometries,
            IntakeWire.Engine engine,
            IntakeWire.Engine last,
            long velocityPing)
            throws InvalidProtocolBufferException {
        IntakeWire.VerdictWriter writer = new IntakeWire.VerdictWriter();
        int size = writer.write(
                bits, tickEndSeq, missing, flags, target, geometries, engine, last, velocityPing, 0, List.of());
        return Verdict.parseFrom(Arrays.copyOf(writer.bytes(), size));
    }

    private static Verdict geometries(IntakeWire.Geometry... geometries) throws InvalidProtocolBufferException {
        return verdict(0, 1, false, List.of(), null, List.of(geometries), null, null, -1);
    }

    private static Verdict engine(long tickEndSeq, IntakeWire.Engine engine, IntakeWire.Engine last, long velocityPing)
            throws InvalidProtocolBufferException {
        return verdict(0, tickEndSeq, false, List.of(), null, List.of(), engine, last, velocityPing);
    }

    private static void add(IntakeWire.FrameBuilder builder, int kind, long sinceLast, byte[] payload) {
        builder.add(kind, sinceLast, payload, payload.length, 0, 0, 0);
    }

    @Test
    void joinWithoutFactsNamesTheClient() throws InvalidProtocolBufferException {
        Join join = message(IntakeWire.join(PLAYER, 773, "vanilla", -64, 384, null, null, 0))
                .getJoin();
        assertEquals(PLAYER_BYTES, join.getPlayer());
        assertEquals(Edition.EDITION_JAVA, join.getEdition());
        assertEquals(773, join.getClientProtocol());
        assertEquals("vanilla", join.getBrand());
        assertFalse(join.hasInputMode());
        assertEquals(-64, join.getMinY());
        assertEquals(384, join.getHeight());
        assertFalse(join.hasEntityId());
        assertFalse(join.hasName());
        assertEquals(Unstreamed.UNSTREAMED_NONE, join.getUnstreamed());
    }

    @Test
    void joinCarriesTheFactsAndTheName() throws InvalidProtocolBufferException {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("update-equipment-on-player-actions", "true");
        settings.put("keepalive-interval-ms", "1000");
        IntakeWire.Facts facts = new IntakeWire.Facts(
                -5,
                3,
                "minecraft:the_nether",
                1_700_000_000_000L,
                50_000,
                true,
                "Paper 26.3",
                settings,
                Map.of("minecraft:damage_type", List.of("minecraft:arrow", "minecraft:cactus")));
        Join join = message(IntakeWire.join(PLAYER, 773, "vanilla", -64, 384, facts, "Steve", 0))
                .getJoin();
        assertEquals(-5, join.getEntityId());
        assertEquals(GameMode.GAME_MODE_SPECTATOR, join.getGameMode());
        assertEquals("minecraft:the_nether", join.getDimensionKey());
        assertEquals(1_700_000_000_000L, join.getEpochMillisAtZero());
        assertEquals(50_000, join.getTickMicros());
        assertTrue(join.getResumed());
        assertEquals("Paper 26.3", join.getServer());
        assertEquals(settings, join.getSettingsMap());
        assertEquals(
                List.of("minecraft:arrow", "minecraft:cactus"),
                join.getRegistriesMap().get("minecraft:damage_type").getEntriesList());
        assertEquals("Steve", join.getName());
    }

    @Test
    void joinLeavesOutWhatIsUnknown() throws InvalidProtocolBufferException {
        Join join = message(IntakeWire.join(PLAYER, 773, "vanilla", -64, 384, BARE_FACTS, null, 0))
                .getJoin();
        assertFalse(join.hasEntityId());
        assertFalse(join.hasGameMode());
        assertFalse(join.hasDimensionKey());
        assertEquals(7, join.getEpochMillisAtZero());
        assertFalse(join.hasTickMicros());
        assertFalse(join.hasServer());
        assertFalse(join.hasName());
        assertTrue(join.getSettingsMap().isEmpty());
        assertTrue(join.getRegistriesMap().isEmpty());
    }

    @Test
    void protocol5JoinCarriesTheServerProtocol() throws InvalidProtocolBufferException {
        Join join = message(IntakeWire.join(PLAYER, 773, "vanilla", -64, 384, BARE_FACTS, null, 777))
                .getJoin();
        assertTrue(join.hasServerProtocol());
        assertEquals(777, join.getServerProtocol());
        assertEquals(773, join.getClientProtocol());
        assertFalse(message(IntakeWire.join(PLAYER, 773, "vanilla", -64, 384, BARE_FACTS, null, 0))
                .getJoin()
                .hasServerProtocol());
    }

    @Test
    void unstreamedJoinNamesTheEditionAndWhy() throws InvalidProtocolBufferException {
        Join bedrock = message(IntakeWire.unstreamed(PLAYER, 766, "Geyser", -64, 384, BARE_FACTS, "Alex"))
                .getJoin();
        assertEquals(Edition.EDITION_BEDROCK, bedrock.getEdition());
        assertFalse(bedrock.hasInputMode());
        assertEquals(Unstreamed.UNSTREAMED_BEDROCK, bedrock.getUnstreamed());
        assertEquals("Alex", bedrock.getName());
    }

    @Test
    void leaveNamesThePlayer() throws InvalidProtocolBufferException {
        assertEquals(PLAYER_BYTES, message(IntakeWire.leave(PLAYER)).getLeave().getPlayer());
    }

    @Test
    void frameCarriesItsSeqAndRecordsInOrder() throws InvalidProtocolBufferException {
        IntakeWire.FrameBuilder builder = new IntakeWire.FrameBuilder();
        add(builder, IntakeWire.SERVERBOUND, 1000, new byte[] {0x0C, 0x01});
        add(builder, IntakeWire.WORLD, 500, IntakeWire.world(-64, 384));
        add(builder, IntakeWire.GAP, 0, IntakeWire.gap(2));
        add(builder, IntakeWire.CLIENTBOUND, 7, new byte[] {0x2D, 0x00, 0x00, 0x00, 0x05});
        add(
                builder,
                IntakeWire.VERDICT,
                100,
                verdict(1L << IntakeWire.TELEPORTED, 300, false).toByteArray());
        Frame frame = message(builder.build(PLAYER, 300)).getFrame();

        assertEquals(PLAYER_BYTES, frame.getPlayer());
        assertEquals(300, frame.getSeq());
        List<Record> records = frame.getRecordsList();
        assertEquals(5, records.size());
        assertEquals(Record.BodyCase.SERVERBOUND, records.get(0).getBodyCase());
        assertEquals(
                ByteString.copyFrom(new byte[] {0x0C, 0x01}), records.get(0).getServerbound());
        assertEquals(1000, records.get(0).getAdvanceNanos());
        assertEquals(
                World.newBuilder().setMinY(-64).setHeight(384).build(),
                records.get(1).getWorld());
        assertEquals(500, records.get(1).getAdvanceNanos());
        assertEquals(2, records.get(2).getGap().getLostFrames());
        assertEquals(0, records.get(2).getAdvanceNanos());
        assertEquals(Record.BodyCase.CLIENTBOUND, records.get(3).getBodyCase());
        Verdict verdict = records.get(4).getVerdict();
        assertEquals(1L << IntakeWire.TELEPORTED, verdict.getConditions());
        assertEquals(300, verdict.getTickEndSeq());
    }

    @Test
    void frameBuilderSizesItsArrayExactly() throws InvalidProtocolBufferException {
        IntakeWire.FrameBuilder builder = new IntakeWire.FrameBuilder();
        for (int i = 0; i < 100; i++) add(builder, IntakeWire.CLIENTBOUND, i * 1_000_000L, new byte[i * 3]);
        byte[] partial = new byte[] {9, 9, 9, 9};
        builder.add(IntakeWire.VERDICT, 1, partial, 0, 0, 0, 0);
        long records = builder.bytes();
        byte[] entry = builder.build(PLAYER, 1L << 40);
        Frame frame = message(entry).getFrame();
        assertEquals(101, frame.getRecordsCount());
        assertEquals(99 * 3, frame.getRecords(99).getClientbound().size());
        assertTrue(frame.getRecords(100).hasVerdict());
        // The player, a seven-byte seq field, then the records.
        assertEquals(18 + 7 + records, frame.getSerializedSize());

        builder.clear();
        assertEquals(0, builder.bytes());
        assertEquals(0, message(builder.build(PLAYER, 0)).getFrame().getSeq());
    }

    @Test
    void gapCountsLostFrames() throws InvalidProtocolBufferException {
        assertEquals(130, Gap.parseFrom(IntakeWire.gap(130)).getLostFrames());
        assertArrayEquals(new byte[0], IntakeWire.gap(0));
    }

    @Test
    void verdictCarriesItsTickEndOrSaysItIsMissing() throws InvalidProtocolBufferException {
        Verdict verdict = verdict(1L << IntakeWire.FLYING, 12, false);
        assertEquals(1L << IntakeWire.FLYING, verdict.getConditions());
        assertTrue(verdict.hasTickEndSeq());
        assertEquals(12, verdict.getTickEndSeq());
        assertFalse(verdict.getMissing());

        Verdict first = verdict(0, 0, false);
        assertTrue(first.hasTickEndSeq());
        assertEquals(0, first.getTickEndSeq());

        Verdict missing = verdict(0, 12, true);
        assertTrue(missing.getMissing());
        assertFalse(missing.hasTickEndSeq());
        assertEquals(Verdict.newBuilder().setMissing(true).build(), missing);
    }

    @Test
    void verdictCarriesTheTicksFlagsInOrder() throws InvalidProtocolBufferException {
        String longName = "é".repeat(40);
        Verdict verdict =
                verdict(0, 3, false, List.of(IntakeWire.Flag.of("Reach", 2.5), IntakeWire.Flag.of(longName, 0.004)));
        assertEquals(2, verdict.getFlagsCount());
        assertEquals("Reach", verdict.getFlags(0).getCheck());
        assertEquals(250, verdict.getFlags(0).getViolationsHundredths());
        assertEquals("é".repeat(32), verdict.getFlags(1).getCheck());
        assertEquals(0, verdict.getFlags(1).getViolationsHundredths());
    }

    @Test
    void verdictCapsItsFlagsAndLeavesThemOutWhenMissing() throws InvalidProtocolBufferException {
        List<IntakeWire.Flag> many = new ArrayList<>();
        for (int i = 0; i < IntakeWire.MAX_FLAGS + 5; i++) many.add(IntakeWire.Flag.of("C" + i, i));
        Verdict verdict = verdict(0, 1, false, many);
        assertEquals(IntakeWire.MAX_FLAGS, verdict.getFlagsCount());
        for (int i = 0; i < IntakeWire.MAX_FLAGS; i++) {
            assertEquals("C" + i, verdict.getFlags(i).getCheck());
            assertEquals(i * 100, verdict.getFlags(i).getViolationsHundredths());
        }

        assertEquals(0, verdict(0, 1, true, many).getFlagsCount());
    }

    @Test
    void aFlagsViolationLevelFitsAnInt() throws InvalidProtocolBufferException {
        Verdict verdict = verdict(
                0,
                1,
                false,
                List.of(IntakeWire.Flag.of("Huge", 1e12), IntakeWire.Flag.of("Inf", Double.POSITIVE_INFINITY)));
        assertEquals(Integer.MAX_VALUE, verdict.getFlags(0).getViolationsHundredths());
        assertEquals(Integer.MAX_VALUE, verdict.getFlags(1).getViolationsHundredths());
    }

    @Test
    void metaCarriesTheFactsThatChanged() throws InvalidProtocolBufferException {
        Meta meta = Meta.parseFrom(IntakeWire.meta(42, 1, "minecraft:overworld"));
        assertEquals(42, meta.getEntityId());
        assertEquals(GameMode.GAME_MODE_CREATIVE, meta.getGameMode());
        assertEquals("minecraft:overworld", meta.getDimensionKey());

        Meta survival = Meta.parseFrom(IntakeWire.meta(null, 0, null));
        assertFalse(survival.hasEntityId());
        assertTrue(survival.hasGameMode());
        assertEquals(GameMode.GAME_MODE_SURVIVAL, survival.getGameMode());
        assertFalse(survival.hasDimensionKey());
    }

    @Test
    void metaCarriesThePlayersSettings() throws InvalidProtocolBufferException {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("max-no-damage-ticks", "20");
        settings.put("combat-mode", "1.9");
        assertEquals(settings, Meta.parseFrom(IntakeWire.meta(settings)).getSettingsMap());
    }

    @Test
    void messagesAreAFlagsByteThenTheEntries() throws DataFormatException, InvalidProtocolBufferException {
        byte[] join = IntakeWire.join(PLAYER, 773, "vanilla", -64, 384, null, null, 0);
        byte[] leave = IntakeWire.leave(PLAYER);
        byte[] records = new byte[join.length + leave.length];
        System.arraycopy(join, 0, records, 0, join.length);
        System.arraycopy(leave, 0, records, join.length, leave.length);
        IntakeWire.MessageWriter writer = new IntakeWire.MessageWriter();

        for (int round = 0; round < 2; round++) {
            byte[] deflated = bytes(writer.write(List.of(join, leave)));
            assertEquals(IntakeWire.COMPRESSED, deflated[0]);
            byte[] inflated = inflate(Arrays.copyOfRange(deflated, 1, deflated.length));
            assertArrayEquals(records, inflated);
            Intake intake = Intake.parseFrom(inflated);
            assertEquals(IntakeMessage.KindCase.JOIN, intake.getMessages(0).getKindCase());
            assertEquals(IntakeMessage.KindCase.LEAVE, intake.getMessages(1).getKindCase());
        }
    }

    @Test
    void largeMessagesDeflateWhole() throws DataFormatException {
        byte[] big = new byte[3 * 1024 * 1024];
        new Random(1).nextBytes(big);
        IntakeWire.MessageWriter writer = new IntakeWire.MessageWriter();
        byte[] deflated = bytes(writer.write(List.of(big)));
        assertArrayEquals(big, inflate(Arrays.copyOfRange(deflated, 1, deflated.length)));
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static byte[] inflate(byte[] deflated) throws DataFormatException {
        Inflater inflater = new Inflater(true);
        inflater.setInput(deflated);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        while (!inflater.finished()) {
            int n = inflater.inflate(chunk);
            if (n == 0 && inflater.needsInput()) break;
            out.write(chunk, 0, n);
        }
        inflater.end();
        return out.toByteArray();
    }

    @Test
    void readsTheServicesReplies() {
        Replies replies = Replies.newBuilder()
                .addReplies(Reply.newBuilder()
                        .setCapture(Capture.newBuilder()
                                .setPlayer(PLAYER_BYTES)
                                .addAllServerbound(List.of(0, 12, 300))
                                .addClientbound(5)))
                .addReplies(Reply.newBuilder()
                        .setRefused(Refused.newBuilder().setPlayer(PLAYER_BYTES).setReason("no")))
                .addReplies(Reply.newBuilder()
                        .setEvent(Event.newBuilder()
                                .setPlayer(PLAYER_BYTES)
                                .setTick(42)
                                .setCheckId("aim.snap")
                                .setCategory("AIM")
                                .setConfidence(0.5)
                                .setDetail("d")))
                .build();
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        message.writeBytes(replies.toByteArray());
        // A reply of a kind this client does not know: Reply field 9, a string.
        message.writeBytes(new byte[] {0x0A, 0x03, 0x4A, 0x01, 0x78});

        List<IntakeWire.Reply> read = IntakeWire.replies(message.toByteArray());

        assertEquals(3, read.size());
        IntakeWire.Capture capture = (IntakeWire.Capture) read.get(0);
        assertEquals(PLAYER, capture.player());
        assertArrayEquals(new int[] {0, 12, 300}, capture.serverbound());
        assertArrayEquals(new int[] {5}, capture.clientbound());
        assertArrayEquals(new int[0], capture.dimension());
        assertEquals("no", ((IntakeWire.Refused) read.get(1)).reason());
        assertEquals(new IntakeWire.Event(PLAYER, 42, "aim.snap", "AIM", 0.5, "d"), read.get(2));
    }

    @Test
    void anOversizedFindingIsCutBeforeItIsKept() {
        String huge = "x".repeat(8 * 1024 * 1024);
        byte[] replies = Replies.newBuilder()
                .addReplies(Reply.newBuilder()
                        .setEvent(Event.newBuilder()
                                .setPlayer(PLAYER_BYTES)
                                .setCheckId(huge)
                                .setCategory(huge)
                                .setDetail(huge)))
                .build()
                .toByteArray();

        IntakeWire.Event event = (IntakeWire.Event) IntakeWire.replies(replies).get(0);

        assertEquals(128, event.checkId().length());
        assertEquals(128, event.category().length());
        assertEquals(IntakeWire.MAX_DETAIL_BYTES, event.detail().length());
    }

    @Test
    void aReplyForAPlayerThatIsNotSixteenBytesIsUnreadable() {
        byte[] replies = Replies.newBuilder()
                .addReplies(
                        Reply.newBuilder().setRefused(Refused.newBuilder().setPlayer(ByteString.copyFrom(new byte[3]))))
                .build()
                .toByteArray();
        assertThrows(IllegalArgumentException.class, () -> IntakeWire.replies(replies));
    }

    @Test
    void readsThePacketId() {
        assertEquals(300, IntakeWire.packetId(new byte[] {(byte) 0xAC, 0x02, 0x55}));
        assertEquals(-1, IntakeWire.packetId(new byte[] {(byte) 0x80}));
    }

    private static final IntakeWire.Engine ENGINE = new IntakeWire.Engine(
            0, 1, 0, 160, 128, 192, 1 << IntakeWire.CONTEXT_WATER, 600, true, 98, -29, true, false, null);

    @Test
    void conditionBitsFollowTheSchemasOrder() {
        assertArrayEquals(
                new int[] {
                    Condition.CONDITION_TELEPORTED_VALUE,
                    Condition.CONDITION_IN_VEHICLE_VALUE,
                    Condition.CONDITION_FLYING_VALUE,
                    Condition.CONDITION_SPECTATOR_VALUE,
                    Condition.CONDITION_GLIDING_VALUE,
                    Condition.CONDITION_RIPTIDE_VALUE,
                    Condition.CONDITION_KNOCKBACK_VALUE,
                    Condition.CONDITION_RESPAWNED_VALUE,
                    Condition.CONDITION_JUMPED_VALUE,
                    Condition.CONDITION_SPRINT_JUMPED_VALUE,
                    Condition.CONDITION_ENGINE_EXEMPT_VALUE
                },
                new int[] {
                    IntakeWire.TELEPORTED,
                    IntakeWire.IN_VEHICLE,
                    IntakeWire.FLYING,
                    IntakeWire.SPECTATOR,
                    IntakeWire.GLIDING,
                    IntakeWire.RIPTIDE,
                    IntakeWire.KNOCKBACK,
                    IntakeWire.RESPAWNED,
                    IntakeWire.JUMPED,
                    IntakeWire.SPRINT_JUMPED,
                    IntakeWire.ENGINE_EXEMPT
                });
    }

    @Test
    void geometryIsWrittenAsTheSchemaSays() throws InvalidProtocolBufferException {
        IntakeWire.Geometry geometry = new IntakeWire.Geometry(
                1,
                "minecraft:player",
                new double[] {1, 2, 3, 1.6, 3.8, 3.6},
                new double[] {0, 65.62, 0, 0, 65.27, 0},
                2.75);
        Verdict verdict = geometries(geometry, geometry);
        assertEquals(2, verdict.getGeometryCount());
        Geometry read = verdict.getGeometry(0);
        assertEquals(1, read.getAttack());
        assertEquals("minecraft:player", read.getType());
        assertEquals(List.of(1.0, 2.0, 3.0, 1.6, 3.8, 3.6), read.getBoxList());
        assertEquals(List.of(0.0, 65.62, 0.0, 0.0, 65.27, 0.0), read.getEyesList());
        assertTrue(read.hasReach());
        assertEquals(2.75, read.getReach(), 0);
    }

    @Test
    void geometryWithoutReachOrWithBadNumbersIsLeftOutWhole() throws InvalidProtocolBufferException {
        IntakeWire.Geometry miss =
                new IntakeWire.Geometry(0, "minecraft:zombie", new double[6], new double[] {0, 1, 0}, null);
        Geometry read = geometries(miss).getGeometry(0);
        assertEquals(0, read.getAttack());
        assertEquals(6, read.getBoxCount());
        assertEquals(3, read.getEyesCount());
        assertFalse(read.hasReach());

        IntakeWire.Geometry nan = new IntakeWire.Geometry(
                0, "minecraft:zombie", new double[] {Double.NaN, 0, 0, 0, 0, 0}, new double[] {0, 1, 0}, 1.0);
        IntakeWire.Geometry noEyes = new IntakeWire.Geometry(0, "minecraft:zombie", new double[6], new double[0], 1.0);
        IntakeWire.Geometry inverted = new IntakeWire.Geometry(
                0, "minecraft:player", new double[] {2, 0, 0, 1, 1, 1}, new double[] {0, 1, 0}, 1.0);
        assertEquals(0, geometries(nan, noEyes, inverted).getGeometryCount());
    }

    @Test
    void geometryCarriesAtMostEightEyes() throws InvalidProtocolBufferException {
        IntakeWire.Geometry many = new IntakeWire.Geometry(0, "minecraft:player", new double[6], new double[30], 1.0);
        assertEquals(IntakeWire.MAX_EYES * 3, geometries(many).getGeometry(0).getEyesCount());
    }

    @Test
    void verdictCarriesAtMostFiveGeometries() throws InvalidProtocolBufferException {
        IntakeWire.Geometry geometry =
                new IntakeWire.Geometry(0, "minecraft:player", new double[6], new double[3], 1.0);
        assertEquals(
                IntakeWire.MAX_GEOMETRIES,
                geometries(geometry, geometry, geometry, geometry, geometry, geometry, geometry)
                        .getGeometryCount());
    }

    @Test
    void targetSaysWhetherTheCrosshairIsOnIt() throws InvalidProtocolBufferException {
        Verdict on = verdict(0, 1, false, List.of(), new IntakeWire.Target(77, 2.5), List.of(), null, null, -1);
        assertEquals(77, on.getTarget().getEntityId());
        assertTrue(on.getTarget().hasReach());
        assertEquals(2.5, on.getTarget().getReach(), 0);

        Verdict off = verdict(0, 1, false, List.of(), new IntakeWire.Target(-3, null), List.of(), null, null, -1);
        assertTrue(off.hasTarget());
        assertEquals(-3, off.getTarget().getEntityId());
        assertFalse(off.getTarget().hasReach());
    }

    @Test
    void engineStateGoesOutInFullThenOnChange() throws InvalidProtocolBufferException {
        Verdict full = engine(1, ENGINE, null, -1);
        assertTrue(full.hasGround());
        assertEquals(Ground.GROUND_ON, full.getGround());
        assertTrue(full.hasNoJumpDelay());
        assertEquals(0, full.getNoJumpDelay());
        assertEquals(160, full.getFall().getDistance());
        assertEquals(128, full.getFall().getLeast());
        assertEquals(192, full.getFall().getGreatest());
        assertEquals(1, full.getContext().getAllowed());
        assertEquals(600, full.getContext().getFrictionThousandths());
        assertEquals(98, full.getInput().getForward());
        assertEquals(-29, full.getInput().getStrafe());
        assertTrue(full.getInput().getSneaking());
        assertTrue(full.hasUsingItem());
        assertTrue(full.getUsingItem());
        assertFalse(full.hasVelocity());
        assertEquals(0, full.getEngineTick());

        IntakeWire.Engine moved = new IntakeWire.Engine(
                1 << IntakeWire.ENGINE_MOVED_UP, 0, 0, 160, 128, 192, 1, 600, true, 98, -29, true, true, 12345);
        Verdict changed = engine(2, moved, ENGINE, 41);
        assertEquals(1 << IntakeWire.ENGINE_MOVED_UP, changed.getEngineTick());
        assertTrue(changed.hasGround());
        assertEquals(Ground.GROUND_OFF, changed.getGround());
        assertFalse(changed.hasNoJumpDelay());
        assertFalse(changed.hasFall());
        assertFalse(changed.hasContext());
        assertFalse(changed.hasInput());
        assertFalse(changed.hasUsingItem());
        assertTrue(changed.getVelocity().hasPing());
        assertEquals(41, changed.getVelocity().getPing());

        Verdict unplaced = engine(3, moved, moved, -1);
        assertTrue(unplaced.hasVelocity());
        assertFalse(unplaced.getVelocity().hasPing());
    }

    @Test
    void engineStateBeforePredictionSendsOnlyWhatIsKnown() throws InvalidProtocolBufferException {
        IntakeWire.Engine none = new IntakeWire.Engine(
                1 << IntakeWire.ENGINE_UNPREDICTED, -1, 0, 0, 0, 0, 0, 0, false, 0, 0, false, false, null);
        Verdict fields = engine(1, none, null, -1);
        assertEquals(1 << IntakeWire.ENGINE_UNPREDICTED, fields.getEngineTick());
        assertFalse(fields.hasGround());
        assertFalse(fields.hasNoJumpDelay());
        assertFalse(fields.hasFall());
        assertTrue(fields.hasUsingItem());
        assertFalse(fields.getUsingItem());
        // The first predicted tick after it restates everything, though some values match.
        Verdict next = engine(2, ENGINE, none, -1);
        assertTrue(next.hasGround());
        assertTrue(next.hasNoJumpDelay());
        assertTrue(next.hasFall());
        assertTrue(next.hasContext());
        assertTrue(next.hasInput());
        assertTrue(next.hasUsingItem());
    }

    @Test
    void aMissingVerdictCarriesNoEngineState() throws InvalidProtocolBufferException {
        assertEquals(
                Verdict.newBuilder().setMissing(true).build(),
                verdict(0, 1, true, List.of(), new IntakeWire.Target(1, 1.0), List.of(), ENGINE, null, -1));
    }

    @Test
    void theVerdictWriterReusesItsArray() throws InvalidProtocolBufferException {
        IntakeWire.VerdictWriter writer = new IntakeWire.VerdictWriter();
        int first = writer.write(1, 7, false, List.of(), null, List.of(), ENGINE, null, -1, 0, List.of());
        byte[] buffer = writer.bytes();
        assertEquals(
                verdict(1, 7, false, List.of(), null, List.of(), ENGINE, null, -1),
                Verdict.parseFrom(Arrays.copyOf(buffer, first)));
        int second = writer.write(0, 8, false, List.of(), null, List.of(), ENGINE, ENGINE, -1, 0, List.of());
        assertTrue(buffer == writer.bytes());
        assertEquals(8, Verdict.parseFrom(Arrays.copyOf(writer.bytes(), second)).getTickEndSeq());
    }

    @Test
    void outcomesGoInTheirOwnFieldWithTheCarryingFramesSeq() {
        byte[] pop = Outcome.newBuilder()
                .setType(Outcome.Type.TYPE_POP)
                .setStatus(Outcome.Status.STATUS_APPLIED)
                .setNanos(1000)
                .setHand(Outcome.Hand.HAND_MAIN)
                .build()
                .toByteArray();
        // status 1, nanos 1000 zigzagged, type 7, and hand 0 written though it is the default.
        assertArrayEquals(bytes(0x08, 0x01, 0x30, 0xD0, 0x0F, 0x60, 0x07, 0xE0, 0x03, 0x00), pop);
        IntakeWire.VerdictWriter writer = new IntakeWire.VerdictWriter();
        int missing = writer.write(0, 0, true, List.of(), null, List.of(), ENGINE, null, -1, 5, List.of(pop));
        assertArrayEquals(
                bytes(
                        0x30, 0x01, 0x42, 0x0E, 0x08, 0x05, 0x12, 0x0A, 0x08, 0x01, 0x30, 0xD0, 0x0F, 0x60, 0x07, 0xE0,
                        0x03, 0x00),
                Arrays.copyOf(writer.bytes(), missing));
    }

    @Test
    void aVerdictsOutcomesSitBetweenItsFlagsAndEngineState() throws InvalidProtocolBufferException {
        byte[] click = Outcome.newBuilder()
                .setType(Outcome.Type.TYPE_CLICK)
                .setStatus(Outcome.Status.STATUS_REJECTED)
                .setActionFrame(3)
                .setActionIndex(1)
                .build()
                .toByteArray();
        IntakeWire.VerdictWriter writer = new IntakeWire.VerdictWriter();
        List<IntakeWire.Flag> flags = List.of(IntakeWire.Flag.of("Reach", 1));
        int size = writer.write(0, 4, false, flags, null, List.of(), ENGINE, null, -1, 4, List.of(click, click));
        Verdict verdict = Verdict.parseFrom(Arrays.copyOf(writer.bytes(), size));
        assertEquals(4, verdict.getOutcomes().getFrameSeq());
        assertEquals(2, verdict.getOutcomes().getOutcomesCount());
        Outcome read = verdict.getOutcomes().getOutcomes(1);
        assertEquals(Outcome.Type.TYPE_CLICK, read.getType());
        assertEquals(Outcome.Status.STATUS_REJECTED, read.getStatus());
        assertEquals(3, read.getActionFrame());
        assertEquals(1, read.getActionIndex());
        assertEquals("Reach", verdict.getFlags(0).getCheck());
        assertTrue(verdict.hasGround());
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) bytes[i] = (byte) values[i];
        return bytes;
    }

    @Test
    void readsAPingsWireId() {
        assertEquals(Integer.valueOf(0x80000001), IntakeWire.pingId(new byte[] {0x3B, (byte) 0x80, 0, 0, 1}, 0x3B));
        assertEquals(null, IntakeWire.pingId(new byte[] {0x3B, 1, 2}, 0x3B));
        assertEquals(null, IntakeWire.pingId(new byte[] {0x3C, 1, 2, 3, 4}, 0x3B));
    }

    @Test
    void captureBytesCountsARecordsWholeField() {
        IntakeWire.FrameBuilder builder = new IntakeWire.FrameBuilder();
        add(builder, IntakeWire.SERVERBOUND, 300, new byte[200]);
        int body = CodedOutputStream.computeTagSize(IntakeWire.SERVERBOUND) + 2 + 200 + 1 + 2;
        assertEquals(1 + 2 + body, builder.bytes());
        assertEquals(builder.bytes(), IntakeWire.captureBytes(IntakeWire.SERVERBOUND, 300, 200, 0, 0, 0, false));
    }

    @Test
    void frameCarriesItsServerTickAndEachRecordsDelta() throws InvalidProtocolBufferException {
        IntakeWire.FrameBuilder builder = new IntakeWire.FrameBuilder();
        builder.add(IntakeWire.CLIENTBOUND, 7, new byte[] {2, 1}, 2, 0, 0, 0);
        builder.add(IntakeWire.CLIENTBOUND, 7, new byte[] {4, 0, 0, 0, 1}, 5, 1, IntakeWire.PING_END_OF_TICK, 0);
        builder.add(IntakeWire.CLIENTBOUND, 7, new byte[] {2, 1}, 2, -1, 0, 0);
        builder.serverTick(123_456_789L);
        Frame frame = message(builder.build(PLAYER, 3)).getFrame();
        assertEquals(123_456_789L, frame.getServerTick());
        assertEquals(1, frame.getRecords(1).getServerTickDelta());
        assertEquals(1, frame.getRecords(1).getPingKindValue());
        assertEquals(-1, frame.getRecords(2).getServerTickDelta());
    }
}
