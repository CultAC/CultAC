package ac.cult.cultac.bedrock.protocol;

/** Provenance comes from the local bridge's dispatch boundary, never from client input. */
public enum BedrockTeleportProvenance {
    GEYSER,
    CULT_SETBACK,
    GFP_REBASE
}
