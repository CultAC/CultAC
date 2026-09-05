package ac.cult.cultac.bedrock.protocol;

public record BedrockProtocolVersion(int protocol) implements Comparable<BedrockProtocolVersion> {
    public static final BedrockProtocolVersion UNKNOWN = new BedrockProtocolVersion(-1);

    public boolean isKnown() {
        return protocol >= 0;
    }

    @Override
    public int compareTo(BedrockProtocolVersion other) {
        return Integer.compare(protocol, other.protocol);
    }
}
