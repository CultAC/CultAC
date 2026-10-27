package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.network.protocol.ClientVersion;
import java.util.List;

/** The two source movement behaviors permitted for protocol 773. */
public enum JavaMovementPatch {
    V_1_21_9,
    V_1_21_10;

    public static final List<JavaMovementPatch> PROTOCOL_773 = List.of(V_1_21_9, V_1_21_10);

    /** Entity#move stops scaling PISTON by stuckSpeedMultiplier in 1.21.10. */
    public static boolean unscaledPiston(ClientVersion version, JavaMovementPatch patch) {
        return is1Dot21Dot10OrLater(version, patch);
    }

    /** BubbleColumnBlock#entityInside starts requiring a precise block intersection in 1.21.10. */
    public static boolean preciseBubbles(ClientVersion version, JavaMovementPatch patch) {
        return is1Dot21Dot10OrLater(version, patch);
    }

    private static boolean is1Dot21Dot10OrLater(ClientVersion version, JavaMovementPatch patch) {
        return version.isNewerThanOrEquals(ClientVersion.V_1_21_11)
                || version == ClientVersion.V_1_21_9 && patch == V_1_21_10;
    }
}
