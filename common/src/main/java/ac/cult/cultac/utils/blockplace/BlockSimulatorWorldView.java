package ac.cult.cultac.utils.blockplace;

import ac.cult.blocksim.engine.BlockEntityData;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.engine.SimWorldView;
import ac.cult.blocksim.engine.Vec3;
import ac.cult.blocksim.environment.BooleanEnvironment;
import ac.cult.cultac.events.packets.PacketWorldBorder;
import ac.cult.cultac.protocol.data.ModelBlockStates;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import java.util.Set;

/** Compensated read boundary: native IDs are projected into the single bundled model. */
public final class BlockSimulatorWorldView implements SimWorldView {
    private static final int VOID_AIR = ac.cult.blocksim.data.DataTables.defaults()
            .registry()
            .block("minecraft:void_air")
            .defaultState();

    @Override
    public int seaLevel() {
        return world.clientSeaLevel();
    }

    @Override
    public String biomeKeyAt(BlockPos pos) {
        return world.clientBiomeKeyAt(new ac.cult.cultac.protocol.value.BlockPos(pos.x(), pos.y(), pos.z()));
    }
    /** BlockItem uses a placement context; standing/wall items use the empty context. */
    public static boolean placementUnobstructed(
            ac.cult.blocksim.interaction.PlacementContext context,
            int state,
            ac.cult.blocksim.interaction.PlacementObstruction.CollisionContext kind) {
        var level = context.level();
        var pos = context.clickedPos();
        var behavior = level.behavior(state);
        ac.cult.blocksim.engine.shapes.VoxelShape shape;
        if (kind == ac.cult.blocksim.interaction.PlacementObstruction.CollisionContext.EMPTY) {
            shape = behavior.collisionShape(level, state, pos);
        } else {
            var facts = context.player().collision();
            shape = behavior.collisionShape(
                    level,
                    state,
                    pos,
                    new ac.cult.blocksim.engine.EntityCollisionContext(
                            facts.bottom(),
                            facts.descending(),
                            facts.fallDistance(),
                            facts.powderSnowWalkable(),
                            true));
        }
        return level.isUnobstructed(shape.move(pos.x(), pos.y(), pos.z()));
    }

    /** Metadata established by received packets at the same transaction boundary as the world. */
    public interface Metadata {
        Set<String> recipeInputs(String key);

        BooleanEnvironment.Values environmentAt(BlockPos pos);

        BlockEntityData blockEntityAt(BlockPos pos);

        default int itemFrameOutputAt(BlockPos pos, Direction direction) {
            throw new IllegalStateException("Missing compensated item-frame data");
        }

        default int detectorRailOutputAt(BlockPos pos) {
            throw new IllegalStateException("Missing compensated minecart data");
        }

        default boolean hasSittingCatAt(BlockPos pos) {
            throw new IllegalStateException("Missing compensated cat data");
        }
    }

    private final CompensatedWorld world;
    private final ModelBlockStates states;
    private final Metadata metadata;
    private final ClientEntityQueries entities;
    private final String dimension;
    private final double minX, minZ, maxX, maxZ;

