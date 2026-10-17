package ac.cult.cultac.bridge.protocol;

import ac.cult.cultac.bridge.protocol.BridgeMessage.AlertState;
import ac.cult.cultac.bridge.protocol.BridgeMessage.AlertToggle;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Auth;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Challenge;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CheckCatalog;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CheckInfo;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ClearActiveItem;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Close;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CloseInventory;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CloudFacts;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Command;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CommandDone;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CommandExecute;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CommandOutput;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ConsoleExec;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Flag;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Hello;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Listeners;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Marker;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Metrics;
import ac.cult.cultac.bridge.protocol.BridgeMessage.PlayerFacts;
import ac.cult.cultac.bridge.protocol.BridgeMessage.PlayerState;
import ac.cult.cultac.bridge.protocol.BridgeMessage.PlayerStateResult;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Ready;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Reject;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Reloaded;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Result;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ServerStats;
import ac.cult.cultac.bridge.protocol.BridgeMessage.SetGameMode;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Setback;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Suggest;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Suggestions;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Teleport;
import ac.cult.cultac.bridge.protocol.BridgeMessage.TeleportToPlayer;
import ac.cult.cultac.bridge.protocol.BridgeMessage.UserInfo;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ValidationBlock;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Verdict;
import ac.cult.cultac.bridge.protocol.BridgeMessage.World;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Frame codec: {@code [u8 type][body]}. Decoding consumes the whole frame and rejects trailing bytes; encoding
 * writes only into the target buffer and throws {@link IllegalArgumentException} for values the wire cannot carry.
 */
public final class BridgeCodec {
    public static final int MAX_LIST_SIZE = 4096;
    /** Entries in a facts message's settings. */
    public static final int MAX_SETTINGS = 64;

    private BridgeCodec() {}

