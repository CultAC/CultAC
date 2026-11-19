package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JavaPistonPoseVersionsTest {
    /** Player#updatePlayerPose prioritizes fall flying through 1.21.4; getDesiredPose changes that in 1.21.5. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aGlidingPlayerUsesTheVersionsOwnPosePriority(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 position = new Vec3(0.5, FLOOR_Y + 5, 0.5);
            f.place(position, Vec3.ZERO);
            f.player.isGliding = true;
            assertEquals(
                    version.isOlderThan(ClientVersion.V_1_21_5) ? Pose.FALL_FLYING : Pose.SWIMMING,
                    f.player.updatedPose(true, position.x, position.y, position.z));
            f.player.isInBed = true;
            assertEquals(
                    version.isOlderThan(ClientVersion.V_1_21_5) ? Pose.FALL_FLYING : Pose.SLEEPING,
                    f.player.updatedPose(true, position.x, position.y, position.z));
        }
    }
}
