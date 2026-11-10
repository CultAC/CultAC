package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import ac.cult.blocksim.engine.BlockSimClientVersion;
import org.junit.jupiter.api.Test;

/** {@link BlockSimClientVersion#of} resolves, and rejects, every protocol as a walk over {@code values()} does. */
class BlockSimClientVersionOfTest {
    private static Object walk(int protocol) {
        for (var version : BlockSimClientVersion.values()) if (version.protocol() == protocol) return version;
        return new IllegalArgumentException("Unsupported client action protocol " + protocol).getMessage();
    }

    private static Object resolve(int protocol) {
        try {
            return BlockSimClientVersion.of(protocol);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    @Test
    void resolvesAsTheWalk() {
        int resolved = 0;
        for (int protocol = -2; protocol < 1200; protocol++) {
            Object expected = walk(protocol);
            Object actual = resolve(protocol);
            if (expected instanceof BlockSimClientVersion) {
                assertSame(expected, actual, "protocol " + protocol);
                resolved++;
            } else assertEquals(expected, actual, "protocol " + protocol);
        }
        assertEquals(BlockSimClientVersion.values().length, resolved);
    }
}
