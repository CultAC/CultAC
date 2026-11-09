package ac.cult.cultac.bedrock.protocol;

public record BedrockProtocolVersion(int protocol) implements Comparable<BedrockProtocolVersion> {
    public static final BedrockProtocolVersion UNKNOWN = new BedrockProtocolVersion(-1);
    // 1.26.60, from which Cult models the positions of living actors, boats, minecarts and end crystals as it does
    // players'; for earlier clients it models only players'.
    public static final BedrockProtocolVersion V1_26_60 = new BedrockProtocolVersion(2225);

    public boolean isKnown() {
        return protocol >= 0;
    }

    @Override
    public int compareTo(BedrockProtocolVersion other) {
        return Integer.compare(protocol, other.protocol);
    }
}
