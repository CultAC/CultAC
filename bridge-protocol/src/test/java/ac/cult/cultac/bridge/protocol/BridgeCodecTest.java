package ac.cult.cultac.bridge.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bridge.protocol.BridgeMessage.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BridgeCodecTest {
    private static final UUID PLAYER = UUID.fromString("12345678-9abc-def0-1122-334455667788");
    private static final byte[] NONCE = new byte[BridgeProtocol.NONCE_BYTES];
    private static final byte[] HMAC = new byte[BridgeProtocol.HMAC_BYTES];

    static {
        for (int i = 0; i < NONCE.length; i++) NONCE[i] = (byte) (i * 7);
        for (int i = 0; i < HMAC.length; i++) HMAC[i] = (byte) (0xF0 - i);
    }

    private static List<BridgeMessage> samples() {
        return List.of(
                new Hello(BridgeProtocol.VERSION, PLAYER, NONCE),
                new Challenge(65535, NONCE),
                new Auth(HMAC),
                new Ready(
                        HMAC,
                        ServerCapability.mask(EnumSet.of(ServerCapability.TELEPORT, ServerCapability.CLEAR_ACTIVE_ITEM))
                                | Long.MIN_VALUE,
                        "Paper 26.3-134 é中😀",
                        new UUID(-1L, 42L),
                        "world_nether",
                        Arrays.stream(ListenerKind.values())
                                .mapToInt(ListenerKind::flag)
                                .reduce(0, (a, b) -> a | b)),
                new Reject("protocol 2 != 1"),
                new Close("malformed frame"),
                new Listeners(0b101010),
                new World(PLAYER, "world"),
                new ServerStats(19.87f),
                new Result(Integer.MAX_VALUE, true),
                new PlayerStateResult(17, 1, new UUID(5L, -6L), -29_999_999.5, -64.0, 1.0e-3, 179.5f, -90f),
                new ClearActiveItem(ClearActiveItem.OFF_HAND, "minecraft:shield"),
                new SetGameMode(SetGameMode.SPECTATOR),
                new Teleport(7, new UUID(3L, -4L), -1.5e7, 320.25, Double.MIN_VALUE, -180f, 90f),
                new TeleportToPlayer(8, PLAYER),
                new CloseInventory(),
                new ValidationBlock(10, -29_999_999, -64, 29_999_999, "minecraft:stone", false),
                new ConsoleExec(11, "ban Notch cheating"),
                new PlayerState(17),
                new UserInfo("vanilla", "1.21.11", true),
                new CheckCatalog(
                        List.of(
                                new CheckInfo(0, "Reach", "Reach", "", "Reach check", "reach", 1.0, 0.05, false, true),
                                new CheckInfo(
                                        4095,
                                        "Simulation",
                                        "Simulation",
                                        "Prediction",
                                        "",
                                        "simulation",
                                        -1,
                                        0,
                                        true,
                                        false)),
                        true),
                new Metrics(85, 0.5, 0.375, 1234, 1230),
                new Flag(12, 3, 4.5, "dist=3.01", true),
                new Verdict(12, false),
                new Setback(13, 1.0, 64.0, -1.0),
                new AlertState(AlertState.ALERTS | AlertState.BRANDS),
                new AlertToggle(AlertToggle.VERBOSE, true, false),
                new Reloaded(false),
                new CommandExecute(14, 2, 10.0, "v", "kick %player%"),
                new Command(15, Command.CONSOLE, "CONSOLE", "cult reload"),
                new CommandOutput(15, "{\"text\":\"Reloaded\"}"),
                new CommandDone(15),
                new Suggest(16, PLAYER, "cult al"),
                new Suggestions(16, List.of("alerts", "")));
    }

    @Test
    void everyMessageRoundTrips() {
        for (BridgeMessage message : samples()) {
            ByteBuf buffer = encode(message);
            try {
                byte[] wire = ByteBufUtil.getBytes(buffer);
                assertEquals(message.type(), wire[0] & 0xFF);
                BridgeMessage decoded = BridgeCodec.decode(buffer);
                assertSameComponents(message, decoded);
                assertArrayEquals(wire, bytes(decoded));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void samplesCoverEveryMessageTypeOnce() {
        Set<Class<?>> sampled = samples().stream().map(Object::getClass).collect(Collectors.toSet());
        Set<Class<?>> permitted = Set.of(BridgeMessage.class.getPermittedSubclasses());
        assertEquals(permitted, sampled);
        assertEquals(
                permitted.size(),
                samples().stream().map(BridgeMessage::type).distinct().count());
    }

    @Test
    void unknownTypeIsReportedSeparately() {
        var failure = assertThrows(BridgeProtocolException.class, () -> decode(out -> out.writeByte(0x7F)));
        assertTrue(failure.isUnknownType());
        assertEquals(0x7F, failure.unknownType());
        var malformed = assertThrows(BridgeProtocolException.class, () -> decode(out -> {}));
        assertFalse(malformed.isUnknownType());
        assertEquals(-1, malformed.unknownType());
    }

    @Test
    void oversizedStringIsRejectedBeforeAllocation() {
        malformed(out -> {
            out.writeByte(Reject.TYPE);
            BridgeCodec.writeVarInt(out, BridgeProtocol.MAX_STRING_BYTES + 1);
        });
        assertThrows(
                IllegalArgumentException.class,
                () -> encode(new Reject("x".repeat(BridgeProtocol.MAX_STRING_BYTES + 1))));
        assertThrows(
                IllegalArgumentException.class,
                () -> encode(new Reject("é".repeat(BridgeProtocol.MAX_STRING_BYTES / 2 + 1))));
    }

    @Test
    void negativeAndOverlongLengthsAreRejected() {
        malformed(out -> {
            out.writeByte(Reject.TYPE);
            BridgeCodec.writeVarInt(out, -1);
        });
        malformed(out -> {
            out.writeByte(Reject.TYPE);
            BridgeCodec.writeVarInt(out, 10);
            out.writeBytes(new byte[5]);
        });
        malformed(out -> {
            out.writeByte(Suggestions.TYPE);
            BridgeCodec.writeVarInt(out, 1);
            BridgeCodec.writeVarInt(out, -5);
        });
        malformed(out -> {
            out.writeByte(Listeners.TYPE);
            out.writeBytes(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0});
        });
    }

    @Test
    void hugeListIsRejected() {
        malformed(out -> {
            out.writeByte(CheckCatalog.TYPE);
            BridgeCodec.writeVarInt(out, BridgeCodec.MAX_LIST_SIZE + 1);
        });
        malformed(out -> {
            out.writeByte(Suggestions.TYPE);
            BridgeCodec.writeVarInt(out, 1);
            BridgeCodec.writeVarInt(out, Integer.MAX_VALUE);
        });
        assertThrows(
                IllegalArgumentException.class,
                () -> encode(new Suggestions(1, Collections.nCopies(BridgeCodec.MAX_LIST_SIZE + 1, ""))));
    }

    @Test
    void truncatedTrailingAndOversizedFramesAreRejected() {
        byte[] hello = bytes(new Hello(1, PLAYER, NONCE));
        malformed(Arrays.copyOf(hello, hello.length - 1));
        malformed(Arrays.copyOf(hello, hello.length + 1));
        malformed(out -> {
            out.writeByte(Reloaded.TYPE);
            out.writeByte(2);
        });
        malformed(new byte[BridgeProtocol.MAX_FRAME_BYTES + 1]);
    }

    @Test
    void outOfRangeFixedWidthValuesAreRejectedOnEncode() {
        assertThrows(IllegalArgumentException.class, () -> encode(new SetGameMode(256)));
        assertThrows(IllegalArgumentException.class, () -> encode(new Challenge(65536, NONCE)));
        assertThrows(IllegalArgumentException.class, () -> new Auth(new byte[31]));
    }

    @Test
    void varLongCoversExtremes() {
        for (long value : new long[] {0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            ByteBuf buffer = Unpooled.buffer();
            BridgeCodec.writeVarLong(buffer, value);
            assertEquals(value, BridgeCodec.readVarLong(buffer));
        }
    }

    @Test
    void serverStatsCarriesOnlyItsReadField() {
        assertEquals(1 + Float.BYTES, bytes(new ServerStats(20f)).length);
    }

    @Test
    void toBytesAndLengthsMatchTheEncoder() {
        for (BridgeMessage message : samples()) assertArrayEquals(bytes(message), BridgeCodec.toBytes(message));
        for (BridgeMessage message : samples()) assertEquals(bytes(message).length, BridgeCodec.encodedLength(message));
        assertThrows(IllegalArgumentException.class, () -> BridgeCodec.encodedLength(new SetGameMode(256)));
        assertThrows(IllegalArgumentException.class, () -> BridgeCodec.toBytes(new SetGameMode(256)));
        assertEquals(2 + 3 + 4 + 3, BridgeCodec.maxEncodedLength("é中😀"));
        assertEquals(1 + 1 + 9, bytes(new Reject("é中😀")).length);
    }

    @Test
    void capabilityAndListenerBitsMatchTheWireTable() {
        assertEquals(1L << 9, ServerCapability.FLAG_VERDICT.flag());
        assertEquals(1L << 10, ServerCapability.CLEAR_ACTIVE_ITEM.flag());
        long mask = ServerCapability.mask(EnumSet.of(ServerCapability.TELEPORT, ServerCapability.CLEAR_ACTIVE_ITEM));
        assertTrue(ServerCapability.has(mask, ServerCapability.TELEPORT));
        assertTrue(ServerCapability.has(mask, ServerCapability.CLEAR_ACTIVE_ITEM));
        assertFalse(ServerCapability.has(mask, ServerCapability.GAMEMODE));
        assertEquals(1 << 2, ListenerKind.SETBACK.flag());
        assertEquals(1 << 3, ListenerKind.RELOAD.flag());
        int listeners = ListenerKind.FLAG.flag() | ListenerKind.SETBACK.flag();
        assertEquals(0b101, listeners);
        assertTrue(ListenerKind.has(listeners, ListenerKind.SETBACK));
        assertFalse(ListenerKind.has(listeners, ListenerKind.RELOAD));
    }

    private static ByteBuf encode(BridgeMessage message) {
        ByteBuf buffer = Unpooled.buffer();
        BridgeCodec.encode(buffer, message);
        return buffer;
    }

    private static byte[] bytes(BridgeMessage message) {
        ByteBuf buffer = encode(message);
        try {
            return ByteBufUtil.getBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    private static BridgeMessage decode(Consumer<ByteBuf> writer) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            writer.accept(buffer);
            return BridgeCodec.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static void malformed(Consumer<ByteBuf> writer) {
        var failure = assertThrows(BridgeProtocolException.class, () -> decode(writer));
        assertFalse(failure.isUnknownType(), failure.getMessage());
    }

    private static void malformed(byte[] frame) {
        malformed(out -> out.writeBytes(frame));
    }

    private static void assertSameComponents(BridgeMessage expected, BridgeMessage actual) {
        assertEquals(expected.getClass(), actual.getClass());
        for (RecordComponent component : expected.getClass().getRecordComponents()) {
            try {
                Object left = component.getAccessor().invoke(expected);
                Object right = component.getAccessor().invoke(actual);
                assertTrue(
                        Objects.deepEquals(left, right),
                        expected.getClass().getSimpleName() + "." + component.getName());
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
    }
}
