package ac.grim.grimac.utils.collisions;

import ac.grim.grimac.bedrock.prediction.api.BedrockCollisionShapeQuery;
import ac.grim.grimac.bedrock.prediction.geometry.BedrockCollisionOverrideCatalog;
import ac.grim.grimac.bedrock.prediction.geometry.BedrockCollisionOverrideShape;
import ac.grim.grimac.bedrock.prediction.geometry.BedrockCollisionWorldBuilder;
import ac.grim.grimac.bedrock.prediction.geometry.BlockAabb;
import ac.grim.grimac.bedrock.prediction.geometry.BlockPosition;
import ac.grim.grimac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.grim.grimac.bedrock.prediction.world.BlockCollisionWorld;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.ComplexCollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.NoCollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.inventory.ItemStack;

public final class BedrockClientBlockShapeMappings {
    private static final AtomicReference<Snapshot> SNAPSHOT = new AtomicReference<>(Snapshot.empty());

    private BedrockClientBlockShapeMappings() {
    }

    public static void initialize() {
        try {
            Snapshot snapshot = build(BedrockCollisionOverrideCatalog.bundled());
            SNAPSHOT.set(snapshot);
            LogUtil.info("[ClientBlockShapes] Bedrock collision override cache: "
                    + snapshot.staticMovementCount() + " static movement states, "
                    + snapshot.dynamicMovementCount() + " dynamic movement states, "
                    + snapshot.clientComputedMovementCount() + " client-computed movement states, "
                    + snapshot.shapeCount() + " unique override shapes.");
        } catch (RuntimeException exception) {
            SNAPSHOT.set(Snapshot.empty());
            LogUtil.warn("[ClientBlockShapes] Failed to build Bedrock collision override cache: " + exception.getMessage());
        }
    }

    static Optional<CollisionBox> movement(GrimPlayer player, BlockData state, int x, int y, int z) {
        if (player == null || player.bedrockState == null || state == null) {
            return Optional.empty();
        }
        BlockState blockState = toBlockState(state);
        if (blockState == null) {
            return Optional.empty();
        }
        Entry entry = SNAPSHOT.get().entry(Block.getId(blockState));
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.dynamicMovement()) {
            return buildDynamicMovement(player, blockState, x, y, z);
        }
        return Optional.of(entry.movement().copy().offset(x, y, z));
    }

    static Optional<CollisionBox> visual(GrimPlayer player, BlockData state, int x, int y, int z) {
        return Optional.empty();
    }

    static Snapshot build(BedrockCollisionOverrideCatalog catalog) {
        Map<Integer, Entry> entries = new HashMap<>();
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            int javaStateId = Block.getId(state);
            Optional<BedrockCollisionOverrideShape> override = catalog.override(javaStateId);
            boolean dynamicMovement = BedrockCollisionWorldBuilder.dynamicMovement(state);
            boolean clientComputedMovement = BedrockCollisionWorldBuilder.clientComputedMovement(state);
            if (dynamicMovement) {
                entries.put(javaStateId, new Entry(null, true, clientComputedMovement));
                continue;
            }
            if (clientComputedMovement) {
                CollisionBox movement = bedrockMovement(catalog, state, BedrockCollisionShapeQuery.NONE);
                entries.put(javaStateId, new Entry(movement, false, true));
                continue;
            }
            override.ifPresent(shape -> entries.put(javaStateId, new Entry(fromLocalBoxes(shape.boxes()), false, false)));
        }
        return new Snapshot(Map.copyOf(entries), catalog.shapeCount());
    }

    private static Optional<CollisionBox> buildDynamicMovement(GrimPlayer player, BlockState state, int x, int y, int z) {
        BlockPosition position = new BlockPosition(x, y, z);
        BlockCollisionWorld world = new BedrockCollisionWorldBuilder(BedrockCollisionOverrideCatalog.bundled())
                .build(Map.of(position, state), bedrockQuery(player, position, state));
        return Optional.of(world.blockAt(position)
                .map(block -> fromWorldBoxes(block.collisionBoxes()))
                .orElse(NoCollisionBox.INSTANCE));
    }

    private static CollisionBox bedrockMovement(
            BedrockCollisionOverrideCatalog catalog,
            BlockState state,
            BedrockCollisionShapeQuery query
    ) {
        BlockPosition position = new BlockPosition(0, 0, 0);
        BlockCollisionWorld world = new BedrockCollisionWorldBuilder(catalog)
                .build(Map.of(position, state), query);
        return world.blockAt(position)
                .map(block -> fromWorldBoxes(block.collisionBoxes()))
                .orElse(NoCollisionBox.INSTANCE);
    }

    public static boolean clientComputedMovement(BlockState state) {
        return BedrockCollisionWorldBuilder.clientComputedMovement(state);
    }

    private static BlockState toBlockState(BlockData state) {
        if (state instanceof CraftBlockData craftBlockData) {
            return craftBlockData.getState();
        }
        return null;
    }

    private static BedrockCollisionShapeQuery bedrockQuery(GrimPlayer player, BlockPosition position, BlockState state) {
        if (player == null || player.boundingBox == null) {
            return BedrockCollisionShapeQuery.NONE;
        }
        SimpleCollisionBox box = player.boundingBox;
        WorldCollisionBox actorBox = new WorldCollisionBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        boolean wearingLeatherBoots = wearingLeatherBoots(player);
        return BedrockCollisionShapeQuery.actor(
                actorBox,
                player.isSneaking,
                wearingLeatherBoots);
    }

    private static boolean wearingLeatherBoots(GrimPlayer player) {
        ItemStack boots = player.getInventory().getBoots();
        return boots != null && boots.getType() == Material.LEATHER_BOOTS;
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

    record Snapshot(Map<Integer, Entry> entries, int shapeCount) {
        static Snapshot empty() {
            return new Snapshot(Map.of(), 0);
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

    private record Entry(
            CollisionBox movement,
            boolean dynamicMovement,
            boolean clientComputedMovement
    ) {
    }
}
