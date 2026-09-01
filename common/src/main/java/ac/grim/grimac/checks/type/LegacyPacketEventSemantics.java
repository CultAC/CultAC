package ac.grim.grimac.checks.type;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;

/** PacketEvents classifications that were part of legacy check behavior. */
public final class LegacyPacketEventSemantics {
    private LegacyPacketEventSemantics() {
    }

    /** Matches the old Check#isAsync classification for serverbound PLAY packets. */
    public static boolean isAsync(Packet<?> packet) {
        return packet instanceof ServerboundKeepAlivePacket
                || packet instanceof ServerboundChunkBatchReceivedPacket
                || packet instanceof ServerboundResourcePackPacket;
    }
}
