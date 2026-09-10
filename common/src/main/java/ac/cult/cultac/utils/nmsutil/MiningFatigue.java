package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.network.protocol.ClientVersion;

final class MiningFatigue {
    private MiningFatigue() {
    }

    static float apply(float speed, int amplifier, ClientVersion version) {
        if (version.isNewerThanOrEquals(ClientVersion.V_26_3_RC_1)) {
            // 26.3 Player#getDestroySpeed casts the exponential scale to float
            // BEFORE multiplication. Amplifiers 2+ no longer use the old table.
            return speed * (float) Math.pow(0.3, amplifier + 1);
        }
        // Preserve Cult's existing arithmetic for older clients.
        double scale = switch (amplifier) {
            case 0 -> 0.3;
            case 1 -> 0.09;
            case 2 -> 0.0027;
            default -> 0.00081;
        };
        return (float) (speed * scale);
    }
}
