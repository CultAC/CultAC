package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.network.protocol.ClientVersion;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckManagerListenerDuplicateMovementTest {
    private static final Vec3 PREVIOUS = new Vec3(10.0D, 64.0D, -5.0D);
    private static final double THRESHOLD = 0.03D;

    @Test
    void classifiesVanilla117Through1206ThresholdPacketAsDuplicate() {
        assertTrue(duplicate(ClientVersion.V_1_18_2, PREVIOUS.add(0.01D, 0.0D, 0.01D)));
        assertTrue(duplicate(ClientVersion.V_1_20_5, PREVIOUS.add(0.01D, 0.0D, 0.01D)));
    }

    @Test
    void rejectsVersionsOutsideAffectedVanillaRange() {
        assertFalse(duplicate(ClientVersion.V_1_15_2, PREVIOUS));
        assertFalse(duplicate(ClientVersion.V_1_21, PREVIOUS));
    }

    @Test
    void rejectsMeaningfulMovementAndGroundChanges() {
        assertFalse(duplicate(ClientVersion.V_1_20_5, PREVIOUS.add(THRESHOLD + 0.001D, 0.0D, 0.0D)));
        assertFalse(CheckManagerListener.isOnePointSeventeenDuplicate(
                ClientVersion.V_1_20_5, false, true, true, false,
                true, false, PREVIOUS, PREVIOUS, THRESHOLD));
    }

    @Test
    void rejectsTeleportAndPacketsWithoutPositionAndLook() {
        assertFalse(CheckManagerListener.isOnePointSeventeenDuplicate(
                ClientVersion.V_1_20_5, true, true, true, false,
                true, true, PREVIOUS, PREVIOUS, THRESHOLD));
        assertFalse(CheckManagerListener.isOnePointSeventeenDuplicate(
                ClientVersion.V_1_20_5, false, false, true, false,
                true, true, PREVIOUS, PREVIOUS, THRESHOLD));
        assertFalse(CheckManagerListener.isOnePointSeventeenDuplicate(
                ClientVersion.V_1_20_5, false, true, false, false,
                true, true, PREVIOUS, PREVIOUS, THRESHOLD));
    }

    @Test
    void preservesBaselineMountedClassification() {
        assertTrue(CheckManagerListener.isOnePointSeventeenDuplicate(
                ClientVersion.V_1_20_5, false, true, true, true,
                false, true, PREVIOUS, PREVIOUS.add(100.0D, 0.0D, 0.0D), THRESHOLD));
    }

    private static boolean duplicate(ClientVersion version, Vec3 packetPosition) {
        return CheckManagerListener.isOnePointSeventeenDuplicate(
                version, false, true, true, false,
                true, true, PREVIOUS, packetPosition, THRESHOLD);
    }
}
