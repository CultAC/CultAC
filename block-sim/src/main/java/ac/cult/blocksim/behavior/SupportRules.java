package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.shapes.SupportType;
import ac.cult.blocksim.data.StateFacts;
import java.util.Set;

public final class SupportRules {
    private final Set<String> unstableBottomCenter;
    public SupportRules(Set<String> unstableBottomCenter) { this.unstableBottomCenter = Set.copyOf(unstableBottomCenter); }

    public static boolean canSupportRigidBlock(SimLevel level, BlockPos pos) {
        return level.isFaceSturdy(level.stateAt(pos), pos, Direction.UP, SupportType.RIGID);
    }

    public boolean canSupportCenter(SimLevel level, BlockPos pos, Direction direction) {
        int state = level.stateAt(pos);
        return !(direction == Direction.DOWN && unstableBottomCenter.contains(level.registry().block(state).key()))
            && level.isFaceSturdy(state, pos, direction, SupportType.CENTER);
    }

    public static boolean canAttach(SimLevel level, BlockPos pos, Direction direction) {
        BlockPos relative = pos.relative(direction);
        return level.isFaceSturdy(level.stateAt(relative), relative, direction.opposite());
    }

    /** Shared attachment tests; tag-based vegetation support stays in its own rule. */
    public boolean testAttachment(String rule, SimLevel level, int state, BlockPos pos, Set<String> water) {
        Direction direction = switch (rule) {
            case "ceiling_full", "ceiling_center_dry" -> Direction.UP;
            case "wall_full", "wall_solid" -> Direction.valueOf(level.registry().value(state, "facing").toUpperCase(java.util.Locale.ROOT)).opposite();
            default -> Direction.DOWN;
        };
        BlockPos supportPos = pos.relative(direction);
        int supportState = level.stateAt(supportPos);
        return switch (rule) {
            case "floor_solid", "wall_solid" -> level.registry().facts(supportState).has(StateFacts.SOLID);
            case "floor_full", "wall_full", "ceiling_full" -> level.isFaceSturdy(supportState, supportPos, direction.opposite());
            case "floor_center" -> canSupportCenter(level, supportPos, Direction.UP);
            case "floor_rigid" -> canSupportRigidBlock(level, supportPos);
            case "floor_plate" -> canSupportRigidBlock(level, supportPos) || canSupportCenter(level, supportPos, Direction.UP);
            case "ceiling_center_dry" -> canSupportCenter(level, supportPos, Direction.DOWN) && !water.contains(level.fluidAt(pos).type());
            default -> throw new IllegalArgumentException("Unknown attachment rule " + rule);
        };
    }

    public static boolean fullFace(SimLevel level, BlockPos pos, Direction facing) {
        var support = pos.relative(facing.opposite());
        return level.isFaceSturdy(level.stateAt(support), support, facing);
    }
}
