package ac.cult.cultac.network.packet;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/** Keeps the removed legacy swing class out of handler method descriptors. */
public final class SwingPacketUtil {
    public static final String LEGACY_SWING_PACKET = "net.minecraft.network.protocol.game.ServerboundSwingPacket";
    public static final String PUNCH_PACKET = "net.minecraft.network.protocol.game.ServerboundPunchPacket";

    private static final ClassValue<MethodHandle> LEGACY_HAND = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            try {
                return MethodHandles.publicLookup().unreflect(type.getMethod("getHand"));
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to read legacy swing hand", exception);
            }
        }
    };

    private SwingPacketUtil() {
    }

    public static boolean isSwing(Object packet) {
        String name = packet.getClass().getName();
        return name.equals(LEGACY_SWING_PACKET) || name.equals(PUNCH_PACKET);
    }

    public static boolean isMainHandSwing(Object packet) {
        // Minecraft#startAttack and #continueAttack send the handless punch
        // packet in 26.3. Item-use, drops, and STAB no longer send a swing.
        if (packet.getClass().getName().equals(PUNCH_PACKET)) {
            return true;
        }
        if (!packet.getClass().getName().equals(LEGACY_SWING_PACKET)) {
            return false;
        }
        try {
            return ((Enum<?>) LEGACY_HAND.get(packet.getClass()).invoke(packet)).name().equals("MAIN_HAND");
        } catch (Throwable exception) {
            throw new IllegalStateException("Unable to read legacy swing hand", exception);
        }
    }
}
