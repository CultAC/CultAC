package ac.cult.placement.api;

import java.util.List;
import java.util.Map;

/** Only JDK values and this interface cross the isolated runtime boundary. */
public interface PlacementEngine extends BlockGeometry {
    record Pos(int x, int y, int z) {}

    record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}

    interface World {
        int stateAt(int x, int y, int z);

        int minY();

        int height();

        boolean loaded(int chunkX, int chunkZ);
        /** The client's current tag bindings; null reads the model's vanilla defaults. */
        default GeometryTags tags() {
            return null;
        }

        default boolean clear(Pos pos, List<Box> shape) {
            return true;
        }

        default boolean insideBorder(int x, int z) {
            return true;
        }

        default int brightness(int x, int y, int z) {
            throw new UnsupportedOperationException("Compensated light is unavailable");
        }
    }

    record Request(
            World world,
            String item,
            int count,
            Map<String, String> blockProperties,
            Pos clicked,
            String face,
            double hitX,
            double hitY,
            double hitZ,
            boolean inside,
            double playerX,
            double playerY,
            double playerZ,
            float yaw,
            float pitch,
            boolean secondaryUse,
            boolean creative) {
        public Request {
            blockProperties = Map.copyOf(blockProperties);
        }
    }

    record Write(Pos pos, int state) {}

    record Result(boolean consumes, int remaining, Pos primary, List<Write> writes) {
        public Result {
            writes = List.copyOf(writes);
        }
    }

    int stateCount();

    String stateName(int state);

    List<Box> collision(World world, Pos pos);

    List<Box> outline(World world, Pos pos);

    Result place(Request request);
}
