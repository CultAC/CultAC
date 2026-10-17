package ac.cult.cultac.bridge.protocol;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Session authentication: HMAC-SHA256 keyed with the shared forwarding secret. */
public final class BridgeAuth {
    private static final byte[] DOMAIN = "cultac-bridge-v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BACKEND_DOMAIN = "cultac-bridge-v1-backend".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private BridgeAuth() {}

    /** The proxy's AUTH proof. */
    public static byte[] hmac(byte[] secret, UUID player, byte[] proxyNonce, byte[] backendNonce) {
        return mac(secret, DOMAIN, player, proxyNonce, backendNonce);
    }

    /** The backend's READY proof; a distinct label and nonce order keep it from ever equalling an AUTH proof. */
    public static byte[] backendHmac(byte[] secret, UUID player, byte[] backendNonce, byte[] proxyNonce) {
        return mac(secret, BACKEND_DOMAIN, player, backendNonce, proxyNonce);
    }

    private static byte[] mac(byte[] secret, byte[] domain, UUID player, byte[] firstNonce, byte[] secondNonce) {
        if (secret.length == 0) throw new IllegalArgumentException("Empty bridge secret");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(domain);
            long msb = player.getMostSignificantBits();
            long lsb = player.getLeastSignificantBits();
            for (int shift = 56; shift >= 0; shift -= 8) mac.update((byte) (msb >>> shift));
            for (int shift = 56; shift >= 0; shift -= 8) mac.update((byte) (lsb >>> shift));
            mac.update(firstNonce);
            mac.update(secondNonce);
            return mac.doFinal();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HmacSHA256 unavailable", failure);
        }
    }

    public static boolean verify(byte[] expected, byte[] actual) {
        return MessageDigest.isEqual(expected, actual);
    }

    public static byte[] nonce() {
        byte[] nonce = new byte[BridgeProtocol.NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return nonce;
    }
}
