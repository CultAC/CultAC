package ac.cult.blocksim.engine;

/** Client action semantics; shared-protocol releases have the same behavior boundaries. */
public enum BlockSimClientVersion {
    V1_21_2(768), V1_21_4(769), V1_21_5(770), V1_21_6(771), V1_21_7(772),
    V1_21_9(773), V1_21_11(774), V26_1(775), V26_2(776), V26_3(777);

    private static final BlockSimClientVersion[] VERSIONS = values();

    private final int protocol;
    BlockSimClientVersion(int protocol) { this.protocol = protocol; }
    public int protocol() { return protocol; }
    public boolean atLeast(BlockSimClientVersion other) { return protocol >= other.protocol; }
    public boolean before(BlockSimClientVersion other) { return protocol < other.protocol; }
    public static BlockSimClientVersion of(int protocol) {
        for (var version : VERSIONS) if (version.protocol == protocol) return version;
        throw new IllegalArgumentException("Unsupported client action protocol " + protocol);
    }
}
