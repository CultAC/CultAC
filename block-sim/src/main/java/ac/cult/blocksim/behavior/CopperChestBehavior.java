package ac.cult.blocksim.behavior;

import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.Set;

public final class CopperChestBehavior extends ChestBehavior {
    private final Set<String> copperChests;
    public CopperChestBehavior(Set<String> copperChests) { this.copperChests = Set.copyOf(copperChests); }
    @Override
    public boolean shouldKeepBlockEntity(ac.cult.blocksim.data.BlockRegistry registry, int state, int oldState) {
        return copperChests.contains(registry.block(oldState).key());
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) {
        return leastOxidized(super.placementState(block, context), context.level(), context.clickedPos());
    }

    private static int leastOxidized(int state, SimLevel level, BlockPos pos) {
        int neighbor = level.stateAt(pos.relative(connectedDirection(level, state)));
        if (level.registry().value(state, "type").equals("single") || !isFamily(level, state, "CopperChestBlock") || !isFamily(level, neighbor, "CopperChestBlock")) return state;
        int updated = state, connected = neighbor;
        if (waxed(level, state) != waxed(level, neighbor)) {
            updated = unwaxed(level, state); connected = unwaxed(level, neighbor);
        }
        BlockDefinition least = oxidation(level, state) <= oxidation(level, neighbor) ? level.registry().block(updated) : level.registry().block(connected);
        return level.registry().withPropertiesOf(least, updated);
    }

    private static boolean waxed(SimLevel level, int state) {
        return Boolean.parseBoolean(level.registry().block(state).bindings().get("CopperChestBlock.isWaxed"));
    }

    private static int unwaxed(SimLevel level, int state) {
        if (!waxed(level, state)) return state;
        String target = level.registry().block(state).bindings().get("CopperChestBlock.unwaxed");
        return target == null ? state : level.registry().withPropertiesOf(level.registry().block(target), state);
    }

    private static int oxidation(SimLevel level, int state) {
        return switch (level.registry().block(state).bindings().get("CopperChestBlock.weatherState")) {
            case "UNAFFECTED" -> 0;
            case "EXPOSED" -> 1;
            case "WEATHERED" -> 2;
            case "OXIDIZED" -> 3;
            default -> throw new IllegalArgumentException("Invalid generated copper weather state");
        };
    }

    @Override
    protected boolean connectsTo(SimLevel level, int state, int neighborState) {
        return copperChests.contains(level.registry().block(neighborState).key()) && level.registry().hasProperty(neighborState, "type");
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        int updated = super.updateShape(level, state, pos, direction, neighborPos, neighborState);
        if (connectsTo(level, state, neighborState) && !level.registry().value(updated, "type").equals("single") && connectedDirection(level, updated) == direction) {
            return level.registry().withPropertiesOf(level.registry().block(neighborState), updated);
        }
        return updated;
    }
}
