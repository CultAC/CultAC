package ac.cult.cultac.bridge.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One frame on {@link BridgeProtocol#CHANNEL}; {@link #type()} is its wire id. */
public sealed interface BridgeMessage {
    int type();

    private static void fixed(byte[] bytes, int length, String name) {
        if (bytes.length != length) throw new IllegalArgumentException(name + " must be " + length + " bytes");
    }

    record Hello(int version, UUID player, byte[] proxyNonce) implements BridgeMessage {
        public static final int TYPE = 0x01;

        public Hello {
            fixed(proxyNonce, BridgeProtocol.NONCE_BYTES, "proxyNonce");
        }

        @Override
        public int type() {
            return TYPE;
        }
    }

    record Challenge(int version, byte[] backendNonce) implements BridgeMessage {
        public static final int TYPE = 0x02;

        public Challenge {
            fixed(backendNonce, BridgeProtocol.NONCE_BYTES, "backendNonce");
        }

        @Override
        public int type() {
            return TYPE;
        }
    }

    record Auth(byte[] hmac) implements BridgeMessage {
        public static final int TYPE = 0x03;

        public Auth {
            fixed(hmac, BridgeProtocol.HMAC_BYTES, "hmac");
        }

        @Override
        public int type() {
            return TYPE;
        }
    }

    /** {@code hmac} is {@link BridgeAuth#backendHmac}, proving the backend holds the secret. */
    record Ready(byte[] hmac, long capabilities, String serverVersion, UUID worldUid, String worldName, int listeners)
            implements BridgeMessage {
        public static final int TYPE = 0x04;

        public Ready {
            fixed(hmac, BridgeProtocol.HMAC_BYTES, "hmac");
        }

        @Override
        public int type() {
            return TYPE;
        }
    }

    record Reject(String reason) implements BridgeMessage {
        public static final int TYPE = 0x05;

        @Override
        public int type() {
            return TYPE;
        }
    }

    /** The proxy closed the session; the companion drops it without answering. */
    record Close(String reason) implements BridgeMessage {
        public static final int TYPE = 0x07;

        @Override
        public int type() {
            return TYPE;
        }
    }

    record Listeners(int listeners) implements BridgeMessage {
        public static final int TYPE = 0x06;

        @Override
        public int type() {
            return TYPE;
        }
    }

    record World(UUID uid, String name) implements BridgeMessage {
        public static final int TYPE = 0x08;

        @Override
        public int type() {
            return TYPE;
        }
    }

    record ServerStats(float tps) implements BridgeMessage {
        public static final int TYPE = 0x09;

        @Override
        public int type() {
            return TYPE;
        }
    }

    record Result(int requestId, boolean success) implements BridgeMessage {
        public static final int TYPE = 0x0A;

        @Override
        public int type() {
            return TYPE;
        }
    }

    /** The player's game mode (a {@link SetGameMode} value) and location on the backend. */
    record PlayerStateResult(
            int requestId, int gameMode, UUID world, double x, double y, double z, float yaw, float pitch)
            implements BridgeMessage {
        public static final int TYPE = 0x0B;

        @Override
        public int type() {
            return TYPE;
        }
    }

    /**
     * The backend's facts for the cloud upload, sent once per session after READY: its target tick length in
     * microseconds (0 when unknown) and server settings that change combat, by key.
     */
    record CloudFacts(int tickMicros, Map<String, String> settings) implements BridgeMessage {
        public static final int TYPE = 0x23;

        public CloudFacts {
            settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
        }

        @Override
        public int type() {
            return TYPE;
        }
    }

    /** The player's settings that change combat, by key; sent after READY and whenever they change. */
    record PlayerFacts(Map<String, String> settings) implements BridgeMessage {
        public static final int TYPE = 0x24;

        public PlayerFacts {
            settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
        }

        @Override
        public int type() {
            return TYPE;
        }
    }

    /**
     * Sent by the proxy after game packets of the player's, and sent back with the same {@code marker} once the server
     * has handled them and the tick that handled them has ended, after every update that tick sent the player.
     */
    record Marker(int marker) implements BridgeMessage {
        public static final int TYPE = 0x25;

        @Override
        public int type() {
            return TYPE;
        }
    }

    /** Ends the active use only while the player uses an item of type {@code itemKey} in {@code hand}. */
    record ClearActiveItem(int hand, String itemKey) implements BridgeMessage {
        public static final int TYPE = 0x0C;
        public static final int MAIN_HAND = 0;
        public static final int OFF_HAND = 1;

        @Override
        public int type() {
            return TYPE;
        }
    }

    record SetGameMode(int mode) implements BridgeMessage {
        public static final int TYPE = 0x0D;
        public static final int SURVIVAL = 0;
        public static final int CREATIVE = 1;
        public static final int ADVENTURE = 2;
        public static final int SPECTATOR = 3;

        @Override
        public int type() {
            return TYPE;
        }
    }

    record Teleport(int requestId, UUID world, double x, double y, double z, float yaw, float pitch)
            implements BridgeMessage {
        public static final int TYPE = 0x0E;

        @Override
        public int type() {
            return TYPE;
        }
    }

    record TeleportToPlayer(int requestId, UUID target) implements BridgeMessage {
        public static final int TYPE = 0x0F;

        @Override
        public int type() {
            return TYPE;
        }
    }

    record CloseInventory() implements BridgeMessage {
        public static final int TYPE = 0x10;

        @Override
        public int type() {
            return TYPE;
        }
    }

    record ValidationBlock(int requestId, int x, int y, int z, String blockState, boolean packetOnly)
            implements BridgeMessage {
        public static final int TYPE = 0x11;

        @Override
        public int type() {
            return TYPE;
        }
    }

    record ConsoleExec(int requestId, String command) implements BridgeMessage {
        public static final int TYPE = 0x12;

        @Override
        public int type() {
            return TYPE;
        }
    }

    /** Answered with {@link PlayerStateResult}, or a failed {@link Result} when the player is gone. */
    record PlayerState(int requestId) implements BridgeMessage {
        public static final int TYPE = 0x13;

        @Override
        public int type() {
            return TYPE;
        }
    }

    record UserInfo(String brand, String versionName, boolean vanillaMath) implements BridgeMessage {
        public static final int TYPE = 0x14;

        @Override
        public int type() {
            return TYPE;
        }
    }

    /** One chunk of the check catalog; {@code last} marks the final chunk. */
    record CheckCatalog(List<CheckInfo> checks, boolean last) implements BridgeMessage {
        public static final int TYPE = 0x15;

        public CheckCatalog {
            checks = List.copyOf(checks);
        }

        @Override
        public int type() {
            return TYPE;
        }
    }

    record CheckInfo(
            int idx,
            String checkName,
            String configName,
            String alternativeName,
            String description,
            String stableKey,
            double setbackVL,
            double decay,
            boolean experimental,
            boolean enabled) {}

    record Metrics(int transactionPing, double sensX, double sensY, int lastTransSent, int lastTransRecv)
            implements BridgeMessage {
        public static final int TYPE = 0x16;

        @Override
        public int type() {
            return TYPE;
        }
    }

    /**
     * A proxy flag no proxy FlagEvent handler cancelled. {@code violations} is the count the check holds once this flag
     * commits; listeners see one less while the event fires, as on Paper. With {@code awaitVerdict} the proxy commits
     * the flag only when the VERDICT for {@code flagId} is not cancelled, or after its timeout; without it the flag
     * has already committed on the proxy.
     */
    record Flag(int flagId, int checkIdx, double violations, String verbose, boolean awaitVerdict)
            implements BridgeMessage {
        public static final int TYPE = 0x17;

        @Override
        public int type() {
            return TYPE;
        }
    }

    record Verdict(int flagId, boolean cancelled) implements BridgeMessage {
        public static final int TYPE = 0x18;

        @Override
        public int type() {
            return TYPE;
        }
    }

    record Setback(int teleportId, double x, double y, double z) implements BridgeMessage {
        public static final int TYPE = 0x19;

        @Override
        public int type() {
            return TYPE;
        }
    }

    record AlertState(int bits) implements BridgeMessage {
        public static final int TYPE = 0x1A;
        public static final int ALERTS = 1;
        public static final int VERBOSE = 1 << 1;
        public static final int BRANDS = 1 << 2;

        @Override
        public int type() {
            return TYPE;
        }
    }

    /** {@code kind} is {@link #ALERTS}, {@link #VERBOSE} or {@link #BRANDS}. */
    record AlertToggle(int kind, boolean enabled, boolean silent) implements BridgeMessage {
        public static final int TYPE = 0x1B;
        public static final int ALERTS = 0;
        public static final int VERBOSE = 1;
        public static final int BRANDS = 2;

        @Override
        public int type() {
            return TYPE;
        }
    }

    record Reloaded(boolean success) implements BridgeMessage {
        public static final int TYPE = 0x1C;

        @Override
        public int type() {
            return TYPE;
        }
    }

    /** A punishment command for the backend console; {@code violations} is the check's count when it ran. */
    record CommandExecute(int requestId, int checkIdx, double violations, String verbose, String command)
            implements BridgeMessage {
        public static final int TYPE = 0x1D;

        @Override
        public int type() {
            return TYPE;
        }
    }

    /** {@code sender} is {@link #CONSOLE} for the backend console. */
    record Command(int requestId, UUID sender, String senderName, String line) implements BridgeMessage {
        public static final int TYPE = 0x1E;
        public static final UUID CONSOLE = new UUID(0L, 0L);

        @Override
        public int type() {
            return TYPE;
        }
    }

    record CommandOutput(int requestId, String componentJson) implements BridgeMessage {
        public static final int TYPE = 0x1F;

        @Override
        public int type() {
            return TYPE;
        }
    }

    record CommandDone(int requestId) implements BridgeMessage {
        public static final int TYPE = 0x20;

        @Override
        public int type() {
            return TYPE;
        }
    }

    record Suggest(int requestId, UUID sender, String line) implements BridgeMessage {
        public static final int TYPE = 0x21;

        @Override
        public int type() {
            return TYPE;
        }
    }

    record Suggestions(int requestId, List<String> suggestions) implements BridgeMessage {
        public static final int TYPE = 0x22;

        public Suggestions {
            suggestions = List.copyOf(suggestions);
        }

        @Override
        public int type() {
            return TYPE;
        }
    }

    /** Asks the backend for the player's Lunar Client mods; it answers with CLIENT_MODS carrying {@code requestId}. */
    record ModsRequest(int requestId) implements BridgeMessage {
        public static final int TYPE = 0x26;

        @Override
        public int type() {
            return TYPE;
        }
    }

    /**
     * The player's installed Lunar Client mods as Apollo reported them. {@code requestId} answers a MODS_REQUEST, or is
     * {@link #PUSHED} when the backend sends it unasked; {@code lunar} is false, with no mods, when the player has no
     * Apollo connection on the backend.
     */
    record ClientMods(int requestId, boolean lunar, List<ClientMod> mods) implements BridgeMessage {
        public static final int TYPE = 0x27;
        public static final int PUSHED = 0;

        public ClientMods {
            mods = List.copyOf(mods);
        }

        @Override
        public int type() {
            return TYPE;
        }
    }

    /** One Lunar Client mod; {@code type} is Apollo's mod type name, such as FABRIC_EXTERNAL. */
    record ClientMod(String id, String name, String version, String type) {}
}
