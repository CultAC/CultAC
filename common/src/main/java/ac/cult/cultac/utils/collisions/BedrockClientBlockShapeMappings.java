package ac.cult.cultac.utils.collisions;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.bedrock.prediction.api.BedrockCollisionShapeQuery;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionOverrideCatalog;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionOverrideShape;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionWorldBuilder;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockServerStateMappings;
import ac.cult.cultac.bedrock.prediction.geometry.BlockAabb;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.ComplexCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.NoCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class BedrockClientBlockShapeMappings {
    // The bundled generator targets the latest Java version supported by this CultAC build.
    private static final ClientVersion CATALOG_JAVA_VERSION = ClientVersion.V_26_2;
    private static final BedrockCollisionOverrideCatalog NATIVE_FALLBACK =
            BedrockCollisionOverrideCatalog.nativeFallback();
    private static final AtomicReference<Snapshot> SNAPSHOT = new AtomicReference<>(Snapshot.empty());

    private BedrockClientBlockShapeMappings() {}

    public static void initialize() {
        BedrockCollisionOverrideCatalog artifact = BedrockCollisionOverrideCatalog.bundled();
        int nativeModelProtocol = ProtocolVersion.V26_3.protocol();
        int[] mappings = BedrockServerStateMappings.create(
                nativeModelProtocol,
                ac.cult.blocksim.data.DataTables.defaults().registry().stateCount(),
                CATALOG_JAVA_VERSION.getProtocolVersion(),
                artifact.javaStateCount());
        Snapshot snapshot = build(artifact.forServerStates(mappings));
        SNAPSHOT.set(snapshot);
        LogUtil.info("[ClientBlockShapes] Bedrock collision cache: native model protocol " + nativeModelProtocol
                + " -> catalog protocol " + CATALOG_JAVA_VERSION.getProtocolVersion() + ", " + mappings.length
                + " translated states, " + snapshot.staticMovementCount() + " static movement states, "
                + snapshot.dynamicMovementCount() + " dynamic movement states.");
    }

    public static BedrockCollisionOverrideCatalog catalog() {
        BedrockCollisionOverrideCatalog catalog = SNAPSHOT.get().catalog();
        return catalog == null ? NATIVE_FALLBACK : catalog;
    }

    public static boolean isInitialized() {
        return SNAPSHOT.get().catalog() != null;
    }

    public static void clear() {
        SNAPSHOT.set(Snapshot.empty());
    }

    static Optional<CollisionBox> movement(CultPlayer player, int state, int x, int y, int z) {
        if (player == null || player.bedrockState == null || state < 0) return Optional.empty();
        Snapshot snapshot = SNAPSHOT.get();
        Entry entry = snapshot.entry(state);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.dynamicMovement()) {
            return buildDynamicMovement(snapshot.catalog(), player, state, x, y, z);
        }
        return Optional.of(entry.movement().copy().offset(x, y, z));
    }

    static Optional<CollisionBox> visual(CultPlayer player, int state, int x, int y, int z) {
        return Optional.empty();
    }

    static Snapshot build(BedrockCollisionOverrideCatalog catalog) {
        Map<Integer, Entry> entries = new HashMap<>();
        for (int id = 0;
                id < ac.cult.blocksim.data.DataTables.defaults().registry().stateCount();
                id++) {
            int javaStateId = id;
            Optional<BedrockCollisionOverrideShape> override = catalog.override(javaStateId);
            boolean dynamicMovement = BedrockCollisionWorldBuilder.dynamicMovement(javaStateId);
            boolean clientComputedMovement = BedrockCollisionWorldBuilder.clientComputedMovement(javaStateId);
            if (dynamicMovement) {
                entries.put(javaStateId, new Entry(null, true, clientComputedMovement));
                continue;
            }
            if (clientComputedMovement) {
                CollisionBox movement = bedrockMovement(catalog, javaStateId, BedrockCollisionShapeQuery.NONE);
                entries.put(javaStateId, new Entry(movement, false, true));
                continue;
            }
            Optional<CollisionBox> movement = override.map(shape -> fromLocalBoxes(shape.boxes()))
                    .or(() -> VersionedJavaBlockShapes.movement(CATALOG_JAVA_VERSION, javaStateId, 0, 0, 0));
            movement.ifPresent(shape -> entries.put(javaStateId, new Entry(shape, false, false)));
        }
        return new Snapshot(Map.copyOf(entries), catalog.shapeCount(), catalog);
    }

    private static Optional<CollisionBox> buildDynamicMovement(
            BedrockCollisionOverrideCatalog catalog, CultPlayer player, int state, int x, int y, int z) {
        BlockPosition position = new BlockPosition(x, y, z);
        BlockCollisionWorld world =
                new BedrockCollisionWorldBuilder(catalog).build(Map.of(position, state), bedrockQuery(player));
        return Optional.of(world.blockAt(position)
                .map(block -> fromWorldBoxes(block.collisionBoxes()))
                .orElse(NoCollisionBox.INSTANCE));
    }

    private static CollisionBox bedrockMovement(
            BedrockCollisionOverrideCatalog catalog, int state, BedrockCollisionShapeQuery query) {
        BlockPosition position = new BlockPosition(0, 0, 0);
        BlockCollisionWorld world = new BedrockCollisionWorldBuilder(catalog).build(Map.of(position, state), query);
        return world.blockAt(position)
                .map(block -> fromWorldBoxes(block.collisionBoxes()))
                .orElse(NoCollisionBox.INSTANCE);
    }

    private static BedrockCollisionShapeQuery bedrockQuery(CultPlayer player) {
        if (player == null || player.boundingBox == null) {
            return BedrockCollisionShapeQuery.NONE;
        }
        SimpleCollisionBox box = player.boundingBox;
        WorldCollisionBox actorBox = new WorldCollisionBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        boolean wearingLeatherBoots = wearingLeatherBoots(player);
        return BedrockCollisionShapeQuery.actor(actorBox, player.isSneaking, wearingLeatherBoots);
    }

    private static boolean wearingLeatherBoots(CultPlayer player) {
        SimItemStack boots = player.getInventory().getBoots();
        return boots != null && boots.getItem() == ac.cult.cultac.utils.inventory.ItemTypes.LEATHER_BOOTS;
    }

    private static CollisionBox fromLocalBoxes(List<BlockAabb> boxes) {
        if (boxes.isEmpty()) {
            return NoCollisionBox.INSTANCE;
        }
        if (boxes.size() == 1) {
            BlockAabb box = boxes.getFirst();
            return new SimpleCollisionBox(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
        }
        ComplexCollisionBox complex = new ComplexCollisionBox();
        for (BlockAabb box : boxes) {
            complex.add(new SimpleCollisionBox(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
        }
        return complex;
    }

    private static CollisionBox fromWorldBoxes(List<WorldCollisionBox> boxes) {
        if (boxes.isEmpty()) {
            return NoCollisionBox.INSTANCE;
        }
        if (boxes.size() == 1) {
            return fromWorldBox(boxes.getFirst());
        }
        ComplexCollisionBox complex = new ComplexCollisionBox();
        for (WorldCollisionBox box : boxes) {
            complex.add(fromWorldBox(box));
        }
        return complex;
    }

    private static SimpleCollisionBox fromWorldBox(WorldCollisionBox box) {
        return new SimpleCollisionBox(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }

    record Snapshot(Map<Integer, Entry> entries, int shapeCount, BedrockCollisionOverrideCatalog catalog) {
        static Snapshot empty() {
            return new Snapshot(Map.of(), 0, null);
        }

        Entry entry(int stateId) {
            return entries.get(stateId);
        }

        int staticMovementCount() {
            int count = 0;
            for (Entry entry : entries.values()) {
                if (!entry.dynamicMovement()) {
                    count++;
                }
            }
            return count;
        }

        int dynamicMovementCount() {
            int count = 0;
            for (Entry entry : entries.values()) {
                if (entry.dynamicMovement()) {
                    count++;
                }
            }
            return count;
        }

        int clientComputedMovementCount() {
            int count = 0;
            for (Entry entry : entries.values()) {
                if (entry.clientComputedMovement()) {
                    count++;
                }
            }
            return count;
        }
    }

    record Entry(CollisionBox movement, boolean dynamicMovement, boolean clientComputedMovement) {}
}