    /** {@code msg} as one frame; throws {@link IllegalArgumentException} like {@link #encode}. */
    public static byte[] toBytes(BridgeMessage msg) {
        ByteBuf buffer = Unpooled.buffer(64);
        try {
            encode(buffer, msg);
            return ByteBufUtil.getBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    /**
     * The length of {@code msg} as one frame, without the final {@code byte[]} copy of {@link #toBytes}; throws
     * {@link IllegalArgumentException} like {@link #encode}.
     */
    public static int encodedLength(BridgeMessage msg) {
        ByteBuf buffer = Unpooled.buffer(64);
        try {
            encode(buffer, msg);
            return buffer.readableBytes();
        } finally {
            buffer.release();
        }
    }

    /** Upper bound of the bytes {@link #encode} writes for {@code value}: its UTF-8 bytes and a VarInt prefix. */
    public static int maxEncodedLength(String value) {
        return ByteBufUtil.utf8Bytes(value) + 3;
    }

    public static void encode(ByteBuf out, BridgeMessage msg) {
        out.writeByte(msg.type());
        switch (msg) {
            case Hello m -> {
                writeU16(out, m.version());
                writeUuid(out, m.player());
                out.writeBytes(m.proxyNonce());
            }
            case Challenge m -> {
                writeU16(out, m.version());
                out.writeBytes(m.backendNonce());
            }
            case Auth m -> out.writeBytes(m.hmac());
            case Ready m -> {
                out.writeBytes(m.hmac());
                writeVarLong(out, m.capabilities());
                writeString(out, m.serverVersion());
                writeUuid(out, m.worldUid());
                writeString(out, m.worldName());
                writeVarInt(out, m.listeners());
            }
            case Reject m -> writeString(out, m.reason());
            case Close m -> writeString(out, m.reason());
            case Listeners m -> writeVarInt(out, m.listeners());
            case World m -> {
                writeUuid(out, m.uid());
                writeString(out, m.name());
            }
            case ServerStats m -> out.writeFloat(m.tps());
            case Result m -> {
                writeVarInt(out, m.requestId());
                out.writeBoolean(m.success());
            }
            case PlayerStateResult m -> {
                writeVarInt(out, m.requestId());
                writeU8(out, m.gameMode());
                writeUuid(out, m.world());
                out.writeDouble(m.x());
                out.writeDouble(m.y());
                out.writeDouble(m.z());
                out.writeFloat(m.yaw());
                out.writeFloat(m.pitch());
            }
            case CloudFacts m -> {
                writeNonNegative(out, m.tickMicros());
                writeSettings(out, m.settings());
            }
            case PlayerFacts m -> writeSettings(out, m.settings());
            case Marker m -> out.writeInt(m.marker());
            case ClearActiveItem m -> {
                writeU8(out, m.hand());
                writeString(out, m.itemKey());
            }
            case SetGameMode m -> writeU8(out, m.mode());
            case Teleport m -> {
                writeVarInt(out, m.requestId());
                writeUuid(out, m.world());
                out.writeDouble(m.x());
                out.writeDouble(m.y());
                out.writeDouble(m.z());
                out.writeFloat(m.yaw());
                out.writeFloat(m.pitch());
            }
            case TeleportToPlayer m -> {
                writeVarInt(out, m.requestId());
                writeUuid(out, m.target());
            }
            case CloseInventory m -> {}
            case ValidationBlock m -> {
                writeVarInt(out, m.requestId());
                writeVarInt(out, m.x());
                writeVarInt(out, m.y());
                writeVarInt(out, m.z());
                writeString(out, m.blockState());
                out.writeBoolean(m.packetOnly());
            }
            case ConsoleExec m -> {
                writeVarInt(out, m.requestId());
                writeString(out, m.command());
            }
            case PlayerState m -> writeVarInt(out, m.requestId());
            case UserInfo m -> {
                writeString(out, m.brand());
                writeString(out, m.versionName());
                out.writeBoolean(m.vanillaMath());
            }
            case CheckCatalog m -> {
                writeCount(out, m.checks().size());
                for (CheckInfo check : m.checks()) {
                    writeVarInt(out, check.idx());
                    writeString(out, check.checkName());
                    writeString(out, check.configName());
                    writeString(out, check.alternativeName());
                    writeString(out, check.description());
                    writeString(out, check.stableKey());
                    out.writeDouble(check.setbackVL());
                    out.writeDouble(check.decay());
                    out.writeBoolean(check.experimental());
                    out.writeBoolean(check.enabled());
                }
                out.writeBoolean(m.last());
            }
            case Metrics m -> {
                writeVarInt(out, m.transactionPing());
                out.writeDouble(m.sensX());
                out.writeDouble(m.sensY());
                writeVarInt(out, m.lastTransSent());
                writeVarInt(out, m.lastTransRecv());
            }
            case Flag m -> {
                writeVarInt(out, m.flagId());
                writeVarInt(out, m.checkIdx());
                out.writeDouble(m.violations());
                writeString(out, m.verbose());
                out.writeBoolean(m.awaitVerdict());
            }
            case Verdict m -> {
                writeVarInt(out, m.flagId());
                out.writeBoolean(m.cancelled());
            }
            case Setback m -> {
                writeVarInt(out, m.teleportId());
                out.writeDouble(m.x());
                out.writeDouble(m.y());
                out.writeDouble(m.z());
            }
            case AlertState m -> writeU8(out, m.bits());
            case AlertToggle m -> {
                writeU8(out, m.kind());
                out.writeBoolean(m.enabled());
                out.writeBoolean(m.silent());
            }
            case Reloaded m -> out.writeBoolean(m.success());
            case CommandExecute m -> {
                writeVarInt(out, m.requestId());
                writeVarInt(out, m.checkIdx());
                out.writeDouble(m.violations());
                writeString(out, m.verbose());
                writeString(out, m.command());
            }
            case Command m -> {
                writeVarInt(out, m.requestId());
                writeUuid(out, m.sender());
                writeString(out, m.senderName());
                writeString(out, m.line());
            }
            case CommandOutput m -> {
                writeVarInt(out, m.requestId());
                writeString(out, m.componentJson());
            }
            case CommandDone m -> writeVarInt(out, m.requestId());
            case Suggest m -> {
                writeVarInt(out, m.requestId());
                writeUuid(out, m.sender());
                writeString(out, m.line());
            }
            case Suggestions m -> {
                writeVarInt(out, m.requestId());
                writeCount(out, m.suggestions().size());
                for (String suggestion : m.suggestions()) writeString(out, suggestion);
            }
        }
    }

    public static BridgeMessage decode(ByteBuf in) {
        int frame = in.readableBytes();
        if (frame > BridgeProtocol.MAX_FRAME_BYTES) throw malformed("Frame of " + frame + " bytes exceeds the cap");
        if (frame == 0) throw malformed("Empty frame");
        BridgeMessage message;
        try {
            message = decodeBody(in, in.readUnsignedByte());
        } catch (IndexOutOfBoundsException truncated) {
            throw malformed("Truncated frame");
        }
        if (in.isReadable()) throw malformed(in.readableBytes() + " trailing bytes after type " + message.type());
        return message;
    }

    private static BridgeMessage decodeBody(ByteBuf in, int type) {
        return switch (type) {
            case Hello.TYPE ->
                new Hello(in.readUnsignedShort(), readUuid(in), readFixed(in, BridgeProtocol.NONCE_BYTES));
            case Challenge.TYPE -> new Challenge(in.readUnsignedShort(), readFixed(in, BridgeProtocol.NONCE_BYTES));
            case Auth.TYPE -> new Auth(readFixed(in, BridgeProtocol.HMAC_BYTES));
            case Ready.TYPE ->
                new Ready(
                        readFixed(in, BridgeProtocol.HMAC_BYTES),
                        readVarLong(in),
                        readString(in),
                        readUuid(in),
                        readString(in),
                        readVarInt(in));
            case Reject.TYPE -> new Reject(readString(in));
            case Close.TYPE -> new Close(readString(in));
            case Listeners.TYPE -> new Listeners(readVarInt(in));
            case World.TYPE -> new World(readUuid(in), readString(in));
            case ServerStats.TYPE -> new ServerStats(in.readFloat());
            case Result.TYPE -> new Result(readVarInt(in), readBoolean(in));
            case PlayerStateResult.TYPE ->
                new PlayerStateResult(
                        readVarInt(in),
                        in.readUnsignedByte(),
                        readUuid(in),
                        in.readDouble(),
                        in.readDouble(),
                        in.readDouble(),
                        in.readFloat(),
                        in.readFloat());
            case CloudFacts.TYPE -> new CloudFacts(readNonNegative(in), readSettings(in));
            case PlayerFacts.TYPE -> new PlayerFacts(readSettings(in));
            case Marker.TYPE -> new Marker(in.readInt());
            case ClearActiveItem.TYPE -> new ClearActiveItem(in.readUnsignedByte(), readString(in));
            case SetGameMode.TYPE -> new SetGameMode(in.readUnsignedByte());
            case Teleport.TYPE ->
                new Teleport(
                        readVarInt(in),
                        readUuid(in),
                        in.readDouble(),
                        in.readDouble(),
                        in.readDouble(),
                        in.readFloat(),
                        in.readFloat());
            case TeleportToPlayer.TYPE -> new TeleportToPlayer(readVarInt(in), readUuid(in));
            case CloseInventory.TYPE -> new CloseInventory();
            case ValidationBlock.TYPE ->
                new ValidationBlock(
                        readVarInt(in),
                        readVarInt(in),
                        readVarInt(in),
                        readVarInt(in),
                        readString(in),
                        readBoolean(in));
            case ConsoleExec.TYPE -> new ConsoleExec(readVarInt(in), readString(in));
            case PlayerState.TYPE -> new PlayerState(readVarInt(in));
            case UserInfo.TYPE -> new UserInfo(readString(in), readString(in), readBoolean(in));
            case CheckCatalog.TYPE -> {
                int count = readCount(in);
                List<CheckInfo> checks = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    checks.add(new CheckInfo(
                            readVarInt(in),
                            readString(in),
                            readString(in),
                            readString(in),
                            readString(in),
                            readString(in),
                            in.readDouble(),
                            in.readDouble(),
                            readBoolean(in),
                            readBoolean(in)));
                }
                yield new CheckCatalog(checks, readBoolean(in));
            }
            case Metrics.TYPE ->
                new Metrics(readVarInt(in), in.readDouble(), in.readDouble(), readVarInt(in), readVarInt(in));
            case Flag.TYPE ->
                new Flag(readVarInt(in), readVarInt(in), in.readDouble(), readString(in), readBoolean(in));
            case Verdict.TYPE -> new Verdict(readVarInt(in), readBoolean(in));
            case Setback.TYPE -> new Setback(readVarInt(in), in.readDouble(), in.readDouble(), in.readDouble());
            case AlertState.TYPE -> new AlertState(in.readUnsignedByte());
            case AlertToggle.TYPE -> new AlertToggle(in.readUnsignedByte(), readBoolean(in), readBoolean(in));
            case Reloaded.TYPE -> new Reloaded(readBoolean(in));
            case CommandExecute.TYPE ->
                new CommandExecute(readVarInt(in), readVarInt(in), in.readDouble(), readString(in), readString(in));
            case Command.TYPE -> new Command(readVarInt(in), readUuid(in), readString(in), readString(in));
            case CommandOutput.TYPE -> new CommandOutput(readVarInt(in), readString(in));
            case CommandDone.TYPE -> new CommandDone(readVarInt(in));
            case Suggest.TYPE -> new Suggest(readVarInt(in), readUuid(in), readString(in));
            case Suggestions.TYPE -> {
                int requestId = readVarInt(in);
                int count = readCount(in);
                List<String> suggestions = new ArrayList<>(count);
                for (int i = 0; i < count; i++) suggestions.add(readString(in));
                yield new Suggestions(requestId, suggestions);
            }
            default -> throw BridgeProtocolException.unknown(type);
        };
    }

    private static BridgeProtocolException malformed(String reason) {
        return new BridgeProtocolException("Malformed bridge frame: " + reason);
    }

    static void writeVarInt(ByteBuf out, int value) {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    static int readVarInt(ByteBuf in) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            byte b = in.readByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw malformed("VarInt longer than 5 bytes");
    }

    static void writeVarLong(ByteBuf out, long value) {
        while ((value & ~0x7FL) != 0L) {
            out.writeByte((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte((int) value);
    }

    static long readVarLong(ByteBuf in) {
        long value = 0L;
        for (int shift = 0; shift < 70; shift += 7) {
            byte b = in.readByte();
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw malformed("VarLong longer than 10 bytes");
    }

    static void writeUuid(ByteBuf out, UUID uuid) {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    static UUID readUuid(ByteBuf in) {
        return new UUID(in.readLong(), in.readLong());
    }

    static void writeString(ByteBuf out, String value) {
        int length = ByteBufUtil.utf8Bytes(value);
        if (length > BridgeProtocol.MAX_STRING_BYTES) {
            throw new IllegalArgumentException("String of " + length + " UTF-8 bytes exceeds the cap");
        }
        writeVarInt(out, length);
        out.writeCharSequence(value, StandardCharsets.UTF_8);
    }

    static String readString(ByteBuf in) {
        int length = readVarInt(in);
        if (length < 0) throw malformed("Negative string length " + length);
        if (length > BridgeProtocol.MAX_STRING_BYTES) throw malformed("String of " + length + " bytes exceeds the cap");
        if (length > in.readableBytes()) throw malformed("String length " + length + " past the frame end");
        String value = in.toString(in.readerIndex(), length, StandardCharsets.UTF_8);
        in.skipBytes(length);
        return value;
    }

    private static void writeU8(ByteBuf out, int value) {
        if ((value & ~0xFF) != 0) throw new IllegalArgumentException("u8 out of range: " + value);
        out.writeByte(value);
    }

    private static void writeU16(ByteBuf out, int value) {
        if ((value & ~0xFFFF) != 0) throw new IllegalArgumentException("u16 out of range: " + value);
        out.writeShort(value);
    }

    private static void writeCount(ByteBuf out, int count) {
        if (count > MAX_LIST_SIZE) throw new IllegalArgumentException("List of " + count + " exceeds the cap");
        writeVarInt(out, count);
    }

    private static int readCount(ByteBuf in) {
        int count = readVarInt(in);
        if (count < 0) throw malformed("Negative list size " + count);
        if (count > MAX_LIST_SIZE) throw malformed("List of " + count + " exceeds the cap");
        return count;
    }

    private static void writeNonNegative(ByteBuf out, int value) {
        if (value < 0) throw new IllegalArgumentException("Negative value " + value);
        writeVarInt(out, value);
    }

    private static int readNonNegative(ByteBuf in) {
        int value = readVarInt(in);
        if (value < 0) throw malformed("Negative value " + value);
        return value;
    }

    private static void writeSettings(ByteBuf out, Map<String, String> settings) {
        if (settings.size() > MAX_SETTINGS) {
            throw new IllegalArgumentException(settings.size() + " settings exceed the cap");
        }
        writeVarInt(out, settings.size());
        for (Map.Entry<String, String> setting : settings.entrySet()) {
            writeString(out, setting.getKey());
            writeString(out, setting.getValue());
        }
    }

    private static Map<String, String> readSettings(ByteBuf in) {
        int count = readVarInt(in);
        if (count < 0 || count > MAX_SETTINGS) throw malformed(count + " settings");
        Map<String, String> settings = new LinkedHashMap<>(count * 2);
        for (int i = 0; i < count; i++) {
            String key = readString(in);
            if (settings.put(key, readString(in)) != null) throw malformed("Repeated setting " + key);
        }
        return settings;
    }

    private static boolean readBoolean(ByteBuf in) {
        byte value = in.readByte();
        if (value == 0) return false;
        if (value == 1) return true;
        throw malformed("Boolean byte " + value);
    }

    private static byte[] readFixed(ByteBuf in, int length) {
        if (in.readableBytes() < length) throw malformed("Truncated " + length + "-byte field");
        byte[] bytes = new byte[length];
        in.readBytes(bytes);
        return bytes;
    }
}
