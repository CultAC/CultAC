package ac.cult.cultac.bedrock.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

public final class BedrockAuthInputPluginMessage {
    public static final String CHANNEL_NAMESPACE = "cultac";
    public static final String CHANNEL_PATH = "bedrock_auth_input/" + newSecret();
    public static final String CHANNEL = CHANNEL_NAMESPACE + ":" + CHANNEL_PATH;
    private static final int VERSION = 18;
    private static final int TYPE_AUTH_INPUT = 0;
    private static final int TYPE_CLIENT_ACTION = 1;
    private static final int TYPE_MOVE_FRAME = 2;
    private static final int TYPE_METADATA_ACKNOWLEDGED = 7;
    private static final int TYPE_PROJECTION_SUPPRESSED = 8;
    private static final int TYPE_ACTOR_CREATED = 9;
    private static final int MAX_STRING_LENGTH = 128;

    private BedrockAuthInputPluginMessage() {
    }

    public static byte[] encodeActorCreated(UUID playerUuid, long runtimeEntityId) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(29);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            out.writeByte(TYPE_ACTOR_CREATED);
            out.writeLong(playerUuid.getMostSignificantBits());
            out.writeLong(playerUuid.getLeastSignificantBits());
            out.writeLong(runtimeEntityId);
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode Bedrock actor creation", exception);
        }
    }

    public static ActorCreatedMessage decodeActorCreated(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != VERSION || in.readUnsignedByte() != TYPE_ACTOR_CREATED) return null;
            var message = new ActorCreatedMessage(new UUID(in.readLong(), in.readLong()), in.readLong());
            return in.available() == 0 ? message : null;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    public record ActorCreatedMessage(UUID playerUuid, long runtimeEntityId) {
    }

    private static String newSecret() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >>> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }

    public static byte[] encode(BedrockAuthInputFrame frame) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(160);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            out.writeByte(TYPE_AUTH_INPUT);
            out.writeLong(frame.getPlayerUuid().getMostSignificantBits());
            out.writeLong(frame.getPlayerUuid().getLeastSignificantBits());
            out.writeInt(frame.getProtocolVersion().protocol());
            out.writeLong(frame.getClientTick());
            out.writeInt(frame.getInputMode());
            out.writeInt(frame.getPlayMode());
            out.writeInt(frame.getDeviceId());
            writeVec3(out, frame.getPosition());
            writeVec3(out, frame.getPacketPosition());
            writeVec3(out, frame.getDelta());
            out.writeFloat(frame.getYaw());
            out.writeFloat(frame.getPitch());
            out.writeFloat(frame.getHeadYaw());
            out.writeBoolean(frame.hasRotation());
            writeMoveVector(out, frame.getMoveVector());
            out.writeLong(frame.getRawInputFlags());
            out.writeLong(frame.getRawInputFlagsHigh());
            out.writeBoolean(frame.isProjectedOnGround());
            out.writeBoolean(frame.isJumping());
            out.writeBoolean(frame.isJumpStarted());
            out.writeBoolean(frame.isJumpPressedRaw());
            out.writeBoolean(frame.isJumpCurrentRaw());
            out.writeBoolean(frame.isWantUp());
            out.writeBoolean(frame.isSneaking());
            out.writeBoolean(frame.isStartSneaking());
            out.writeBoolean(frame.isStopSneaking());
            out.writeBoolean(frame.isSprinting());
            out.writeBoolean(frame.isStartSwimming());
            out.writeBoolean(frame.isStopSwimming());
            out.writeBoolean(frame.isStartCrawling());
            out.writeBoolean(frame.isStopCrawling());
            out.writeBoolean(frame.isStartGliding());
            out.writeBoolean(frame.isStopGliding());
            out.writeBoolean(frame.isUsingItem());
            out.writeBoolean(frame.hasBlockAction());
            writeString(out, frame.getAuthorityMode());
            out.writeLong(frame.getRewindCorrectionId());
            writeVec3(out, frame.getReportedEndOfTickVelocity());
            writeCoordinateFrame(out, frame.getCoordinateFrame());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode Bedrock auth input plugin message", exception);
        }
    }

    public static byte[] encodeSuppressedProjection(UUID uuid, long tick) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            out.writeByte(TYPE_PROJECTION_SUPPRESSED);
            out.writeLong(uuid.getMostSignificantBits());
            out.writeLong(uuid.getLeastSignificantBits());
            out.writeLong(tick);
            return bytes.toByteArray();
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    public static SuppressedProjection decodeSuppressedProjection(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int version = in.readInt();
            if (version < 17 || version > VERSION || in.readUnsignedByte() != TYPE_PROJECTION_SUPPRESSED) return null;
            SuppressedProjection value = new SuppressedProjection(new UUID(in.readLong(), in.readLong()), in.readLong());
            return in.available() == 0 ? value : null;
        } catch (IOException | RuntimeException exception) { return null; }
    }

    public record SuppressedProjection(UUID playerUuid, long clientTick) { }

    public static byte[] encode(UUID playerUuid, BedrockClientAction action) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(32);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            out.writeByte(TYPE_CLIENT_ACTION);
            out.writeLong(playerUuid.getMostSignificantBits());
            out.writeLong(playerUuid.getLeastSignificantBits());
            out.writeUTF(action.name());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode Bedrock client action plugin message", exception);
        }
    }

    public static byte[] encode(BedrockMoveFrame frame) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(80);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            out.writeByte(TYPE_MOVE_FRAME);
            out.writeLong(frame.playerUuid().getMostSignificantBits());
            out.writeLong(frame.playerUuid().getLeastSignificantBits());
            out.writeInt(frame.protocolVersion().protocol());
            out.writeLong(frame.clientTick());
            writeVec3(out, frame.position());
            out.writeFloat(frame.yaw());
            out.writeFloat(frame.pitch());
            out.writeFloat(frame.headYaw());
            writeVec3(out, frame.packetPosition());
            writeCoordinateFrame(out, frame.coordinateFrame());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode Bedrock move frame plugin message", exception);
        }
    }

    public static byte[] encodeAcknowledgedMetadata(
            UUID playerUuid,
            Float width,
            Float height,
            Boolean gliding,
            Boolean crawling,
            Boolean swimming
    ) {
        return encodeAcknowledgedMetadata(playerUuid, width, height, gliding, crawling, swimming, null, null, null);
    }

    public static byte[] encodeAcknowledgedMetadata(UUID playerUuid, Float width, Float height,
            Boolean gliding, Boolean crawling, Boolean swimming,
            Boolean sneaking, Boolean spinning, Boolean sleeping) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(40);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            out.writeByte(TYPE_METADATA_ACKNOWLEDGED);
            out.writeLong(playerUuid.getMostSignificantBits());
            out.writeLong(playerUuid.getLeastSignificantBits());
            writeNullableFloat(out, width);
            writeNullableFloat(out, height);
            writeNullableBoolean(out, gliding);
            writeNullableBoolean(out, crawling);
            writeNullableBoolean(out, swimming);
            writeNullableBoolean(out, sneaking);
            writeNullableBoolean(out, spinning);
            writeNullableBoolean(out, sleeping);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode Bedrock metadata plugin message", exception);
        }
    }

    public static BedrockAuthInputFrame decode(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int version = in.readInt();
            if ((version < 1 || version > VERSION) || in.readUnsignedByte() != TYPE_AUTH_INPUT) {
                return null;
            }

            UUID uuid = new UUID(in.readLong(), in.readLong());
            BedrockAuthInputFrame.Builder builder = BedrockAuthInputFrame.builder(uuid)
                    .protocolVersion(in.readInt())
                    .clientTick(in.readLong())
                    .inputMode(in.readInt())
                    .playMode(in.readInt())
                    .deviceId(in.readInt());
            Vec3 position = readVec3(in);
            builder.position(position);
            if (version >= 2) {
                builder.packetPosition(readVec3(in));
            } else {
                builder.packetPosition(position);
            }
            builder.delta(readVec3(in));

            float yaw = in.readFloat();
            float pitch = in.readFloat();
            float headYaw = in.readFloat();
            if (in.readBoolean()) {
                builder.rotation(yaw, pitch, headYaw);
            }

            BedrockMoveVector moveVector = readMoveVector(in);
            if (moveVector != null) {
                builder.moveVector(moveVector.x(), moveVector.z());
            }

            builder.rawInputFlags(in.readLong());
            if (version >= 5) {
                builder.rawInputFlagsHigh(in.readLong());
            }
            if (version >= 8) {
                builder.projectedOnGround(in.readBoolean());
            }
            builder.jumping(in.readBoolean())
                    .jumpStarted(in.readBoolean())
                    .jumpPressedRaw(in.readBoolean())
                    .jumpCurrentRaw(in.readBoolean())
                    .wantUp(in.readBoolean())
                    .sneaking(in.readBoolean())
                    .startSneaking(in.readBoolean())
                    .stopSneaking(in.readBoolean())
                    .sprinting(in.readBoolean())
                    .startSwimming(in.readBoolean())
                    .stopSwimming(in.readBoolean())
                    .startCrawling(in.readBoolean())
                    .stopCrawling(in.readBoolean())
                    .startGliding(in.readBoolean())
                    .stopGliding(in.readBoolean())
                    .usingItem(in.readBoolean())
                    .blockAction(in.readBoolean())
                    .authorityMode(readString(in))
                    .rewindCorrectionId(in.readLong());
            if (version >= 14) {
                builder.reportedEndOfTickVelocity(readVec3(in));
            } else {
                // Consume retired fields to preserve older payload layouts.
                if (version >= 12) {
                    in.readLong();
                    builder.reportedEndOfTickVelocity(readVec3(in));
                } else if (version == 11) {
                    in.readLong();
                    builder.reportedEndOfTickVelocity(readVec3(in));
                    in.readLong();
                }
            }

            builder.coordinateProvenance(version >= 17);
            if (version >= 17) builder.coordinateFrame(readCoordinateFrame(in));
            if (in.available() != 0) {
                return null;
            }
            return builder.build();
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    public static ClientActionMessage decodeClientAction(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int version = in.readInt();
            if ((version < 1 || version > VERSION) || in.readUnsignedByte() != TYPE_CLIENT_ACTION) {
                return null;
            }

            UUID uuid = new UUID(in.readLong(), in.readLong());
            BedrockClientAction action = BedrockClientAction.valueOf(in.readUTF());
            if (in.available() != 0) {
                return null;
            }
            return new ClientActionMessage(uuid, action);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    public static BedrockMoveFrame decodeMoveFrame(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int version = in.readInt();
            if ((version < 1 || version > VERSION) || in.readUnsignedByte() != TYPE_MOVE_FRAME) {
                return null;
            }

            UUID uuid = new UUID(in.readLong(), in.readLong());
            BedrockMoveFrame frame = new BedrockMoveFrame(
                    uuid,
                    new BedrockProtocolVersion(in.readInt()),
                    in.readLong(),
                    readVec3(in),
                    in.readFloat(),
                    in.readFloat(),
                    in.readFloat());
            if (version >= 17) {
                frame = new BedrockMoveFrame(uuid, frame.protocolVersion(), frame.clientTick(), frame.position(),
                        frame.yaw(), frame.pitch(), frame.headYaw(), readVec3(in), readCoordinateFrame(in), true);
            }
            if (in.available() != 0) {
                return null;
            }
            return frame;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    public static MetadataMessage decodeAcknowledgedMetadata(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int version = in.readInt();
            if ((version < 16 || version > VERSION) || in.readUnsignedByte() != TYPE_METADATA_ACKNOWLEDGED) {
                return null;
            }
            MetadataMessage message = new MetadataMessage(
                    new UUID(in.readLong(), in.readLong()),
                    readNullableFloat(in),
                    readNullableFloat(in),
                    readNullableBoolean(in),
                    readNullableBoolean(in),
                    readNullableBoolean(in),
                    version >= 18 ? readNullableBoolean(in) : null,
                    version >= 18 ? readNullableBoolean(in) : null,
                    version >= 18 ? readNullableBoolean(in) : null);
            if (in.available() != 0
                    || message.width() != null && (!Float.isFinite(message.width()) || message.width() <= 0.0F)
                    || message.height() != null && (!Float.isFinite(message.height()) || message.height() <= 0.0F)) {
                return null;
            }
            return message;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    public record ClientActionMessage(UUID playerUuid, BedrockClientAction action) {
    }

    public record MetadataMessage(
            UUID playerUuid,
            Float width,
            Float height,
            Boolean gliding,
            Boolean crawling,
            Boolean swimming,
            Boolean sneaking,
            Boolean spinning,
            Boolean sleeping
    ) {
    }

    private static void writeNullableFloat(DataOutputStream out, Float value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeFloat(value);
        }
    }

    private static Float readNullableFloat(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readFloat() : null;
    }

    private static void writeNullableBoolean(DataOutputStream out, Boolean value) throws IOException {
        out.writeByte(value == null ? -1 : value ? 1 : 0);
    }

    private static Boolean readNullableBoolean(DataInputStream in) throws IOException {
        return switch (in.readByte()) {
            case -1 -> null;
            case 0 -> false;
            case 1 -> true;
            default -> throw new IOException("Invalid nullable boolean");
        };
    }


    private static boolean isFinite(Vec3 vector) {
        return vector != null
                && Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }

    private static void writeCoordinateFrame(DataOutputStream out, BedrockCoordinateFrame frame) throws IOException {
        out.writeInt(frame.originX());
        out.writeInt(frame.originZ());
        out.writeLong(frame.revision());
    }

    private static BedrockCoordinateFrame readCoordinateFrame(DataInputStream in) throws IOException {
        return new BedrockCoordinateFrame(in.readInt(), in.readInt(), in.readLong());
    }

    private static void writeVec3(DataOutputStream out, Vec3 vec) throws IOException {
        out.writeBoolean(vec != null);
        if (vec == null) {
            return;
        }
        out.writeDouble(vec.x);
        out.writeDouble(vec.y);
        out.writeDouble(vec.z);
    }

    private static Vec3 readVec3(DataInputStream in) throws IOException {
        if (!in.readBoolean()) {
            return null;
        }
        return new Vec3(in.readDouble(), in.readDouble(), in.readDouble());
    }

    private static void writeMoveVector(DataOutputStream out, BedrockMoveVector vector) throws IOException {
        out.writeBoolean(vector != null);
        if (vector == null) {
            return;
        }
        out.writeFloat(vector.x());
        out.writeFloat(vector.z());
    }

    private static BedrockMoveVector readMoveVector(DataInputStream in) throws IOException {
        if (!in.readBoolean()) {
            return null;
        }
        return new BedrockMoveVector(in.readFloat(), in.readFloat());
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value == null) {
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_LENGTH) {
            throw new IOException("string too long");
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        if (!in.readBoolean()) {
            return null;
        }
        int length = in.readUnsignedShort();
        if (length > MAX_STRING_LENGTH) {
            throw new IOException("string too long");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("truncated string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