    public BlockSimulatorWorldView(CompensatedWorld world, PacketWorldBorder border, ModelBlockStates states) {
        this(world, border, states, new Metadata() {
            @Override
            public Set<String> recipeInputs(String key) {
                return world.clientRecipeInputs(key);
            }

            @Override
            public BooleanEnvironment.Values environmentAt(BlockPos pos) {
                return world.clientEnvironmentAt(nativePos(pos));
            }

            @Override
            public BlockEntityData blockEntityAt(BlockPos pos) {
                return world.getClientBlockEntityData(nativePos(pos));
            }

            private ac.cult.cultac.protocol.value.BlockPos nativePos(BlockPos pos) {
                return new ac.cult.cultac.protocol.value.BlockPos(pos.x(), pos.y(), pos.z());
            }

            @Override
            public int itemFrameOutputAt(BlockPos pos, Direction direction) {
                var query =
                        new ac.cult.blocksim.data.Box(pos.x(), pos.y(), pos.z(), pos.x() + 1, pos.y() + 1, pos.z() + 1);
                ac.cult.cultac.utils.data.packetentity.PacketEntityHanging found = null;
                for (var entity : world.player.compensatedEntities.entityMap.values())
                    if (entity instanceof ac.cult.cultac.utils.data.packetentity.PacketEntityHanging frame
                            && !frame.typeKey.equals("minecraft:painting")
                            && frame.facing == direction
                            && frame.actionBounds().intersects(query)) {
                        if (found != null) return Integer.MIN_VALUE;
                        found = frame;
                    }
                return found == null ? Integer.MIN_VALUE : found.analogOutput();
            }

            @Override
            public int detectorRailOutputAt(BlockPos pos) {
                // DetectorRailBlock reads cart inventory or command successCount. Neither is synced
                // to the client entity: received menus use their own storage, and cart metadata
                // only syncs command/lastOutput. The newly created client cart retains empty/zero.
                return 0;
            }

            @Override
            public boolean hasSittingCatAt(BlockPos pos) {
                return new ClientEntityQueries(world.player).hasSittingCatAt(pos);
            }
        });
    }

    public BlockSimulatorWorldView(
            CompensatedWorld world, PacketWorldBorder border, ModelBlockStates states, Metadata metadata) {
        this.world = java.util.Objects.requireNonNull(world);
        this.states = java.util.Objects.requireNonNull(states);
        this.metadata = java.util.Objects.requireNonNull(metadata);
        entities = new ClientEntityQueries(world.player, this);
        dimension = world.getVisibleDimension();
        double radius = border.getBlockInteractionDiameter() / 2.0, limit = border.getAbsoluteMaxSize();
        minX = Math.clamp(border.getCenterX() - radius, -limit, limit);
        minZ = Math.clamp(border.getCenterZ() - radius, -limit, limit);
        maxX = Math.clamp(border.getCenterX() + radius, -limit, limit);
        maxZ = Math.clamp(border.getCenterZ() + radius, -limit, limit);
    }

    @Override
    public int stateAt(BlockPos pos) {
        return isLoaded(pos) ? states.toModel(world.getBlockStateIdAt(pos.x(), pos.y(), pos.z())) : VOID_AIR;
    }

