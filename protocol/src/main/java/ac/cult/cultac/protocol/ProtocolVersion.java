package ac.cult.cultac.protocol;

/**
 * Supported release wire formats, named after their pinned registry-report version.
 * Protocol 768 also covers 1.21.2; 772 covers 1.21.8; 773 covers 1.21.10;
 * 775 covers 26.1.1 and 26.1.2. Snapshots and release candidates are unsupported.
 */
public enum ProtocolVersion {
    V1_21_3(768, "1.21.3"),
    V1_21_4(769, "1.21.4"),
    V1_21_5(770, "1.21.5"),
    V1_21_6(771, "1.21.6"),
    V1_21_7(772, "1.21.7"),
    V1_21_9(773, "1.21.9"),
    V1_21_11(774, "1.21.11"),
    V26_1(775, "26.1"),
    V26_2(776, "26.2"),
    V26_3(777, "26.3");

    private final int protocol;
    private final String minecraftVersion;

    ProtocolVersion(int protocol, String minecraftVersion) {
        this.protocol = protocol;
        this.minecraftVersion = minecraftVersion;
    }

    public int protocol() {
        return protocol;
    }

    public String minecraftVersion() {
        return minecraftVersion;
    }

    public boolean atLeast(ProtocolVersion other) {
        return protocol >= other.protocol;
    }

    public static ProtocolVersion of(int protocol) {
        for (ProtocolVersion version : values()) {
            if (version.protocol == protocol) {
                return version;
            }
        }
        throw new ProtocolResolutionException("Unsupported Minecraft protocol " + protocol);
    }
}
