package ac.cult.cultac.bedrock.bridge;

import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeyserEntityDeltaFlagsTest {
    @Test public void negotiatedCodecSelectsTheWireFlags() {
        var packet = new ModernDelta();
        packet.getFlags().add(MoveEntityDeltaPacket.Flag.HAS_X);
        assertEquals(new GeyserEntityDeltaFlags(true, true, true, true), GeyserEntityDeltaFlags.read(packet, 2168));
        assertEquals(new GeyserEntityDeltaFlags(false, false, false, false), GeyserEntityDeltaFlags.read(packet, 1001));
        packet.getFlags().addAll(java.util.EnumSet.of(MoveEntityDeltaPacket.Flag.ON_GROUND,
                MoveEntityDeltaPacket.Flag.TELEPORTING, MoveEntityDeltaPacket.Flag.FORCE_MOVE_LOCAL_ENTITY,
                MoveEntityDeltaPacket.Flag.FORCE_COMPLETION));
        packet.enabled = false;
        assertEquals(new GeyserEntityDeltaFlags(false, false, false, false), GeyserEntityDeltaFlags.read(packet, 2168));
        assertEquals(new GeyserEntityDeltaFlags(true, true, true, true), GeyserEntityDeltaFlags.read(packet, 1001));
    }

    public static class ModernDelta extends MoveEntityDeltaPacket {
        boolean enabled = true;
        public boolean isOnGround() { return enabled; }
        public boolean isForceMove() { return enabled; }
        public boolean isForceMoveLocalEntity() { return enabled; }
        public boolean isForceCompletion() { return enabled; }
    }
}
