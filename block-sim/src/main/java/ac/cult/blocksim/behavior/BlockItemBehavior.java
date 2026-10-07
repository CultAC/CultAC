package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.engine.BlockEntityComponentWriter;
import ac.cult.blocksim.engine.BlockRaycast;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.interaction.BlockHit;
import ac.cult.blocksim.interaction.PlacementContext;
import ac.cult.blocksim.interaction.PlacementObstruction;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;
import java.util.Set;

/** One placement workflow, with the small vanilla item-family differences kept explicit. */
public final class BlockItemBehavior implements ItemBehavior {
    public enum Kind { BLOCK, DOUBLE_HIGH, GAME_MASTER, STANDING_WALL, HANGING_SIGN, SCAFFOLDING, SOLID_BUCKET, WATER_SURFACE }
    private final Kind kind;
    private final Set<String> enabledFeatures;
    private final Set<String> water;
    private final ItemRegistry items;
    private final ItemBehavior generalItem;
    protected final PlacementObstruction obstruction;
    private final BlockEntityComponentWriter blockEntityComponents;

    public BlockItemBehavior(DataTables data, ItemBehavior generalItem, PlacementObstruction obstruction, BlockEntityComponentWriter blockEntityComponents) {
        this(data, generalItem, obstruction, blockEntityComponents, Kind.BLOCK);
    }
    public BlockItemBehavior(DataTables data, ItemBehavior generalItem, PlacementObstruction obstruction, BlockEntityComponentWriter blockEntityComponents, Kind kind) {
        this(data, kind == Kind.SOLID_BUCKET ? new ItemRegistry(data) : null, generalItem, obstruction, blockEntityComponents, kind);
    }
    public BlockItemBehavior(DataTables data, ItemRegistry items, ItemBehavior generalItem, PlacementObstruction obstruction, BlockEntityComponentWriter blockEntityComponents, Kind kind) {
        this.kind = java.util.Objects.requireNonNull(kind); water = data.tags().get("fluid:minecraft:water");
        this.items = items;
        enabledFeatures = data.enabledFeatures(); this.generalItem = java.util.Objects.requireNonNull(generalItem);
        this.obstruction = java.util.Objects.requireNonNull(obstruction); this.blockEntityComponents = java.util.Objects.requireNonNull(blockEntityComponents);
    }
    private BlockDefinition block(UseContext context) { return context.level().registry().block(context.usedItem().block()); }

    @Override
    public SimInteraction useOn(UseContext context) {
        if (kind == Kind.WATER_SURFACE) return SimInteraction.PASS;
        var result = useOnBlock(context);
        if (kind == Kind.SOLID_BUCKET && result.consumesAction() && context.player() != null) {
            context.player().hand(context.hand(), context.player().state().infiniteMaterials() ? context.stack() : items.stack("minecraft:bucket", 1));
        }
        return result;
    }

    private SimInteraction useOnBlock(UseContext context) {
        SimInteraction placed = place(new PlacementContext(context));
        if (placed.consumesAction()) return placed;
        SimInteraction fallback = generalItem.useOn(context);
        if (fallback.consumesAction()) return fallback;
        return context.stack().components().has("minecraft:consumable") ? generalItem.use(context) : placed;
    }
    @Override public SimInteraction use(UseContext context) {
        if (kind != Kind.WATER_SURFACE) return generalItem.use(context);
        var hit = BlockRaycast.playerPick(context.level(), context.player(), BlockRaycast.Fluid.SOURCE_ONLY).hit();
        var above = new BlockHit(hit.pos().relative(Direction.UP), hit.face(), hit.location(), hit.inside());
        return useOnBlock(new UseContext(context.level(), context.player(), context.hand(), context.player().hand(context.hand()), above, context.usedItem()));
    }

    public SimInteraction place(PlacementContext original) {
        if (!enabledFeatures.containsAll(block(original).features()) || !original.canPlace()) return SimInteraction.FAIL;
        PlacementContext context = updatePlacementContext(original);
        if (context == null) return SimInteraction.FAIL;
        int placement = placementState(context);
        if (placement < 0 || !placeBlock(context, placement)) return SimInteraction.FAIL;
        var level = context.level(); var pos = context.clickedPos(); int placed = level.stateAt(pos);
        if (level.registry().sameBlock(placed, placement)) {
            placed = updateBlockStateFromTag(context, placed);
            // updateCustomBlockEntityTag returns immediately on the client.
            blockEntityComponents.apply(context);
            level.behavior(placed).setPlacedBy(level, placed, pos, context.player(), context.stack());
        }
        // Sound, game events and ServerPlayer criteria have no contract outputs.
        context.stack().consume(1, context.player());
        return SimInteraction.SUCCESS;
    }

