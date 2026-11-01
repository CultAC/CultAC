package ac.cult.cultac.bridge.protocol;

import java.util.Set;

/** Server-side features a bridged backend serves; each owns one bit of READY.capabilities. */
public enum ServerCapability {
    TELEPORT(0),
    GAMEMODE(1),
    CLOSE_INVENTORY(2),
    VALIDATION_BLOCK(3),
    CONSOLE_EXEC(4),
    WORLD_INFO(5),
    SERVER_STATS(6),
    GRIM_API(7),
    COMMANDS(8),
    FLAG_VERDICT(9),
    /** Ends the player's active item use, in order with the player's game packets. */
    CLEAR_ACTIVE_ITEM(10),
    CLOUD_FACTS(11),
    /** Answers MARKER frames, in order with the player's game packets. */
    MARKER(12),
    /** Answers MODS_REQUEST with the player's Lunar Client mods from Apollo. */
    APOLLO_MODS(13);

    private final int bit;

    ServerCapability(int bit) {
        this.bit = bit;
    }

    public long flag() {
        return 1L << bit;
    }

    public static long mask(Set<ServerCapability> capabilities) {
        long mask = 0L;
        for (ServerCapability capability : capabilities) mask |= capability.flag();
        return mask;
    }

    public static boolean has(long mask, ServerCapability capability) {
        return (mask & capability.flag()) != 0L;
    }
}
