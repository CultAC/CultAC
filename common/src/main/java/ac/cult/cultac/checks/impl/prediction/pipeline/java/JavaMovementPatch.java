package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.network.protocol.ClientVersion;

/**
 * Protocol 773 is simulated as 1.21.10; the wire protocol does not distinguish 1.21.9. A state starts on
 * {@link #V_1_21_10} and holds {@link #V_1_21_9} only after the 1.21.9 bubble callback or stuck-scaled piston move
 * changed its outcome; that state then keeps 1.21.9's rules. The player's proven patch ends the alternative.
 */
public enum JavaMovementPatch {
    V_1_21_9,
    V_1_21_10;

    /** Entity#move stops scaling PISTON by stuckSpeedMultiplier in 1.21.10. */
    public static boolean unscaledPiston(ClientVersion version, JavaMovementPatch patch) {
        return is1Dot21Dot10OrLater(version, patch);
    }

    /** BubbleColumnBlock#entityInside starts requiring a precise block intersection in 1.21.10. */
    public static boolean preciseBubbles(ClientVersion version, JavaMovementPatch patch) {
        return is1Dot21Dot10OrLater(version, patch);
    }

    /**
     * Whether a protocol 773 state on the assumed 1.21.10 patch also replays a block-entity pass with 1.21.9's
     * stuck-scaled PISTON moves: only until an accepted movement proves the player's patch.
     */
    public static boolean forksPistonRule(ClientVersion version, JavaMovementPatch proven, JavaMovementPatch patch) {
        return version == ClientVersion.V_1_21_9 && proven == null && patch == V_1_21_10;
    }

    /** The patch a protocol 773 state starts with: the proven one, else the assumed 1.21.10. */
    public static JavaMovementPatch assumed(JavaMovementPatch proven) {
        return proven == null ? V_1_21_10 : proven;
    }

    private static boolean is1Dot21Dot10OrLater(ClientVersion version, JavaMovementPatch patch) {
        return version.isNewerThanOrEquals(ClientVersion.V_1_21_11)
                || version == ClientVersion.V_1_21_9 && patch != V_1_21_9;
    }
}
