package ac.cult.cultac.bridge.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BridgeAuthTest {
    private static final byte[] SECRET = "forwarding-secret".getBytes(StandardCharsets.UTF_8);
    private static final UUID PLAYER = UUID.fromString("12345678-9abc-def0-1122-334455667788");

    private static byte[] range(int from) {
        byte[] bytes = new byte[BridgeProtocol.NONCE_BYTES];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (from + i);
        return bytes;
    }

    @Test
    void hmacMatchesIndependentlyComputedValue() {
        // Python: hmac.new(secret, b"cultac-bridge-v1" + uuid.bytes + bytes(range(16)) + bytes(range(16, 32)), sha256)
        byte[] expected = HexFormat.of().parseHex("24d6bffe1b756ee6fea41f6299291fcdf3829f0222ad2faae8ca53e6e5500b50");
        byte[] actual = BridgeAuth.hmac(SECRET, PLAYER, range(0), range(16));
        assertEquals(BridgeProtocol.HMAC_BYTES, actual.length);
        assertArrayEquals(expected, actual);
    }

    @Test
    void backendHmacMatchesIndependentlyComputedValue() {
        // Python: hmac.new(secret, b"cultac-bridge-v1-backend" + uuid.bytes + backendNonce + proxyNonce, sha256)
        byte[] expected = HexFormat.of().parseHex("1c2ed6d6dba6c78fcc4808adeb5d53bbb7e07dd966c59c397b3376dc04027d52");
        byte[] actual = BridgeAuth.backendHmac(SECRET, PLAYER, range(0), range(16));
        assertArrayEquals(expected, actual);
        assertFalse(BridgeAuth.verify(BridgeAuth.hmac(SECRET, PLAYER, range(16), range(0)), actual));
        assertFalse(BridgeAuth.verify(BridgeAuth.hmac(SECRET, PLAYER, range(0), range(16)), actual));
    }

    @Test
    void verifyAcceptsOnlyTheExactMac() {
        byte[] mac = BridgeAuth.hmac(SECRET, PLAYER, range(0), range(16));
        assertTrue(BridgeAuth.verify(mac, mac.clone()));
        byte[] flipped = mac.clone();
        flipped[31] ^= 1;
        assertFalse(BridgeAuth.verify(mac, flipped));
        assertFalse(BridgeAuth.verify(mac, BridgeAuth.hmac(SECRET, PLAYER, range(16), range(0))));
        assertFalse(BridgeAuth.verify(mac, BridgeAuth.hmac(SECRET, new UUID(0L, 0L), range(0), range(16))));
    }

    @Test
    void noncesAreFreshAndSized() {
        byte[] first = BridgeAuth.nonce();
        assertEquals(BridgeProtocol.NONCE_BYTES, first.length);
        assertFalse(Arrays.equals(first, BridgeAuth.nonce()));
    }
}
