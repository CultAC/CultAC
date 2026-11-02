package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.network.protocol.ClientVersion;
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
}
