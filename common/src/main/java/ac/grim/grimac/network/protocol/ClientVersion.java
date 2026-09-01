package ac.grim.grimac.network.protocol;

public enum ClientVersion {
    V_1_7_10(5, "1.7.10"),
    V_1_8(47, "1.8"),
    V_1_9(107, "1.9"),
    V_1_12_2(340, "1.12.2"),
    V_1_13(393, "1.13"),
    V_1_14(477, "1.14"),
    V_1_14_4(498, "1.14.4"),
    V_1_15_2(578, "1.15.2"),
    V_1_18_2(758, "1.18.2"),
    V_1_19(759, "1.19"),
    // TODO: Everything older than 1.19.4 is unsupported in CultAC
    V_1_19_4(762, "1.19.4"),
    V_1_20(763, "1.20"),
    V_1_20_2(764, "1.20.2"),
    V_1_20_3(765, "1.20.3"),
    V_1_20_5(766, "1.20.5/1.20.6"),
    V_1_21(767, "1.21.1"),
    V_1_21_2(768, "1.21.2"),
    V_1_21_4(769, "1.21.4"),
    V_1_21_5(770, "1.21.5"),
    V_1_21_6(771, "1.21.6"),
    V_1_21_7(772, "1.21.7"),
    V_1_21_9(773, "1.21.9"),
    V_1_21_11(774, "1.21.11"),
    V_26_1(775, "26.1"),
    V_26_2(776, "26.2"),
    HIGHER_THAN_SUPPORTED_VERSIONS(V_26_2.protocolVersion + 1, "HIGHER_THAN_SUPPORTED");

    private final int protocolVersion;
    private final String releaseName;

    ClientVersion(int protocolVersion, String releaseName) {
        this.protocolVersion = protocolVersion;
        this.releaseName = releaseName;
    }

    public boolean isOlderThan(ClientVersion version) {
        return protocolVersion < version.protocolVersion;
    }

    public boolean isOlderThanOrEquals(ClientVersion version) {
        return protocolVersion <= version.protocolVersion;
    }

    public boolean isNewerThan(ClientVersion version) {
        return protocolVersion > version.protocolVersion;
    }

    public boolean isNewerThanOrEquals(ClientVersion version) {
        return protocolVersion >= version.protocolVersion;
    }

    public String getReleaseName() {
        return releaseName;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    // TODO: Should we add anticheat logic here? I mean, it kind of makes sense
    public boolean usesModernEntityStepCollision() {
        // Entity#collide uses the legacy two-probe step calculation through 1.20.6. 1.21/protocol
        // 767 is the first stable release using collectCandidateStepUpHeights.
        return isNewerThanOrEquals(V_1_21);
    }

    // TODO: This i
    public static ClientVersion fromProtocolVersion(int protocolVersion) {
        ClientVersion selected = V_1_7_10;
        for (ClientVersion version : values()) {
            if (protocolVersion >= version.protocolVersion) {
                selected = version;
            }
        }
        return selected;
    }
}
