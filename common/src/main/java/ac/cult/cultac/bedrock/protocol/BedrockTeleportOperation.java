package ac.cult.cultac.bedrock.protocol;

/** Stable identity across wire emissions; the existing teleport queue owns transaction and setback state. */
public record BedrockTeleportOperation(long id, BedrockTeleportProvenance provenance,
                                      Integer setbackTransaction) {
    public BedrockTeleportOperation {
        if (id <= 0) throw new IllegalArgumentException("Invalid teleport operation ID");
    }

    public boolean changesWorldPosition() {
        return provenance != BedrockTeleportProvenance.GFP_REBASE;
    }
}
