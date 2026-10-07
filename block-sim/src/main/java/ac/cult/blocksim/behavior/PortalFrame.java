package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.*;
import java.util.Set;

/** MCP 26.3 PortalShape: one frame scan serves ignition and client neighbor shape updates. */
final class PortalFrame {
    private final Set<String> frames, fires;
    PortalFrame(Set<String> frames, Set<String> fires) { this.frames = frames; this.fires = fires; }
    private boolean frame(SimLevel level, BlockPos pos) { return frames.contains(level.registry().block(level.stateAt(pos)).key()); }
    private boolean empty(SimLevel level, BlockPos pos) {
        int state = level.stateAt(pos); String key = level.registry().block(state).key();
        return level.registry().facts(state).has(StateFacts.AIR) || fires.contains(key) || key.equals("minecraft:nether_portal");
    }
    boolean canIgnite(SimLevel level, BlockPos pos, Direction forward) {
        String dimension = level.dimensionKey();
        if (!dimension.equals("minecraft:overworld") && !dimension.equals("minecraft:the_nether")) return false;
        for (Direction face : Direction.values()) if (frame(level, pos.relative(face))) {
            Direction right = forward.axisName().equals("z") ? Direction.WEST : Direction.SOUTH;
            return matchesFrame(level, pos, right, false) || matchesFrame(level, pos, right == Direction.WEST ? Direction.SOUTH : Direction.WEST, false);
        }
        return false;
    }
    private int edge(SimLevel level, BlockPos pos, Direction direction) {
        for (int width = 0; width <= 21; width++) {
            var cell = pos.relative(direction, width);
            if (!empty(level, cell)) return frame(level, cell) ? width : 0;
            if (!frame(level, cell.relative(Direction.DOWN))) break;
        }
        return 0;
    }
    boolean isComplete(SimLevel level, BlockPos pos, String axis) {
        return matchesFrame(level, pos, axis.equals("x") ? Direction.WEST : Direction.SOUTH, true);
    }
    private boolean matchesFrame(SimLevel level, BlockPos pos, Direction right, boolean complete) {
        int minY = Math.max(level.minY(), pos.y() - 21);
        while (pos.y() > minY && empty(level, pos.relative(Direction.DOWN))) pos = pos.relative(Direction.DOWN);
        int left = edge(level, pos, right.opposite()) - 1;
        if (left < 0) return false;
        var bottom = pos.relative(right.opposite(), left);
        int width = edge(level, bottom, right), portals = 0;
        if (width < 2 || width > 21) return false;
        int height = 0;
        rows: for (; height < 21; height++) {
            var row = bottom.relative(Direction.UP, height);
            if (!frame(level, row.relative(right, -1)) || !frame(level, row.relative(right, width))) break;
            for (int x = 0; x < width; x++) {
                var cell = row.relative(right, x);
                if (!empty(level, cell)) break rows;
                if (level.registry().block(level.stateAt(cell)).key().equals("minecraft:nether_portal")) portals++;
            }
        }
        if (height < 3 || portals != (complete ? width * height : 0)) return false;
        for (int x = 0; x < width; x++) if (!frame(level, bottom.relative(Direction.UP, height).relative(right, x))) return false;
        return true;
    }
}
