package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.CollideAxisData.CollideResult;
import java.util.List;

/**
 * Collisions probed once in each world of {@link EntityPositionWorlds}. Each axis holds what any world allows; a move
 * is clipped against one world at a time, or, for {@link #bounds}, between its two worlds on each axis.
 */
public final class EntityWorldCollisions extends CollideAxisData {
    private final List<CollideAxisData> worlds;
    private final List<List<SimpleCollisionBox>> colliders;
    private final boolean bounding;
    private CollideAxisData allAtReportedHeight;
    private CollideAxisData noneAtReportedHeight;

    private EntityWorldCollisions(
            List<CollideAxisData> worlds, List<List<SimpleCollisionBox>> colliders, boolean bounding) {
        super(null, null, null, null, List.of());
        this.worlds = worlds;
        this.colliders = colliders;
        this.bounding = bounding;
        for (int i = 0; i < worlds.size(); i++) {
            CollideAxisData world = worlds.get(i);
            if (world.getX() != null)
                combineX(
                        world.getX().isLikelyCollide(),
                        world.getX().getResult(),
                        world.getX().getSide());
            if (world.getYPos() != null)
                combineYPos(world.getYPos().isLikelyCollide(), world.getYPos().getResult());
            if (world.getYNeg() != null)
                combineYNeg(world.getYNeg().isLikelyCollide(), world.getYNeg().getResult());
            if (world.getZ() != null)
                combineZ(
                        world.getZ().isLikelyCollide(),
                        world.getZ().getResult(),
                        world.getZ().getSide());
            combineDirection(world.getXPos(), world.getXNeg(), world.getZPos(), world.getZNeg());
        }
    }

    private void combineDirection(CollideResult xPos, CollideResult xNeg, CollideResult zPos, CollideResult zNeg) {
        if (xPos != null) combineX(1, xPos.isLikelyCollide(), xPos.getResult());
        if (xNeg != null) combineX(-1, xNeg.isLikelyCollide(), xNeg.getResult());
        if (zPos != null) combineZ(1, zPos.isLikelyCollide(), zPos.getResult());
        if (zNeg != null) combineZ(-1, zNeg.isLikelyCollide(), zNeg.getResult());
    }

    /** The collisions of each world, probed with the entity boxes {@code colliders} holds at the same index. */
    public static EntityWorldCollisions of(List<CollideAxisData> worlds, List<List<SimpleCollisionBox>> colliders) {
        return new EntityWorldCollisions(List.copyOf(worlds), List.copyOf(colliders), false);
    }

    /**
     * The worlds that bound the client's, when the engine cannot place each box it may meet: {@code all}, probed with
     * every possible box {@code allColliders} holds, and {@code none}, probed with none of them. {@code unknown} holds
     * the boxes that each bound every shape a changing block may have.
     */
    public static EntityWorldCollisions bounds(
            CollideAxisData all,
            List<SimpleCollisionBox> allColliders,
            CollideAxisData none,
            List<SimpleCollisionBox> unknown,
            CollideAxisData allAtReportedHeight,
            CollideAxisData noneAtReportedHeight) {
        EntityWorldCollisions bounds = new EntityWorldCollisions(
                List.of(all, none), List.of(List.copyOf(allColliders), List.of()), true);
        bounds.setUnknown(List.copyOf(unknown));
        bounds.allAtReportedHeight = allAtReportedHeight;
        bounds.noneAtReportedHeight = noneAtReportedHeight;
        return bounds;
    }

    /** Whether {@link #worlds()} are the two {@link #bounds}, all first. */
    public boolean bounding() {
        return bounding;
    }

    /** For {@link #bounds}, the X and Z collisions of every possible box from the reported height; Y is unprobed. */
    public CollideAxisData allAtReportedHeight() {
        return allAtReportedHeight;
    }

    /** For {@link #bounds}, the X and Z collisions of none of the possible boxes from the reported height. */
    public CollideAxisData noneAtReportedHeight() {
        return noneAtReportedHeight;
    }

    public List<CollideAxisData> worlds() {
        return worlds;
    }

    /** The entity boxes of each world, at the index of its collisions in {@link #worlds()}. */
    public List<List<SimpleCollisionBox>> colliders() {
        return colliders;
    }
}
