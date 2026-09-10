package ac.cult.cultac.network.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Also run against each unmodified Mojang server jar in the compatibility probe. */
class SwingPacketUtilTest {
    @Test
    void recognizesTheSwingFamilyProvidedByTheRuntime() throws Exception {
        Class<?> legacy = optionalClass(SwingPacketUtil.LEGACY_SWING_PACKET);
        Class<?> punch = optionalClass(SwingPacketUtil.PUNCH_PACKET);
        assertTrue((legacy == null) != (punch == null), "Exactly one swing protocol must be present");
        if (legacy != null) {
            Class<?> hand = Class.forName("net.minecraft.world.InteractionHand");
            Object main = legacy.getConstructor(hand).newInstance(hand.getEnumConstants()[0]);
            Object off = legacy.getConstructor(hand).newInstance(hand.getEnumConstants()[1]);
            assertTrue(SwingPacketUtil.isSwing(main));
            assertTrue(SwingPacketUtil.isMainHandSwing(main));
            assertTrue(SwingPacketUtil.isSwing(off));
            assertFalse(SwingPacketUtil.isMainHandSwing(off));
        } else {
            Object packet = punch.getField("INSTANCE").get(null);
            assertTrue(SwingPacketUtil.isSwing(packet));
            assertTrue(SwingPacketUtil.isMainHandSwing(packet));
        }
    }

    @Test
    void anUnrelatedPacketDoesNotSatisfyTheSwingRequirement() throws Exception {
        Object packet = Class.forName("net.minecraft.network.protocol.game.ServerboundClientTickEndPacket")
                .getField("INSTANCE").get(null);
        assertFalse(SwingPacketUtil.isSwing(packet));
        assertFalse(SwingPacketUtil.isMainHandSwing(packet));
    }

    private static Class<?> optionalClass(String name) throws ClassNotFoundException {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }
}