    @Override
    public boolean isLoaded(BlockPos pos) {
        return world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4);
    }

    // ClientChunkCache.getChunk supplies EmptyLevelChunk, not unknown world data.
    @Override
    public boolean isReadable(BlockPos pos) {
        return true;
    }

    @Override
    public boolean isSectionEmpty(BlockPos pos) {
        var chunk = world.getChunk(pos.x() >> 4, pos.z() >> 4);
        if (chunk == null) return true;
        var section = chunk.getSection((pos.y() - minY()) >> 4);
        return section == null || section.isEmpty();
    }

    @Override
    public int minY() {
        return world.getMinHeight();
    }

    @Override
    public int height() {
        return world.getMaxHeight() - minY();
    }

    @Override
    public String dimensionKey() {
        return dimension;
    }

    @Override
    public boolean isWithinBorder(BlockPos pos) {
        return pos.x() >= minX && pos.x() < maxX && pos.z() >= minZ && pos.z() < maxZ;
    }

    @Override
    public Vec3 borderHit(Vec3 from, Vec3 to) {
        // CollisionGetter.clipIncludingBorder clamps the endpoint, rather than intersecting a plane.
        boolean fromInside = from.x() >= minX && from.x() < maxX && from.z() >= minZ && from.z() < maxZ;
        boolean toInside = to.x() >= minX && to.x() < maxX && to.z() >= minZ && to.z() < maxZ;
        return fromInside && !toInside
                ? new Vec3(Math.clamp(to.x(), minX, maxX - 1.0E-5F), to.y(), Math.clamp(to.z(), minZ, maxZ - 1.0E-5F))
                : null;
    }

    @Override
    public boolean hasPickableEntityHit(Vec3 from, Vec3 to, SimPlayer player) {
        return entities.hasPickableEntityHit(from, to, player);
    }

    @Override
    public boolean hasPickableEntityAtEye(SimPlayer player) {
        return entities.hasPickableEntityAtEye(player);
    }

    @Override
    public boolean hasEntityCollision(ac.cult.blocksim.data.Box box, boolean boatSource) {
        return entities.hasEntityCollision(box, boatSource);
    }

    @Override
    public int itemFrameOutputAt(BlockPos pos, Direction direction) {
        return metadata.itemFrameOutputAt(pos, direction);
    }

    @Override
    public int detectorRailOutputAt(BlockPos pos) {
        return metadata.detectorRailOutputAt(pos);
    }

    @Override
    public boolean hasSittingCatAt(BlockPos pos) {
        return metadata.hasSittingCatAt(pos);
    }

    @Override
    public boolean canSpawn(ac.cult.blocksim.entity.EntityTypes.Type type) {
        return world.clientCanSpawn(type);
    }

    @Override
    public boolean hasFeature(String key) {
        return world.clientHasFeature(key);
    }

    @Override
    public boolean hasMinecartIn(ac.cult.blocksim.data.Box box) {
        return entities.hasMinecartIn(box);
    }

    @Override
    public boolean isUnobstructed(ac.cult.blocksim.engine.shapes.VoxelShape shape) {
        return entities.isUnobstructed(shape);
    }

    @Override
    public boolean hasHangingEntityOverlap(
            ac.cult.blocksim.data.Box box, Direction direction, String type, boolean allowSameType) {
        return world.player.compensatedEntities.entityMap.values().stream()
                .filter(entity -> entity instanceof ac.cult.cultac.utils.data.packetentity.PacketEntityHanging)
                .map(entity -> (ac.cult.cultac.utils.data.packetentity.PacketEntityHanging) entity)
                .anyMatch(entity -> entity.actionBounds().intersects(box)
                        && (entity.facing == direction || !allowSameType && entity.typeKey.equals(type)));
    }

    @Override
    public boolean hasBorderCollision(ac.cult.blocksim.data.Box box, Vec3 source) {
        return new ac.cult.blocksim.engine.BorderBounds(minX, minZ, maxX, maxZ).collides(box, source);
    }

    @Override
    public java.util.List<ac.cult.blocksim.entity.PaintingSize> placeablePaintings() {
        return world.player.getWorldRegistries().placeablePaintings();
    }

    @Override
    public ac.cult.blocksim.entity.PaintingSize paintingSize(com.google.gson.JsonElement holder) {
        if (holder.isJsonObject())
            return new ac.cult.blocksim.entity.PaintingSize(
                    holder.getAsJsonObject().get("width").getAsInt(),
                    holder.getAsJsonObject().get("height").getAsInt());
        return world.player.getWorldRegistries().painting(holder.getAsString());
    }

    @Override
    public ac.cult.blocksim.engine.shapes.VoxelShape movingPistonCollisionAt(BlockPos pos) {
        var boxes = ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper.toBoxes(
                        world.pistons.getMovingPistonCollisionShape(
                                new ac.cult.cultac.protocol.value.BlockPos(pos.x(), pos.y(), pos.z())))
                .stream()
                .map(box -> new ac.cult.blocksim.data.Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ))
                .toList();
        return ac.cult.blocksim.engine.shapes.Shapes.fromBoxes(boxes);
    }

    @Override
    public Set<String> recipeInputs(String key) {
        return metadata.recipeInputs(key);
    }

    @Override
    public boolean creakingActiveAt(BlockPos pos) {
        return metadata.environmentAt(pos).creakingActive();
    }

    @Override
    public boolean waterEvaporatesAt(BlockPos pos) {
        return metadata.environmentAt(pos).waterEvaporates();
    }

    @Override
    public int rawBrightnessAt(BlockPos pos) {
        return world.getRawBrightness(pos.x(), pos.y(), pos.z());
    }

    @Override
    public BlockEntityData blockEntityAt(BlockPos pos) {
        return isLoaded(pos) ? metadata.blockEntityAt(pos) : null;
    }
}