    public PlacementContext updatePlacementContext(PlacementContext context) {
        if (kind != Kind.SCAFFOLDING) return context;
        var level = context.level(); var pos = context.clickedPos(); var block = block(context);
        if (level.registry().block(level.stateAt(pos)) != block) return ScaffoldingBehavior.distance(level, pos) == 7 ? null : context;
        Direction direction = context.secondaryUseActive() ? context.inside() ? context.clickedFace().opposite() : context.clickedFace()
            : context.clickedFace() == Direction.UP ? context.horizontalDirection() : Direction.UP;
        int horizontalDistance = 0; var placement = pos.relative(direction);
        while (horizontalDistance < 7) {
            // Vanilla's world-bound/build-limit branch is guarded by !level.isClientSide().
            int state = level.stateAt(placement);
            if (level.registry().block(state) != block) {
                return level.behavior(state).canBeReplaced(level, state, context) ? context.at(placement, direction) : null;
            }
            placement = placement.relative(direction);
            if (direction.horizontal()) horizontalDistance++;
        }
        return null;
    }

    private int placementState(PlacementContext context) {
        if (kind == Kind.GAME_MASTER && context.player() != null && !context.player().state().gameMaster()) return -1;
        if (kind == Kind.STANDING_WALL || kind == Kind.HANGING_SIGN) return standingOrWallState(context);
        var block = block(context); int state = context.level().behavior(block.defaultState()).placementState(block, context);
        return state >= 0 && canPlace(context, state) ? state : -1;
    }

    private boolean canPlace(PlacementContext context, int state) {
        return (kind == Kind.SCAFFOLDING || context.level().behavior(state).canSurvive(context.level(), state, context.clickedPos()))
            && obstruction.isUnobstructed(context, state, PlacementObstruction.CollisionContext.PLACEMENT);
    }

    private int standingOrWallState(PlacementContext context) {
        var level = context.level(); var bindings = context.usedItem().bindings();
        var wall = level.registry().block(bindings.get("StandingAndWallBlockItem.wallBlock"));
        Direction attachment = Direction.valueOf(bindings.get("StandingAndWallBlockItem.attachmentDirection"));
        int wallState = level.behavior(wall.defaultState()).placementState(wall, context);
        int placed = -1;
        for (Direction direction : context.nearestLookingDirections()) {
            if (direction == attachment.opposite()) continue;
            int candidate = direction == attachment ? level.behavior(block(context).defaultState()).placementState(block(context), context) : wallState;
            if (candidate < 0) continue;
            var behavior = level.behavior(candidate);
            if (kind == Kind.HANGING_SIGN && behavior instanceof WallHangingSignBehavior hanging && !hanging.canPlace(level, candidate, context.clickedPos())) continue;
            if (behavior.canSurvive(level, candidate, context.clickedPos())) { placed = candidate; break; }
        }
        return placed >= 0 && obstruction.isUnobstructed(context, placed, PlacementObstruction.CollisionContext.EMPTY) ? placed : -1;
    }

    private boolean placeBlock(PlacementContext context, int state) {
        if (kind == Kind.DOUBLE_HIGH) {
            var level = context.level(); var above = context.clickedPos().relative(Direction.UP);
            int aboveState = level.registry().block(water.contains(level.fluidAt(above).type()) ? "minecraft:water" : "minecraft:air").defaultState();
            level.setBlock(above, aboveState, 27);
        }
        return context.level().setBlock(context.clickedPos(), state, 11);
    }

    private static int updateBlockStateFromTag(PlacementContext context, int placed) {
        var properties = context.stack().components().stringMap("minecraft:block_state");
        if (properties.isEmpty()) return placed;
        var level = context.level(); int modified = placed;
        for (var entry : properties.entrySet()) modified = level.registry().withIfValid(modified, entry.getKey(), entry.getValue());
        String age = properties.get("age");
        boolean knownAge = age != null && level.registry().block(placed).properties().stream()
            .anyMatch(property -> property.name().equals("age") && property.values().contains(age));
        if (modified != placed || knownAge && !level.statePossibilitiesAt(context.clickedPos()).exact()) {
            level.setBlockFromItemProperties(context.clickedPos(), modified, knownAge);
        }
        return modified;
    }
}
