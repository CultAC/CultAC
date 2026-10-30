package ac.cult.cultac.network.routing;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Predicate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jetbrains.annotations.Nullable;

/**
 * Writes and reads the wire text {@code <base64 HMAC-SHA256>.<envelope JSON>}, the HMAC taken over the exact JSON
 * bytes. Reading drops what this instance must not act on and remembers every id it accepted for
 * {@value #REPLAY_WINDOW_MILLIS} ms. Any thread.
 */
final class EnvelopeCodec {
    /** Under the 1 MiB a NATS server takes by default, with room for its headers. */
    static final int MAX_BYTES = 768 * 1024;

    static final long MAX_SKEW_MILLIS = 30_000;
    static final long REPLAY_WINDOW_MILLIS = 60_000;

    private static final String HMAC = "HmacSHA256";

    enum Drop {
        TOO_LARGE,
        MALFORMED,
        BAD_SIGNATURE,
        UNSUPPORTED_VERSION,
        NOT_ADDRESSED,
        OWN_BROADCAST,
        STALE,
        UNKNOWN_TYPE,
        REPLAYED
    }

    /** {@code drop} is null when the envelope is accepted; {@code envelope} is set whenever the text parsed. */
    record Result(@Nullable Envelope envelope, @Nullable Drop drop) {
        static Result dropped(Drop drop) {
            return new Result(null, drop);
        }
    }

    private final @Nullable SecretKeySpec key;
    private final String self;
    private final Predicate<String> knownType;
    /** The accepted ids, in a scope of this codec's own. */
    private final SharedClaims seen;

    /**
     * {@code key} null writes unsigned text, which only {@link #decode} with {@code trusted} accepts. {@code seen}
     * remembers the accepted ids and is this codec's alone.
     */
    EnvelopeCodec(byte @Nullable [] key, String self, Predicate<String> knownType, SharedClaims seen) {
        this.key = key == null || key.length == 0 ? null : new SecretKeySpec(key, HMAC);
        this.self = self;
        this.knownType = knownType;
        this.seen = seen;
    }

    String encode(Envelope envelope) {
        String json = envelope.toJson().toString();
        String signature = key == null ? "" : Base64.getEncoder().encodeToString(sign(json));
        return signature + "." + json;
    }

    /**
     * Reads {@code text}; this instance's own are dropped on the {@code broadcast} channel, and {@code trusted} bus
     * text needs no signature.
     */
    Result decode(String text, boolean broadcast, boolean trusted, long now) {
        if (utf8Length(text) > MAX_BYTES) return Result.dropped(Drop.TOO_LARGE);
        int dot = text.indexOf('.');
        if (dot < 0) return Result.dropped(Drop.MALFORMED);
        String json = text.substring(dot + 1);
        if (!trusted && !verified(text.substring(0, dot), json)) return Result.dropped(Drop.BAD_SIGNATURE);
        Envelope envelope;
        try {
            envelope = parse(json);
        } catch (RuntimeException malformed) {
            return Result.dropped(Drop.MALFORMED);
        }
        if (envelope.v() != Envelope.VERSION) return Result.dropped(Drop.UNSUPPORTED_VERSION);
        if (!envelope.broadcast() && !envelope.to().equals(self)) return Result.dropped(Drop.NOT_ADDRESSED);
        if (!trusted && broadcast && envelope.from().equals(self)) return new Result(envelope, Drop.OWN_BROADCAST);
        if (Math.abs(now - envelope.ts()) > MAX_SKEW_MILLIS) return new Result(envelope, Drop.STALE);
        if (!knownType.test(envelope.type())) return new Result(envelope, Drop.UNKNOWN_TYPE);
        if (!seen.claim(envelope.id().toString(), now)) return Result.dropped(Drop.REPLAYED);
        return new Result(envelope, null);
    }

    private boolean verified(String signature, String json) {
        if (key == null || signature.isEmpty()) return false;
        byte[] claimed;
        try {
            claimed = Base64.getDecoder().decode(signature);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        return MessageDigest.isEqual(claimed, sign(json));
    }

    private byte[] sign(String json) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return mac.doFinal(json.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", unavailable);
        }
    }

    private static Envelope parse(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        JsonElement body = object.get("body");
        return new Envelope(
                object.get("v").getAsInt(),
                object.get("type").getAsString(),
                UUID.fromString(object.get("id").getAsString()),
                nonEmpty(object.get("from").getAsString()),
                nonEmpty(object.get("to").getAsString()),
                object.get("ts").getAsLong(),
                body == null ? new JsonObject() : body.getAsJsonObject());
    }

    private static String nonEmpty(String value) {
        if (value.isEmpty()) throw new IllegalArgumentException("empty instance");
        return value;
    }

    /** The UTF-8 length of {@code text}; past {@link #MAX_BYTES} characters, its character count, already too large. */
    static int utf8Length(String text) {
        if (text.length() > MAX_BYTES) return text.length();
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) bytes++;
            else if (c < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(c)
                    && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else bytes += 3;
        }
        return bytes;
    }
}
