package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JavaRepeatedEntityLerpTest {
    /** The native inverse-contact false: repeated identical rotations reset legacy lerpSteps to three. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void identicalTargetsRestartOnlyTheLegacyFixedLerp(ClientVersion version) throws Exception {
        for (boolean rotationOnly : new boolean[] {false, true}) {
            try (var f = new JavaTickFixture()) {
                JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
                f.addRemotePlayer(100, new Vec3(0, 80, .5), GameMode.SURVIVAL);
                var remote = f.player.compensatedEntities.getEntity(100);
                remote.onBundleTransaction(false, true, 3, 80, .5, 0F, 0F, f.player);
                remote.onMovement(f.player);
                double first = version == ClientVersion.V_26_3 ? 1.5 : 1;
                assertEquals(first, remote.clientPhysicalPosition.x, 1e-13);
                f.player.packetStateData.acceptedClientTick++;
                remote.onBundleTransaction(false, !rotationOnly, 3, 80, .5, 0F, 0F, f.player);
                remote.onMovement(f.player);
                double second = version == ClientVersion.V_1_21_4
                        ? first + (3 - first) / 3
                        : version == ClientVersion.V_26_3 ? 3 : 2;
                assertEquals(second, remote.clientPhysicalPosition.x, 1e-13, version + " rotationOnly=" + rotationOnly);
            }
        }
    }

    /**
     * InterpolationHandler#interpolateTo compares the packet position with its exact target. At this X the centre of
     * the boat's 1.375-wide box rebuilds 0.20041362610221336, which must not restart the running lerp: with one step
     * left, the duplicate leaves the boat at its 64.0 target, where a restart would hold it 0.0833 above.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_2", "V_26_3"})
    void aDuplicateTargetItsBoxCannotRebuildKeepsTheRunningLerp(ClientVersion version) throws Exception {
        double x = 0.20041362610221333;
        ProtocolVersion protocol = switch (version) {
            case V_1_21_11 -> ProtocolVersion.V1_21_11;
            case V_26_2 -> ProtocolVersion.V26_2;
            default -> ProtocolVersion.V26_3;
        };
        try (var f = new JavaTickFixture(protocol, version)) {
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.OAK_BOAT, new Vec3(x, 64.375, .5), 0, 0, 0);
            var boat = f.player.compensatedEntities.getEntity(100);
            boat.onBundleTransaction(false, true, x, 64, .5, 0F, 0F, f.player);
            for (double y : new double[] {64.25, 64.125}) {
                boat.onMovement(f.player);
                assertEquals(y, boat.clientPhysicalPosition.y, 1e-13);
                f.player.packetStateData.acceptedClientTick++;
            }
            boat.onBundleTransaction(false, true, x, 64, .5, 0F, 0F, f.player);
            boat.onMovement(f.player);
            assertEquals(64.0, boat.clientPhysicalPosition.y, 1e-13, version.toString());
            assertEquals(x, boat.clientPhysicalPosition.x);
        }
    }
}
