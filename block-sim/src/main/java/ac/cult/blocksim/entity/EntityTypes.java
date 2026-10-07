package ac.cult.blocksim.entity;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.engine.Vec3;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable 26.3 model entity facts. Host and wire IDs are mapped before this lookup. */
public final class EntityTypes {
    public static final int LIVING = 1, ANIMAL = 2, AGEABLE = 4, PROJECTILE = 8, HORSE = 16, BOAT = 32, MINECART = 64,
        BLOCK_ATTACHED = 128, ARROW = 256, CHESTED_HORSE = 512;
    public record Type(int id, String key, String category, float width, float height, float eyeHeight, boolean fixed,
                       int families, boolean allowedInPeaceful, Set<String> features, List<Vec3> passengers, List<Vec3> vehicle,
                       float spawnScale, int updateInterval) {
        public Type { features = Set.copyOf(features); passengers = List.copyOf(passengers); vehicle = List.copyOf(vehicle); }
        public boolean has(int family) { return (families & family) != 0; }
        public boolean canSpawn(Set<String> enabledFeatures, boolean peaceful) {
            return enabledFeatures.containsAll(features) && (allowedInPeaceful || !peaceful);
        }
        /** EntityAttachments#getClamped(PASSENGER), after EntityDimensions#scale. */
        public Vec3 passengerAttachment(int index, float scale, float yaw) {
            if (passengers.isEmpty()) throw new IllegalStateException("No passenger attachment for " + key);
            return attachment(passengers.get(Math.clamp(index, 0, passengers.size() - 1)), scale, yaw);
        }
        /** EntityAttachments#get(VEHICLE, 0), after EntityDimensions#scale. */
        public Vec3 vehicleAttachment(float scale, float yaw) {
            if (vehicle.isEmpty()) throw new IllegalStateException("No vehicle attachment for " + key);
            return attachment(vehicle.get(0), scale, yaw);
        }
        private Vec3 attachment(Vec3 point, float scale, float yaw) {
            if (!fixed && scale != 1.0F) point = point.scale(scale);
            return point.yRot(-yaw * (float) (Math.PI / 180.0));
        }
        public Box boxAt(Vec3 position) {
            double radius = width / 2.0F;
            return new Box(position.x() - radius, position.y(), position.z() - radius,
                position.x() + radius, position.y() + height, position.z() + radius);
        }
        public Box spawnBoxAt(Vec3 position) {
            double radius = spawnScale * width / 2.0F;
            float spawnHeight = spawnScale * height;
            return new Box(position.x() - radius, position.y(), position.z() - radius,
                position.x() + radius, position.y() + spawnHeight, position.z() + radius);
        }
    }
    private static final class Defaults {
        static final EntityTypes VALUE;
        static { try { VALUE = load("26.3"); } catch (IOException e) { throw new ExceptionInInitializerError(e); } }
    }
    public static EntityTypes defaults() { return Defaults.VALUE; }
    private final List<Type> types;
    private final Map<String, Type> byKey;
    private EntityTypes(List<Type> types) {
        this.types = List.copyOf(types);
        var keys = new HashMap<String, Type>();
        for (var type : types) if (keys.put(type.key(), type) != null) throw new IllegalArgumentException("Duplicate entity " + type.key());
        byKey = Map.copyOf(keys);
    }
    public Type byId(int id) { return types.get(id); }
    public Type byKey(String key) { return byKey.get(key.contains(":") ? key : "minecraft:" + key); }
    public List<Type> types() { return types; }
    public static EntityTypes load(String version) throws IOException {
        String path = "/block-sim/" + version + "/entity-types.tsv";
        var stream = EntityTypes.class.getResourceAsStream(path);
        if (stream == null) throw new IOException("Missing entity table " + path);
        var types = new java.util.ArrayList<Type>();
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            reader.readLine();
            for (String row; (row = reader.readLine()) != null; ) {
                String[] fields = row.split("\t", -1);
                if (fields.length != 14 || Integer.parseInt(fields[0]) != types.size()) throw new IOException("Invalid entity row " + row);
                types.add(new Type(types.size(), fields[1], fields[2], Float.parseFloat(fields[3]), Float.parseFloat(fields[4]),
                    Float.parseFloat(fields[5]), Boolean.parseBoolean(fields[6]), Integer.parseInt(fields[7]), Boolean.parseBoolean(fields[8]),
                    fields[9].isEmpty() ? Set.of() : Set.of(fields[9].split(",")), points(fields[10]), points(fields[11]), Float.parseFloat(fields[12]), Integer.parseInt(fields[13])));
            }
        }
        return new EntityTypes(types);
    }
    private static List<Vec3> points(String field) {
        if (field.isEmpty()) return List.of();
        return java.util.Arrays.stream(field.split(";")).map(point -> {
            String[] xyz = point.split(",");
            return new Vec3(Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2]));
        }).toList();
    }
}
