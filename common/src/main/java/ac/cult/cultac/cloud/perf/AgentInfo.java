package ac.cult.cultac.cloud.perf;

/**
 * The agent a profile comes from, as the cloud connect announced it: {@code "paper"} or {@code "velocity"}, the server
 * id, and the build and config; commit, version and config hash are null when unknown.
 */
public record AgentInfo(
        String platform, String serverId, String commit, String version, String configHash, String mode) {}
