package ac.grim.grimac.bedrock.prediction.model;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import java.util.Objects;

public record BlockMovementSlowdownState(
    boolean active,
    double xMultiplier,
    double yMultiplier,
    double zMultiplier,
    boolean clearVelocityAfterMove
) {
    private static final double COBWEB_HORIZONTAL_MOVE_SCALE = 0.25D;
    private static final double COBWEB_VERTICAL_MOVE_SCALE = 0.05D;
    private static final double WEAVING_COBWEB_HORIZONTAL_MOVE_SCALE = 0.5D;
    private static final double WEAVING_COBWEB_VERTICAL_MOVE_SCALE = 0.25D;
    private static final double SWEET_BERRY_HORIZONTAL_MOVE_SCALE = 0.8D;
    private static final double SWEET_BERRY_VERTICAL_MOVE_SCALE = 0.75D;
    private static final double POWDER_SNOW_HORIZONTAL_MOVE_SCALE = 0.9D;
    private static final double POWDER_SNOW_VERTICAL_MOVE_SCALE = 1.5D;

    public static final BlockMovementSlowdownState NONE = new BlockMovementSlowdownState(
        false,
        1.0D,
        1.0D,
        1.0D,
        false
    );
    public static final BlockMovementSlowdownState COBWEB = new BlockMovementSlowdownState(
        true,
        COBWEB_HORIZONTAL_MOVE_SCALE,
        COBWEB_VERTICAL_MOVE_SCALE,
        COBWEB_HORIZONTAL_MOVE_SCALE,
        true
    );
    public static final BlockMovementSlowdownState WEAVING_COBWEB = new BlockMovementSlowdownState(
        true,
        WEAVING_COBWEB_HORIZONTAL_MOVE_SCALE,
        WEAVING_COBWEB_VERTICAL_MOVE_SCALE,
        WEAVING_COBWEB_HORIZONTAL_MOVE_SCALE,
        true
    );
    public static final BlockMovementSlowdownState SWEET_BERRY_BUSH = new BlockMovementSlowdownState(
        true,
        SWEET_BERRY_HORIZONTAL_MOVE_SCALE,
        SWEET_BERRY_VERTICAL_MOVE_SCALE,
        SWEET_BERRY_HORIZONTAL_MOVE_SCALE,
        true
    );
    public static final BlockMovementSlowdownState POWDER_SNOW = new BlockMovementSlowdownState(
        true,
        POWDER_SNOW_HORIZONTAL_MOVE_SCALE,
        POWDER_SNOW_VERTICAL_MOVE_SCALE,
        POWDER_SNOW_HORIZONTAL_MOVE_SCALE,
        true
    );

    public BlockMovementSlowdownState {
        if (!Double.isFinite(xMultiplier) || !Double.isFinite(yMultiplier) || !Double.isFinite(zMultiplier)) {
            throw new IllegalArgumentException("block movement slowdown multipliers must be finite");
        }
    }

    public static BlockMovementSlowdownState combine(
        BlockMovementSlowdownState first,
        BlockMovementSlowdownState second
    ) {
        if (!first.active) {
            return second;
        }
        if (!second.active) {
            return first;
        }
        return new BlockMovementSlowdownState(
            true,
            Math.min(first.xMultiplier, second.xMultiplier),
            Math.min(first.yMultiplier, second.yMultiplier),
            Math.min(first.zMultiplier, second.zMultiplier),
            first.clearVelocityAfterMove || second.clearVelocityAfterMove
        );
    }

    public Vec3d applyToMoveRequest(Vec3d moveRequest) {
        Objects.requireNonNull(moveRequest, "moveRequest");
        if (!active) {
            return moveRequest;
        }
        return new Vec3d(
            (float) moveRequest.x() * (float) xMultiplier,
            (float) moveRequest.y() * (float) yMultiplier,
            (float) moveRequest.z() * (float) zMultiplier
        );
    }
}
