package ac.cult.cultac.checks.impl.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.bedrock.prediction.state.BedrockCameraWaterState;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.visibility.BedrockEye;
import java.util.List;
import org.junit.jupiter.api.Test;

class BedrockReachDebugTest {
    private static final double STANDING = BedrockCameraWaterState.STANDING_EYE_HEIGHT;
    private static final double SNEAKING = STANDING - 0.35;

    @Test
    void aSneakingClientsClaimStartsAtItsLoweredEye() {
        // A sneaking client reports its position at the standing eye height; its eye is 0.35 lower.
        Vec3 feet = new Vec3(0.5, 64, 0.5);
        Vec3 hit = new Vec3(0.5, 64.9, 2.3);
        Vec3 reported = feet.add(0, STANDING, 0);
        Vec3 eye = feet.add(0, SNEAKING, 0);
        var pick = pick(feet, hit, new BedrockEye(SNEAKING, SNEAKING));
        assertEquals(eye.y, ReachCheck.claimedEye(pick, reported, eye.distanceTo(hit)).y, 1.0E-9);
    }

    @Test
    void aStandingClientsClaimStartsAtTheReportedEye() {
        Vec3 feet = new Vec3(0.5, 64, 0.5);
        Vec3 hit = new Vec3(0.5, 64.9, 2.3);
        Vec3 reported = feet.add(0, STANDING, 0);
        var pick = pick(feet, hit, new BedrockEye(STANDING, STANDING));
        assertEquals(reported.y, ReachCheck.claimedEye(pick, reported, reported.distanceTo(hit)).y, 1.0E-9);
    }

    @Test
    void ofTheEyesTheClientMayHaveHadTheNearestTheMeasuredReachIsTaken() {
        Vec3 feet = new Vec3(0.5, 64, 0.5);
        Vec3 hit = new Vec3(0.5, 64.9, 2.3);
        Vec3 reported = feet.add(0, STANDING, 0);
        Vec3 sneaking = feet.add(0, SNEAKING, 0);
        var pick = pick(feet, hit, new BedrockEye(STANDING, STANDING), new BedrockEye(SNEAKING, SNEAKING));
        assertEquals(sneaking.y, ReachCheck.claimedEye(pick, reported, sneaking.distanceTo(hit)).y, 1.0E-9);
        assertEquals(reported.y, ReachCheck.claimedEye(pick, reported, reported.distanceTo(hit)).y, 1.0E-9);
    }

    private static BedrockAttackPick pick(Vec3 feet, Vec3 hit, BedrockEye... eyes) {
        return new BedrockAttackPick(feet, feet, hit, 1.0E-5, 0, 0, 0, 0, false, 5.7, List.of(eyes));
    }
}
