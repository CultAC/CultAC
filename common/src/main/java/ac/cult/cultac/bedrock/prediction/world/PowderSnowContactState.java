package ac.cult.cultac.bedrock.prediction.world;

public record PowderSnowContactState(boolean actorIntersection, boolean feetSurface, boolean rawAtFeetAscendable) {
    public static final PowderSnowContactState NONE = new PowderSnowContactState(false, false, false);
}
